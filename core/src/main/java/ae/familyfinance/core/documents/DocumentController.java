package ae.familyfinance.core.documents;

import ae.familyfinance.core.audit.AgentRunFilter;
import ae.familyfinance.core.auth.CurrentMember;
import ae.familyfinance.core.common.ApiException;
import ae.familyfinance.core.documents.DocumentService.DocumentView;
import ae.familyfinance.core.documents.DocumentService.Extracted;
import ae.familyfinance.core.documents.DocumentService.ExtractedLine;
import ae.familyfinance.core.documents.DocumentService.FileContent;
import ae.familyfinance.core.documents.FindingsEngine.Finding;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Receipts and statements, and what was found in them. Parents only (checked in the services).
 * The Bookkeeper agent reads a file through {@code GET /file} and sends its draft to {@code POST /extraction};
 * only a parent's tap in the app confirms it.
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documents;
    private final FindingService findings;
    private final DemoSamples samples;

    public DocumentController(DocumentService documents, FindingService findings, DemoSamples samples) {
        this.documents = documents;
        this.findings = findings;
        this.samples = samples;
    }

    public record LineBody(@Size(max = 40) String date, @Size(max = 300) String description, @Size(max = 30) String amount,
                           Boolean credit, @Size(max = 40) String category) {
    }

    /** An ExtractedDocument: what the agent read, every amount as the text it saw. */
    public record ExtractionBody(@Size(max = 20) String kind, @Size(max = 200) String merchant, @Size(max = 40) String date,
                                 @Size(max = 30) String total, @Size(max = 4) String cardLast4,
                                 @Size(max = 30) String closingBalance, @Size(max = 40) String category,
                                 @Size(max = 8) String readBy, @Size(max = 300) String note,
                                 @Size(max = 400) List<@Valid LineBody> lines) {
    }

    public record FailedBody(@Size(max = 300) String reason) {
    }

    public record ConfirmBody(UUID accountId, @Size(max = 40) String category, boolean updateBalance) {
    }

    public record DecideBody(@NotNull @Size(max = 120) String key, @NotNull @Size(max = 12) String action) {
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "kind", required = false) String kind,
                               @RequestParam(value = "accountId", required = false) UUID accountId,
                               HttpServletRequest http) throws IOException {
        AgentRunFilter.refuseAgentCall(http, "Only a parent uploads documents, in the app");
        return documents.upload(CurrentMember.from(jwt), file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                kind, accountId);
    }

    @GetMapping("/documents")
    public List<DocumentView> list(@AuthenticationPrincipal Jwt jwt) {
        return documents.list(CurrentMember.from(jwt));
    }

    @GetMapping("/documents/{id}")
    public DocumentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return documents.get(CurrentMember.from(jwt), id);
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        FileContent f = documents.file(CurrentMember.from(jwt), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(f.bytes());
    }

    /** The Bookkeeper's typed draft. Always waits for a parent's Confirm. */
    @PostMapping("/documents/{id}/extraction")
    public DocumentView extraction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody ExtractionBody b, HttpServletRequest http) {
        return documents.saveExtraction(CurrentMember.from(jwt), id, new Extracted(b.kind(), b.merchant(), b.date(),
                b.total(), b.cardLast4(), b.closingBalance(), b.category(), b.readBy(), b.note(),
                b.lines() == null ? null : b.lines().stream().map(l -> new ExtractedLine(l.date(), l.description(),
                        l.amount(), l.credit(), l.category())).toList()), AgentRunFilter.runId(http));
    }

    @PostMapping("/documents/{id}/failed")
    public DocumentView failed(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody FailedBody b,
                               HttpServletRequest http) {
        return documents.markFailed(CurrentMember.from(jwt), id, b.reason(), AgentRunFilter.runId(http));
    }

    @PostMapping("/documents/{id}/confirm")
    public DocumentView confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody(required = false) ConfirmBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can confirm a document, in the app");
        return documents.confirm(CurrentMember.from(jwt), id, b == null ? null : b.accountId(),
                b == null ? null : b.category(), b != null && b.updateBalance());
    }

    @PostMapping("/documents/{id}/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent can throw a document away, in the app");
        documents.discard(CurrentMember.from(jwt), id);
    }

    /** Demo files dated to line up with the seeded family: {@code receipt} or {@code statement}. */
    @GetMapping("/documents/samples/{name}")
    public ResponseEntity<String> sample(@AuthenticationPrincipal Jwt jwt, @PathVariable String name) {
        if (!CurrentMember.from(jwt).isParent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a parent can upload documents");
        }
        DemoSamples.Sample s = samples.get(name);
        if (s == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No such sample");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(s.contentType() + ";charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(s.fileName()).build().toString())
                .body(s.text());
    }

    /** Double charges and subscriptions nobody listed. */
    @GetMapping("/findings")
    public List<Finding> findings(@AuthenticationPrincipal Jwt jwt) {
        return findings.list(CurrentMember.from(jwt));
    }

    @PostMapping("/findings/decide")
    public List<Finding> decide(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DecideBody b, HttpServletRequest http) {
        AgentRunFilter.refuseAgentCall(http, "Only a parent decides what to do about a finding, in the app");
        return findings.decide(CurrentMember.from(jwt), b.key(), FindingService.action(b.action()));
    }
}
