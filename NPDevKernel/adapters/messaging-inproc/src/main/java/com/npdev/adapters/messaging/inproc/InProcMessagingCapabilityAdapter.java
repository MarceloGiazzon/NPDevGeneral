package com.npdev.adapters.messaging.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.MessagingCapability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * R6.4: the baseline, single-process {@link MessagingCapability} implementation -- same shape as
 * {@code InProcEventBus} (topic -&gt; handler fan-out, synchronous, at-most-once, no persistence),
 * but implemented directly against the {@code MessagingCapability} port/{@link CapabilityAdapter}
 * SDK rather than the separate {@code EventBus} port. This is the dev-friendly half of the
 * {@code messaging-inproc}/{@code messaging-http} pair (mirroring {@code mail-inproc}/
 * {@code mail-smtp}, {@code events-inproc}): a model can bind {@code MessagingCapability} to this
 * adapter for a single-app deployment with zero network dependency, or to
 * {@code messaging-http}'s {@code HttpMessagingCapabilityAdapter} for the cross-app bridge.
 *
 * <p><b>Delivery semantics: at-most-once, synchronous, in-process only.</b> {@link #publish(Object)}
 * invokes every currently-registered handler for the message's topic on the calling thread, in
 * registration order, and returns once all of them have run (or thrown). There is no retry, no
 * persistence, and no cross-process delivery whatsoever -- a handler that throws is not retried, and
 * nothing here survives a restart. This is a deliberate, narrower contract than
 * {@code HttpMessagingCapabilityAdapter}'s at-least-once cross-app bridge; do not bind this adapter
 * where a subscriber MUST see every publish despite a crash or a network partition.
 */
public final class InProcMessagingCapabilityAdapter implements CapabilityAdapter, MessagingCapability {

    private final Map<String, CopyOnWriteArrayList<Subscription>> subscribersByTopic = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptionsById = new ConcurrentHashMap<>();

    @Override
    public String adapterId() {
        return "messaging-inproc";
    }

    @Override
    public String capability() {
        return "messaging";
    }

    @Override
    public String capabilityType() {
        return "MessagingCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        return switch (call.operation()) {
            case "publish" -> CapabilityResult.success(publish(call.input()));
            default -> CapabilityResult.failure(
                    "MESSAGING_OPERATION_UNSUPPORTED_VIA_CAPABILITY_CALL",
                    "Operation '" + call.operation() + "' is not reachable through a flow capabilityCall step; "
                            + "'subscribe'/'unsubscribe' take a Java handler reference and are a Java-API-only "
                            + "surface on this adapter, not JSON-representable.",
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        };
    }

    /**
     * @param message a {@code Map} carrying {@code topic} (required, non-blank String) plus whatever
     *                application fields make up the message body.
     * @return an ack map: {@code messageId}, {@code topic}, {@code deliveredTo} (handler count).
     */
    @Override
    public Object publish(Object message) {
        Map<String, Object> request = normalizePayload(message);
        String topic = requireTopic(request);
        String messageId = UUID.randomUUID().toString();

        List<Subscription> handlers = subscribersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>());
        for (Subscription subscription : handlers) {
            subscription.handler().accept(request);
        }

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("messageId", messageId);
        ack.put("topic", topic);
        ack.put("deliveredTo", handlers.size());
        return ack;
    }

    /**
     * @param handlerRef must be a {@link Consumer}{@code <Map<String,Object>>} invoked with the full
     *                   published message (including {@code topic}) on the publishing thread.
     * @return the subscription id (a String), also valid as the {@code subscriptionRef} passed to
     *         {@link #unsubscribe(Object)}.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object subscribe(String topic, Object handlerRef) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("messaging.subscribe requires a non-blank topic");
        }
        if (!(handlerRef instanceof Consumer<?> rawHandler)) {
            throw new IllegalArgumentException(
                    "messaging.subscribe requires a java.util.function.Consumer<Map<String,Object>> handlerRef, got: "
                            + (handlerRef == null ? "null" : handlerRef.getClass()));
        }
        Consumer<Map<String, Object>> handler = (Consumer<Map<String, Object>>) rawHandler;
        String subscriptionId = UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionId, topic, handler);
        subscribersByTopic.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(subscription);
        subscriptionsById.put(subscriptionId, subscription);
        return subscriptionId;
    }

    @Override
    public Object unsubscribe(Object subscriptionRef) {
        String subscriptionId = String.valueOf(subscriptionRef);
        Subscription subscription = subscriptionsById.remove(subscriptionId);
        Map<String, Object> ack = new LinkedHashMap<>();
        if (subscription == null) {
            ack.put("status", "not_found");
            return ack;
        }
        List<Subscription> handlers = subscribersByTopic.get(subscription.topic());
        if (handlers != null) {
            handlers.remove(subscription);
        }
        ack.put("status", "unsubscribed");
        return ack;
    }

    private static String requireTopic(Map<String, Object> request) {
        Object topic = request.get("topic");
        if (!(topic instanceof String topicString) || topicString.isBlank()) {
            throw new IllegalArgumentException("messaging.publish payload must contain a non-blank 'topic' field");
        }
        return topicString;
    }

    private static Map<String, Object> normalizePayload(Object payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        throw new IllegalArgumentException(
                "messaging.publish payload must be a map containing at least 'topic'; got: " + payload.getClass());
    }

    private record Subscription(String id, String topic, Consumer<Map<String, Object>> handler) {
        private Subscription {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(handler, "handler");
        }
    }
}
