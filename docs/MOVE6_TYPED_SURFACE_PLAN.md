# Move 6 — Close the typed/untyped fault line

> **STATUS: EXECUTED (2026-07-30).** Moves A/B/C/D all landed, tested, and live-verified (a full
> WmsOffice rebuild+boot+curl-based verification); the §7.5 test-gateway-fidelity fix also shipped.
> Kept as a record.
>
> Written 2026-07-30 against `beta1-vision-spine` @ `4d95dee`, from
> `D:\WorkSpace\XPZs\NPDEV_PXTOOLS_COMPLETE_ANALYSIS.md`.
> **Code-focused by instruction.** Docs/verification appear only as build-blocking gates (§7).

---

## 0. Verification — the analysis holds, and undercounts

Every load-bearing claim re-derived at HEAD:

**The fault line, exactly as stated:**
```
panelAction                      18 typed props · additionalProperties: FALSE
autoPanel.transaction.metadata   {"type": "object"}          ← nothing
```

**The unwired seam, exactly as stated** — `AutoPanelExpander.java:685`:
```java
return new CompiledPanelDataSource("rows", concept, null, null, Map.of(), null, null, null);
//                                                  ^^^^                  ^^^^  query, procedure
```
`PanelRuntime.java:376` already executes procedure-backed data sources with a typed
`PANEL_PROCEDURE_UNAVAILABLE` fallback. Built, live, never called from AutoPanel.

**Zero-witness — worse than the document reports.** It names `recompute` and `bandPickers`. Measured
across `AppGen/apps` + `NPDevSamples`, and against `check-dsl-coverage.py`:

| metadata key | corpus models | coverage-gate probes |
|---|---:|---:|
| `recompute` | **0** | **0** |
| `bandPickers` | **0** | **0** |
| `selectionPanel` | **0** | **0** |
| `computed` | **0** | **0** |
| `fkFields` | **0** | **0** |
| `editable` | 1 | **0** |

**Five shipped features with zero witnesses; six invisible to the gate.** The gate grew +213 lines in
Move 5 and cannot see any of them — a probe-based gate only sees keys someone hand-wrote a probe
for, so a key added to an untyped bag is undetectable *by construction*.

**No settings rung exists.** Top-level model properties contain no `settings`, `i18n`, `locale`,
`labels`, `theme`, or `strings`. Consequence, in one shipped file — `workbench-page.html.mustache`,
copied into every generated app:
```
Header 11 · Save 4 · revert 5 · Saved 2 · New 1 · Read-only 1     (English)
Selecionar 2 · Adicionar 1 · Cancelar 1 · Fase 1                  (Portuguese)
```
Generate WordLab today and it renders `Save` beside `Adicionar`.

---

## 1. Root cause, one sentence

**A feature is typed when it attaches to `panelAction`/`procedure`/`flow`/`aggregate`, and untyped
when it attaches to `autoPanel.transaction` — determined by which object it landed on, not by what
the feature is.** Move 5 added six typed properties and zero to the metadata bag, which is the
cleanest possible demonstration.

Everything below follows from closing that, in cost order.

---

## 2. Move A — the i18n/settings rung *(smallest; fixes a defect shipping today)*

Not in the source document's two moves. It belongs first: it is the cheapest item here and the only
one that fixes a bug **every generated app currently has**.

### A.1 Schema — a top-level `settings` block

```json
"settings": {
  "locale": "pt-BR",
  "strings": { "action.select": "Selecionar", "action.add": "Adicionar",
               "action.cancel": "Cancelar", "state.phase": "Fase" },
  "ui": { "pageRows": 20, "dateFormat": "dd/MM/yyyy" }
}
```

Typed, `additionalProperties: false` on `settings` and `settings.ui`; `strings` is an open
`{string: string}` map keyed by a **closed catalogue of platform string ids** (below).

### A.2 Generator — de-hardcode the template

```
NPDevGenerator\generator\src\main\resources\npdev-templates\workbench-page.html.mustache
```
Replace all ten literals with `{{strings.action.select}}` etc. Emit a defaults map (English) merged
under `settings.strings` at generation time, so an app that declares nothing gets coherent English
instead of today's mix.

**Move WmsOffice's Portuguese into WmsOffice's own `settings.strings`.** That is the whole point —
the app's language stops being the platform's default.

Also lift the two literal-bearing sites in `AutoPanelExpander.java` into the same catalogue:
`"Selecionar"` (`:431`) and the action labels `"New"` (`:697`) / `"Save"` (`:585`) /
`"Delete"` (`:586`).

### A.3 Files

```
NPDevContract\schemas\model.schema.json                                  (+3 mirrors, §7.2)
NPDevContract\dsl\...\compiled\CompiledSettings.java                     new
NPDevContract\dsl\...\compiled\CompiledModel.java                        + settings()
NPDevContract\dsl\...\compiled\CompiledModelCanonicalJson.java           write
NPDevContract\dsl\...\compiled\CompiledModelCanonicalJsonReader.java     read      ← §7.3
NPDevContract\dsl\...\compiler\ModelCompiler.java                        parse + merge defaults
NPDevContract\dsl\...\compiler\AutoPanelExpander.java                    :431 :585 :586 :697
NPDevGenerator\...\npdev-templates\workbench-page.html.mustache          10 literals
```

**Additive. No codemod.**

---

## 3. Move B — the hook axis *(the structural fix)*

### B.1 The precedent is already four-for-four

All four existing hooks call the same runner with the same signature —
`procedureRunner.execute(name, payload, ctx)` → `ok()/failureCode()/failureMessage()/state()`:

| Hook | Site | Disposition |
|---|---|---|
| `aggregate.onValidate` | `AggregateRuntime.java:151` | **guard** — `!ok()` throws, no writes yet |
| `aggregate.onCommit` | `AggregateRuntime.java:177` | **sideEffect** — inside the txn, rolls back |
| `invoke()` | `/api/runtime/aggregate/{a}/invoke/{p}` | **patch** — `state()` becomes the draft |
| `dataSource.procedure` | `PanelRuntime.java:376` | **produce** — `state().return` becomes rows |

Four dispositions, each with a working implementation. This is generalization, not invention.

### B.2 The declaration

```json
"transaction": {
  "hooks": {
    "onLoad":       { "procedure": "SeedMovimentoContext" },
    "onFieldChange":{ "procedure": "RecalcularTotais" },
    "beforeAction": { "procedure": "ValidarSugestao", "disposition": "guard" },
    "afterAction":  { "procedure": "AplicarSugestao" },
    "onValidate":   { "procedure": "ValidateMovimento" },
    "onCommit":     { "procedure": "SyncOcupacao" }
  },
  "computed": {
    "origemTotal": { "tier": "client", "expression": "sum(itens[].posicoes[].quantidade)" },
    "saldoFiscal": { "tier": "server", "procedure": "CalcularSaldoFiscal" }
  }
}
```

- `hooks` keys are a **closed enum of positions** → `"onRowLaod"` fails at schema time. This is the
  entire prize.
- `disposition` defaults per position; `tier` defaults to `server` with `procedure`, `client` with
  `expression`.
- Aggregate-level `onValidate`/`onCommit` **join this vocabulary** instead of living in a separate
  dialect one level up. Keep the aggregate-level spelling working (§B.5).

### B.3 The tier is the reason to do this as an axis

Two reactivity tiers already exist as two keys that don't know about each other:

```
metadata.recompute → 450 ms debounce → POST /invoke/{p} → server Procedure → patch draft
metadata.derived   → client-local, every render, "a DELIBERATELY narrow expression subset"
```

`tier` converts a standing apology in a code comment into a declared, author-visible trade.

### B.4 What this retires

| Untyped key | Becomes | Corpus witnesses to migrate |
|---|---|---:|
| `metadata.recompute` | `hooks.onFieldChange` | 0 |
| `metadata.computed` | `computed.*` | 0 |
| `metadata.derived` | `computed.*` `tier: "client"` | 1 |
| `metadata.actions[].applyTo` | `hooks.afterAction` | 2 |

**`afterAction` subsumes `applyTo` entirely.** Today `applyTo` is one hardcoded mode
(`AutoPanelExpander.java:256` — `!"appendRow".equals(mode) → return null`). A procedure receiving
`{draft, result}` and returning a patched draft is strictly more general, needs no mode enum ever,
and is unit-testable.

### B.5 This one **is breaking** — codemod required

Unlike Moves 4 and 5, this removes/renames declared surface. Per `CLAUDE.md`'s standing rule and
`BREAKING.md`: **the `npdev migrate` codemod ships in the same commit**, never after.

```
NPDevCli\dsl_v2_migration.py     metadata.recompute → hooks.onFieldChange
                                 metadata.derived/computed → computed.*
                                 metadata.actions[].applyTo → hooks.afterAction
BREAKING.md                      one line
```

Migration cost is small and measured: **3 corpus witnesses total.** Keep the old keys accepted for
one release, emitting a validation warning, and have the codemod rewrite them.

### B.6 Files

```
NPDevContract\schemas\model.schema.json                       hooks + computed, both closed  (+3 mirrors)
NPDevContract\dsl\...\compiled\CompiledTransactionHooks.java  new
NPDevContract\dsl\...\compiled\CompiledModelCanonicalJson*.java   writer AND reader   ← §7.3
NPDevContract\dsl\...\compiler\AutoPanelExpander.java         read typed hooks, not the bag
NPDevContract\dsl\...\validation\PanelValidation.java         resolve every position against
                                                              declared procedures — it already does
                                                              exactly this for `recompute`; extend
                                                              the existing loop
NPDevRuntimeHost\...\service\AggregateRuntime.java            dispatch new positions
NPDevRuntimeHost\...\service\PanelRuntime.java                beforeAction / afterAction
NPDevGenerator\...\workbench-page.html.mustache               client-tier hooks
```

### B.7 Fill the unwired seam while here

`AutoPanelExpander.java:685` passes `null` for both `query` and `procedure` on every generated data
source. `PanelRuntime` supports both. Wire `dataSource.procedure` through from the declaration —
this is the `produce` disposition, and it is the difference between "a list must be a concept table"
and "a list can be anything a procedure returns."

---

## 4. Move C — `onRowLoad`, gated on a batch guarantee

The single largest reason a list forces a hand-written screen: no way to enrich rows.

```
input :  { "rows": [ … ] }     // one page
output:  { "rows": [ … ] }     // same order, same count, enriched
```

**Hard rules, in code not review comments:**

1. **Row identity preserved** — enrichment adds fields; never reorders, adds, or drops. A different
   count is a hard failure, not silent truncation.
2. **If the batch guarantee cannot be enforced, do not ship the position.** An `onRowLoad` that
   quietly becomes N+1 on a 500-row list discredits the whole axis.
3. **Keep `produce` and `patch` distinct.** `dataSource.procedure` *replaces* the row source (§B.7);
   `onRowLoad` *enriches* rows the gateway produced. Conflating them is the likeliest design error.

Not a stretch — `invoke()` already passes a whole aggregate tree with nested collections as a `Map`.

---

## 5. Move D — addressable regions + mounted components *(highest cost, removes the cliff)*

### D.1 Regions exist in the client, unnamed in the DSL

`workbench-page.html.mustache` already does per-region dirty tracking (`:105`), per-region revert
(`:492`), and already resolves `{top}` and `{parent, band}` addresses (`:401-408`). Two
surface→surface edges exist, special-cased: `bandPickers` and `selectionPanel` — **both currently
zero-witness (§0)**. Generalization, not invention.

### D.2 Addresses, derived — no new authoring burden

```
Movimento.transaction.header
Movimento.transaction.itens
Movimento.transaction.itens.posicoes
```
`Expedicao` stays four lines and silently acquires addressable regions it never mentions.

### D.3 Three ways to fill a region

| `render` | Status |
|---|---|
| `"generated"` *(default)* | exists |
| `"panel"` | generalizes `bandPickers` |
| `"component"` | **new** |

### D.4 The mount contract — the crux

A mounted component edits **through** the store, never around it. That single constraint is what
separates this from an iframe, and it preserves dirty tracking, per-region revert, and atomic commit.

```js
window.npdev.regions.register("posicao-grid", { mount(el, api), update(api), unmount() });
```

`api` is a **narrowed** view: `rows` (the region's slice, not the draft), `columns`, `editRow`,
`addRow`/`deleteRow`, `isDirty`/`revert`, `readOnly`, `invoke`.

**Two hard rules:**
1. **No access to sibling regions or the root draft.** Enforceable — the store already slices by
   collection. Pass a frozen slice; never hand out the store.
2. **No direct persistence.** The component has no save path. Workbench Save commits the whole tree
   atomically, so **REG-72's atomicity extends to custom regions for free**.

### D.5 Where the file lives

App-owned `web/regions/*.js`, served by the existing static-locations mechanism — the pattern
`shell.js`'s auto-injection of app-owned `web/theme.css` already established. **Never** inside
`npdev-generated/`, which is hash-guarded.

### D.6 The metric — do not claim bytes

Mounting regions **moves** hand-written bytes from `web/inventario.html` into `web/regions/*.js`.
Deleting the original and counting 28,031 B would be exactly the gaming the bytes-deleted metric was
written to prevent.

**Pair this move with a different metric: bytes of un-declared, un-provenanced surface.** Under that
measure, converting 15/20 and mounting 5 is a large, non-gameable gain — and a console can finally
reach a *terminal* state instead of sitting at "15/20, 0 B deleted" forever.

---

## 6. Sequencing

```
A  settings/i18n      small    fixes a defect in every generated app today
B  hook axis          medium   retires 4 untyped keys; makes zero-witness structurally impossible
C  onRowLoad          medium   gated on the batch guarantee
D  regions            large    removes the fall-to-hand-written-HTML cliff
```

**B before D**, per the source document's argument, which is right: once hooks absorb the behaviours
that don't need custom *rendering*, the remaining regions are genuinely rendering problems.
Reversed, the first reach will be for a component where a `beforeAction` would have done.

**A can run in parallel** with anything — it touches the template and one compiler file.

### The one-line case for B

Move 4 shipped `onCommit` as a typed hook. Move 5 Wave 3B shipped `onValidate` the same way. **Same
move, done twice, ad hoc.** Doing it as an axis is the difference between closing one gap and
closing the class — and §0 is the evidence that closing them one key at a time now produces features
nobody can find.

---

## 7. Build-blocking requirements

**7.1 Coverage.** `check-dsl-coverage.py` must gain probes for every new surface. **Better: once
`hooks`/`computed` are typed and closed, replace the hand-written probes for those keys with schema
enumeration** — the gate's own comments admit the probes exist only because the data lives in an
untyped blob. That is the structural fix; the probes are the symptom.

**7.2 Schema mirrored to all four:**
```
NPDevContract\schemas\model.schema.json
NPDevContract\schemas\authoring\model.schema.json
NPDevContract\dsl\src\main\resources\schema\model.schema.json
NPDevContract\dsl\resources\Schemas\model.schema.json
```

**7.3 Canonical-JSON round-trip.** Writer **and** reader for `settings`, `hooks`, `computed`,
`regions`. The generator reads canonical JSON, not the in-memory model — writer-only changes pass
unit tests and vanish in generated apps.

**7.4 Move B is breaking → codemod in the same commit** (§B.5), plus one `BREAKING.md` line. A/C/D
are additive.

**7.5 Test-gateway fidelity.** REG-83 shipped broken for nine commits because unit tests used a
gateway without the governed semantic policy. Any new write-path behaviour here is exposed the same
way. **Fix the default test gateway once**, rather than remembering per feature.

---

## 8. Non-goals

- **No per-app screen source emission.** NPDev's 0 bytes of generated per-app screen source is a real
  advantage over PXTools's 3.2 MB; none of this touches it.
- **No `templateObject` equivalent** (app-authorable mustache). Move A's settings rung covers the
  common cases; the template rung is a separate, larger decision.
- **No plugin system.** A region component is a *renderer for declared data*, not an extension point
  for new data access. It gets `api.invoke` and nothing else reaching the server.
- **Not touching `REG-80/81/82`** — unrelated to this fault line.

---

## 9. Note on the console metric, reconciled

Two explanations for "0 bytes deleted across five moves" are both true and are not the same:

1. **Nobody re-measured.** `docs/SCREEN_TAXONOMY.md` was last touched at `ed94669` (Move 3); its
   verdict still cites blockers closed in Moves 4 and 5. `crossdocking`'s last named blocker is
   genuinely gone — `AtivarCrossDocking` is now `['createConcept','callProcedure','return']` — so it
   may already be at parity, unrecorded.
2. **The other four cannot reach a terminal state at all**, because there is no way to keep a
   console's unconverted behaviours *inside* a generated screen. That is Move D.

**Re-running the `crossdocking` checklist is worth doing before any of this** — it is an afternoon,
and it may book 12,748 B that is already earned.
