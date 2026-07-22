package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GenericMountedCapabilityHandler implements DynamicCapabilityHandler {

    private static final String HANDLER_ID = "genericMountedCapabilityHandler";

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "completed");
        payload.put("capability", call.capability());
        payload.put("capabilityType", call.capabilityType());
        payload.put("adapterId", call.adapterId());
        payload.put("operation", call.operation());
        payload.put("correlationId", call.correlationId());
        payload.put("input", call.input());
        payload.put("args", List.copyOf(call.args()));
        payload.put("mountedBy", HANDLER_ID);
        return CapabilityResult.success(payload);
    }
}
