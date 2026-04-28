package com.npdev.kernel.ports;

public interface MessagingCapability {
    Object publish(Object message);

    Object subscribe(String topic, Object handlerRef);

    Object unsubscribe(Object subscriptionRef);
}

