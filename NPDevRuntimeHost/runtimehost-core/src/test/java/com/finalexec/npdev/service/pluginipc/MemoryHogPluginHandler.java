package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Real-process stand-in for a plugin that deliberately exceeds a configured memory ceiling -- SEC-3 step
 * 4's live-fired proof that an OS-level resource limit (Windows Job Object / Linux cgroup) actually kills
 * the child before it can run away, not just the wall-clock timeout. Allocates and touches memory in a
 * loop, held in a local list so the JVM's own GC cannot reclaim it out from under the test; the OS is
 * expected to terminate this process ({@code PLUGIN_EXECUTION_PROCESS_KILLED}) well before it returns.
 *
 * <p>Twin of NPDevRuntimeHost/src/test/java/com/finalexec/pluginipc/MemoryHogPluginHandler.java (that
 * copy lives in the template-app test tree and only compiles inside an assembled generated app; this one
 * lives in runtimehost-core's own independently-buildable test source set so the Linux live-fire proof
 * does not need the full generate+assemble pipeline to run -- twin-pair rule
 * plugin-linux-proof-fixture-duplication in scripts/quality/twin-pair-registry.json keeps the two in
 * sync). SEC-3 fork (a), 2026-09-01.</p>
 */
public final class MemoryHogPluginHandler implements CapabilityAdapter {

    private static final int CHUNK_BYTES = 50 * 1024 * 1024;
    private static final int CHUNKS = 20; // ~1GB total, comfortably past any sane test ceiling

    public MemoryHogPluginHandler(PluginIpcCallbackClient callbackClient) {
        // Unused: this handler never calls back into the host.
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
        List<byte[]> held = new ArrayList<>();
        try {
            for (int i = 0; i < CHUNKS; i++) {
                byte[] chunk = new byte[CHUNK_BYTES];
                Arrays.fill(chunk, (byte) 1); // force real page commits, not just a virtual reservation
                held.add(chunk);
            }
        } catch (OutOfMemoryError exhausted) {
            // Expected if this JVM's own -Xmx is reached before the OS-level ceiling bites; fall through
            // to spinning so a resource-limiting mechanism (if any) still gets the chance to kill this
            // process, rather than this handler quietly returning as if nothing happened.
        }
        //noinspection InfiniteLoopStatement -- expected to be killed by the OS before reaching a return.
        while (true) {
            Thread.onSpinWait();
        }
    }
}
