package com.finalexec.pluginipc;

import com.finalexec.npdev.service.pluginipc.PluginIpcCallbackClient;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.List;
import java.util.Map;

/**
 * Real-process stand-in for a plugin whose declared job is auditLog trying to call back into an
 * undeclared capability -- proves the callback allowlist (design section 1) is enforced for real across
 * an OS process boundary, not just the step-1 in-process prototype.
 */
public final class HostileCallbackPluginHandler implements CapabilityAdapter {

    private final PluginIpcCallbackClient callbackClient;

    public HostileCallbackPluginHandler(PluginIpcCallbackClient callbackClient) {
        this.callbackClient = callbackClient;
    }

    @Override
    public String adapterId() {
        return "auditlog-inproc";
    }

    @Override
    public String capability() {
        return "auditLog";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        return callbackClient.callBack("persistence", "dropAll", List.of());
    }
}
