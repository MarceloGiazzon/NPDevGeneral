package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.PropertyAst;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * INT-1: semantic checks for a declarative HTTP connector -- a capability carrying an {@code http}
 * block and bound to adapter {@code http-connector}. The JSON schema already checks shapes
 * (enums, required keys, additionalProperties:false); this class checks the cross-references the
 * schema cannot see. Wired from {@link SemanticValidator} next to WebhookValidation.
 */
final class HttpConnectorValidation {

    static final String ADAPTER_ID = "http-connector";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private HttpConnectorValidation() {
    }

    static void validate(ModelAst model, List<String> errors) {
        Set<String> connectorCapabilities = new HashSet<>();
        for (CapabilityBindingAst binding : model.getBindings()) {
            if (ADAPTER_ID.equalsIgnoreCase(safe(binding.getAdapter()))) {
                connectorCapabilities.add(normalize(binding.getCapability()));
            }
        }
        Set<String> propertyNames = new HashSet<>();
        for (PropertyAst property : model.getProperties()) {
            propertyNames.add(normalize(property.name()));
        }

        for (CapabilityAst capability : model.getCapabilities()) {
            boolean bound = connectorCapabilities.contains(normalize(capability.getName()));
            Map<String, Object> http = capability.getHttp();
            if (!bound) {
                if (!http.isEmpty()) {
                    errors.add("Capability " + capability.getName() + " declares an http block but is not bound to adapter '"
                            + ADAPTER_ID + "' -- the block is inert -- suggestedFix: add {\"capability\": \"" + capability.getName()
                            + "\", \"adapter\": \"" + ADAPTER_ID + "\"} to bindings[] or remove the http block");
                }
                continue;
            }
            String where = "Capability " + capability.getName();
            String baseUrl = text(http.get("baseUrl"));
            String baseUrlProperty = text(http.get("baseUrlProperty"));
            if (baseUrl.isBlank() && baseUrlProperty.isBlank()) {
                errors.add(where + " is bound to '" + ADAPTER_ID + "' but its http block declares neither baseUrl nor baseUrlProperty -- suggestedFix: set http.baseUrl to the remote API root or http.baseUrlProperty to a declared properties[] name");
            }
            if (!baseUrl.isBlank()) {
                validateBaseUrl(where, baseUrl, errors);
            }
            if (!baseUrlProperty.isBlank() && !propertyNames.contains(normalize(baseUrlProperty))) {
                errors.add(where + " http.baseUrlProperty '" + baseUrlProperty + "' does not name a declared properties[] entry -- suggestedFix: declare that property in properties[] or fix the name");
            }
            validateAuth(where, http.get("auth"), errors);

            for (CapabilityOperationAst operation : capability.getOperations()) {
                String opWhere = where + " operation " + operation.getName();
                Map<String, Object> opHttp = operation.getHttp();
                if (opHttp.isEmpty()) {
                    errors.add(opWhere + " has no http block (method + path) -- every operation of an "
                            + ADAPTER_ID + " capability must be an object with http.method and http.path -- suggestedFix: replace the operation with {\"name\": ..., \"http\": {\"method\": \"GET\", \"path\": \"/...\"}}");
                    continue;
                }
                Set<String> params = new LinkedHashSet<>();
                if (opHttp.get("params") instanceof List<?> list) {
                    for (Object p : list) {
                        params.add(String.valueOf(p));
                    }
                }
                Set<String> used = new LinkedHashSet<>();
                collectPlaceholders(opHttp.get("path"), used);
                collectPlaceholders(opHttp.get("query"), used);
                collectPlaceholders(opHttp.get("headers"), used);
                collectPlaceholders(opHttp.get("body"), used);
                for (String name : used) {
                    if (!params.contains(name)) {
                        errors.add(opWhere + " uses placeholder {" + name + "} that is not listed in http.params " + params
                                + " -- suggestedFix: add \"" + name + "\" to http.params or remove the placeholder");
                    }
                }
            }
        }
    }

    private static void validateBaseUrl(String where, String baseUrl, List<String> errors) {
        try {
            URI uri = new URI(baseUrl);
            String scheme = safe(uri.getScheme()).toLowerCase();
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
                errors.add(where + " http.baseUrl must be an absolute http(s) URL with a host: " + baseUrl
                        + " -- suggestedFix: use a value like https://api.example.com/v1");
            }
        } catch (Exception invalid) {
            errors.add(where + " http.baseUrl is not a valid URL: " + baseUrl
                    + " -- suggestedFix: use a value like https://api.example.com/v1");
        }
    }

    private static void validateAuth(String where, Object rawAuth, List<String> errors) {
        if (!(rawAuth instanceof Map<?, ?> auth)) {
            return; // absent = none
        }
        String type = text(auth.get("type"));
        boolean needsSecret = type.equals("bearer") || type.equals("apiKeyHeader") || type.equals("apiKeyQuery");
        if (needsSecret && text(auth.get("secretEnvVar")).isBlank()) {
            errors.add(where + " http.auth type '" + type + "' requires secretEnvVar (an environment variable NAME) -- suggestedFix: add http.auth.secretEnvVar naming the environment variable that holds the secret");
        }
        if ((type.equals("apiKeyHeader") || type.equals("apiKeyQuery")) && text(auth.get("name")).isBlank()) {
            errors.add(where + " http.auth type '" + type + "' requires name (the header or query parameter name) -- suggestedFix: add http.auth.name, for example X-Api-Key");
        }
        if (type.equals("basic") && text(auth.get("usernameEnvVar")).isBlank()) {
            errors.add(where + " http.auth type 'basic' requires usernameEnvVar -- suggestedFix: add http.auth.usernameEnvVar naming the environment variable that holds the username");
        }
    }

    private static void collectPlaceholders(Object value, Set<String> out) {
        if (value instanceof String text) {
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                out.add(matcher.group(1));
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                collectPlaceholders(nested, out);
            }
        } else if (value instanceof List<?> list) {
            for (Object nested : new ArrayList<>(list)) {
                collectPlaceholders(nested, out);
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
