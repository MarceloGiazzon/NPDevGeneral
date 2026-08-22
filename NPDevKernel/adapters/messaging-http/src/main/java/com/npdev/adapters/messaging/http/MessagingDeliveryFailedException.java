package com.npdev.adapters.messaging.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thrown by {@link HttpMessagingCapabilityAdapter#publish(Object)} when at least one targeted peer
 * could not be delivered to (denied, timed out, or exhausted its retries) -- see the class javadoc's
 * "failure must be visible" note. Local (same-process) delivery and every OTHER peer that succeeded
 * are unaffected by this exception being thrown; {@link #succeededPeerAppIds()} names them so a
 * caller inspecting the failure still knows what already happened, and {@link #failuresByPeerAppId()}
 * names exactly which peers failed and why. This adapter never silently drops an undeliverable
 * message -- the publisher is always told.
 */
public final class MessagingDeliveryFailedException extends RuntimeException {
    private final String topic;
    private final String deliveryId;
    private final List<String> succeededPeerAppIds;
    private final Map<String, String> failuresByPeerAppId;

    public MessagingDeliveryFailedException(
            String topic,
            String deliveryId,
            List<String> succeededPeerAppIds,
            Map<String, RuntimeException> failures
    ) {
        super(buildMessage(topic, deliveryId, succeededPeerAppIds, failures));
        this.topic = topic;
        this.deliveryId = deliveryId;
        this.succeededPeerAppIds = List.copyOf(succeededPeerAppIds);
        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeException> entry : failures.entrySet()) {
            messages.put(entry.getKey(), String.valueOf(entry.getValue().getMessage()));
        }
        this.failuresByPeerAppId = Map.copyOf(messages);
    }

    public String topic() {
        return topic;
    }

    public String deliveryId() {
        return deliveryId;
    }

    public List<String> succeededPeerAppIds() {
        return succeededPeerAppIds;
    }

    public Map<String, String> failuresByPeerAppId() {
        return failuresByPeerAppId;
    }

    private static String buildMessage(
            String topic,
            String deliveryId,
            List<String> succeededPeerAppIds,
            Map<String, RuntimeException> failures
    ) {
        Objects.requireNonNull(failures, "failures");
        StringBuilder message = new StringBuilder("messaging.publish for topic '")
                .append(topic).append("' (deliveryId=").append(deliveryId)
                .append(") failed for peer(s): ");
        boolean first = true;
        for (Map.Entry<String, RuntimeException> entry : failures.entrySet()) {
            if (!first) {
                message.append("; ");
            }
            first = false;
            message.append(entry.getKey()).append(" -> ").append(entry.getValue().getMessage());
        }
        message.append(". Succeeded: ").append(succeededPeerAppIds);
        message.append(". This message was NOT silently dropped -- the caller must decide whether to retry.");
        return message.toString();
    }
}
