package com.npdev.kernel.ports;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;

import java.util.Map;

public interface CapabilityDispatcher {
    CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState);
}
