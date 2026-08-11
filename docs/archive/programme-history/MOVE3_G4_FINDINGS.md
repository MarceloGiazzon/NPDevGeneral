# Move 3 G4 — C10 investigation: result

> Per `docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md` G4: "C10 + the second Class A console, if G3 holds."
> G3 held (`docs/MOVE3_G3_FINDINGS.md`). This covers the C10 half of G4. The `inventario` file-fed
> Class A console is tracked separately and was not started in this pass.

## What C10 is

`docs/MOVE1_PANEL_GAPS.md`: "C10 — an action triggering writes to *other* concepts beyond its own
flow's output has no declared mechanism." `crossdocking.html`'s `Ativar`/`Concluir`/`Cancelar`
handlers each PUT the linked `Recebimento`/`Expedicao` with `crossDockingAtivo` flipped, on top of
their own primary write — Panel/procedure/flow actions are all single-binding, single-call.

A second, related, previously-named residual (`docs/MOVE1_PANEL_GAPS.md`'s G2 note): a `scope: row`
action's caller body is always exactly `{id}` — there is no way to declare a *fixed* override value
(e.g. "always set `situacao` to `Concluido`"), so clicking **Concluir** re-invoked the flow with the
row's *current* `situacao` unchanged. This was live, real, and undetected until this session verified
it via direct REST calls.

## What this pass fixed

**The situacao-transition half.** `ConcluirCrossDockingProcedure`/`CancelarCrossDockingProcedure`
(new) replace the `concluir`/`cancelar` panel actions' `binding: flow` with `binding: procedure`.
Each procedure calls a new, tiny Java capability (`crossDockingSync.marcarConcluido`/
`.marcarCancelado`) that returns the current draft with `situacao` set to a literal target value —
procedures have no way to inject a literal constant (`mapValue`'s `value` always resolves as a state
ref, confirmed by reading `DefaultProcedureExecutor.resolve()`), so the literal lives in Java instead,
the same pattern `AllocationCapability`/`FiscalImportCapability` already use.

**Verified live, both directions:**
- REST: `POST .../CrossDockingConsolePanel/actions/concluir` on a real `Ativo` row returned
  `situacao: "Concluido"` with `gatewayTrace.lifecycleTransition: "Ativo->Concluido"` — a genuine,
  server-confirmed transition. Same for `cancelar` -> `Cancelado`.
- Real browser: clicked "Concluir" on a real `Ativo` row in the actual generated business UI
  (`move3-g4-concluir-transition-routine.json`, routine green, 0 console/page errors) — the reloaded
  table shows `situacao: Concluido`, and the panel's own "last action result" debug block renders the
  full server response confirming the same.

**A real platform bug found and fixed along the way: REG-74.** Declaring `crossDockingSync` (a
capability referenced ONLY by the two new procedures, never by any flow) crashed the generated app at
Spring boot: `Adapter 'plugin:java-source' for capability 'crossDockingSync' ... is not declared in
active plugin manifest`. Root cause: `CompiledPluginRequirementGraphBuilder` (the compiler pass that
decides which capabilities get mounted/compiled into the app) only ever scanned **flow** steps for
`capabilityCall` usage, never procedure steps. Every custom capability used so far (`alocacao`,
`fiscalImport`) had ALSO been called by a pre-existing flow, which mounted it and masked the gap.
Fixed by adding a parallel scan over `modelAst.getProcedures()`. RED->GREEN proven
(`CompiledPluginRequirementGraphBuilderTest.collectsCapabilityRequirementsFromProcedureStepsToo`,
verified failing before the fix, passing after), zero regressions in the full DSL test suite, and
verified live (the app now boots and the capability mounts correctly).

## What this pass did NOT fix — named, not silently dropped

**The Recebimento/Expedicao `crossDockingAtivo` sync — C10's original, literal scope — remains
`cannot-express`.** Attempted via a procedure (`readConcept` the sibling record, flip one field,
`saveConcept` it back) and hit a real, precisely-evidenced wall, filed as the open gap **REG-75**:

- `readConcept`'s output is a raw `ConceptRecord`, not a `Map`.
- `saveConcept`'s own data-ref resolution unwraps `ConceptRecord` automatically (so re-saving an
  *unchanged* read works) — but `capabilityCall`'s arg resolution does **not**, so passing a
  `readConcept` result to a capability (to patch one field, Java-side, the same trick that fixed the
  situacao problem) hands the capability a `ConceptRecord` where it expects a `Map` — a reflective
  type-mismatch the dispatcher does not even catch cleanly.
- Custom Java-source capabilities compile with no `com.npdev.kernel.*` on their classpath (confirmed:
  the three existing ones import nothing beyond `java.*`), so a capability cannot accept
  `ConceptRecord` as a workaround either.
- No procedure step constructs or merges a map at all — `mapValue` always writes one flat top-level
  key, never "copy this map but override one field."
- Confirmed there is no silent gateway-level merge to fall back on:
  `ConfiguredConceptGatewaySemanticPolicy.normalizeAndValidate` builds its working data strictly from
  the caller's own supplied map, never consulting the previous record for omitted fields.

Ativar's own equivalent gap (setting `crossDockingAtivo: true` on the same two records) was not
attempted either, and has an additional wrinkle: converting `ativar` to a procedure would also need
procedure-side auto-generated-id creation (today only flows' `createConcept` step supports it) — a
second, separate prerequisite, named here rather than half-solved.

**inventario's file-fed Class A screens (Gerar Template / Importar Contagem / Recebimento por
Arquivo)** are tracked separately — see `docs/MOVE3_G4_INVENTARIO_FINDINGS.md`.

## Verdict

C10 is **partially closed**: the transition-value gap (a real, live bug affecting every use of
Concluir/Cancelar) is fixed and verified; the cross-concept write gap is not, and now has a precise,
evidenced description (REG-75) instead of a one-line "cannot-express" note. Two real platform bugs
(REG-74, and REG-73 earlier in this same move) were found and fixed in the course of this
investigation — neither was anticipated by the plan, both are now closed for the whole platform, not
just this console.
