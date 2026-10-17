package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.WebhookAst;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * R6.2: structural checks for the optional top-level {@code webhooks} declaration -- source
 * uniqueness/shape, a non-blank secret env var name, and an {@code eventName} that actually
 * names a declared {@code events[]} member (the same discipline {@link FlowValidation} already
 * applies to {@code awaitEvent}/{@code emitEvent} steps naming an event, so a typo'd webhook
 * event name fails loudly at validation time rather than silently publishing an event no flow
 * will ever match).
 */
final class WebhookValidation {

    /** Must be safe as a URL path segment ({@code POST /api/hooks/{source}}) -- same shape
     *  discipline pack ids already use ({@code validatePackIdentifier}), starting with a letter so
     *  it can never collide with a numeric-looking segment. */
    private static final Pattern SOURCE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]*$");

    private WebhookValidation() {
    }

    static void validateWebhooks(ModelAst modelAst, List<String> errors) {
        Set<String> eventNames = new HashSet<>();
        for (EventAst event : modelAst.getEvents()) {
            eventNames.add(normalize(event.getName()));
        }

        Set<String> flowNames = new HashSet<>();
        for (FlowAst flow : modelAst.getFlows()) {
            flowNames.add(normalize(flow.getName()));
        }

        Set<String> sourcesSeen = new HashSet<>();
        for (WebhookAst webhook : modelAst.getWebhooks()) {
            if (!hasText(webhook.source())) {
                errors.add("Webhook: source is required");
                continue;
            }
            String here = "Webhook " + webhook.source();
            if (!SOURCE_PATTERN.matcher(webhook.source()).matches()) {
                errors.add(here + ": source must match ^[a-zA-Z][a-zA-Z0-9_-]*$ (it becomes the URL "
                        + "path segment POST /api/hooks/{source})");
            }
            if (!sourcesSeen.add(normalize(webhook.source()))) {
                errors.add(here + ": duplicate webhook source");
            }
            if (!hasText(webhook.hmacSecretEnvVar())) {
                errors.add(here + ": hmacSecretEnvVar is required (the name of an environment "
                        + "variable holding the HMAC-SHA256 verification secret -- never a literal "
                        + "secret value)");
            }
            if (!hasText(webhook.eventName()) && !hasText(webhook.startFlow())) {
                errors.add(here + ": declare eventName (publish an event), startFlow (start a flow), or both"
                        + " -- suggestedFix: Add eventName (a name from events[]) or startFlow (a name from flows[]).");
            }
            if (hasText(webhook.eventName()) && !eventNames.contains(normalize(webhook.eventName()))) {
                errors.add(here + ": references unknown event " + webhook.eventName());
            }
            if (hasText(webhook.startFlow()) && !flowNames.contains(normalize(webhook.startFlow()))) {
                errors.add(here + ": startFlow references unknown flow " + webhook.startFlow()
                        + " -- suggestedFix: Set startFlow to the name of a flow declared in flows[].");
            }
            validateSignature(here, webhook, errors);
            for (Map.Entry<String, String> mapping : webhook.fieldMapping().entrySet()) {
                if (!hasText(mapping.getKey()) || !hasText(mapping.getValue())) {
                    errors.add(here + ": fieldMapping entries must have non-blank keys and values");
                    break;
                }
            }
        }
    }

    /** INT-6: provider signature rules the schema cannot express (header required per scheme,
     *  stripe-v1 carries no prefix, tolerance only applies to stripe-v1). */
    private static void validateSignature(String here, WebhookAst webhook, List<String> errors) {
        Map<String, Object> signature = webhook.signature();
        if (signature.isEmpty()) {
            return;
        }
        Object schemeValue = signature.get("scheme");
        String scheme = schemeValue == null ? "npdev" : String.valueOf(schemeValue);
        boolean headerDeclared = signature.get("header") != null && hasText(String.valueOf(signature.get("header")));
        if (!"npdev".equals(scheme) && !headerDeclared) {
            errors.add(here + ": signature.header is required for scheme " + scheme
                    + " -- suggestedFix: Set signature.header to the request header carrying the provider's signature.");
        }
        if ("stripe-v1".equals(scheme) && signature.get("prefix") != null
                && hasText(String.valueOf(signature.get("prefix")))) {
            errors.add(here + ": signature.prefix is not valid for scheme stripe-v1"
                    + " -- suggestedFix: Remove signature.prefix; stripe-v1 parses the t=/v1= header itself.");
        }
        if (signature.get("toleranceSeconds") != null && !"stripe-v1".equals(scheme)) {
            errors.add(here + ": signature.toleranceSeconds only applies to scheme stripe-v1"
                    + " -- suggestedFix: Remove signature.toleranceSeconds or switch scheme to stripe-v1.");
        }
    }
}
