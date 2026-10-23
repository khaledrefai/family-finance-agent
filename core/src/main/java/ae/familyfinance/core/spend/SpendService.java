package ae.familyfinance.core.spend;

import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.family.FamilyRepository;
import ae.familyfinance.core.family.Member;
import ae.familyfinance.core.family.MemberRepository;
import ae.familyfinance.core.money.Money;
import ae.familyfinance.core.money.MoneyView;
import ae.familyfinance.core.spend.SpendSummary.EntryView;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Facts;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Goal;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Line;
import ae.familyfinance.core.spend.SpendSummaryCalculator.Period;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Spend logging: a member (usually a child) records what they bought, and parents see where the money went.
 * The agent sends amounts as the text the child typed ("12", "7.50"); turning that into fils, totals,
 * shares and comparisons all happens here.
 */
@Service
public class SpendService {

    /** Biggest single pocket-money purchase we accept by chat. Anything bigger should be a request to a parent. */
    static final Money MAX_ITEM = Money.aed(100_000);
    /** How far back a child can log ("yesterday", "on Monday"). Older than this is probably a mistake. */
    static final int MAX_DAYS_BACK = 7;
    static final int MAX_ITEMS = 10;

    public record NewItem(String amount, String description, String merchant, SpendCategory category) {
    }

    public record LogResult(List<EntryView> logged, MoneyView today, MoneyView thisMonth) {
    }

    /** Facts the agent uses to decide whether to nudge ("Did you buy lunch today?"). */
    public record LoggingStatus(LocalDate today, DayOfWeek dayOfWeek, boolean schoolDay, boolean loggedToday,
                                LocalDate lastLoggedOn, MoneyView todayTotal) {
    }

    private final LedgerEntryRepository ledger;
    private final MemberRepository members;
    private final FamilyRepository families;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SpendService(LedgerEntryRepository ledger, MemberRepository members, FamilyRepository families,
                        JdbcTemplate jdbc, Clock clock) {
        this.ledger = ledger;
        this.members = members;
        this.families = families;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public LogResult log(CurrentMember me, List<NewItem> items, LocalDate occurredOn, String loggedText) {
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Log between 1 and " + MAX_ITEMS + " things at a time");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate date = occurredOn == null ? today : occurredOn;
        if (date.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That date is in the future");
        }
        if (date.isBefore(today.minusDays(MAX_DAYS_BACK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can only log things from the last " + MAX_DAYS_BACK + " days");
        }
        String currency = currency(me.familyId());
        Map<SpendCategory, String> blocked = blockedCategories(me.familyId(), me.memberId());

        List<EntryView> logged = new ArrayList<>();
        for (NewItem item : items) {
            Money amount = parseAmount(item.amount(), currency);
            String description = item.description().trim();
            String hash = duplicateHash(me.memberId(), date, amount, description);
            UUID duplicateOf = ledger.findFirstByFamilyIdAndDuplicateHashOrderByCreatedAtAsc(me.familyId(), hash)
                    .map(LedgerEntry::getId).orElse(null);
            LedgerEntry saved = ledger.save(LedgerEntry.chatLoggedSpend(me.familyId(), me.memberId(), date,
                    description, blankToNull(item.merchant()), item.category(), amount.minor(), currency, hash,
                    duplicateOf, loggedText));
            logged.add(SpendSummaryCalculator.entry(line(saved), currency, blocked));
        }
        return new LogResult(logged, MoneyView.of(total(me.familyId(), me.memberId(), today, today, currency)),
                MoneyView.of(total(me.familyId(), me.memberId(), today.withDayOfMonth(1), today, currency)));
    }

    @Transactional(readOnly = true)
    public List<EntryView> entries(CurrentMember me, UUID memberId, YearMonth month) {
        Member subject = subject(me, memberId);
        Period p = period(month);
        String currency = currency(me.familyId());
        Map<SpendCategory, String> blocked = blockedCategories(me.familyId(), subject.getId());
        return lines(me.familyId(), subject.getId(), p).stream()
                .map(l -> SpendSummaryCalculator.entry(l, currency, blocked))
                .toList();
    }

    @Transactional(readOnly = true)
    public SpendSummary summary(CurrentMember me, UUID memberId, YearMonth month) {
        Member subject = subject(me, memberId);
        UUID family = me.familyId();
        Period p = period(month);
        Period prev = previousPeriod(p, month);
        LocalDate lastLogged = ledger.findFirstByFamilyIdAndMemberIdAndDirectionOrderByOccurredOnDesc(
                family, subject.getId(), LedgerEntry.DEBIT).map(LedgerEntry::getOccurredOn).orElse(null);
        return SpendSummaryCalculator.summarise(new Facts(subject.getId(), subject.getDisplayName(), currency(family),
                p, lines(family, subject.getId(), p), prev, lines(family, subject.getId(), prev),
                pocketMoney(family, subject.getId(), p), lastLogged, goals(family, subject.getId()),
                blockedCategories(family, subject.getId())));
    }

    @Transactional(readOnly = true)
    public LoggingStatus status(CurrentMember me) {
        LocalDate today = LocalDate.now(clock);
        LocalDate last = ledger.findFirstByFamilyIdAndMemberIdAndDirectionOrderByOccurredOnDesc(
                me.familyId(), me.memberId(), LedgerEntry.DEBIT).map(LedgerEntry::getOccurredOn).orElse(null);
        // UAE schools run Monday to Friday
        boolean schoolDay = today.getDayOfWeek() != DayOfWeek.SATURDAY && today.getDayOfWeek() != DayOfWeek.SUNDAY;
        return new LoggingStatus(today, today.getDayOfWeek(), schoolDay, today.equals(last), last,
                MoneyView.of(total(me.familyId(), me.memberId(), today, today, currency(me.familyId()))));
    }

    // ------------------------------------------------------------------ helpers

    /** Parents can look at anyone in their family; a child only at themselves. Other families "don't exist". */
    private Member subject(CurrentMember me, UUID memberId) {
        UUID id = memberId == null ? me.memberId() : memberId;
        if (!me.isParent() && !id.equals(me.memberId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only see your own spending");
        }
        return members.findById(id)
                .filter(m -> m.getFamilyId().equals(me.familyId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));
    }

    private Period period(YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        YearMonth m = month == null ? YearMonth.from(today) : month;
        if (m.isAfter(YearMonth.from(today))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That month hasn't happened yet");
        }
        boolean running = m.equals(YearMonth.from(today));
        return new Period(m.atDay(1), running ? today : m.atEndOfMonth(), label(m), running);
    }

    /** While a month is running, compare with the same days of last month; otherwise with the whole month. */
    private static Period previousPeriod(Period p, YearMonth month) {
        YearMonth prev = YearMonth.from(p.from()).minusMonths(1);
        LocalDate to = prev.atEndOfMonth();
        if (p.monthToDate()) {
            LocalDate sameDay = prev.atDay(Math.min(p.to().getDayOfMonth(), prev.lengthOfMonth()));
            to = sameDay.isBefore(to) ? sameDay : to;
        }
        return new Period(prev.atDay(1), to, label(prev), false);
    }

    private static String label(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + m.getYear();
    }

    private List<Line> lines(UUID family, UUID member, Period p) {
        return ledger.findByFamilyIdAndMemberIdAndDirectionAndOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(
                family, member, LedgerEntry.DEBIT, p.from(), p.to()).stream().map(SpendService::line).toList();
    }

    private static Line line(LedgerEntry e) {
        return new Line(e.getId(), e.getOccurredOn(), e.getDescription(), e.getMerchant(), e.spendCategory(),
                e.getAmountMinor(), e.getLoggedText(), e.getDuplicateOfId() != null);
    }

    private Money total(UUID family, UUID member, LocalDate from, LocalDate to, String currency) {
        Money total = Money.zero(currency);
        for (Line l : lines(family, member, new Period(from, to, "", false))) {
            total = total.plus(new Money(l.minor(), currency));
        }
        return total;
    }

    // The tables below have no JPA entities yet (their features arrive in later weeks), so they are read directly.

    private long pocketMoney(UUID family, UUID member, Period p) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount_minor), 0)
                FROM demo_wallet_entry e JOIN demo_wallet w ON w.id = e.wallet_id
                WHERE w.family_id = ? AND w.member_id = ? AND e.kind = 'POCKET_MONEY'
                  AND e.created_at::date BETWEEN ? AND ?
                """, Long.class, family, member, p.from(), p.to());
        return sum == null ? 0 : sum;
    }

    private List<Goal> goals(UUID family, UUID member) {
        return jdbc.query("""
                SELECT name, target_minor, saved_minor FROM goal
                WHERE family_id = ? AND member_id = ? AND status = 'ACTIVE' ORDER BY created_at
                """, (rs, i) -> new Goal(rs.getString(1), rs.getLong(2), rs.getLong(3)), family, member);
    }

    /** Categories this member is not supposed to spend on, with the parent's note. */
    private Map<SpendCategory, String> blockedCategories(UUID family, UUID member) {
        Map<SpendCategory, String> blocked = new LinkedHashMap<>();
        jdbc.query("""
                SELECT category, note FROM family_rule
                WHERE family_id = ? AND kind = 'BLOCK_CATEGORY'
                  AND (applies_to_member_id IS NULL OR applies_to_member_id = ?)
                """, rs -> {
            try {
                SpendCategory c = SpendCategory.valueOf(rs.getString(1));
                String note = rs.getString(2);
                blocked.put(c, note == null ? "Against a family rule" : note);
            } catch (IllegalArgumentException ignored) {
                // a rule about a category children can't log (e.g. GROCERIES)
            }
        }, family, member);
        return blocked;
    }

    private String currency(UUID family) {
        return families.findById(family).orElseThrow().getCurrency();
    }

    static Money parseAmount(String text, String currency) {
        Money amount;
        try {
            amount = Money.ofMajor(text.trim().replace(',', '.'), currency);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "I couldn't read the amount \"" + text + "\"");
        }
        if (amount.minor() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The amount must be more than zero");
        }
        if (amount.compareTo(new Money(MAX_ITEM.minor(), currency)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "That's more than " + new Money(MAX_ITEM.minor(), currency).toDisplay()
                            + ". For big things, send a request to a parent instead");
        }
        return amount;
    }

    /** Same child, same day, same amount, same thing = probably logged twice. */
    static String duplicateHash(UUID member, LocalDate date, Money amount, String description) {
        String key = member + "|" + date + "|" + amount.minor() + "|" + SpendSummaryCalculator.normalise(description);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
