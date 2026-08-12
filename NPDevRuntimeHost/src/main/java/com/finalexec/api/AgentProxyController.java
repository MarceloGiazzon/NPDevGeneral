package com.finalexec.api;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiGenerationRequest;
import com.npdev.kernel.ports.ExternalAiGenerationResult;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated app's own server-side proxy to an external AI vendor, so {@code agent-prompter.html}
 * can send a composed prompt without the browser ever holding an API key.
 *
 * <p><b>Why a proxy at all.</b> The two alternatives were rejected on evidence, not taste. A
 * Manager-brokered localhost endpoint only works when the browser and the desktop Manager are the
 * same machine, which a FinalApp served over a network is not. Pasting a key into the page fails on
 * provider CORS for most vendors, teaches users to paste credentials into web pages, and leaves the
 * key readable by any XSS on the origin. A server-side proxy over the existing, fail-closed
 * {@link ExternalAiCapabilityContract} egress seam reuses machinery that already refuses to send
 * without a key, and keeps the secret on the same side of the wire as the database password.
 *
 * <p><b>Two different gates, deliberately.</b> {@code /config} answers any authenticated caller --
 * it is the same read posture as {@code PropertyResolverController}, and it returns nothing a caller
 * could not learn by trying. {@code /generate} spends the operator's money and reaches the public
 * internet, so it requires SUPERUSER via the manual {@code requireSuperUser} idiom every hand-written
 * admin controller here uses. It is specifically NOT {@code hasRole("ADMIN")}: in an
 * {@code auth.mode=none} app the generated {@code RuntimeContextService} hands ADMIN to every
 * anonymous caller, so an ADMIN check would be no gate at all in exactly the dev apps most likely to
 * be exposed. SUPERUSER is never in that fallback set.
 *
 * <p><b>SSRF.</b> A caller picks WHICH configured vendor and WHICH model. It never picks where the
 * request goes: the endpoint comes from the server-side vendor profile, and a vendor id that is not
 * in {@link ExternalAiCapabilityContract#configuredVendors()} is a 400 before anything is sent.
 *
 * <p><b>Logging.</b> Vendor id, model, and outcome only -- never the prompt, never a header, never
 * the key. An app's {@code logs/} directory ships verbatim inside {@code npdev monitor logs export},
 * so anything written here should be assumed to end up in a support bundle in a chat window.
 *
 * <p><b>Registration, and why this package.</b> The simple name is listed in
 * {@code npdev/runtime-supported-controllers.json}'s {@code allowedControllers}. That entry is not
 * optional: {@code RuntimeControllerAllowlistConfig} removes the bean of ANY unlisted
 * {@code com.finalexec.*Controller} at runtime under the default enforced profile, turning every
 * route here into a silent 404.
 *
 * <p>Given that the entry has to exist, {@code com.finalexec.api} is the only package all three
 * enforcement points agree on. {@code build.gradle.template} excludes unlisted
 * {@code api/*Controller.java} from compilation -- listed, so it compiles.
 * {@code run-runtime-surface-evidence.ps1} additionally asserts that every name in
 * {@code allowedControllers} RESOLVES to a file under {@code com/finalexec/api}, and fails the
 * RuntimeHost gate for a listed controller that lives anywhere else. Putting a supported-core
 * controller in its own package to dodge the compile-exclusion trades a trap that fails loudly at
 * build time for one that fails in a gate nobody expects to be about packaging.
 */
@RestController
@RequestMapping("/api/agent-proxy")
public class AgentProxyController {

    private static final Logger LOG = LoggerFactory.getLogger(AgentProxyController.class);

    /**
     * Server-side ceiling on a single prompt. The page caps its model context at 60k characters, so
     * this is roughly three times the largest thing the supported client composes -- generous for a
     * hand-edited prompt, and low enough that a page bug looping over a large model cannot stream a
     * gigabyte at a provider on the operator's account.
     */
    private static final int MAX_PROMPT_CHARS = 200_000;

    /** Matches the CLI's cap on echoing a vendor body back to a caller. */
    private static final int MAX_RAW_CHARS = 20_000;

    private final ObjectProvider<ExternalAiCapabilityContract> externalAiCapabilityContract;
    private final RuntimeContextService runtimeContextService;

    public AgentProxyController(
            ObjectProvider<ExternalAiCapabilityContract> externalAiCapabilityContract,
            RuntimeContextService runtimeContextService
    ) {
        this.externalAiCapabilityContract = externalAiCapabilityContract;
        this.runtimeContextService = runtimeContextService;
    }

    public record GenerateRequest(String vendor, String model, String effort, String prompt) {
    }

    /**
     * What this app can send with, if anything.
     *
     * <p>{@code configured} is false unless a contract is wired AND at least one vendor's key env var
     * actually resolves. Both halves matter: the default {@code inproc} provider gives a bean that
     * denies everything, and a wired {@code http} provider with no key set would otherwise advertise
     * vendors that fail on first use.
     *
     * <p>No field here may match the platform's redaction pattern
     * ({@code pass|pwd|secret|token|apikey|api[_-]?key|authorization|credential|privatekey}) --
     * a field named {@code apiKeyEnvVar} would come back {@code <redacted>} in every support bundle
     * and destroy the one diagnostic this endpoint exists to give. Hence {@code keyPresent} and
     * {@code keyEnvVarName}.
     */
    @GetMapping("/config")
    public Map<String, Object> config(HttpServletRequest httpRequest) {
        runtimeContextService.currentContext(httpRequest); // any authenticated caller; throws if not

        List<ExternalAiVendorSummary> summaries = configuredVendors();
        List<Map<String, Object>> vendors = new ArrayList<>();
        for (ExternalAiVendorSummary summary : summaries) {
            Map<String, Object> vendor = new LinkedHashMap<>();
            vendor.put("id", summary.vendorId());
            vendor.put("defaultModel", summary.defaultModel() == null ? "" : summary.defaultModel());
            // The server knows one model per vendor -- the configured default. Rather than ship a
            // curated catalogue that rots silently every time a vendor renames a model, this is a
            // SUGGESTION list and the page pairs it with a free-text input.
            vendor.put("models", summary.defaultModel() == null ? List.of() : List.of(summary.defaultModel()));
            vendor.put("keyEnvVarName", summary.keyEnvVarName() == null ? "" : summary.keyEnvVarName());
            vendor.put("keyPresent", summary.keyPresent());
            vendor.put("effortSupported", summary.effortSupported());
            vendors.add(vendor);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("configured", summaries.stream().anyMatch(ExternalAiVendorSummary::keyPresent));
        response.put("vendors", vendors);
        response.put("effortSupported", summaries.stream().anyMatch(ExternalAiVendorSummary::effortSupported));
        return response;
    }

    /**
     * Every failure here returns an EXPLICIT body rather than throwing
     * {@link ResponseStatusException}, because Spring Boot defaults
     * {@code server.error.include-message} to {@code never}: a thrown exception's carefully worded
     * reason arrives at the browser as an empty string. These reasons are the entire point of the
     * 409 -- "no API key configured (env var NPDEV_EXTERNALAI_ANTHROPIC_API_KEY)" is what turns a
     * dead button into a fixable one -- so the body has to be ours.
     *
     * <p>The 403 from {@code requireSuperUser} is the exception, and stays an exception: there is
     * nothing to tell an unauthorized caller.
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            HttpServletRequest httpRequest, @RequestBody GenerateRequest body) {
        requireSuperUser(httpRequest);

        if (body == null || body.prompt() == null || body.prompt().isBlank()) {
            return failure(HttpStatus.BAD_REQUEST, "PROMPT_REQUIRED", "prompt is required");
        }
        if (body.prompt().length() > MAX_PROMPT_CHARS) {
            return failure(HttpStatus.PAYLOAD_TOO_LARGE, "PROMPT_TOO_LARGE",
                    "prompt is " + body.prompt().length() + " characters; the limit is " + MAX_PROMPT_CHARS);
        }

        List<ExternalAiVendorSummary> summaries = configuredVendors();
        ExternalAiVendorSummary vendor = summaries.stream()
                .filter(summary -> summary.vendorId().equals(body.vendor()))
                .findFirst()
                .orElse(null);
        if (vendor == null) {
            return failure(HttpStatus.BAD_REQUEST, "UNKNOWN_VENDOR",
                    "unknown vendor '" + body.vendor() + "'; configured vendors are "
                            + summaries.stream().map(ExternalAiVendorSummary::vendorId).toList());
        }

        // Dropped rather than rejected when the vendor has no equivalent: a page that remembers a
        // selection across a vendor switch should not start failing sends because of it.
        String effort = vendor.effortSupported() ? blankToNull(body.effort()) : null;

        ExternalAiGenerationRequest request;
        try {
            request = new ExternalAiGenerationRequest(
                    vendor.vendorId(), blankToNull(body.model()), effort, body.prompt());
        } catch (IllegalArgumentException invalid) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", invalid.getMessage());
        }

        ExternalAiGenerationResult result;
        try {
            result = contract().generateText(request);
        } catch (ExternalAiEgressDeniedException denied) {
            LOG.info("agent-proxy generate denied: vendor={} code={}", vendor.vendorId(), denied.code());
            return failure(HttpStatus.CONFLICT, denied.code(), denied.getMessage());
        } catch (RuntimeException failed) {
            LOG.warn("agent-proxy generate failed: vendor={} model={}", vendor.vendorId(), body.model());
            return failure(HttpStatus.BAD_GATEWAY, "VENDOR_CALL_FAILED", failed.getMessage());
        }

        LOG.info("agent-proxy generate ok: vendor={} model={}", result.vendorId(), result.model());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("vendor", result.vendorId());
        response.put("model", result.model());
        response.put("text", result.text());
        response.put("raw", truncate(result.rawResponse()));
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<Map<String, Object>> failure(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }

    private List<ExternalAiVendorSummary> configuredVendors() {
        ExternalAiCapabilityContract contract = externalAiCapabilityContract.getIfAvailable();
        return contract == null ? List.of() : contract.configuredVendors();
    }

    private ExternalAiCapabilityContract contract() {
        ExternalAiCapabilityContract contract = externalAiCapabilityContract.getIfAvailable();
        if (contract == null) {
            throw new ExternalAiEgressDeniedException(
                    "EGRESS_DENIED_NOT_CONFIGURED",
                    "This app has no external-AI provider wired; set NPDEV_EXTERNALAI_PROVIDER=http "
                            + "and a vendor key in secrets/agent-proxy.env.");
        }
        return contract;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_RAW_CHARS ? raw : raw.substring(0, MAX_RAW_CHARS) + "... (truncated)";
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
