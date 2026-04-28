package com.npdev.kernel.concepts;

import java.util.List;

public interface ConceptGatewayTraceSink {
    ConceptGatewayTraceSink NOOP = new ConceptGatewayTraceSink() {
        @Override
        public void append(ConceptGatewayTraceRecord record) {
        }
    };

    void append(ConceptGatewayTraceRecord record);

    default List<ConceptGatewayTraceRecord> records() {
        return List.of();
    }

    static ConceptGatewayTraceSink noop() {
        return NOOP;
    }
}
