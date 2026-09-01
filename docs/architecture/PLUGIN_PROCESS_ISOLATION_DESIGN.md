# Plugin Process Isolation ("Model B") — Design

**Status:** design only, not implemented. Tracked by `ledger/items/SEC-3.yml`. This document answers
the five open questions SEC-3's 2026-08-31 scoping note left for whoever picks this up, in the order
they gate each other. It proposes concrete decisions, not just options, so a future session can
implement without re-deriving the shape — but every decision here is still a *proposal*: nothing in
this document satisfies SEC-3's own done-when bar (live-fired regression tests against a real child
process), and no code should be written against it without a maintainer confirming the calls below,
especially §1 and §3.

## 0. What Model A already closed, and what this document is for

Model A (shipped 2026-08-27, see SEC-3's own `detail` history) added a static-analysis admission
barrier: `TrustedSourceBytecodeInspector` (kernel) scans a plugin's *compiled bytecode* for direct
references to a fixed denylist (`java/lang/Runtime`, filesystem/socket packages, reflection,
`java/lang/invoke/*`, detached-execution classes) at two enforcement points —
`PluginJavaSourcePolicy` at generation time, `PluginBytecodeBootGate` at app boot. A plugin that
*directly* calls `System.exit()`, opens a socket, or reads a file is refused before it ever runs.

What Model A does **not** touch, and what this document is about: an *admitted* plugin still runs
in-process, on a thread from `TimeBoundedPluginExecutionEngine`'s cached thread pool, with full
access to whatever the host wires it to. Memory and CPU are unbounded beyond the wall-clock timeout
(`future.get(timeoutMs)` cannot forcibly stop a running thread — `cancel(true)` only sets an interrupt
flag a tight CPU loop never observes). A plugin that allocates unboundedly or spins a non-blocking
loop degrades or kills the whole host process, taking every other tenant's in-flight request with it.
Model B is process isolation: run the plugin's handler in a **separate OS process**, so a runaway
plugin can be resource-limited and killed by the OS without touching the host.

## 1. IPC shape

This is the crux question, and the one every other decision depends on. Today
`TimeBoundedPluginExecutionEngine.execute(contribution, realizationSummary, call, contextState,
handler)` invokes `handler` **directly, in-process**, via one of three paths (`CapabilityAdapter`,
`DynamicCapabilityHandler`, or plain reflection over `handler`'s methods) — see
`NPDevRuntimeHost/runtimehost-core/.../TimeBoundedPluginExecutionEngine.java:232` (`invokeHandler`).
The call is a `CapabilityCall(capability, capabilityType, adapterId, operation, args, correlationId,
idempotencyKey)` (`NPDevKernel/kernel/.../CapabilityCall.java`); the result is a `CapabilityResult(ok,
value, error)` where `error` is a `CapabilityError(code, message, CapabilityErrorKind, details)`
(`CapabilityResult.java`). Both directions carry **plain Java objects** in `args`/`value`/`details` —
whatever the plugin author's method signature declares. There is no existing serialization boundary
anywhere in this call path.

Two directions need a protocol:

- **Outbound invoke (host → child):** "run this plugin with this `CapabilityCall`." Happens once per
  `execute()` call. Low frequency, can tolerate real serialization cost.
- **Callback (child → host):** whatever the plugin's own handler code does *while running* — e.g. an
  `auditLog`-capability plugin calling back into `AuditLogStore.append`, or a plugin that (today,
  in-process) reads via `persistence.save`. This is the surface Model A's static scanner cannot see at
  all: a proxy class elsewhere on the classpath performing a forbidden capability on the plugin's
  behalf is invisible to a constant-pool scan, and a *legitimate* callback (the plugin's whole
  purpose) still needs a real cross-process call.

### Decision: length-prefixed JSON frames over the child's stdin/stdout pipes

`ProcessBuilder` gives every child process a stdin/stdout pipe for free — no new dependency, no port
to bind (and no firewall/loopback-security surface a TCP or Unix-domain-socket approach would add).
Frame shape, one direction each way, both pipes always open for the process's lifetime:

```
[4-byte big-endian length][UTF-8 JSON payload]
```

Reuse **Jackson**, already a hard dependency everywhere in this codebase (`CompiledModelCanonicalJson`
and friends), for the JSON encoding — no new library. Two message envelopes, symmetric enough to
share one framing reader/writer:

```jsonc
// host -> child: outbound invoke
{
  "kind": "invoke",
  "requestId": "<uuid, matches the eventual response>",
  "capability": "auditLog", "capabilityType": "...", "adapterId": "...",
  "operation": "append",
  "args": [ /* JSON-serialized CapabilityCall.args() */ ],
  "correlationId": "...", "idempotencyKey": null,
  "contextState": { /* JSON-serialized subset -- see below */ }
}

// child -> host: callback (mid-flight, zero or more per invoke)
{
  "kind": "callback",
  "requestId": "<the invoke's requestId>",
  "callbackId": "<uuid, matches the eventual callback response>",
  "capability": "persistence", "operation": "save", "args": [ /* ... */ ]
}

// either direction: a response to a request/callback by id
{
  "kind": "response",
  "requestId": "<matches a prior invoke or callback id>",
  "ok": true, "value": /* ... */,
  "error": { "code": "...", "message": "...", "kind": "CONTRACT", "details": {} }
}
```

**`args`/`value`/`contextState` must be restricted to a JSON-safe subset** (primitives, `String`,
`Map<String,?>`, `List<?>`, and record types Jackson can round-trip) — a raw Java object graph
(current in-process behavior) cannot cross a process boundary. This is a real, visible contract
change for plugin authors: `PluginJavaSourcePolicy`'s AST-level check should grow a rule flagging a
non-JSON-safe parameter/return type on a plugin's public handler methods, so the incompatibility is
caught at generation time, not as a mid-flight serialization crash. `contextState`
(`Map<String,Object>` today) needs the same audit — grep what's actually placed into it today (e.g.
`_npdevEntityName`, per `adaptCallForHandler`) before finalizing which keys are safe to forward.

**The callback allowlist is a separate, NEW runtime gate — not a reuse of
`TrustedSourceBytecodeInspector`.** That inspector scans compiled bytecode for *direct* forbidden
references at admission time; it says nothing about which capabilities a *running* child may ask the
host to perform over this new IPC channel. Without an explicit check here, isolation is decorative —
a plugin whose declared job is `auditLog` could send a `callback` frame naming `persistence.dropAll`
and the host would happily execute it if nothing stops it. Proposed rule: the host validates every
`callback` frame's `capability`/`operation` against the SAME `RuntimePluginAdapterRegistry` binding
that authorized the original `invoke` (i.e. a plugin may only call back into capabilities its own
manifest entry declares it needs) — reusing `PluginExecutionPolicyEvaluator`'s existing
allowed/denied decision shape (`PluginExecutionPolicyDecision`) rather than inventing a second policy
model.

## 2. Process lifecycle

A cold JVM start is measured elsewhere in this platform at several hundred ms — too slow against
`npdev.runtime.plugin-timeout-ms` (default 1000 ms, already observed to occasionally miss under load,
per Move 14 Phase D item D1). A **warm pool of pre-started child processes** is the only viable shape
for meeting the existing timeout budget; a cold-start-per-invocation model would need the timeout
raised platform-wide first, which is its own (unwanted) behavior change.

### Decision: fixed-size warm pool, one child JVM per pool slot, keyed by nothing (fungible workers)

- **Pool size:** a static config (`npdev.runtime.plugin-pool-size`, default small — 2–4) rather than
  dynamic scaling. Dynamic pool sizing is its own can of worms (backpressure, queueing policy under
  burst) and nothing in the shipped corpus's plugin usage today justifies it; start static, revisit
  with real load data.
- **Workers are fungible**, not bound to a specific plugin/pluginId. Each child JVM is a *bare*
  process holding no plugin-specific state; which plugin class to load and invoke travels in the
  `invoke` frame itself (plugin JAR path + fully-qualified class name — both already known to the
  host via `RuntimePluginPackageRealizationService.RealizationSummaryItem`). This means class-loading
  cost is paid on every invoke (not amortized across calls to the same plugin), which is the right
  trade for MVP correctness over throughput — a per-plugin warm-class cache is a later optimization,
  not a blocker.
- **Idle recycling:** every worker is recycled (killed, a fresh one started) after N invocations
  (config, e.g. 50) *and* independently on a wall-clock idle timer (e.g. 10 minutes unused) — bounding
  the blast radius of a plugin that leaked native memory or file descriptors without crashing outright,
  without needing per-invocation health probing.
- **Host redeploy / shutdown:** the pool's owning Spring bean's `@PreDestroy` sends a `close` frame to
  every live worker (grace period, e.g. 2s) then `Process.destroyForcibly()` any still alive. No
  in-flight-request draining logic beyond what `TimeBoundedPluginExecutionEngine`'s own
  `executorService.shutdownNow()` already does for the host side today.

## 3. Per-OS resource limiting

Two real, separate implementations — there is no cross-platform API for hard memory/CPU caps on a
child process from within the JVM. This platform's own CI matrix already accepts asymmetric OS
support elsewhere (`local-test-profile.json`: Postgres/MySQL/Docker gated to Linux CI, H2/SQLServer
the Windows-dev/CI default per `docs/maintainers/SUBSYSTEM_CONTRACTS.md`'s storage section) — the
same shape applies here, not a new precedent.

- **Linux:** cgroups v2, via the child's own control group. Launch the child under `systemd-run
  --scope` (or write directly into `/sys/fs/cgroup/<name>/{memory.max,cpu.max}` for the child's PID if
  systemd is unavailable in the target container) rather than hand-rolling a `clone()`/namespace
  wrapper — reuses a stable, already-present OS mechanism instead of new unsafe JNI/JNA surface.
- **Windows:** Job Objects (`CreateJobObject` + `SetInformationJobObject` with
  `JobObjectExtendedLimitInformation`, assigning the child process on creation via
  `AssignProcessToJobObject`) — the standard Windows mechanism for exactly this (a process group with
  a hard memory ceiling and CPU rate limit that the OS itself enforces, including all
  grandchildren). Requires a small JNA (or JNI) binding; no such binding exists in this codebase yet
  — this is genuinely new dependency surface, called out explicitly rather than glossed over.
- **Limits:** memory ceiling and CPU rate are both config (`npdev.runtime.plugin-memory-limit-mb`,
  `npdev.runtime.plugin-cpu-rate-percent`), not hardcoded — different deployments will want different
  headroom, and there is no principled platform-wide default without real usage data.
- **A platform with neither mechanism available** (e.g. a container runtime without cgroup delegation)
  degrades to **wall-clock timeout only**, logged loudly as a posture warning at boot (the same
  `npdev why`-discoverable shape B30 already uses) — never a silent downgrade.

## 4. Crash / violation handling

A killed-for-resource-violation or OOM-killed child must surface as an **ordinary `CAPABILITY_FAILED`**
to the original caller — the same shape `TimeBoundedPluginExecutionEngine` already returns for a
timeout or an in-process exception (`SandboxedPluginExecutionResult.Status.FAILED`, error code
`PLUGIN_EXECUTION_FAILED`/a new `PLUGIN_EXECUTION_PROCESS_KILLED`, `CapabilityErrorKind.PERMANENT`).
No caller-visible behavior change beyond a new, more specific error code — this is a design constraint
this document imposes, not a suggestion: every plugin-invocation caller today already handles
`CapabilityResult.failure(...)`, so a resource-killed child must reuse that path exactly, not add a
new exception type callers would need new handling for.

Sequence when the host's pipe read/write to a worker fails, or the worker's process exits
unexpectedly, mid-`invoke`:

1. The host-side pool manager marks that request's `Future` (or equivalent) failed with
   `PLUGIN_EXECUTION_PROCESS_KILLED`, immediately unblocking the caller — no waiting for the full
   `npdev.runtime.plugin-timeout-ms` if the process is already confirmed dead (`Process.isAlive() ==
   false` short-circuits the wait).
2. The dead worker slot is removed from the pool and a replacement is started asynchronously (not on
   the request's own thread — pool refill must never add invoke-path latency).
3. **No other in-flight request on other workers is affected.** This is the core invariant the whole
   feature buys, and needs its own dedicated test, not an inference from the pool design: start N
   concurrent invokes on N different pool workers, kill one worker mid-flight (e.g. a plugin that
   calls `System.exit()` inside its own process — now safe, since it only kills its own OS process,
   not the host), assert the other N-1 complete normally and the host process itself never restarts.

This is exactly the two live-fired tests SEC-3's own done-when bar names: "a plugin that actually
calls `System.exit` and does NOT take down the host process" and "a plugin that actually attempts a
forbidden file/socket operation and is actually blocked." The second one is worth being precise
about: Model B does **not** add a NEW enforcement layer for filesystem/socket access beyond what OS
process boundaries + (optionally) cgroup/Job-Object restriction give for free — a child process still
has the same OS-user filesystem permissions as the host (see §5). "Blocked" here means: Model A's
existing static bytecode scan already refuses *direct* references at admission (unchanged, still the
first line of defense), and Model B's contribution is that even an admitted plugin exploiting a gap in
that scan can only damage its own throwaway process, not the host or its neighbours.

## 5. Trust boundary after Model B — stated explicitly

Worth stating plainly so Model B is not oversold the way "Sandboxed" (`TimeBoundedPluginExecutionEngine`'s
own former class name) already was once:

- The plugin process runs as the **same OS user** as the host. No container, no VM, no user-namespace
  remapping. Process isolation stops a runaway plugin from taking down the host or exhausting host
  memory/CPU *unboundedly* — it does not add a new privilege boundary beyond what OS process
  separation and (where available) cgroups/Job Objects already provide.
- A plugin process can still read/write anything the host's own OS user can, **if** it gets there
  without tripping Model A's static admission scan (e.g. via a callback the host's new allowlist
  fails to restrict tightly enough — see §1's allowlist design, which is the actual security-critical
  piece here, not the process boundary itself).
- This is a deliberate, bounded claim: "contained against resource exhaustion and accidental host
  crashes," not "contained against a plugin author who deliberately tries to escape." A genuinely
  hostile-author threat model (as opposed to hostile-*input*-to-a-trusted-author's-plugin, which is
  what Model A + this design defend against) needs container/VM-level isolation, which is explicitly
  out of scope here and should not be implied by shipping Model B.

## 6. Recommended implementation sequencing

In the order SEC-3's own scoping note gives, each step independently testable before the next starts:

1. **Prototype §1 alone, in-process, against `auditLog`.** No process spawning yet: serialize a real
   `CapabilityCall` to the JSON envelope above and back, and implement the callback allowlist check
   against `PluginExecutionPolicyEvaluator`, all still running in the SAME JVM (e.g. over a pair of
   `PipedInputStream`/`PipedOutputStream` standing in for the eventual child's stdin/stdout). This
   proves the protocol and the allowlist without the (separately hard) problem of process lifecycle.
2. **Real child process, no pool, no resource limits.** Spawn one `ProcessBuilder` child per invoke
   (accept the cold-start cost for now), wire the real stdin/stdout pipes, prove the crash-handling
   sequence (§4) end to end including the two live-fired tests.
3. **Warm pool (§2).** Swap cold-start-per-invoke for the fixed pool; this is where the timeout-budget
   concern actually gets resolved.
4. **Per-OS resource limits (§3).** Linux cgroups first (this platform's CI is Linux for anything
   Docker-adjacent already); Windows Job Objects second. Each ships with its own test matrix — a
   design that only proves out on one OS is not done, per SEC-3's own bar.
5. **Update SEC-3's boundary statement and docs** (`NPDEV_USER_MANUAL`/feature guide,
   `model.schema.json` adapter description) to reflect §5's bounded claim once shipped — the same
   places Model A's boundary statement already lives.

Steps 1–2 are the ones worth attempting before committing to 3–5: they are where "the different-sized
piece of work" framing in SEC-3's own history is most likely to reveal itself as an underestimate (or
not) with real evidence, rather than more up-front design.
