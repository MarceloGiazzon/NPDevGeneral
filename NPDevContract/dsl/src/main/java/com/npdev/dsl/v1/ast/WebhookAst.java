package com.npdev.dsl.v1.ast;

import java.util.Map;

/**
 * R6.2: a model-declared INBOUND webhook door -- {@code POST /api/hooks/{source}} verifies an
 * HMAC-SHA256 signature against a named environment variable and, once verified, publishes
 * {@code eventName} into the flow engine's event store so a flow parked on {@code awaitEvent} can
 * be resumed by a third party that holds no NPDev credential at all (the "payment confirmation"
 * door the flow docs cite as the engine's reason to exist, R6.1's sibling for the inbound
 * direction).
 *
 * <p>Reuses R6.1's ({@code webhook-http}, {@code ledger/items/RUN-14.yml}) posture and vocabulary
 * rather than inventing a second style: HMAC-SHA256, a secret resolved by ENVIRONMENT VARIABLE
 * NAME -- never a literal value in the model, never committed -- and fail-closed verification.
 *
 * @param source          path segment identifying this webhook door: {@code POST /api/hooks/{source}}.
 *                        Deliberately NOT namespace-qualified by pack composition (unlike most
 *                        {@code MODEL_ARRAY_KEYS} members, which key qualification off a {@code name}
 *                        field) -- a third party posts to a fixed, predictable URL; a pack-qualified
 *                        {@code mypack::stripe} segment would make that URL depend on composition
 *                        order, which the wire contract cannot tolerate.
 * @param hmacSecretEnvVar name of the environment variable holding the HMAC-SHA256 verification
 *                         secret. Never a literal secret value -- resolved at request time only.
 * @param eventName        the flow event name published into the event store once the signature
 *                         verifies; must reference a name in the model's own {@code events[]}
 *                         (validated by {@code WebhookValidation}, the same discipline
 *                         {@code FlowValidation} already applies to {@code awaitEvent}/{@code
 *                         emitEvent} steps naming an event).
 * @param fieldMapping     target event-payload field name -> dot-path into the inbound JSON body.
 *                         Empty/absent means the raw parsed JSON body becomes the event payload
 *                         unchanged.
 */
public record WebhookAst(String source, String hmacSecretEnvVar, String eventName, Map<String, String> fieldMapping) {
    public WebhookAst {
        fieldMapping = fieldMapping == null ? Map.of() : Map.copyOf(fieldMapping);
    }
}
