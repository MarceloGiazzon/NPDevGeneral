package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FORM P3-T10: an INBOUND webhook door derived from a capability that declares {@code inbound} --
 * {@code POST /api/hooks/{path}} verifies an HMAC signature against a named environment variable
 * and, once verified, publishes {@code eventName} into the flow engine's event store and starts the
 * flow whose trigger is {@code {webhook: <capability>}}. A third party holding no NPDev credential
 * can therefore resume a flow parked on {@code awaitEvent} (the "payment confirmation" door the flow
 * docs cite as the engine's reason to exist).
 *
 * <p>Not a model root key any more: the door is a property of the capability it serves (there used to
 * be a top-level {@code webhooks[]} with its own identity). This record is the DERIVED view the
 * compiler and the validators share, so the defaults live in one place.
 *
 * @param capability       name of the declaring capability (what {@code trigger.webhook} names).
 * @param path             URL path segment: {@code inbound.path}, else the capability name. Never
 *                         pack-qualified -- a third party posts to a fixed, predictable URL, so a
 *                         pack-qualified capability name must declare {@code inbound.path} itself.
 * @param pathDeclared     whether {@code inbound.path} was written in the model.
 * @param hmacSecretEnvVar {@code inbound.hmacSecretEnvVar}, else the convention
 *                         {@code NPDEV_WEBHOOK_<PATH upper-cased, '-' to '_'>_SECRET}. Never a literal
 *                         secret -- resolved by name at request time only.
 * @param eventName        event published once the signature verifies (blank = none).
 * @param fieldMapping     target payload field -> dot-path into the inbound JSON body.
 * @param idempotencyKey   replay de-dupe key (dot-path, or {@code $header.<Name>}); blank = none.
 * @param signature        provider signature scheme block; empty means the NPDev scheme.
 */
public record InboundDoorAst(String capability, String path, boolean pathDeclared, String hmacSecretEnvVar,
                             String eventName, Map<String, String> fieldMapping, String idempotencyKey,
                             Map<String, Object> signature) {

    public InboundDoorAst {
        eventName = eventName == null ? "" : eventName;
        fieldMapping = fieldMapping == null ? Map.of() : Map.copyOf(fieldMapping);
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        signature = signature == null ? Map.of() : Map.copyOf(signature);
    }

    /** The environment variable a door reads its secret from when it declares none. */
    public static String defaultSecretEnvVar(String path) {
        return "NPDEV_WEBHOOK_" + path.toUpperCase(Locale.ROOT).replace('-', '_') + "_SECRET";
    }

    /** Every door the model declares, in capability order. */
    public static List<InboundDoorAst> of(List<CapabilityAst> capabilities) {
        List<InboundDoorAst> doors = new ArrayList<>();
        for (CapabilityAst capability : capabilities) {
            Map<String, Object> inbound = capability.getInbound();
            if (!capability.hasInbound()) {
                continue;
            }
            String declaredPath = text(inbound.get("path"));
            String path = declaredPath.isBlank() ? capability.getName() : declaredPath;
            String secret = text(inbound.get("hmacSecretEnvVar"));
            doors.add(new InboundDoorAst(
                    capability.getName(),
                    path,
                    !declaredPath.isBlank(),
                    secret.isBlank() ? defaultSecretEnvVar(path) : secret,
                    text(inbound.get("eventName")),
                    stringMap(inbound.get("fieldMapping")),
                    text(inbound.get("idempotencyKey")),
                    objectMap(inbound.get("signature"))));
        }
        return doors;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return out;
    }

    private static Map<String, Object> objectMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }
}
