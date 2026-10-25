package ae.familyfinance.core.documents;

import ae.familyfinance.core.accounts.AccountService;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.documents.LineMatcher.Known;
import ae.familyfinance.core.documents.LineMatcher.Line;
import ae.familyfinance.core.documents.LineMatcher.Result;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.plan.Amounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Receipts and statements (feature 2). A parent uploads a file; the Bookkeeper agent reads it and sends back a
 * typed draft (an ExtractedDocument); the core checks it, converts every amount to fils, matches the document to
 * an account or card and each line to what is already in the ledger. Nothing counts until a parent confirms.
 *
 * <p>The agent never decides what is new or a duplicate and never adds anything up: this service does.
 */
@Service
public class DocumentService {

    /** 5 MB: a phone photo of a receipt or a few pages of statement. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf",
            "text/csv", "text/plain", "application/vnd.ms-excel");

    /** Categories a document line can have: the family's spending categories, plus money coming back. */
    static final List<String> LINE_CATEGORIES;

    static {
        List<String> c = new ArrayList<>(AccountService.CATEGORIES);
        c.add("REFUND");
        c.add("CARD_PAYMENT");
        LINE_CATEGORIES = List.copyOf(c);
    }

    // ------------------------------------------------------------------ what the agent sends (amounts are text)

    public record ExtractedLine(String date, String description, String amount, Boolean credit, String category) {
    }

    public record Extracted(String kind, String merchant, String date, String total, String cardLast4,
                            String closingBalance, String category, String readBy, String note,
                            List<ExtractedLine> lines) {
    }

    // ------------------------------------------------------------------ views

    public record LineView(int no, LocalDate date, String description, MoneyView amount, boolean credit,
                           String category, String status, String matchNote, boolean possibleDuplicate) {
    }

    public record Counts(int lines, int added, int alreadyLogged, int possibleDuplicates) {
    }

    /**
     * {@code summary}: one sentence the agent and the screen can show as is. {@code facts}: every number the
     * agent may repeat. {@code newTotal}: what Confirm would add as spending.
     */
    public record DocumentView(UUID id, String kind, String fileName, String contentType, int sizeBytes, String status,
                               OffsetDateTime uploadedAt, String uploadedBy, UUID accountId, String accountLabel,
                               String accountLast4, String matchReason, String readBy, String merchant, LocalDate date,
                               MoneyView total, String category, MoneyView closingBalance, String note,
                               String matchedNote, Counts counts, MoneyView newTotal, MoneyView itemsTotal,
                               List<LineView> lines, String summary, List<String> facts) {
    }

    public record FileContent(String fileName, String contentType, byte[] bytes) {
    }

    private record Doc(UUID id, String kind, String fileName, String contentType, int size, String status,
                       OffsetDateTime uploadedAt, String uploadedBy, UUID accountId, String matchReason, String readBy,
                       String merchant, LocalDate date, Long totalMinor, String category, Long closingMinor,
                       String note, UUID matchedTx) {
    }

    private record Account(UUID id, String kind, String label, String last4, UUID ownerId, LocalDate asOf) {
    }

    private record StoredLine(UUID id, int no, LocalDate on, String description, String merchantKey, boolean credit,
                              long minor, String category, String status, UUID matchedTx, String matchNote,
                              boolean possibleDuplicate) {
    }

    private final JdbcClient db;
    private final Clock clock;
    private final ObjectMapper json;

    public DocumentService(JdbcClient db, Clock clock, ObjectMapper json) {
        this.db = db;
        this.clock = clock;
        this.json = json;
    }

    // ------------------------------------------------------------------ upload

    /** {@code kind}: RECEIPT or STATEMENT (bank or card: the account decides). {@code accountId}: optional. */
    @Transactional
    public DocumentView upload(CurrentMember me, String fileName, String contentType, byte[] bytes, String kind,
                               UUID accountId) {
        requireParent(me);
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "That file is over 5 MB. Try a smaller photo");
        }
        String type = contentType(fileName, contentType);
        if (!TYPES.contains(type)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload a photo, a PDF or a CSV file");
        }
        String k = kind == null ? "RECEIPT" : kind.toUpperCase(Locale.ROOT);
        String stored = switch (k) {
            case "RECEIPT" -> "RECEIPT";
            case "INVOICE" -> "INVOICE";
            case "STATEMENT", "BANK_STATEMENT", "CARD_STATEMENT" -> "BANK_STATEMENT";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Is it a receipt or a statement?");
        };
        Account account = accountId == null ? null : account(me.familyId(), accountId);
        if (account != null && stored.equals("BANK_STATEMENT") && account.kind().equals("CREDIT_CARD")) {
            stored = "CARD_STATEMENT";
        }
        String sha = sha256(bytes);
        String earlier = db.sql("SELECT file_name, uploaded_at FROM source_document WHERE family_id = ? AND sha256 = ?")
                .params(me.familyId(), sha)
                .query((rs, n) -> rs.getString(1) + ", "
                        + day(rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(clock.getZone()).toLocalDate()))
                .optional().orElse(null);
        if (earlier != null) {
            throw new ApiException(HttpStatus.CONFLICT, "You already uploaded this file (" + earlier + ")");
        }
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO source_document (id, family_id, uploaded_by, kind, file_name, content_type, storage_path, sha256,
                                             content, size_bytes, account_id, match_reason)
                VALUES (?, ?, ?, ?, ?, ?, 'db', ?, ?, ?, ?, ?)
                """).params(id, me.familyId(), me.memberId(), stored, cut(cleanName(fileName), 255), type, sha, bytes,
                        bytes.length, account == null ? null : account.id(), account == null ? null : "Picked by you")
                .update();
        return view(me.familyId(), id);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> list(CurrentMember me) {
        requireParent(me);
        return db.sql("SELECT id FROM source_document WHERE family_id = ? ORDER BY uploaded_at DESC LIMIT 50")
                .param(me.familyId()).query(UUID.class).list().stream().map(id -> view(me.familyId(), id)).toList();
    }

    @Transactional(readOnly = true)
    public DocumentView get(CurrentMember me, UUID id) {
        requireParent(me);
        return view(me.familyId(), id);
    }

    /** The file itself, for the Bookkeeper to read (with the parent's own token). */
    @Transactional(readOnly = true)
    public FileContent file(CurrentMember me, UUID id) {
        requireParent(me);
        return db.sql("SELECT file_name, content_type, content FROM source_document WHERE id = ? AND family_id = ?")
                .params(id, me.familyId())
                .query((rs, n) -> new FileContent(rs.getString(1), rs.getString(2), rs.getBytes(3))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    // ------------------------------------------------------------------ the Bookkeeper's draft

    /**
     * The typed draft read from the file. Checked and matched here; the document waits as EXTRACTED for a parent.
     * {@code runId}: the agent run that read it, or null when a parent typed it in.
     */
    @Transactional
    public DocumentView saveExtraction(CurrentMember me, UUID id, Extracted in, UUID runId) {
        requireParent(me);
        Doc doc = doc(me.familyId(), id);
        if (doc.status().equals("CONFIRMED")) {
            throw new ApiException(HttpStatus.CONFLICT, "This document is already confirmed");
        }
        String cur = currency(me.familyId());
        String kind = kind(in.kind(), doc.kind());
        boolean receipt = kind.equals("RECEIPT") || kind.equals("INVOICE");

        // The account: the one the parent picked, else the card or account with the same last 4 digits
        Account account = doc.accountId() == null ? null : account(me.familyId(), doc.accountId());
        String reason = doc.matchReason();
        if (account == null && in.cardLast4() != null && in.cardLast4().trim().matches("[0-9]{4}")) {
            String last4 = in.cardLast4().trim();
            List<Account> same = accounts(me.familyId()).stream().filter(a -> last4.equals(a.last4())).toList();
            if (same.size() == 1) {
                account = same.get(0);
                reason = "Matched by the last 4 digits " + last4;
            }
        }
        if (!receipt && account != null) {
            kind = account.kind().equals("CREDIT_CARD") ? "CARD_STATEMENT" : "BANK_STATEMENT";
        }

        List<String> notes = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        LocalDate docDate = date(in.date());
        if (docDate != null && docDate.isAfter(today)) {
            docDate = null;
        }
        Money total = null;
        Money closing = null;
        List<StoredLine> lines = new ArrayList<>();
        int skipped = 0;
        int no = 0;
        for (ExtractedLine l : in.lines() == null ? List.<ExtractedLine>of() : in.lines()) {
            no++;
            Signed amount = signed(l.amount(), cur);
            LocalDate on = date(l.date());
            String description = l.description() == null ? "" : l.description().trim();
            if (amount == null || description.isEmpty() || (!receipt && (on == null || on.isAfter(today)))) {
                skipped++;
                continue;
            }
            boolean credit = amount.credit() || Boolean.TRUE.equals(l.credit());
            lines.add(new StoredLine(null, no, receipt ? null : on, cut(description, 255), MerchantKey.of(description),
                    credit, amount.money().minor(), category(l.category(), credit, description, receipt ? "OTHER" : null),
                    receipt ? "ITEM" : "NEW", null, null, false));
        }
        if (skipped > 0) {
            notes.add(skipped == 1 ? "1 line couldn't be read and was left out." : skipped + " lines couldn't be read and were left out.");
        }
        Money itemsTotal = null;
        if (receipt) {
            Signed t = signed(in.total(), cur);
            if (t == null || t.credit()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "I couldn't find the receipt's total");
            }
            total = t.money();
            if (docDate == null) {
                docDate = doc.uploadedAt().atZoneSameInstant(clock.getZone()).toLocalDate();
                notes.add("No date on the receipt, so it uses the day it was uploaded.");
            }
            if (!lines.isEmpty()) {
                itemsTotal = Money.zero(cur);
                for (StoredLine l : lines) {
                    itemsTotal = l.credit() ? itemsTotal.minus(new Money(l.minor(), cur)) : itemsTotal.plus(new Money(l.minor(), cur));
                }
                if (!itemsTotal.equals(total)) {
                    notes.add("The lines add up to " + itemsTotal.toDisplay() + " but the total says " + total.toDisplay()
                              + ". The total is used. Check it against the paper.");
                }
            }
        } else {
            if (lines.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "I couldn't find any payments on this statement");
            }
            Signed c = signed(in.closingBalance(), cur);
            closing = c == null ? null : c.money();
            if (docDate == null) {
                docDate = lines.stream().map(StoredLine::on).max(LocalDate::compareTo).orElseThrow();
            }
        }
        if (in.note() != null && !in.note().isBlank()) {
            notes.add(in.note().trim());
        }
        String merchant = in.merchant() == null || in.merchant().isBlank() ? null : cut(in.merchant().trim(), 160);
        String category = receipt ? category(in.category(), false, merchant == null ? "" : merchant, "GROCERIES") : null;
        String readBy = runId == null ? "PARENT" : "LLM".equalsIgnoreCase(in.readBy()) ? "LLM" : "RULES";

        db.sql("DELETE FROM document_line WHERE document_id = ?").param(id).update();
        for (StoredLine l : lines) {
            db.sql("""
                    INSERT INTO document_line (document_id, line_no, occurred_on, description, merchant_key, direction,
                                               amount_minor, category, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(id, l.no(), l.on(), l.description(), l.merchantKey(), l.credit() ? "CREDIT" : "DEBIT",
                            l.minor(), l.category(), l.status())
                    .update();
        }
        db.sql("""
                UPDATE source_document SET kind = ?, status = 'EXTRACTED', account_id = ?, match_reason = ?,
                       extracted = CAST(? AS jsonb), read_by = ?, agent_run_id = ?, merchant = ?, doc_date = ?,
                       total_minor = ?, category = ?, closing_minor = ?, note = ?, matched_tx_id = NULL
                WHERE id = ? AND family_id = ?
                """).params(kind, account == null ? null : account.id(), account == null ? null : reason, toJson(in),
                        readBy, runId, merchant, docDate, total == null ? null : total.minor(), category,
                        closing == null ? null : closing.minor(), notes.isEmpty() ? null : cut(String.join(" ", notes), 300),
                        id, me.familyId())
                .update();
        match(me.familyId(), id);
        return view(me.familyId(), id);
    }

    /** The agent could not read the file (a photo with the AI switched off, a blurred scan). */
    @Transactional
    public DocumentView markFailed(CurrentMember me, UUID id, String reason, UUID runId) {
        requireParent(me);
        Doc doc = doc(me.familyId(), id);
        if (doc.status().equals("CONFIRMED")) {
            throw new ApiException(HttpStatus.CONFLICT, "This document is already confirmed");
        }
        db.sql("UPDATE source_document SET status = 'FAILED', note = ?, agent_run_id = ? WHERE id = ?")
                .params(cut(reason == null || reason.isBlank() ? "I couldn't read this file." : reason.trim(), 300), runId, id)
                .update();
        return view(me.familyId(), id);
    }

    // ------------------------------------------------------------------ a parent decides

    /**
     * A parent confirms the draft: new lines go into the ledger, tagged with the account; lines already there are
     * linked, not added. {@code updateBalance}: also take the statement's closing balance as the account's balance.
     */
    @Transactional
    public DocumentView confirm(CurrentMember me, UUID id, UUID accountId, String category, boolean updateBalance) {
        requireParent(me);
        Doc doc = doc(me.familyId(), id);
        if (!doc.status().equals("EXTRACTED")) {
            throw new ApiException(HttpStatus.CONFLICT, doc.status().equals("CONFIRMED")
                    ? "This document is already confirmed" : "This document hasn't been read yet");
        }
        if (accountId != null && !accountId.equals(doc.accountId())) {
            Account a = account(me.familyId(), accountId);
            boolean receipt = doc.kind().equals("RECEIPT") || doc.kind().equals("INVOICE");
            String kind = receipt ? doc.kind() : a.kind().equals("CREDIT_CARD") ? "CARD_STATEMENT" : "BANK_STATEMENT";
            db.sql("UPDATE source_document SET account_id = ?, match_reason = 'Picked by you', kind = ? WHERE id = ?")
                    .params(a.id(), kind, id).update();
        }
        if (category != null && !category.isBlank()) {
            String c = category.toUpperCase(Locale.ROOT);
            if (!AccountService.CATEGORIES.contains(c)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Pick one of: " + String.join(", ", AccountService.CATEGORIES));
            }
            db.sql("UPDATE source_document SET category = ? WHERE id = ?").params(c, id).update();
        }
        doc = doc(me.familyId(), id);
        if (doc.accountId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Which card or account is this from? Pick one first");
        }
        // Match again: something may have been typed in or confirmed since the draft was read
        match(me.familyId(), id);
        Account account = account(me.familyId(), doc.accountId());
        UUID member = account.ownerId() != null ? account.ownerId() : me.memberId();
        String cur = currency(me.familyId());

        if (doc.kind().equals("RECEIPT") || doc.kind().equals("INVOICE")) {
            doc = doc(me.familyId(), id);
            if (doc.matchedTx() == null) {
                String description = doc.merchant() == null ? "Receipt" : doc.merchant();
                insertTx(me.familyId(), member, id, doc.date(), description, doc.merchant(), doc.category(), false,
                        doc.totalMinor(), cur, account.id());
            }
        } else {
            for (StoredLine l : lines(id)) {
                if (l.status().equals("NEW")) {
                    UUID tx = insertTx(me.familyId(), member, id, l.on(), l.description(), MerchantKey.label(
                            l.merchantKey() == null ? "?" : l.merchantKey()), l.category(), l.credit(), l.minor(), cur,
                            account.id());
                    db.sql("UPDATE document_line SET ledger_tx_id = ? WHERE id = ?").params(tx, l.id()).update();
                }
            }
            if (updateBalance && doc.closingMinor() != null && doc.date() != null && !doc.date().isBefore(account.asOf())) {
                db.sql("UPDATE money_account SET balance_minor = ?, balance_as_of = ? WHERE id = ? AND family_id = ?")
                        .params(doc.closingMinor(), doc.date(), account.id(), me.familyId()).update();
            }
        }
        db.sql("UPDATE source_document SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ? WHERE id = ?")
                .params(me.memberId(), OffsetDateTime.now(clock), id).update();
        return view(me.familyId(), id);
    }

    /** Throws the draft away (and the file), so it can be uploaded again. A confirmed document stays. */
    @Transactional
    public void discard(CurrentMember me, UUID id) {
        requireParent(me);
        Doc doc = doc(me.familyId(), id);
        if (doc.status().equals("CONFIRMED")) {
            throw new ApiException(HttpStatus.CONFLICT, "This document is confirmed and its payments are in your spending");
        }
        db.sql("DELETE FROM source_document WHERE id = ? AND family_id = ?").params(id, me.familyId()).update();
    }

    // ------------------------------------------------------------------ matching

    /** Compares each payment line (a receipt: its total) with the ledger, and stores what it found. */
    private void match(UUID family, UUID id) {
        Doc doc = doc(family, id);
        boolean receipt = doc.kind().equals("RECEIPT") || doc.kind().equals("INVOICE");
        List<Line> lines = new ArrayList<>();
        List<StoredLine> stored = lines(id);
        if (receipt) {
            if (doc.totalMinor() == null || doc.date() == null) {
                return;
            }
            lines.add(new Line(0, doc.date(), doc.totalMinor(), false, MerchantKey.of(doc.merchant())));
        } else {
            for (StoredLine l : stored) {
                lines.add(new Line(l.no(), l.on(), l.minor(), l.credit(), l.merchantKey()));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        LocalDate from = lines.stream().map(Line::on).min(LocalDate::compareTo).orElseThrow().minusDays(LineMatcher.DAYS);
        LocalDate to = lines.stream().map(Line::on).max(LocalDate::compareTo).orElseThrow().plusDays(LineMatcher.DAYS);
        Map<UUID, String> labels = new java.util.HashMap<>();
        List<Known> known = db.sql("""
                SELECT t.id, t.occurred_on, t.amount_minor, t.direction, t.paid_from_account_id, t.transfer_to_account_id,
                       coalesce(t.merchant, t.description), t.description, t.source, t.source_document_id, d.kind
                FROM ledger_transaction t LEFT JOIN source_document d ON d.id = t.source_document_id
                WHERE t.family_id = ? AND t.duplicate_of_id IS NULL AND t.occurred_on BETWEEN ? AND ?
                  AND (t.paid_from_account_id IS NOT NULL OR t.transfer_to_account_id IS NOT NULL)
                  AND (t.source_document_id IS NULL OR t.source_document_id <> ?)
                ORDER BY t.occurred_on, t.created_at
                """).params(family, from, to, id)
                .query((rs, n) -> {
                    UUID tx = rs.getObject(1, UUID.class);
                    LocalDate on = rs.getObject(2, LocalDate.class);
                    String source = rs.getString(9);
                    String docKind = rs.getString(11);
                    String where = "DOCUMENT".equals(source)
                            ? ("RECEIPT".equals(docKind) ? "from your receipt" : "from an earlier upload")
                            : "typed in";
                    labels.put(tx, "Already in your spending: " + rs.getString(8) + ", " + day(on) + ", " + where + ".");
                    return new Known(tx, on, rs.getLong(3), "CREDIT".equals(rs.getString(4)), rs.getObject(5, UUID.class),
                            rs.getObject(6, UUID.class), MerchantKey.of(rs.getString(7)));
                }).list();
        List<Result> results = LineMatcher.match(lines, known, doc.accountId());
        if (receipt) {
            UUID tx = results.get(0).txId();
            db.sql("UPDATE source_document SET matched_tx_id = ? WHERE id = ?").params(tx, id).update();
            return;
        }
        for (Result r : results) {
            db.sql("""
                    UPDATE document_line SET status = ?, matched_tx_id = ?, match_note = ?, possible_duplicate = ?
                    WHERE document_id = ? AND line_no = ?
                    """).params(r.txId() == null ? "NEW" : "ALREADY_LOGGED", r.txId(),
                            r.txId() == null ? (r.possibleDuplicate() ? "Same shop, same day, same amount as the line above." : null)
                                    : cut(labels.get(r.txId()), 200),
                            r.possibleDuplicate(), id, r.no())
                    .update();
        }
    }

    // ------------------------------------------------------------------ the view (all sums worked out here)

    private DocumentView view(UUID family, UUID id) {
        Doc d = doc(family, id);
        String cur = currency(family);
        Account account = d.accountId() == null ? null : account(family, d.accountId());
        List<StoredLine> stored = lines(id);
        boolean receipt = d.kind().equals("RECEIPT") || d.kind().equals("INVOICE");

        List<LineView> lines = stored.stream().map(l -> new LineView(l.no(), l.on(), l.description(),
                MoneyView.of(new Money(l.minor(), cur)), l.credit(), l.category(), l.status(), l.matchNote(),
                l.possibleDuplicate())).toList();
        int already = (int) stored.stream().filter(l -> l.status().equals("ALREADY_LOGGED")).count();
        int added = (int) stored.stream().filter(l -> l.status().equals("NEW")).count();
        int twice = (int) stored.stream().filter(StoredLine::possibleDuplicate).count();
        Money newTotal = Money.zero(cur);
        Money items = Money.zero(cur);
        for (StoredLine l : stored) {
            Money m = new Money(l.minor(), cur);
            if (l.status().equals("NEW") && !l.credit()) {
                newTotal = newTotal.plus(m);
            }
            if (l.status().equals("ITEM")) {
                items = l.credit() ? items.minus(m) : items.plus(m);
            }
        }
        String matchedNote = null;
        if (receipt) {
            if (d.matchedTx() != null) {
                matchedNote = db.sql("""
                        SELECT t.description, t.occurred_on, t.source, a.label FROM ledger_transaction t
                        LEFT JOIN money_account a ON a.id = t.paid_from_account_id WHERE t.id = ?
                        """).param(d.matchedTx())
                        .query((rs, n) -> "This is already in your spending: " + rs.getString(1) + " on "
                                + day(rs.getObject(2, LocalDate.class)) + ("DOCUMENT".equals(rs.getString(3))
                                ? " from a statement" : ", typed in") + ". Confirming attaches the receipt, it won't count twice.")
                        .single();
                already = 1;
            } else if (d.totalMinor() != null) {
                newTotal = new Money(d.totalMinor(), cur);
                added = 1;
            }
        }
        Counts counts = new Counts(receipt ? stored.size() : stored.size(), added, already, twice);
        Money total = d.totalMinor() == null ? null : new Money(d.totalMinor(), cur);
        Money closing = d.closingMinor() == null ? null : new Money(d.closingMinor(), cur);

        List<String> facts = new ArrayList<>();
        String summary = switch (d.status()) {
            case "UPLOADED" -> "Uploaded. Not read yet.";
            case "FAILED" -> d.note() == null ? "I couldn't read this file." : d.note();
            default -> null;
        };
        if (summary == null) {
            String accountText = account == null ? "No account matched yet." : "For " + account.label()
                    + (account.last4() == null ? "" : " ···" + account.last4()) + ".";
            if (receipt) {
                facts.add("Shop: " + (d.merchant() == null ? "not found" : d.merchant()));
                facts.add("Date: " + (d.date() == null ? "not found" : day(d.date())));
                facts.add("Total: " + (total == null ? "not found" : total.toDisplay()));
                summary = (d.merchant() == null ? "A receipt" : d.merchant()) + (total == null ? "" : ", " + total.toDisplay())
                          + (d.date() == null ? "" : ", " + day(d.date())) + ". " + accountText
                          + (d.matchedTx() != null ? " It is already in your spending, so it won't count twice." : "");
            } else {
                facts.add("Lines read: " + stored.size());
                facts.add("New: " + added + ", " + newTotal.toDisplay() + " of spending");
                facts.add("Already in your spending: " + already);
                facts.add("Look charged twice: " + twice);
                if (closing != null) {
                    facts.add("Closing balance: " + closing.toDisplay());
                }
                summary = stored.size() + (stored.size() == 1 ? " line. " : " lines. ") + added + " new ("
                          + newTotal.toDisplay() + " of spending), " + already + " already in your spending"
                          + (twice > 0 ? ", " + twice + (twice == 1 ? " looks" : " look") + " charged twice" : "") + ". "
                          + accountText;
            }
            facts.add(accountText);
            if (d.status().equals("CONFIRMED")) {
                summary = "Confirmed. " + summary;
            }
        }
        return new DocumentView(d.id(), d.kind(), d.fileName(), d.contentType(), d.size(), d.status(), d.uploadedAt(),
                d.uploadedBy(), d.accountId(), account == null ? null : account.label(),
                account == null ? null : account.last4(), d.matchReason(), d.readBy(), d.merchant(), d.date(),
                total == null ? null : MoneyView.of(total), d.category(), closing == null ? null : MoneyView.of(closing),
                d.note(), matchedNote, counts, MoneyView.of(newTotal), receipt && !stored.isEmpty() ? MoneyView.of(items) : null,
                lines, summary, facts);
    }

    // ------------------------------------------------------------------ queries and helpers

    private UUID insertTx(UUID family, UUID member, UUID doc, LocalDate on, String description, String merchant,
                          String category, boolean credit, long minor, String cur, UUID account) {
        UUID tx = UUID.randomUUID();
        db.sql("""
                INSERT INTO ledger_transaction (id, family_id, member_id, source_document_id, occurred_on, description,
                                                merchant, category, direction, amount_minor, currency, duplicate_hash,
                                                source, paid_from_account_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DOCUMENT', ?)
                """).params(tx, family, member, doc, on, cut(description, 255), merchant == null ? null : cut(merchant, 160),
                        category == null ? "OTHER" : category, credit ? "CREDIT" : "DEBIT", minor, cur,
                        sha256(("doc|" + account + "|" + on + "|" + minor + "|" + MerchantKey.of(description))
                                .getBytes(StandardCharsets.UTF_8)), account)
                .update();
        return tx;
    }

    private Doc doc(UUID family, UUID id) {
        return db.sql("""
                SELECT d.id, d.kind, d.file_name, d.content_type, coalesce(d.size_bytes, 0), d.status, d.uploaded_at,
                       m.display_name, d.account_id, d.match_reason, d.read_by, d.merchant, d.doc_date, d.total_minor,
                       d.category, d.closing_minor, d.note, d.matched_tx_id
                FROM source_document d JOIN member m ON m.id = d.uploaded_by
                WHERE d.id = ? AND d.family_id = ?
                """).params(id, family)
                .query((rs, n) -> new Doc(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getString(8),
                        rs.getObject(9, UUID.class), rs.getString(10), rs.getString(11), rs.getString(12),
                        rs.getObject(13, LocalDate.class), rs.getObject(14, Long.class), rs.getString(15),
                        rs.getObject(16, Long.class), rs.getString(17), rs.getObject(18, UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    private List<StoredLine> lines(UUID doc) {
        return db.sql("""
                SELECT id, line_no, occurred_on, description, merchant_key, direction, amount_minor, category, status,
                       matched_tx_id, match_note, possible_duplicate
                FROM document_line WHERE document_id = ? ORDER BY line_no
                """).param(doc)
                .query((rs, n) -> new StoredLine(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, LocalDate.class),
                        rs.getString(4), rs.getString(5), "CREDIT".equals(rs.getString(6)), rs.getLong(7), rs.getString(8),
                        rs.getString(9), rs.getObject(10, UUID.class), rs.getString(11), rs.getBoolean(12)))
                .list();
    }

    private List<Account> accounts(UUID family) {
        return db.sql("""
                SELECT id, kind, label, last4, owner_member_id, balance_as_of FROM money_account
                WHERE family_id = ? AND status = 'ACTIVE'
                """).param(family)
                .query((rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, LocalDate.class)))
                .list();
    }

    private Account account(UUID family, UUID id) {
        return accounts(family).stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private String currency(UUID family) {
        return db.sql("SELECT currency FROM family WHERE id = ?").param(family).query(String.class).single();
    }

    private String toJson(Extracted in) {
        try {
            String s = json.writeValueAsString(in);
            return s.length() > 200_000 ? "{\"truncated\":true}" : s;
        } catch (Exception e) {
            return "{}";
        }
    }

    record Signed(Money money, boolean credit) {
    }

    /** "189.00", "AED 1,234.50", "-3,200.00", "3,200.00 CR". Null when it isn't an amount. */
    static Signed signed(String text, String cur) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        boolean credit = false;
        if (t.startsWith("-") || t.startsWith("−")) {
            credit = true;
            t = t.substring(1).trim();
        }
        if (t.toUpperCase(Locale.ROOT).endsWith("CR")) {
            credit = true;
            t = t.substring(0, t.length() - 2).trim();
        }
        if (t.startsWith("(") && t.endsWith(")")) {
            credit = true;
            t = t.substring(1, t.length() - 1).trim();
        }
        try {
            return new Signed(Amounts.parse(t, cur, "amount"), credit);
        } catch (ApiException e) {
            return null;
        }
    }

    /** "2026-10-14" or "14/10/2026". UAE documents put the day first. */
    static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(t);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})").matcher(t);
            if (m.find()) {
                int year = Integer.parseInt(m.group(3));
                return LocalDate.of(year < 100 ? 2000 + year : year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String kind(String agentKind, String uploaded) {
        String k = agentKind == null ? "" : agentKind.toUpperCase(Locale.ROOT);
        return switch (k) {
            case "RECEIPT" -> "RECEIPT";
            case "INVOICE" -> "INVOICE";
            case "BANK_STATEMENT", "CARD_STATEMENT", "STATEMENT" -> uploaded.endsWith("STATEMENT") ? uploaded : "BANK_STATEMENT";
            default -> uploaded;
        };
    }

    /** The agent's category when it is one of ours, else a guess from the shop's name, else {@code fallback}. */
    static String category(String given, boolean credit, String description, String fallback) {
        if (credit) {
            return description.toUpperCase(Locale.ROOT).contains("PAYMENT") ? "CARD_PAYMENT" : "REFUND";
        }
        String g = given == null ? "" : given.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (AccountService.CATEGORIES.contains(g)) {
            return g;
        }
        String d = description.toUpperCase(Locale.ROOT);
        Map<String, List<String>> words = Map.of(
                "GROCERIES", List.of("CARREFOUR", "SPINNEYS", "LULU", "UNION COOP", "WAITROSE", "CHOITHRAMS", "GROCER", "SUPERMARKET", "KIBSONS"),
                "TRANSPORT", List.of("ENOC", "ADNOC", "EPPCO", "SALIK", "CAREEM", "UBER", "RTA", "NOL", "PARKING", "TAXI"),
                "EATING_OUT", List.of("TALABAT", "DELIVEROO", "RESTAURANT", "CAFE", "COFFEE", "STARBUCKS", "MCDONALD", "KFC", "NOON FOOD"),
                "ENTERTAINMENT", List.of("VOX", "REEL CINEMA", "NOVO", "SHAHID", "NETFLIX", "OSN", "SPOTIFY", "ANGHAMI", "DISNEY", "PLAYSTATION"),
                "SHOPPING", List.of("NOON", "AMAZON", "IKEA", "CENTREPOINT", "MAX FASHION", "NAMSHI", "SHARAF", "EMAX"),
                "HEALTH", List.of("PHARMACY", "ASTER", "BOOTS", "LIFE PHARMACY", "CLINIC", "HOSPITAL"),
                "HOTEL", List.of("HOTEL", "RESORT", "BOOKING.COM", "AGODA"));
        for (String c : List.of("EATING_OUT", "GROCERIES", "TRANSPORT", "ENTERTAINMENT", "HEALTH", "HOTEL", "SHOPPING")) {
            if (words.get(c).stream().anyMatch(d::contains)) {
                return c;
            }
        }
        return fallback == null ? "OTHER" : fallback;
    }

    private static String contentType(String fileName, String given) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String type = given == null ? "" : given.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (name.endsWith(".csv")) {
            return "text/csv";
        }
        if (type.isEmpty() || type.equals("application/octet-stream")) {
            if (name.endsWith(".pdf")) return "application/pdf";
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
            if (name.endsWith(".png")) return "image/png";
            if (name.endsWith(".txt")) return "text/plain";
        }
        return type;
    }

    private static String cleanName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload";
        }
        String n = fileName.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String day(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static void requireParent(CurrentMember me) {
        if (!me.isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can upload and check the family's documents");
        }
    }
}
