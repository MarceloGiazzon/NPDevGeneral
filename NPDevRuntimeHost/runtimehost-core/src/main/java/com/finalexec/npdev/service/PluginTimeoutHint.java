package com.finalexec.npdev.service;

/**
 * SEC-6: optional per-handler timeout floor. {@link TimeBoundedPluginExecutionEngine} bounds every
 * capability call with one shared {@code npdev.runtime.plugin-timeout-ms} (default 1000ms) budget --
 * appropriate for the in-process handlers it was sized against, whose {@code
 * plugin-executions.jsonl} durations measure 1-10ms. A handler dispatched over a real OS process
 * boundary (a {@code plugin:java-source} mount's {@code PluginIpcCapabilityHandler}, SEC-5) pays a
 * real classload + manifest-resolution + IPC round-trip cost on a pooled worker's FIRST invocation --
 * measured live at 393ms and 223ms for the pool's two workers against lib-probe's SignWithLibrary
 * flow, dropping to 1-2ms once each worker is warm -- leaving only a ~2.5x margin against the shared
 * 1000ms default on a clean local dev machine, thinner still under real contention (slower host,
 * antivirus-scanned child process I/O, a heavier plugin, concurrent cold starts across workers).
 *
 * <p>Raising the shared default would soften containment for every OTHER handler too -- the
 * soft-timeout is B30's only backstop against a hostile in-process plugin that ignores
 * {@code Thread.interrupt()}, so a longer global budget gives such a plugin more time to do damage
 * before being flagged. A handler that implements this interface instead declares its OWN floor;
 * the engine takes {@code max(configuredTimeoutMs, hint.minimumTimeoutMs())} so the floor can only
 * raise that ONE handler's budget, never lower any other handler's.</p>
 */
public interface PluginTimeoutHint {
    long minimumTimeoutMs();
}
