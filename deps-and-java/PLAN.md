# PLAN — App dependencies + per-app Java level

**Status:** proposed, not started · **Filed:** 2026-08-07 · **Trigger:** 3rd-user feedback, points 2 and 3

Two defects that together make NPDev unable to host real business logic. Both are the same
underlying gap: *the escape hatch into real Java exists (`plugin:java-source`) but has no way to
bring anything with it.*

---

## 1. The two defects

### P2 — the Java level is a platform-wide constant

A generated app cannot compile or run against a library that requires Java > 17, and there is
**no per-app knob**. The level is baked into 8 `JavaLanguageVersion.of(17)` pins, one of which
([`NPDevRuntimeHost/build.gradle.template:18`](../NPDevRuntimeHost/build.gradle.template#L18)) is
the generated app's own. A dependency carrying class-file major 65 throws
`UnsupportedClassVersionError` at load.

Secondary, and what the user actually sees first:
[`npdev_cli.py:1941`](../NPDevCli/npdev_cli.py#L1941) is exact string equality —

```python
if found_version != "17":   # Java 21 -> FAIL. Java 25 -> FAIL.
```

so a *newer* Java is reported as a failure. That is defensible on a 21-only machine (no
`settings.gradle` in the repo registers a toolchain resolver, so Gradle cannot auto-provision a 17
and the build genuinely dies with `No matching toolchains found`) but it is a **false negative**
when a JDK 17 also exists elsewhere on the machine — Gradle's auto-detection finds it and builds
fine while doctor still prints FAIL.

### P3 — there is no way to declare a dependency

Verified absent in all three places it could live:

| Location | Finding |
|---|---|
| `model.schema.json` | zero `dependencies` / `maven` / `groupId` fields |
| `capability.plugin.json` | [`GeneratedPluginMountPlan.java:157-248`](../NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/GeneratedPluginMountPlan.java#L157) reads a **closed** field set — no dependency slot |
| `config.json` | `additionalProperties: false`, no build block |

Custom Java compiles against a fixed classpath only
([`build.gradle.template:81-129`](../NPDevRuntimeHost/build.gradle.template#L81)). The two
workarounds both fail:

1. **Drop a jar in the staged libs dir** — machine-global (leaks into every app on the box) and
   `fileTree` does no transitive resolution, so the user must assemble the whole dependency
   closure by hand.
2. **Edit the generated `build.gradle`** — destroyed on the next generate. `npdev generate` passes
   `--clean --cleanFinalApp` and
   [`FinalAppAssembler:80`](../NPDevGenerator/generator/src/main/java/com/npdev/generator/assembly/FinalAppAssembler.java#L80)
   re-materializes it from the template every run.

---

## 2. Design decisions

### D1 — split the *platform* toolchain from the *app* toolchain

Platform modules (dsl, kernel, generator, adapters, runtimehost source) stay pinned at Java 17.
Their bytecode loads on **any** JVM ≥ 17. Only the generated app's toolchain becomes variable.

This is what makes P2 cheap: one line in the template, no platform-wide bump, nothing breaks for
users on 17.

**Verified ceiling.** Spring Boot 3.3.2 ([template:7](../NPDevRuntimeHost/build.gradle.template#L7))
supports 17–22. Gradle 8.5 (all three wrappers) supports up to **21**; 22 needs Gradle 8.8, 23
needs 8.10, 24/25 need Gradle 9.

> **Supported set at landing: `17`, `21`.** The schema enumerates them, so an unsupported request
> fails at generation with a message naming the limiter — not a Gradle stack trace four minutes in.
> Going past 21 is a Gradle-bump follow-on (see Risk 1).

### D2 — dependencies belong in `config.json`, not `model.json`

- `model.json` is portable domain truth. A Maven coordinate is not domain truth; it is a build fact.
- A new array in `model.json` triggers the **four-place threading hazard** (`MODEL_ARRAY_KEYS` +
  both `pack.schema.json` copies + `ModelResolver.resolve` + the canonical JSON writer/reader) that
  produced REG-108. `config.json` avoids it entirely.
- `config.json` is already read as a raw `JsonNode` in
  [`GeneratorMain:46`](../NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java#L46)
  and already carries a non-schema'd block (`packs.included`, line 262). Threading is one hop.

### D3 — keep `build.gradle.template` a byte-for-byte copy; inject via a generated sidecar

`materializeRootTemplate` is **documented** (line 378) as substitution-free. Rather than break that
convention, the template gains a fixed three-line hook that applies a generated file **if present**.
Precedent already exists: `appendRuntimeHostLibsDirDefault`
([line 473](../NPDevGenerator/generator/src/main/java/com/npdev/generator/assembly/FinalAppAssembler.java#L473))
appends a resolved value to the app's `gradle.properties` at assembly time.

Wins: full Gradle transitive resolution, a human-readable artifact you can open and debug, no
template forking, and an app with no `build` block emits no sidecar and behaves **exactly** as today.

### D4 — local jars live in the app definition folder

`--cleanFinalApp` deletes the final app root on every generate, so anything dropped there dies.
Local jars go in `<definition>/libs/*.jar` — sibling of `model.json`, the same convention as
`<definition>/capabilities/<name>/src/main/java` — and are copied in at assembly.

---

## 3. Work breakdown

### Wave 1 — P2: per-app Java level + an honest doctor

| # | Work | Files |
|---|---|---|
| W1.1 | Add `build.javaVersion` (enum `[17, 21]`, default `17`) to the config schema. Root is `additionalProperties:false` — without this any config carrying `build` fails validation. | **all 3 copies**: `NPDevContract/schemas/config.schema.json`, `NPDevContract/schemas/authoring/config.schema.json`, `NPDevContract/dsl/resources/Schemas/config.schema.json` |
| W1.2 | Extend the mirror checker to cover the config trio — it covers only `model`/`pack` today, so those three copies can silently drift. Add the trio to the twin-pair registry. | `scripts/quality/check-schema-mirror-consistency.py`, `scripts/quality/twin-pair-registry.json` |
| W1.3 | Read + validate `build.javaVersion`; fail generation with a named error listing the supported set and the limiter. Thread into `FinalAppAssembler.Options`. | `GeneratorMain.java`, `FinalAppAssembler.java` |
| W1.4 | Replace the hard pin with a property-driven value defaulting to 17; append `npdevAppJavaVersion=<n>` to the app's `gradle.properties` in the same method that already writes `npdevRuntimeHostLibsDir`. | `NPDevRuntimeHost/build.gradle.template:16-20`, `FinalAppAssembler#appendRuntimeHostLibsDirDefault` |
| W1.5 | Register the foojay toolchain resolver for the generated app so Gradle auto-provisions a missing JDK instead of dying. **Note:** `settings.gradle.template` does not exist yet — only a 30-byte `settings.gradle`; create the `.template` to match the `build.gradle` convention. | new `NPDevRuntimeHost/settings.gradle.template` |
| W1.6 | Doctor: `>= 17` passes instead of `== 17`. If the resolved java is ≥17 but no matching toolchain is discoverable → **warn** with the auto-provision explanation, not fail. Keep the check `id` (`java-version`) — the Manager keys on it — and change the hardcoded label `"Java 17"` → `"Java 17+"`. | [`npdev_cli.py:1941`](../NPDevCli/npdev_cli.py#L1941), [`NPDevManager/ui/app.js:45`](../NPDevManager/ui/app.js#L45) |
| W1.7 | Update the wrong-java fixture (currently asserts Java 22 = fail) and add an `acceptable-newer-java` fixture. | `NPDevManager/fixtures/doctor-wrong-java.json` + new |
| W1.8 | Parameterize `resolve_jdk17()` → `resolve_jdk(major)`. The Adoptium URL already has `17` in a format string. 17 stays the default install. | [`NPDevManager/src/runtime.rs:77`](../NPDevManager/src/runtime.rs#L77) |

**W1.4 shape:**

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(
            (providers.gradleProperty('npdevAppJavaVersion').orNull ?: '17') as Integer)
    }
}
```

Behaviour is byte-identical to today when the property is unset.

### Wave 2 — P3: declared dependencies

| # | Work | Files |
|---|---|---|
| W2.1 | Add `build.repositories[]` and `build.dependencies[]` to the config schema (all 3 copies). Pattern-validate coordinate shape so a typo fails at generation, not deep inside Gradle. | 3 × `config.schema.json` |
| W2.2 | New `AppDependenciesEmitter` producing `npdev-dependencies.gradle` in the final app root. | `NPDevGenerator/.../emitters/` |
| W2.3 | Three-line hook in the template, after the existing `dependencies {}` block. | `build.gradle.template` |
| W2.4 | Copy `<modelSourceParent>/libs/*.jar` → `<finalApp>/npdev-app-libs/`. Thread the model source parent into `Options` (the generator already computes it — `GeneratedPluginMountPlan` calls it `artifactRoot`). | `FinalAppAssembler.java`, `GeneratorMain.java` |
| W2.5 | Generation-time collision warning when a declared dependency duplicates a Spring-BOM-managed or staged NPDev artifact — named at generation, not discovered as a runtime `NoSuchMethodError`. | `AppDependenciesEmitter` |
| W2.6 | **No change** to `capability.plugin.json`. Dependencies are app-scoped and java-source plugins compile into the app's own source set, so they pick them up automatically. State this in the docs so nobody hunts for a per-capability slot. | docs only |

**W2.1 shape:**

```json
"build": {
  "javaVersion": 21,
  "repositories": [ { "name": "corp", "url": "https://nexus.internal/repository/maven-public/" } ],
  "dependencies": [
    "com.google.guava:guava:33.2.1-jre",
    { "coordinate": "org.apache.poi:poi-ooxml:5.3.0", "scope": "implementation" },
    { "jar": "libs/legacy-driver.jar", "scope": "implementation" },
    { "platform": "com.fasterxml.jackson:jackson-bom:2.17.2" }
  ]
}
```

String shorthand = `implementation` + Maven coordinate. Scopes:
`implementation` | `runtimeOnly` | `compileOnly` | `testImplementation`.

**W2.3 shape:**

```groovy
def npdevAppDeps = file('npdev-dependencies.gradle')
if (npdevAppDeps.isFile()) { apply from: npdevAppDeps }
```

**W2.4 guards** (both non-optional):
- Reject a jar path escaping the definition root — reuse the `startsWith(artifactRoot)` check at
  [`GeneratedPluginMountPlan.java:190`](../NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/GeneratedPluginMountPlan.java#L190).
- Reject a jar named `npdev-migrations-*.jar` — the template's `enforceSingleSchemaRealizationSource`
  ([line 421](../NPDevRuntimeHost/build.gradle.template#L421)) would otherwise fail the build with a
  message about schema realization that has nothing to do with what the user did.

### Wave 3 — proof, corpus, docs

| # | Work |
|---|---|
| W3.1 | **Corpus proof** (this is the acceptance test, not a unit test): a real sample where a custom capability *calls* a Maven dependency and *calls* a local jar — built, booted, REST-hit, proving the class loads. Claude Support Desk is the existing `plugin:java-source` reference. A new model needs a `corpusRole` entry in `scripts/quality/corpus-roles.json`. |
| W3.2 | **Java-21 proof**: one app with `"javaVersion": 21` consuming a library that *requires* 21, built and booted. Without this, P2 is unproven. |
| W3.3 | **Regeneration-survival proof**: generate → build → regenerate → build, asserting the dependency and local jar survive. This is the exact property both current workarounds fail. |
| W3.4 | Docs: `build` block in `docs/NPDEV_USER_MANUAL.md` §5.2, plus a "Using a third-party library" section in Level 4 where `customCapabilities` is already taught. (`DSL_REFERENCE.md` is generated from `model.schema.json` only — config has no generated reference.) |
| W3.5 | `BREAKING.md`: not breaking (both fields optional, defaults preserve current behaviour) — one "added" line. The stability policy's codemod rule does not trigger. |
| W3.6 | File both ledger items under `ledger/items/`; `docs/OPEN_ITEMS.md` regenerates via `scripts/quality/generate_open_items.py` — never hand-edited. |

### Verification

- **T1** (`scripts/quality/run-fast-gate.ps1`) at the end of each wave.
- **T2** (`pwsh -NoProfile -File scripts/quality/run-all-gates.ps1`) before closing — all four
  gates, one command. Never report green from a single gate.
- Any new `check-*.py` must be invoked by some `run-*.ps1` or `run-script-inventory-check.ps1` fails.

---

## 4. Risks and open decisions

1. **Gradle 8.5 caps the app at Java 21.** Enumerating `[17, 21]` is the honest answer today.
   *Recommendation: keep the Gradle bump out of this plan* — it touches all three wrappers and every
   module build, and would make this un-reviewable. Track as a follow-on that raises the enum.
2. **foojay auto-provisioning needs network and downloads a JDK.** Corporate environments will block
   it. Must be opt-out-able and must degrade to today's message, never something worse.
3. **The Manager's private JDK is 17.** If an app requests 21 the Manager needs a second private JDK.
   W1.8 makes it *possible*; wiring the Run screen to notice the app's requested level is extra
   scope. **Decision needed** — recommend deferring, since the raw-CLI path plus foojay already
   covers it.
4. **`config.schema.json`'s third copy under `authoring/`** was not verified byte-equivalent in
   intent to the other two. W1.2's checker will surface any divergence — do W1.2 before W1.1 lands
   so the first mirror edit is already guarded.

---

## 5. Sequencing

```
W1.2 (guard first)  →  W1.1  →  W1.3  →  W1.4  →  W1.6  →  W1.7      [P2 usable]
                                            ↘  W1.5  ↗
W2.1  →  W2.2  →  W2.3  →  W2.4  →  W2.5                              [P3 usable]
W3.1 · W3.2 · W3.3  (proofs, parallel)  →  W3.4 · W3.5 · W3.6         [closable]
```

Wave 1 and Wave 2 both touch the same three `config.schema.json` copies and the same
`build.gradle.template`; running them **sequentially, not in parallel**, avoids the
uncommitted-work collision that has bitten this repo twice before.
