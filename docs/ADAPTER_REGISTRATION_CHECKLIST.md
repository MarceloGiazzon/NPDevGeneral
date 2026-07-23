# Adapter registration checklist

> When you add (or rename) an adapter module under `NPDevKernel\adapters\`, its jar must be listed in
> EVERY place below, or a freshly generated FinalApp will fail to compile on a clean machine / CI with
> a silent symptom (a bare 404 or a `NoClassDefFoundError`, not an obvious build error). This has
> already caused three incidents (`mail-inproc`/`mail-smtp`, `document-render-inproc`/`document-render-stub`).
>
> Do all of these in the same change. Then run the RuntimeHost gate and, if possible, a clean CI run.

## The four places to update

1. `NPDevGenerator\generator\src\test\java\com\npdev\generator\emitters\TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java`
   — add `":adapters:<your-adapter>:jar"` to the adapter-jar build list (kept in alphabetical order).
2. `NPDevGenerator\generator\src\test\java\com\npdev\generator\emitters\HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest.java`
   — same list, same entry.
3. `NPDevGenerator\generator\src\test\java\com\npdev\generator\emitters\HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.java`
   — same list, same entry.
4. `scripts\runtimehost\sync-runtimehost-libs.ps1` — the local jar-staging path; make sure the new
   adapter's jar is staged into the runtimehost-libs directory the generated app compiles against.

## Also check (if the adapter is imported by the RuntimeHost template)

- `NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevPluginConfig.java` imports some adapters
  (e.g. the mail adapters) unconditionally. If yours is imported there, its jar MUST exist or every
  generated app fails to compile — the same reason the three proof-test lists above exist.

## How to verify you got all of them

- Run `pwsh -File scripts\quality\run-runtimehost-gate.ps1` and confirm it passes.
- The real proof is a clean Linux CI run (`.github/workflows/npdev-pr-gate.yml`) — the dev machine
  often has stale jars that hide a missing entry.

## Automated guard (added 2026-07-22)

`NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/AdapterRegistrationConsistencyTest.java`
now enforces this checklist mechanically: every `NPDevKernel/settings.gradle` adapter must appear in
ALL three proof-test lists (or in the test's explicit `KNOWN_NOT_PACKAGED` set, with the reason
recorded here), and the three lists must stay identical. It caught a real drift on arrival
(`TrustedSourceEmitter...` was missing `file-store-objectstore`). The sync script needs no guard —
verified list-free (jar discovery). If you consciously exclude a new adapter, add it to
`KNOWN_NOT_PACKAGED` AND record the reason below:

- `audit-*`, `events-inproc`, `eventstore-postgres`, `flowinstance-*`, `idempotency-*`,
  `tracestore-postgres`, `tracing-inproc`: not imported unconditionally by the RuntimeHost template.
- `postgres-test-support`: test-support module, never packaged.

## For a capable agent (future work, not part of this checklist)

Replace these three hand-maintained lists with a single source of truth — e.g. a test that enumerates
the directories under `NPDevKernel\adapters\` and asserts each appears in all three proof tests — so a
new adapter cannot be added without the guard failing loudly. Tracked as the "adapter-list fragility"
latent item in `docs/NPDEV_OPEN_ITEMS_REGISTER.md`.
