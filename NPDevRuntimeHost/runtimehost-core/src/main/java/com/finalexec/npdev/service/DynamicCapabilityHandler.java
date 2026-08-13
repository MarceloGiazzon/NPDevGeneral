package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;

import java.util.Map;

public interface DynamicCapabilityHandler {

    CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState);
}
