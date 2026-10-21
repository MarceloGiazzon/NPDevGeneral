# Plugin sandbox: what is contained, on which OS

A `plugin:java-source` or `plugin:java-controller` capability runs author-supplied Java inside your app.
This page says what NPDev does to contain that code, what each operating system actually enforces, and
what is **not** contained. Each statement names the class or test that backs it. If a statement here
and the code disagree, the code wins and this page is a bug.

**Recommendation: run production on Linux or in a Docker Linux container.** Linux is the only platform
with an OS-level network and filesystem sandbox. On Windows and macOS the containment is weaker; see the
table below.

## Threat model

The design defends against **a trusted author's mistake or a hostile input to that author's plugin**:
a runaway loop, a memory leak, an accidental `System.exit`, an unintended file or network call. It does
**not** defend against an author who deliberately tries to escape. The worker process runs as the same OS
user as the app, with no container or VM around it
(`docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md`, section 5). Mount only plugin code whose source
you trust; the checks below look for escapes, they do not review business logic.

## The layers

| # | Layer | What it does | Backed by |
|---|---|---|---|
| 1 | Generation-time admission | Refuses to emit plugin source that references an escape (file and network IO, `Runtime`/`Process`, reflection, `Class`/`ClassLoader`, threads, executors, scripting, JVM internals, `System.exit`/`getenv`/`getProperty`). | `PluginJavaSourcePolicy` (generator) |
| 2 | Boot-time admission | Scans the compiled bytecode of every plugin class and its `$` siblings against the same denylist; a violation stops the app with exit code 4 and `B30:plugin_bytecode_violation:`. | `PluginBytecodeBootGate`, `UntrustedExtensionBytecodeInspector`; tests `PluginBytecodeBootGateTest`, `UntrustedExtensionBytecodeInspectorTest` |
| 3 | Separate OS process | Every call runs in a pooled child JVM, not on a host thread. A crash or `System.exit` in the child cannot take down the app. | `PluginIpcChildProcessPool`, `PluginIpcChildProcess`; tests `PluginIpcChildProcessPoolRealPluginTest`, `PluginIpcChildProcessTest` |
| 4 | Restricted classpath | The child gets only the classpath entries it needs to start (the IPC main class, kernel API types, Jackson, the app's compiled output, and Spring web annotations only when a controller mount exists), not the host's full classpath. | `PluginChildClasspath`; test `PluginChildClasspathTest` |
| 5 | Class-loader denylist | The child loads the plugin through a loader that refuses the same escape classes at run time (closing the case where a class name is built at run time and never appears in bytecode), and refuses any `com.npdev.generated.*` or `com.finalexec.*` class outside `com.npdev.generated.plugin.`. | `PluginRestrictedClassLoader`; test `PluginRestrictedClassLoaderTest` |
| 6 | Memory and CPU ceiling | The OS caps the child's memory and CPU (Windows and Linux only, see below). | `PluginProcessResourceLimiter`; tests `PluginIpcChildProcessWindowsResourceLimitTest`, `PluginIpcChildProcessLinuxResourceLimitTest` |
| 7 | OS network and filesystem sandbox | The child gets no network and a read-only filesystem. **Linux only.** | `PluginLinuxCgroupResourceLimiter.wrapCommand` |
| 8 | Wall-clock timeout | The call fails if it runs too long. This is the only limit on every OS, including where layers 6 and 7 are unavailable. | `PluginIpcCapabilityHandler` (`npdev.runtime.plugin-java-source-timeout-ms`, default 5000) |

## What each OS enforces

| | Linux | Windows | macOS |
|---|---|---|---|
| Layers 1 to 5 and 8 | yes | yes | yes |
| Memory and CPU ceiling (6) | yes, if cgroup v2 is usable | yes, via a Job Object | **no** |
| Network and filesystem sandbox (7) | yes, only under `systemd-run` and only if this host's systemd accepts the properties | **no** | **no** |

How each cell is decided in code:

- **Selection.** `PluginProcessResourceLimiter.Holder.detect()` picks the Windows limiter when `os.name`
  contains `win`, the Linux limiter when it contains `linux`, and a no-op limiter otherwise. That is why
  macOS has no ceiling. Any probe failure degrades to the no-op limiter; it never stops the app.
- **Linux ceiling.** `systemd-run --user --scope` with `MemoryMax=`, `MemorySwapMax=0` and `CPUQuota=`.
  Where that is not usable, the limiter writes `memory.max`, `memory.swap.max` and `cpu.max` into a cgroup
  that is a sibling of the JVM's own (`PluginLinuxCgroupResourceLimiter`). The swap cap matters: without it
  the kernel swaps instead of killing a runaway child. Live-fired by
  `PluginIpcChildProcessLinuxResourceLimitTest`, which asserts both that a well-behaved plugin completes
  and that a runaway one is killed.
- **Linux sandbox.** Adds `PrivateNetwork=yes` (loopback only), `ProtectSystem=strict` (read-only
  filesystem) and `ProtectHome=yes`. `networkFilesystemSandboxActive` is true only in `systemd-run` mode,
  with the sandbox switch on, **and** when a startup probe shows the host accepts those properties on a
  scope unit. The raw-cgroup fallback has no network or filesystem sandbox. A container without a usable
  `systemd-run --user` therefore gets the ceiling but not the sandbox. `PrivateTmp` is deliberately not
  used because it would hide the child's classpath argfile from the child.
- **Windows ceiling.** A Job Object with `JOB_OBJECT_LIMIT_PROCESS_MEMORY`, a hard CPU-rate cap, and
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` so the child dies with the host
  (`PluginWindowsJobObjectResourceLimiter`). Live-fired by `PluginIpcChildProcessWindowsResourceLimitTest`.
  Windows has no equivalent of the Linux network and filesystem sandbox, so a Windows child's containment
  rests on layers 1 to 5 and the Job Object.
- **No unit test covers the Linux sandbox flags, and no CI job proves them.** There is no unit test of
  `wrapCommand`'s sandbox directives. The sandbox was proven by hand with
  `scripts/proofs/run-r10-plugin-controller-proof.py`, which no CI workflow runs. The `E8` job in
  `.github/workflows/engine-support.yml` runs a java-source plugin on a GitHub-hosted runner whose systemd
  rejects the sandbox properties, so what it witnesses is that such a host degrades to "no sandbox"
  instead of failing every call (`ledger/items/SEC-10.yml`). Do not read a green `E8` as evidence that the
  sandbox is active on that runner.

What is active on a given host is logged once at boot: `plugin containment: classpath-restricted (N
entries) + classloader-denylist + os-sandbox(linux)` or `os-sandbox(unavailable on this platform)`
(`PluginContainmentTierLog`). Read that line instead of assuming. When no ceiling mechanism exists the
limiter logs a loud warning at boot and falls back to the timeout alone (`PluginNoOpResourceLimiter`).

## Configuration

| Property | Code default | Shipped in `application.properties` | Meaning |
|---|---|---|---|
| `npdev.runtime.plugin-ipc-pool.memory-limit-mb` | 0 (no ceiling) | 512 | Per-child memory ceiling |
| `npdev.runtime.plugin-ipc-pool.cpu-rate-percent` | 0 (no ceiling) | 50 | Per-child CPU cap |
| `npdev.runtime.plugin-ipc-pool.sandbox` | `on` | not set | `off` disables layer 7 |
| `npdev.runtime.plugin-ipc-pool.size` | 2 | not set | Warm worker count |
| `npdev.runtime.plugin-ipc-pool.max-invocations-per-worker` | 200 | not set | Worker is replaced after this many calls |
| `npdev.runtime.plugin-ipc-pool.idle-timeout-ms` | 600000 | not set | Idle worker is recycled |

Source: `NpdevPluginConfig` (defaults) and `NPDevRuntimeHost/src/main/resources/application.properties`
(shipped values). A generated app therefore gets 512 MB and 50 percent unless you override them; a bare
`runtimehost-core` embedding gets no ceiling.

## What a plugin can and cannot do

- **Cannot call back into the host.** A `callback` frame from the child is answered with
  `JAVA_SOURCE_CALLBACK_NOT_SUPPORTED` (`PluginIpcCapabilityHandler.rejectCallback`). A plugin sees only
  its own request data and has no database handle or Spring context.
- **Arguments and results must be JSON-safe.** They cross a process boundary as JSON
  (`PluginIpcFrameCodec`, `PluginIpcJsonSafeValues`; tests `PluginIpcFrameCodecTest`,
  `PluginIpcJsonSafeValuesTest`).
- **A controller mount is not a live Spring bean.** The reserved `com.npdev.generated.plugin.*` package is
  excluded from component scanning, and each request to its `basePath` is serialized and run in the same
  worker pool (`PluginControllerProxyHandler`, `ManifestDrivenJavaControllerPluginHandler`).
  `plugin:java-controller` signatures are limited to `@PathVariable`, `@RequestParam` and `@RequestBody`
  parameters; anything else is refused at generation time. The generator prints a warning naming each
  mounted controller (`RuntimeApiEmitter.emitJavaControllerMounts`, test
  `RuntimeApiEmitterPluginControllerWarningTest`).

## What is not contained

These are permanent limits, not TODOs (`docs/ACCEPTED_BOUNDARIES.md`, B30):

- **Static analysis is the only admission gate.** A class elsewhere on the app classpath that performs a
  forbidden action on a plugin's behalf is invisible to a constant-pool scan
  (`PluginBytecodeBootGate` class comment).
- **A busy loop inside the ceilings is bounded, not stopped.** A plugin that spins without exceeding the
  memory or CPU cap runs until the timeout.
- **The worker has the app's OS user.** Where layer 7 is off, nothing at the OS level stops it reading
  what that user can read.
- **macOS has no OS-level ceiling at all.**

## Declaring and testing one

1. Declare a custom capability and bind it to the adapter in the model:
   `"bindings": [{"capability": "adminTools", "adapter": "plugin:java-controller"}]`, or
   `"adapter": "plugin:java-source"` for a capability a flow calls. Working examples:
   `NPDevSamples/probes/p7-plugin-controller` (controller) and `NPDevSamples/probes/lib-probe`
   (java-source).
2. Put the source under `Input/capabilities/<name>/src/main/java/` with a `capability.plugin.json`, in the
   package the sample shows (controllers must live under `com.npdev.generated.plugin.`).
3. Generate. An escape in the source is refused at generation time with the policy's message.
4. Boot and read the `plugin containment:` log line to see which layers this host really has.
5. To check the ceiling on Linux from a Windows machine, run
   `scripts/quality/run-linux-plugin-resource-proof.ps1`; for a mounted controller end to end, run
   `scripts/proofs/run-r10-plugin-controller-proof.py`.
