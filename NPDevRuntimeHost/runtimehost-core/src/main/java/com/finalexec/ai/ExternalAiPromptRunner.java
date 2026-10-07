package com.finalexec.ai;

import com.finalexec.filestore.TenantFileReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.npdev.dsl.v1.compiled.CompiledExternalAi;
import com.npdev.dsl.v1.compiled.CompiledExternalAiPrompt;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiStructuredRequest;
import com.npdev.kernel.ports.ExternalAiStructuredResult;
import com.npdev.kernel.ports.FileStoreContract;
import com.npdev.kernel.properties.PropertyResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P4 (G3, AI as a model capability): the {@code externalAi} capability a flow reaches with
 * {@code capabilityCall externalAi.generate args: ["<prompt>", "$input"]}. It owns everything between
 * the flow and the vendor port, in this order, each step able to stop the call:
 * <ol>
 *   <li>ADR-0009 egress: the model's {@code externalAi.egress} must be {@code apiEnabled} and the
 *       prompt's vendor in {@code externalAi.vendors} (re-checked here, not only at validate time);</li>
 *   <li>limits: {@code externalAi.limits} names properties resolved through the scoped-property cascade
 *       for THIS caller -- calls per user per UTC day, and the tenant's estimated spend per UTC month
 *       -- counted from this runner's own audit rows, so no extra table exists to drift;</li>
 *   <li>the template's {@code {{field}}} placeholders filled from the input map, and the optional image
 *       read from the platform file store -- only a handle under the CALLER's tenant, never a URL;</li>
 *   <li>{@link ExternalAiCapabilityContract#generateStructured} (inproc = offline sample, http = vendor);</li>
 *   <li>the answer parsed and validated against the prompt's {@code outputSchema} -- a flow never
 *       receives unvalidated vendor output.</li>
 * </ol>
 * Every outcome (including denials) is appended to the audit log with vendor, model, token counts and
 * estimated cost -- never the prompt text, the input values or the answer.
 */
public final class ExternalAiPromptRunner implements CapabilityAdapter {

    public static final String AUDIT_ACTION = "externalAi.generate";
    static final String AUDIT_RESOURCE_TYPE = "externalAiPrompt";
    static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*}}");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** USD per million tokens, input and output. */
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
        public Price {
            inputPerMillion = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
            outputPerMillion = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
        }
    }

    private final Supplier<CompiledModel> modelSupplier;
    private final ExternalAiCapabilityContract contract;
    private final Supplier<PropertyResolver> propertyResolver;
    private final AuditLogStore auditLogStore;
    private final TenantFileReader fileReader;
    private final Map<String, Price> pricesByVendor;
    private final Clock clock;
    private final Map<String, JsonSchema> compiledSchemas = new ConcurrentHashMap<>();

    public ExternalAiPromptRunner(
            Supplier<CompiledModel> modelSupplier,
            ExternalAiCapabilityContract contract,
            Supplier<PropertyResolver> propertyResolver,
            AuditLogStore auditLogStore,
            Supplier<FileStoreContract> fileStore,
            Map<String, Price> pricesByVendor,
            Clock clock
    ) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier");
        this.contract = Objects.requireNonNull(contract, "contract");
        this.propertyResolver = propertyResolver == null ? () -> null : propertyResolver;
        this.auditLogStore = auditLogStore == null ? AuditLogStore.noop() : auditLogStore;
        this.fileReader = new TenantFileReader(fileStore);
        this.pricesByVendor = pricesByVendor == null ? Map.of() : Map.copyOf(pricesByVendor);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String adapterId() {
        return "externalAi";
    }

    @Override
    public String capability() {
        return "externalAi";
    }

    @Override
    public String capabilityType() {
        return "ExternalAiCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"generate".equals(call.operation())) {
            return CapabilityResult.failure("EXTERNAL_AI_OPERATION_UNSUPPORTED",
                    "Unsupported externalAi operation: " + call.operation() + " (flows call 'generate')",
                    CapabilityErrorKind.CONTRACT, Map.of("operation", String.valueOf(call.operation())));
        }
        List<Object> args = call.args() == null ? List.of() : call.args();
        // Args are matched by TYPE, not position: a flow passes ["Prompt", "$input"], but a procedure's
        // args object is positional in ALPHABETICAL key order (CompiledProcedureStep), so
        // { "prompt": .., "input": .. } arrives as [input, prompt]. The name is the one string arg,
        // the input the one map arg.
        String promptName = "";
        Map<String, Object> input = Map.of();
        for (Object arg : args) {
            if (arg instanceof CharSequence text && promptName.isEmpty()) {
                promptName = text.toString().trim();
            } else if (arg instanceof Map<?, ?> map && input.isEmpty()) {
                input = stringKeys(map);
            }
        }
        String tenantId = stateText(contextState, "tenantId", "default");
        String actorId = stateText(contextState, "actorId", "anonymous");
        return generate(promptName, input, contextState == null ? Map.of() : contextState,
                ExecutionContext.of(tenantId, actorId));
    }

    CapabilityResult generate(String promptName, Map<String, Object> input, Map<String, Object> callState,
                              ExecutionContext context) {
        CompiledModel model = modelSupplier.get();
        CompiledExternalAi externalAi = model == null ? null : model.getExternalAi();
        if (externalAi == null || !"apiEnabled".equalsIgnoreCase(externalAi.getEgress())) {
            return deny(context, promptName, null, "EGRESS_DENIED",
                    "externalAi.egress is not 'apiEnabled' for this app (ADR-0009) -- nothing is sent", Map.of());
        }
        CompiledExternalAiPrompt prompt = externalAi.findPrompt(promptName).orElse(null);
        if (prompt == null) {
            return deny(context, promptName, null, "UNKNOWN_PROMPT",
                    "No externalAi.prompts entry named '" + promptName + "'", Map.of());
        }
        String vendor = prompt.vendor() == null ? "" : prompt.vendor().trim().toLowerCase(Locale.ROOT);
        boolean allowed = externalAi.getVendors().stream()
                .anyMatch(listed -> listed.trim().equalsIgnoreCase(vendor));
        if (!allowed) {
            return deny(context, promptName, prompt, "VENDOR_NOT_ALLOWED",
                    "Vendor '" + prompt.vendor() + "' is not in externalAi.vendors", Map.of());
        }

        CapabilityResult limitDenial = checkLimits(externalAi, prompt, context);
        if (limitDenial != null) {
            return limitDenial;
        }

        String renderedPrompt = render(prompt.template(), input, callState);
        byte[] imageBytes = null;
        String imageMime = null;
        if (prompt.image() != null && !prompt.image().isBlank()) {
            Object imageValue = lookup(input, prompt.image());
            if (imageValue != null && !(imageValue instanceof String text && text.isBlank())) {
                TenantFileReader.ImageInput image;
                try {
                    image = fileReader.readImage(imageValue, context.tenantId(), MAX_IMAGE_BYTES);
                } catch (IllegalArgumentException exception) {
                    return deny(context, promptName, prompt, "IMAGE_REJECTED", exception.getMessage(), Map.of());
                }
                imageBytes = image.bytes();
                imageMime = image.mimeType();
            }
        }

        long started = clock.millis();
        ExternalAiStructuredResult result;
        try {
            result = contract.generateStructured(new ExternalAiStructuredRequest(
                    vendor, prompt.model(), renderedPrompt, prompt.outputSchemaJson(),
                    imageBytes, imageMime, prompt.maxOutputTokens()));
        } catch (ExternalAiEgressDeniedException exception) {
            // e.g. EGRESS_DENIED_NO_API_KEY: the http adapter has no key for this vendor.
            return deny(context, promptName, prompt, exception.code(), exception.getMessage(), Map.of());
        } catch (RuntimeException exception) {
            audit(context, promptName, prompt, "failure", "VENDOR_CALL_FAILED", Map.of(
                    "latencyMs", String.valueOf(clock.millis() - started),
                    "image", String.valueOf(imageBytes != null)));
            return CapabilityResult.failure("EXTERNAL_AI_VENDOR_CALL_FAILED",
                    "External AI call for prompt '" + promptName + "' failed: " + exception.getMessage(),
                    CapabilityErrorKind.TRANSIENT, Map.of("prompt", promptName, "vendor", vendor));
        }

        BigDecimal cost = estimateCost(vendor, result.inputTokens(), result.outputTokens());
        Map<String, String> usage = new LinkedHashMap<>();
        usage.put("model", String.valueOf(result.model()));
        usage.put("inputTokens", String.valueOf(result.inputTokens()));
        usage.put("outputTokens", String.valueOf(result.outputTokens()));
        usage.put("costUsd", cost.toPlainString());
        usage.put("latencyMs", String.valueOf(clock.millis() - started));
        usage.put("image", String.valueOf(imageBytes != null));

        JsonNode answer;
        try {
            answer = MAPPER.readTree(result.json());
        } catch (Exception exception) {
            answer = null;
        }
        List<String> problems = answer == null
                ? List.of("answer is not JSON")
                : schemaFor(prompt).validate(answer).stream().map(ValidationMessage::getMessage).limit(5).toList();
        if (!problems.isEmpty()) {
            audit(context, promptName, prompt, "failure", "INVALID_AI_OUTPUT", usage);
            return CapabilityResult.failure("EXTERNAL_AI_INVALID_OUTPUT",
                    "The answer to prompt '" + promptName + "' does not match its outputSchema: " + problems,
                    CapabilityErrorKind.CONTRACT, Map.of("prompt", promptName, "problems", problems));
        }
        audit(context, promptName, prompt, "success", null, usage);
        return CapabilityResult.success(MAPPER.convertValue(answer, Object.class));
    }

    // ------------------------------------------------------------------------------------------
    // Limits
    // ------------------------------------------------------------------------------------------

    private CapabilityResult checkLimits(CompiledExternalAi externalAi, CompiledExternalAiPrompt prompt,
                                         ExecutionContext context) {
        String callsProperty = externalAi.getCallsPerUserPerDayProperty();
        String costProperty = externalAi.getMonthlyCostCapUsdProperty();
        if (callsProperty == null && costProperty == null) {
            return null;
        }
        PropertyResolver resolver = propertyResolver.get();
        if (resolver == null) {
            return deny(context, prompt.name(), prompt, "LIMIT_UNRESOLVABLE",
                    "externalAi.limits are declared but no PropertyResolver is available -- denying rather than "
                            + "running unbounded", Map.of());
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (callsProperty != null) {
            BigDecimal limit = resolveNumber(resolver, callsProperty, context);
            if (limit == null) {
                return deny(context, prompt.name(), prompt, "LIMIT_UNRESOLVABLE",
                        "Property '" + callsProperty + "' did not resolve to a number", Map.of());
            }
            long since = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long used = billableRows(context.tenantId(), context.actorId(), since).size();
            if (BigDecimal.valueOf(used).compareTo(limit) >= 0) {
                return deny(context, prompt.name(), prompt, "QUOTA_EXCEEDED",
                        "Daily AI quota reached (" + used + "/" + limit.toPlainString() + " calls today, property '"
                                + callsProperty + "')", Map.of("used", String.valueOf(used), "limit", limit.toPlainString()));
            }
        }
        if (costProperty != null) {
            BigDecimal cap = resolveNumber(resolver, costProperty, context);
            if (cap == null) {
                return deny(context, prompt.name(), prompt, "LIMIT_UNRESOLVABLE",
                        "Property '" + costProperty + "' did not resolve to a number", Map.of());
            }
            long since = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            BigDecimal spent = billableRows(context.tenantId(), null, since).stream()
                    .map(row -> parseDecimal(row.meta() == null ? null : row.meta().get("costUsd")))
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (spent.compareTo(cap) >= 0) {
                return deny(context, prompt.name(), prompt, "BUDGET_EXCEEDED",
                        "Monthly AI budget reached (US$" + spent.setScale(4, RoundingMode.HALF_UP).toPlainString()
                                + " of US$" + cap.toPlainString() + ", property '" + costProperty + "')",
                        Map.of("spentUsd", spent.toPlainString(), "capUsd", cap.toPlainString()));
            }
        }
        return null;
    }

    /** Rows for calls that reached a vendor (success or an invalid answer) -- denials cost nothing. */
    private List<AuditRecord> billableRows(String tenantId, String actorId, long sinceMs) {
        List<AuditRecord> rows = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<AuditRecord> page = auditLogStore.search(new AuditQuery(
                    tenantId, actorId, AUDIT_ACTION, AUDIT_RESOURCE_TYPE, null, sinceMs, null, 1000, offset));
            for (AuditRecord row : page) {
                if ("success".equals(row.outcome()) || "INVALID_AI_OUTPUT".equals(row.reasonCode())) {
                    rows.add(row);
                }
            }
            if (page.size() < 1000) {
                return rows;
            }
            offset += page.size();
        }
    }

    private static BigDecimal resolveNumber(PropertyResolver resolver, String property, ExecutionContext context) {
        try {
            Object value = resolver.resolve(property, context);
            return value instanceof Number number ? new BigDecimal(number.toString()) : parseDecimal(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal estimateCost(String vendor, long inputTokens, long outputTokens) {
        Price price = pricesByVendor.get(vendor);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return price.inputPerMillion().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 8, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    // ------------------------------------------------------------------------------------------
    // Template + image
    // ------------------------------------------------------------------------------------------

    /**
     * {{path}} resolves against the call's input map first, then the calling flow/procedure state --
     * so a template can use a prior step's output (e.g. {{caps}} after a listConcepts step) without
     * the author assembling one combined map first.
     */
    static String render(String template, Map<String, Object> input, Map<String, Object> callState) {
        Matcher matcher = PLACEHOLDER.matcher(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = lookup(input, matcher.group(1));
            if (value == null && callState != null) {
                value = lookup(callState, matcher.group(1));
            }
            String text;
            if (value == null) {
                text = "";
            } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                try {
                    text = MAPPER.writeValueAsString(value);
                } catch (Exception exception) {
                    text = String.valueOf(value);
                }
            } else {
                text = String.valueOf(value);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static Object lookup(Map<String, Object> input, String path) {
        Object current = input;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    // ------------------------------------------------------------------------------------------
    // Schema, audit, helpers
    // ------------------------------------------------------------------------------------------

    private JsonSchema schemaFor(CompiledExternalAiPrompt prompt) {
        return compiledSchemas.computeIfAbsent(prompt.name() + "\u0000" + prompt.outputSchemaJson(), ignored -> {
            try {
                return SCHEMAS.getSchema(MAPPER.readTree(prompt.outputSchemaJson()));
            } catch (Exception exception) {
                throw new IllegalStateException("outputSchema of prompt '" + prompt.name() + "' is not JSON", exception);
            }
        });
    }

    private CapabilityResult deny(ExecutionContext context, String promptName, CompiledExternalAiPrompt prompt,
                                  String reason, String message, Map<String, String> meta) {
        audit(context, promptName, prompt, "denied", reason, meta);
        Map<String, Object> details = new LinkedHashMap<>(meta);
        details.put("prompt", promptName);
        return CapabilityResult.failure("EXTERNAL_AI_" + reason, message, CapabilityErrorKind.PERMANENT, details);
    }

    private void audit(ExecutionContext context, String promptName, CompiledExternalAiPrompt prompt,
                       String outcome, String reasonCode, Map<String, String> usage) {
        Map<String, String> meta = new LinkedHashMap<>(usage);
        if (prompt != null) {
            meta.putIfAbsent("vendor", String.valueOf(prompt.vendor()));
            meta.putIfAbsent("model", prompt.model() == null ? "(vendor default)" : prompt.model());
        }
        try {
            auditLogStore.append(AuditRecord.create(
                    context.tenantId(), context.actorId(), context.roles(), AUDIT_ACTION, AUDIT_RESOURCE_TYPE,
                    promptName == null || promptName.isBlank() ? "(none)" : promptName,
                    outcome, reasonCode, Map.of(), meta));
        } catch (RuntimeException ignored) {
            // An audit-store outage must not turn a finished vendor call into a failed flow step; the
            // quota then under-counts, which is the safer direction than double-charging a retry.
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static String stateText(Map<String, Object> state, String key, String fallback) {
        Object value = state == null ? null : state.get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}
