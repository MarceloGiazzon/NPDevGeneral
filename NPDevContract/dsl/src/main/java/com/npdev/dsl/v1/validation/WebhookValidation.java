package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.InboundDoorAst;
import com.npdev.dsl.v1.ast.ModelAst;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * R6.2 / FORM P3-T10: structural checks for the inbound doors declared by {@code capabilities[].inbound} --
 * path uniqueness/shape (a pack-qualified capability must name its own {@code inbound.path}), an
 * {@code eventName} that actually names a declared {@code events[]} member (the same discipline
 * {@link FlowValidation} applies to {@code awaitEvent}/{@code emitEvent} steps, so a typo'd event name
 * fails at validation time rather than silently publishing an event no flow will match), a door that does
 * something (an event, or a flow triggered by it) and at most one flow per door.
 */
final class WebhookValidation {

    /** Must be safe as a URL path segment ({@code POST /api/hooks/{path}}) -- same shape discipline pack ids
     *  already use ({@code validatePackIdentifier}), starting with a letter so it can never collide with a
     *  numeric-looking segment. */
    private static final Pattern PATH_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]*$");

    private WebhookValidation() {
    }

    static void validateWebhooks(ModelAst modelAst, List<String> errors) {
        Set<String> eventNames = new HashSet<>();
        for (EventAst event : modelAst.getEvents()) {
            eventNames.add(normalize(event.getName()));
        }

        Map<String, Integer> flowsPerDoor = new HashMap<>();
        for (FlowAst flow : modelAst.getFlows()) {
            if (flow.getTrigger() != null && "webhook".equals(flow.getTrigger().kind())
                    && flow.getTrigger().webhook() != null) {
                flowsPerDoor.merge(flow.getTrigger().webhook().trim(), 1, Integer::sum);
            }
        }

        Set<String> pathsSeen = new HashSet<>();
        for (InboundDoorAst door : InboundDoorAst.of(modelAst.getCapabilities())) {
            String here = "Capability " + door.capability() + " inbound";
            if (door.capability().contains("::") && !door.pathDeclared()) {
                errors.add(here + ": a pack-qualified capability must declare inbound.path -- the URL a third party posts to"
                        + " is never namespace-qualified, so it cannot be derived from " + door.capability()
                        + " -- suggestedFix: add inbound.path (for example \"stripe\").");
                continue;
            }
            if (!PATH_PATTERN.matcher(door.path()).matches()) {
                errors.add(here + ": path " + door.path() + " must match ^[a-zA-Z][a-zA-Z0-9_-]*$ (it becomes the URL "
                        + "path segment POST /api/hooks/{path}) -- suggestedFix: set inbound.path to a plain URL-safe name.");
            }
            if (!pathsSeen.add(normalize(door.path()))) {
                errors.add(here + ": duplicate inbound path " + door.path());
            }
            int flows = flowsPerDoor.getOrDefault(door.capability(), 0);
            if (!hasText(door.eventName()) && flows == 0) {
                errors.add(here + ": the door does nothing -- suggestedFix: add inbound.eventName (a name from events[]) or"
                        + " a flow with trigger {\"webhook\": \"" + door.capability() + "\"}.");
            }
            if (flows > 1) {
                errors.add(here + ": " + flows + " flows declare trigger.webhook " + door.capability()
                        + " -- suggestedFix: keep ONE flow per door and fan out with inbound.eventName plus flows whose"
                        + " trigger is {\"event\": ...}.");
            }
            if (hasText(door.eventName()) && !eventNames.contains(normalize(door.eventName()))) {
                errors.add(here + ": references unknown event " + door.eventName());
            }
            validateSignature(here, door, errors);
            for (Map.Entry<String, String> mapping : door.fieldMapping().entrySet()) {
                if (!hasText(mapping.getKey()) || !hasText(mapping.getValue())) {
                    errors.add(here + ": fieldMapping entries must have non-blank keys and values");
                    break;
                }
            }
        }
    }

    /** INT-6: provider signature rules the schema cannot express (header required per scheme, stripe-v1
     *  carries no prefix, tolerance only applies to stripe-v1). stripe-v1 defaults its header to
     *  Stripe-Signature, so it needs no explicit one. */
    private static void validateSignature(String here, InboundDoorAst door, List<String> errors) {
        Map<String, Object> signature = door.signature();
        if (signature.isEmpty()) {
            return;
        }
        Object schemeValue = signature.get("scheme");
        String scheme = schemeValue == null ? "npdev" : String.valueOf(schemeValue);
        boolean headerDeclared = signature.get("header") != null && hasText(String.valueOf(signature.get("header")));
        if (!"npdev".equals(scheme) && !"stripe-v1".equals(scheme) && !headerDeclared) {
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
