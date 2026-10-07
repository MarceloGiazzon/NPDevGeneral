package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ExternalAiAst;
import com.npdev.dsl.v1.ast.ExternalAiPromptAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.PropertyAst;
import com.npdev.dsl.v1.ast.StepAst;

import java.io.IOException;
import java.util.HashSet;
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

    private ExternalAiValidation() {
    }

    static void validate(ModelAst modelAst, List<String> errors) {
        ExternalAiAst externalAi = modelAst.getExternalAi();
        List<ExternalAiPromptAst> prompts = externalAi == null ? List.of() : externalAi.getPrompts();
        Set<String> promptNames = new HashSet<>();

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
            for (ExternalAiPromptAst prompt : prompts) {
                validatePrompt(prompt, vendors, promptNames, errors);
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
            validateGenerateCalls(flow, flow.getSteps(), promptNames, errors);
        }
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            validateProcedureGenerateCalls(procedure.name(), procedure.steps(), promptNames, errors);
        }
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
        String vendor = prompt.vendor() == null ? "" : prompt.vendor().trim().toLowerCase(Locale.ROOT);
        if (vendor.isEmpty()) {
            errors.add(prefix + "vendor is required");
        } else if (!vendors.contains(vendor)) {
            errors.add(prefix + "vendor '" + prompt.vendor() + "' is not in externalAi.vendors " + vendors
                    + " -- suggestedFix: add it to externalAi.vendors (the app-level allow-list) or pick a listed vendor");
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
            FlowAst flow, List<StepAst> steps, Set<String> promptNames, List<String> errors) {
        if (steps == null) {
            return;
        }
        for (StepAst step : steps) {
            if (step == null) {
                continue;
            }
            if (isGenerate(step.getCapability(), step.getOperation())) {
                List<String> args = step.getArgs();
                checkPromptArg(args == null || args.isEmpty() ? null : args.get(0),
                        "Flow " + flow.getName() + " step " + step.getName() + ": ", promptNames, errors);
            }
            validateGenerateCalls(flow, step.getThenSteps(), promptNames, errors);
            validateGenerateCalls(flow, step.getElseSteps(), promptNames, errors);
            validateGenerateCalls(flow, step.getLoopSteps(), promptNames, errors);
            validateGenerateCalls(flow, step.getOnFailureSteps(), promptNames, errors);
            validateGenerateCalls(flow, step.getOnTimeoutSteps(), promptNames, errors);
        }
    }

    private static void validateProcedureGenerateCalls(
            String procedureName, List<ProcedureStepAst> steps, Set<String> promptNames, List<String> errors) {
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
                checkPromptArg(literal,
                        "Procedure " + procedureName + " step " + step.name() + ": ", promptNames, errors);
            }
            validateProcedureGenerateCalls(procedureName, step.thenSteps(), promptNames, errors);
            validateProcedureGenerateCalls(procedureName, step.elseSteps(), promptNames, errors);
            validateProcedureGenerateCalls(procedureName, step.steps(), promptNames, errors);
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
