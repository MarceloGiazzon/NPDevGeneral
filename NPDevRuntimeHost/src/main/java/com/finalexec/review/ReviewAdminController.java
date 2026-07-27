package com.finalexec.review;

import com.npdev.adapters.externalai.packcore.ReviewPackBuilder;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * ADR-0009 / P6: ControlPanel surface for the external-AI review feature -- an app author's first
 * concrete use of the same machinery the platform uses on itself for M1-M6. SUPERUSER-gated,
 * following the same manual {@code requireSuperUser} guard idiom every other hand-written admin
 * controller in this codebase uses (see {@code SchemaImpactController},
 * {@code com.finalexec.seed.DataSeedAdminController}) rather than an annotation.
 *
 * <p>{@code /pack} builds a redacted pack and returns it for review -- no egress happens here.
 * {@code /submit} is the actual publish action (honesty rule 6: egress only after the redacted pack
 * has been reviewable). {@code /ingest} records a verdict, structurally validated (never
 * {@code independent-human-review}, never auto-applied) by {@link ExternalAiCapabilityContract}'s
 * adapter before this controller ever sees it.</p>
 */
@RestController
@RequestMapping("/api/admin/review")
public class ReviewAdminController {

    private static final int CHUNK_LINES = 400;

    private final ExternalAiCapabilityContract externalAiCapabilityContract;
    private final RuntimeContextService runtimeContextService;

    public ReviewAdminController(
            ExternalAiCapabilityContract externalAiCapabilityContract,
            RuntimeContextService runtimeContextService
    ) {
        this.externalAiCapabilityContract = externalAiCapabilityContract;
        this.runtimeContextService = runtimeContextService;
    }

    public record ContentSectionRequest(String label, String text) {
    }

    public record BuildPackRequest(
            String missionId,
            String appId,
            String modelVersion,
            List<ContentSectionRequest> sections,
            List<String> shown,
            List<String> notShown
    ) {
    }

    @PostMapping("/pack")
    public Map<String, Object> buildPack(HttpServletRequest httpRequest, @RequestBody BuildPackRequest body) {
        requireSuperUser(httpRequest);
        if (body.missionId() == null || body.missionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missionId is required");
        }
        if (body.sections() == null || body.sections().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one content section is required");
        }
        Map<String, String> source = Map.of(
                "kind", "product-app",
                "appId", body.appId() == null ? "" : body.appId(),
                "modelVersion", body.modelVersion() == null ? "" : body.modelVersion()
        );
        List<ReviewPackBuilder.ContentSection> sections = body.sections().stream()
                .map(s -> new ReviewPackBuilder.ContentSection(s.label(), s.text()))
                .toList();
        try {
            return ReviewPackBuilder.build(
                    body.missionId(),
                    source,
                    sections,
                    body.shown() == null ? List.of() : body.shown(),
                    body.notShown() == null ? List.of() : body.notShown(),
                    CHUNK_LINES
            );
        } catch (ReviewPackBuilder.SanitizerFailedException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    public record SubmitPackRequest(String missionId, String vendorId, String packManifestSha256, String packJson) {
    }

    /** The publish action: sends a previously-built, already-reviewed pack to a vendor. */
    @PostMapping("/submit")
    public Map<String, Object> submitPack(HttpServletRequest httpRequest, @RequestBody SubmitPackRequest body) {
        requireSuperUser(httpRequest);
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                body.missionId(), body.vendorId(), body.packManifestSha256(), body.packJson());
        ExternalAiRunResult result = externalAiCapabilityContract.submitPack(submission);
        return Map.of(
                "missionId", result.missionId(),
                "runStatus", result.runStatus(),
                "packManifestSha256", result.packManifestSha256() == null ? "" : result.packManifestSha256(),
                "vendorId", result.vendorId() == null ? "" : result.vendorId()
        );
    }

    public record IngestVerdictRequest(String missionId, String vendorId, String verdictJson) {
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingestVerdict(HttpServletRequest httpRequest, @RequestBody IngestVerdictRequest body) {
        requireSuperUser(httpRequest);
        ExternalAiVerdictRecord record;
        try {
            record = externalAiCapabilityContract.ingestVerdict(body.missionId(), body.vendorId(), body.verdictJson());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        return Map.of(
                "missionId", record.missionId(),
                "vendorId", record.vendorId(),
                "model", record.model() == null ? "" : record.model()
        );
    }

    /**
     * Minimal, self-contained operator page: no static asset path exists in the RuntimeHost
     * template to serve this from (same reasoning as {@code SchemaImpactController#impactView}),
     * so it is inline HTML served straight from the controller.
     */
    @GetMapping(value = "/view", produces = "text/html")
    public ResponseEntity<String> view(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>External AI Review</title>"
                + "<style>body{font:14px/1.5 system-ui,monospace;margin:2rem;max-width:70rem}"
                + "textarea{width:100%;height:8rem;font:13px/1.4 monospace}"
                + "input{font:13px/1.4 monospace}"
                + "pre{background:#0b1020;color:#d6e2ff;padding:1rem;overflow:auto;border-radius:6px;white-space:pre-wrap}"
                + "label{display:block;margin-top:.75rem;font-weight:600}"
                + "</style></head><body>"
                + "<h1>External AI review (ADR-0009)</h1>"
                + "<p>Requires the SUPERUSER key. Building a pack does not send anything anywhere -- "
                + "review it below, then Submit is the only action that egresses.</p>"
                + "<label>Mission id</label><input id=missionId value=\"M7-IMPACT-CONVERT\">"
                + "<label>App id</label><input id=appId>"
                + "<label>Model version</label><input id=modelVersion>"
                + "<label>Content label</label><input id=label value=\"content.txt\">"
                + "<label>Content text</label><textarea id=text></textarea>"
                + "<p><button id=buildBtn>Build pack</button>"
                + " <button id=submitBtn>Submit (egress)</button> "
                + "Vendor: <input id=vendorId value=\"openai\" style=\"width:8rem\"></p>"
                + "<label>Verdict JSON (paste after a manual review round)</label><textarea id=verdictJson></textarea>"
                + "<p><button id=ingestBtn>Ingest verdict</button></p>"
                + "<pre id=out>(nothing built yet)</pre>"
                + "<script>"
                + "let lastPack=null;let key=null;"
                + "function getKey(){if(!key)key=prompt('X-Super-User-Key');return key;}"
                + "async function post(path,body){"
                + "const res=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Super-User-Key':getKey()},body:JSON.stringify(body)});"
                + "const text=await res.text();"
                + "if(!res.ok){document.getElementById('out').textContent='HTTP '+res.status+': '+text;return null;}"
                + "return JSON.parse(text);}"
                + "document.getElementById('buildBtn').onclick=async()=>{"
                + "const body={missionId:document.getElementById('missionId').value,"
                + "appId:document.getElementById('appId').value,"
                + "modelVersion:document.getElementById('modelVersion').value,"
                + "sections:[{label:document.getElementById('label').value,text:document.getElementById('text').value}],"
                + "shown:[document.getElementById('label').value],notShown:[]};"
                + "const pack=await post('/api/admin/review/pack',body);"
                + "if(!pack)return;lastPack=pack;"
                + "document.getElementById('out').textContent='manifestSha256: '+pack.manifestSha256"
                + "+'\\nsanitizer hits: '+pack.sanitizer.secretHitCount+'\\nchunks: '+pack.chunks.length"
                + "+'\\n\\n'+JSON.stringify(pack,null,2);};"
                + "document.getElementById('submitBtn').onclick=async()=>{"
                + "if(!lastPack){alert('Build a pack first');return;}"
                + "const body={missionId:lastPack.missionId,vendorId:document.getElementById('vendorId').value,"
                + "packManifestSha256:lastPack.manifestSha256,packJson:JSON.stringify(lastPack)};"
                + "const result=await post('/api/admin/review/submit',body);"
                + "if(result)document.getElementById('out').textContent='SENT: '+JSON.stringify(result,null,2);};"
                + "document.getElementById('ingestBtn').onclick=async()=>{"
                + "const body={missionId:document.getElementById('missionId').value,"
                + "vendorId:document.getElementById('vendorId').value,"
                + "verdictJson:document.getElementById('verdictJson').value};"
                + "const result=await post('/api/admin/review/ingest',body);"
                + "if(result)document.getElementById('out').textContent='INGESTED: '+JSON.stringify(result,null,2);};"
                + "</script></body></html>";
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8").body(html);
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
