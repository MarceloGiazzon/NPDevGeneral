package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ExternalAiAst;
import com.npdev.dsl.v1.ast.ExternalAiPromptAst;
import com.npdev.dsl.v1.ast.ExternalAiProviderAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.PropertyAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.compiler.AiCatalogueProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * P4 (G3): author-time checks for {@code externalAi.prompts[]} / {@code externalAi.limits} and for
 * every flow {@code capabilityCall externalAi.generate} -- so a typo'd prompt name, a vendor the app
 * never opted into, or a limit naming no declared property is caught at validate time instead of as
 * a failed flow step in production.
 */
final class ExternalAiValidation {

    /** {{ path }} where path is a dotted identifier chain. Anything else between braces is an error. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");
    private static final Pattern PLACEHOLDER_PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> REVIEW_MODES = Set.of("required", "optional", "none");

    /**
     * AIP-3 (3.2): the {@code appVendor} providers of the AI catalogue bundled into this jar (from
     * {@code NPDevCli/ai-providers.json}, the file external-ai-http builds its vendor profiles from), id to
     * {@code baseUrl}, in catalogue order. Loaded once, on first use; missing means a broken build, so it
     * fails loudly.
     */
    static final class AppVendorCatalogue {
        static final String RESOURCE = "npdev/dsl/ai-providers.json";
        static final Map<String, String> BASE_URLS = load();
        static final Set<String> IDS = BASE_URLS.keySet();
        /** AIP-3 (3.6): every shipped provider id, app vendor or not -- an app's own provider may reuse none. */
        static final Set<String> ALL_IDS = loadAllIds();

        private AppVendorCatalogue() {
        }

        /** AIP-3 (3.4c): a vendor served from the machine the app runs on (a local LLM server). */
        static boolean isLoopback(String vendorId) {
            return isLoopbackUrl(BASE_URLS.get(vendorId));
        }

        static boolean isLoopbackUrl(String baseUrl) {
            if (baseUrl == null) {
                return false;
            }
            try {
                String host = URI.create(baseUrl).getHost();
                return host != null && (host.equalsIgnoreCase("localhost") || host.startsWith("127.")
                        || host.equals("[::1]") || host.equals("::1") || host.equals("0.0.0.0"));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private static Set<String> loadAllIds() {
            try (InputStream in = ExternalAiValidation.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not on the classpath -- rebuild the dsl jar");
                }
                Set<String> ids = new java.util.LinkedHashSet<>();
                MAPPER.readTree(in).path("providers")
                        .forEach(provider -> ids.add(provider.path("id").asText().toLowerCase(Locale.ROOT)));
                return Collections.unmodifiableSet(ids);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + RESOURCE, e);
            }
        }

        private static Map<String, String> load() {
            try (InputStream in = ExternalAiValidation.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not on the classpath -- the dsl build copies it "
                            + "from NPDevCli/ai-providers.json; rebuild the dsl jar");
                }
                Map<String, String> baseUrls = new LinkedHashMap<>();
                for (JsonNode provider : MAPPER.readTree(in).path("providers")) {
                    if (provider.path("appVendor").asBoolean(false) && !provider.path("disabled").asBoolean(false)) {
                        baseUrls.put(provider.path("id").asText().toLowerCase(Locale.ROOT),
                                provider.path("baseUrl").asText());
                    }
                }
                return Collections.unmodifiableMap(baseUrls);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + RESOURCE, e);
            }
        }
    }

    private ExternalAiValidation() {
    }

    /** AIP-3 (3.4a): the vendor a prompt calls -- its own, or its catalogue profile's; "" when neither resolves. */
    private static String resolvedVendor(ExternalAiPromptAst prompt) {
        String vendor = prompt.vendor();
        if (prompt.profile() != null && !prompt.profile().isBlank()) {
            vendor = AiCatalogueProfiles.find(prompt.profile()).map(AiCatalogueProfiles.Profile::provider).orElse(null);
        }
        return vendor == null ? "" : vendor.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * AIP-3 (3.4c): a keyless local provider is a valid app vendor, but its loopback address is the
     * machine the APP runs on -- fine on a developer laptop, unreachable from a server deployment unless
     * the same server also runs the LLM. A warning, never an error.
     */
    private static void warnIfLocal(ExternalAiPromptAst prompt, Map<String, String> ownBaseUrls, List<String> warnings) {
        String vendor = resolvedVendor(prompt);
        String baseUrl = ownBaseUrls.containsKey(vendor) ? ownBaseUrls.get(vendor) : AppVendorCatalogue.BASE_URLS.get(vendor);
        if (AppVendorCatalogue.isLoopbackUrl(baseUrl)) {
            warnings.add("externalAi.prompts['" + prompt.name() + "'] uses vendor '" + vendor
                    + "', a local LLM server at " + baseUrl + " -- a deployed app "
                    + "reaches it only if that server runs on the same host as the app; pick a cloud vendor for a "
                    + "server deployment.");
        }
    }

    static void validate(ModelAst modelAst, List<String> errors, List<String> warnings) {
        ExternalAiAst externalAi = modelAst.getExternalAi();
        List<ExternalAiPromptAst> prompts = externalAi == null ? List.of() : externalAi.getPrompts();
        Set<String> promptNames = new HashSet<>();
        Set<String> reviewRequired = new HashSet<>();

        if (externalAi != null) {
            boolean egressDenied = "denied".equalsIgnoreCase(externalAi.getEgress());
            if (!prompts.isEmpty() && egressDenied) {
                errors.add("externalAi.prompts declares " + prompts.size() + " prompt(s) but externalAi.egress is "
                        + "'denied' -- suggestedFix: set externalAi.egress to 'apiEnabled' (and list the vendor in "
                        + "externalAi.vendors), or remove the prompts; a denied app can never run them.");
            }
            Set<String> vendors = externalAi.getVendors().stream()
                    .map(vendor -> vendor.trim().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            Map<String, String> ownBaseUrls = validateProviders(externalAi.getProviders(), errors);
            for (String vendor : externalAi.getVendors()) {
                String id = vendor.trim().toLowerCase(Locale.ROOT);
                if (!AppVendorCatalogue.IDS.contains(id) && !ownBaseUrls.containsKey(id)) {
                    errors.add("externalAi.vendors: '" + vendor + "' is not an app vendor in the NPDev AI catalogue "
                            + "nor one of this model's externalAi.providers -- known: " + AppVendorCatalogue.IDS
                            + (ownBaseUrls.isEmpty() ? "" : " + " + ownBaseUrls.keySet())
                            + " -- suggestedFix: use one of the known ids (`npdev ai providers` lists them), or declare "
                            + "your own endpoint in externalAi.providers.");
                }
            }
            for (ExternalAiPromptAst prompt : prompts) {
                validatePrompt(prompt, vendors, promptNames, errors);
                warnIfLocal(prompt, ownBaseUrls, warnings);
                if (prompt.reviewRequired() && prompt.name() != null) {
                    reviewRequired.add(prompt.name().trim());
                }
            }
            Map<String, PropertyAst> properties = modelAst.getProperties().stream()
                    .collect(Collectors.toMap(PropertyAst::name, Function.identity(), (left, right) -> left));
            validateLimit("callsPerUserPerDay", externalAi.getCallsPerUserPerDayProperty(),
                    Set.of("int"), properties, errors);
            // properties[] has no decimal type: whole dollars as int, or a decimal string ("2.50").
            validateLimit("monthlyCostCapUsd", externalAi.getMonthlyCostCapUsdProperty(),
                    Set.of("int", "string"), properties, errors);
        }

        for (FlowAst flow : modelAst.getFlows()) {
            validateGenerateCalls(flow, flow.getSteps(), promptNames, reviewRequired, errors);
        }
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            validateProcedureGenerateCalls(procedure.name(), procedure.steps(), promptNames, reviewRequired, errors);
        }
    }

    /**
     * AIP-3 (3.6, D4): the app's own endpoints. Returns id to baseUrl of every entry whose id is usable, so a
     * vendors[] reference to it is not reported twice. The key variable is derived from the id, so there is
     * no authored key name to check; the id may not shadow a shipped provider, whose key it would then get.
     */
    private static Map<String, String> validateProviders(List<ExternalAiProviderAst> providers, List<String> errors) {
        Map<String, String> ids = new LinkedHashMap<>();
        Set<String> protocols = Set.of("openai-chat", "anthropic-messages", "gemini-native");
        for (ExternalAiProviderAst provider : providers) {
            String id = provider.id() == null ? "" : provider.id().trim();
            String prefix = "externalAi.providers '" + id + "': ";
            if (!id.matches("[a-z][a-z0-9-]{0,39}")) {
                errors.add("externalAi.providers: id '" + id + "' must be lower-case letters, digits and '-', "
                        + "starting with a letter (at most 40)");
                continue;
            }
            if (AppVendorCatalogue.ALL_IDS.contains(id)) {
                errors.add(prefix + "the id is a provider the NPDev AI catalogue ships -- suggestedFix: list '" + id
                        + "' in vendors without redeclaring it, or give your endpoint its own id (e.g. 'my-" + id + "')");
                continue;
            }
            if (ids.containsKey(id)) {
                errors.add(prefix + "duplicate provider id");
                continue;
            }
            if (provider.protocol() == null || !protocols.contains(provider.protocol())) {
                errors.add(prefix + "protocol '" + provider.protocol() + "' must be one of " + new java.util.TreeSet<>(protocols));
            }
            if (provider.defaultModel() == null || provider.defaultModel().isBlank()) {
                errors.add(prefix + "defaultModel is required (the model a prompt naming none calls)");
            }
            String baseUrl = provider.baseUrl() == null ? "" : provider.baseUrl().trim();
            String problem = baseUrlProblem(baseUrl);
            if (problem != null) {
                errors.add(prefix + "baseUrl '" + baseUrl + "' " + problem
                        + " -- suggestedFix: point baseUrl at the endpoint root over https, e.g. https://ai.example.com/v1");
            }
            if ((provider.inputUsdPerMTok() != null && provider.inputUsdPerMTok().signum() < 0)
                    || (provider.outputUsdPerMTok() != null && provider.outputUsdPerMTok().signum() < 0)) {
                errors.add(prefix + "price.in and price.out must be >= 0");
            }
            ids.put(id, baseUrl);
        }
        return ids;
    }

    /** Null when usable: an absolute https URL, or http to a loopback host (a key never crosses a network in clear). */
    private static String baseUrlProblem(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            return "is not a URL";
        }
        if (uri.getHost() == null || uri.getScheme() == null) {
            return "must be an absolute URL with a host, e.g. https://ai.example.com/v1";
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            return "must not carry a query, a fragment or credentials -- the key travels in the auth header";
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) {
            return null;
        }
        if (scheme.equals("http") && AppVendorCatalogue.isLoopbackUrl(baseUrl)) {
            return null;
        }
        return "must use https (http is allowed only to localhost / 127.x / [::1])";
    }

    private static void validatePrompt(
            ExternalAiPromptAst prompt, Set<String> vendors, Set<String> promptNames, List<String> errors) {
        String name = prompt.name() == null ? "" : prompt.name().trim();
        String prefix = "externalAi.prompts '" + name + "': ";
        if (name.isEmpty()) {
            errors.add("externalAi.prompts: every prompt needs a non-blank name"
                    + " -- suggestedFix: give the prompt a unique 'name' that generate calls can reference");
            return;
        }
        if (!promptNames.add(name)) {
            errors.add(prefix + "duplicate prompt name");
        }
        boolean hasVendor = prompt.vendor() != null && !prompt.vendor().isBlank();
        boolean hasProfile = prompt.profile() != null && !prompt.profile().isBlank();
        String vendor = resolvedVendor(prompt);
        if (hasVendor && hasProfile) {
            errors.add(prefix + "give vendor or profile, not both -- suggestedFix: keep the profile and drop vendor"
                    + " (and model), or keep vendor and drop profile");
        } else if (hasProfile && prompt.model() != null && !prompt.model().isBlank()) {
            errors.add(prefix + "model cannot be set with a profile (the profile names its model) -- suggestedFix:"
                    + " drop model, or replace the profile with vendor + model");
        } else if (!hasVendor && !hasProfile) {
            errors.add(prefix + "vendor or profile is required");
        } else if (hasProfile && AiCatalogueProfiles.find(prompt.profile()).isEmpty()) {
            errors.add(prefix + "profile '" + prompt.profile() + "' is not in the NPDev AI catalogue -- known: "
                    + AiCatalogueProfiles.names() + " -- suggestedFix: use one of the known profiles (`npdev ai profiles`)");
        } else if (!vendors.contains(vendor)) {
            errors.add(prefix + "vendor '" + vendor + "'" + (hasProfile ? " (from profile '" + prompt.profile() + "')" : "")
                    + " is not in externalAi.vendors " + vendors
                    + " -- suggestedFix: add it to externalAi.vendors (the app-level allow-list) or pick a listed vendor");
        }
        if (prompt.effort() != null && !Set.of("low", "medium", "high").contains(prompt.effort())) {
            errors.add(prefix + "effort '" + prompt.effort() + "' must be low, medium or high");
        }
        String template = prompt.template() == null ? "" : prompt.template();
        if (template.isBlank()) {
            errors.add(prefix + "template is required");
        } else {
            Matcher matcher = PLACEHOLDER.matcher(template);
            while (matcher.find()) {
                if (!PLACEHOLDER_PATH.matcher(matcher.group(1)).matches()) {
                    errors.add(prefix + "template placeholder '" + matcher.group() + "' is not a field path "
                            + "(expected {{field}} or {{field.nested}}) -- suggestedFix: rename the placeholder to an input field path"
                            + " such as {{rows}} or {{cap.label}}");
                }
            }
            String stripped = PLACEHOLDER.matcher(template).replaceAll("");
            if (stripped.contains("{{") || stripped.contains("}}")) {
                errors.add(prefix + "template has an unbalanced '{{' or '}}'"
                        + " -- suggestedFix: close every {{ with a matching }} (or remove the stray braces)");
            }
        }
        if (prompt.outputSchemaJson() == null) {
            errors.add(prefix + "outputSchema is required -- a flow never receives unvalidated vendor output");
        } else {
            try {
                JsonNode schema = MAPPER.readTree(prompt.outputSchemaJson());
                if (!schema.isObject() || !schema.hasNonNull("type")) {
                    errors.add(prefix + "outputSchema must be a JSON Schema object with a top-level 'type'");
                }
            } catch (IOException exception) {
                errors.add(prefix + "outputSchema is not valid JSON: " + exception.getMessage()
                        + " -- suggestedFix: fix the outputSchema so it parses as a JSON Schema object");
            }
        }
        if (prompt.maxOutputTokens() != null && prompt.maxOutputTokens() < 1) {
            errors.add(prefix + "maxOutputTokens must be >= 1");
        }
        if (!REVIEW_MODES.contains(prompt.review())) {
            errors.add(prefix + "review '" + prompt.review() + "' is not one of " + REVIEW_MODES
                    + " -- suggestedFix: use 'required' (a human approves before anything applies the answer), "
                    + "'optional' or 'none'");
        }
    }

    private static void validateLimit(
            String limit, String propertyKey, Set<String> allowedTypes,
            Map<String, PropertyAst> properties, List<String> errors) {
        if (propertyKey == null) {
            return;
        }
        PropertyAst property = properties.get(propertyKey);
        if (property == null) {
            errors.add("externalAi.limits." + limit + " names property '" + propertyKey
                    + "' which is not declared in properties[] -- suggestedFix: declare it (with a default) so the "
                    + "limit resolves through the scoped-property cascade");
            return;
        }
        String type = property.type() == null ? "" : property.type().trim().toLowerCase(Locale.ROOT);
        if (!allowedTypes.contains(type)) {
            errors.add("externalAi.limits." + limit + " names property '" + propertyKey + "' of type '"
                    + property.type() + "'; expected one of " + allowedTypes);
        }
    }

    private static void validateGenerateCalls(
            FlowAst flow, List<StepAst> steps, Set<String> promptNames, Set<String> reviewRequired,
            List<String> errors) {
        if (steps == null) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            StepAst step = steps.get(i);
            if (step == null) {
                continue;
            }
            if (isGenerate(step.getCapability(), step.getOperation())) {
                List<String> args = step.getArgs();
                String prefix = "Flow " + flow.getName() + " step " + step.getName() + ": ";
                String promptArg = args == null || args.isEmpty() ? null : args.get(0);
                checkPromptArg(promptArg, prefix, promptNames, errors);
                if (reviewRequired.contains(trim(promptArg))) {
                    checkReviewGate(prefix, trim(promptArg), step, i + 1 < steps.size() ? steps.get(i + 1) : null, errors);
                }
            }
            validateGenerateCalls(flow, step.getThenSteps(), promptNames, reviewRequired, errors);
            validateGenerateCalls(flow, step.getElseSteps(), promptNames, reviewRequired, errors);
            validateGenerateCalls(flow, step.getLoopSteps(), promptNames, reviewRequired, errors);
            validateGenerateCalls(flow, step.getOnFailureSteps(), promptNames, reviewRequired, errors);
            validateGenerateCalls(flow, step.getOnTimeoutSteps(), promptNames, reviewRequired, errors);
        }
    }

    /**
     * AI7-1: a {@code review: required} prompt's answer may not reach any step before a human has seen
     * it, so the call must be followed IMMEDIATELY (same step list) by the awaitEvent task that
     * presents exactly that output as its proposal. Anything between the two could read the answer.
     */
    private static void checkReviewGate(String prefix, String promptName, StepAst call, StepAst next, List<String> errors) {
        String output = trim(call.getOutput());
        String fix = " -- suggestedFix: put an awaitEvent step with a task block directly after this step, with"
                + " task.proposal.value set to the call's output (e.g. \"output\": \"$suggestion\" and"
                + " \"proposal\": { \"value\": \"$suggestion\" }), and apply the values only after it";
        if (output.isEmpty()) {
            errors.add(prefix + "prompt '" + promptName + "' has review: required but this call has no output to review" + fix);
            return;
        }
        boolean gated = next != null && next.getTask() != null && output.equals(trim(next.getTask().getProposalValue()));
        if (!gated) {
            errors.add(prefix + "prompt '" + promptName + "' has review: required, so the next step must be an awaitEvent"
                    + " task whose proposal.value is '" + output + "'" + fix);
        }
    }

    private static void validateProcedureGenerateCalls(
            String procedureName, List<ProcedureStepAst> steps, Set<String> promptNames, Set<String> reviewRequired,
            List<String> errors) {
        if (steps == null) {
            return;
        }
        for (ProcedureStepAst step : steps) {
            if (step == null) {
                continue;
            }
            if (isGenerate(step.capability(), step.operation())) {
                // A procedure's args object reaches the runtime positional in ALPHABETICAL key order
                // (CompiledProcedureStep), so "first" means nothing here: the runtime takes the one
                // string arg as the prompt name, and so does this check -- the literal (non-$) value.
                String literal = step.args().values().stream()
                        .filter(value -> value instanceof String text && !text.trim().startsWith("$"))
                        .map(String::valueOf)
                        .findFirst().orElse(null);
                String prefix = "Procedure " + procedureName + " step " + step.name() + ": ";
                checkPromptArg(literal, prefix, promptNames, errors);
                if (reviewRequired.contains(trim(literal))) {
                    errors.add(prefix + "prompt '" + trim(literal) + "' has review: required, and a procedure cannot wait"
                            + " for a human -- suggestedFix: call it from a flow, followed by an awaitEvent task whose"
                            + " proposal.value is the call's output");
                }
            }
            validateProcedureGenerateCalls(procedureName, step.thenSteps(), promptNames, reviewRequired, errors);
            validateProcedureGenerateCalls(procedureName, step.elseSteps(), promptNames, reviewRequired, errors);
            validateProcedureGenerateCalls(procedureName, step.steps(), promptNames, reviewRequired, errors);
        }
    }

    private static boolean isGenerate(String capability, String operation) {
        return "externalai".equalsIgnoreCase(trim(capability)) && "generate".equalsIgnoreCase(trim(operation));
    }

    private static void checkPromptArg(String firstArg, String prefix, Set<String> promptNames, List<String> errors) {
        String promptName = trim(firstArg);
        if (promptName.isEmpty() || promptName.startsWith("$")) {
            errors.add(prefix + "externalAi.generate needs the prompt name as a literal arg "
                    + "(e.g. args: [\"PaintMosaic\", \"$input\"]) -- never a $ref, so the call is checkable here"
                    + " -- suggestedFix: pass the prompt name literally, e.g. args: { \"prompt\": \"PaintMosaic\","
                    + " \"input\": \"$input\" }");
        } else if (!promptNames.contains(promptName)) {
            errors.add(prefix + "externalAi.generate names prompt '" + promptName
                    + "' which is not declared in externalAi.prompts " + promptNames);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
