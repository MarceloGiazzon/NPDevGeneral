# Move 3 G4 — inventario.html's file-fed Class A wizards: result

> Per `docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md` G4: "the second Class A console, if G3 holds."
> `inventario.html` has three wizards, not one. They are NOT uniform — one composes cleanly with
> the same propose->review->commit pattern G3 proved, one is a shape mismatch (generate + download,
> not persist), and one is blocked by the same platform gap G4's C10 investigation already named
> (REG-75). Each is given its own honest verdict below, per the plan's "no silent skips" standard.

## Wizard 2 — Importar Contagem: SUCCESS, same pattern as G3

`inventario.html`'s step 2 (paste a pipe-delimited count back for an already-`Gerado`
`InventarioArquivo`; parse -> write N `InventarioArquivoLinha` rows -> advance `Gerado->Conferido`)
composes cleanly with the exact G3 shape: a new `InventarioArquivoAggregate` (root
`InventarioArquivo`, collection `linhas` -> `InventarioArquivoLinha`, both concepts unchanged, reused
as-is) + a new `ImportarContagemProcedure` that calls the same `inventoryFile.importarContagem`
capability the original flow uses and maps its `linhas` output directly onto the draft's own
`linhas` collection (no reshaping needed: the capability's per-line keys already match
`InventarioArquivoLinha`'s real fields, plus one harmless extra `contado` key the aggregate commit
ignores).

The `Gerado -> Conferido` transition needed **no new procedure or capability code at all** — the
original screen itself does that transition as a bare field `PUT`, not a flow call, so the
Workbench's own generic lifecycle-transition button (`store.editHeader(statusField, target);
commitDraft(...)`) already covers it correctly, for free.

**Verified live:**
- REST: `POST .../invoke/ImportarContagemProcedure` with a real pipe-delimited payload returned a
  correctly-shaped draft (`linhas: [{localArmazenagemId, produtoId, loteId, quantidadeEsperada,
  quantidadeContada, divergente, contado}]`). `POST .../InventarioArquivoAggregate` (no id) then
  created a real `InventarioArquivo` + nested `InventarioArquivoLinha` in one atomic commit. A
  follow-up commit with `situacao: "Conferido"` confirmed the transition persists correctly.
- Real browser: opened a brand-new draft, pasted the multi-line payload into the action's inline
  input, clicked "Importar Contagem (Parse)" — the draft populated with a real, correctly-parsed
  `linhas` row, confirmed by screenshot.

**A real bug found and fixed along the way: REG-76.** The first live browser attempt returned 0
rows despite a visually-correct paste. Root cause, confirmed by hooking `window.fetch` in the page
to log the actual POST body: the workbench's `inputFields` mini-form rendered a plain
`<input type="text">`, which silently collapses embedded newlines to a space on assignment — the
two-line CSV payload became one line, so the capability's own header-row-skip logic consumed the
entire payload as "the header" and found zero data lines. G3's `ParseNfeProcedure` test never hit
this because its sample XML happened to be single-line. Fixed by rendering a `<textarea>` instead
(preserves newlines, degrades fine for short values too); re-verified live, the identical routine
now shows a real parsed row.

## Wizard 1 — Gerar Template: assessed, not attempted (shape mismatch)

Generates a CSV **for download** from current warehouse occupancy, and creates an `InventarioArquivo`
header row with **zero lines** (the lines only get written back in wizard 2, after the operator
fills in counts offline). This is not a propose->review->commit cycle at all — there is nothing to
review or edit, and the "output" that matters to the user (the downloadable CSV text) is never
persisted as aggregate data; only the empty header record is. The Workbench primitive has no
"invoke a procedure and offer its text result as a file download" affordance — everything it renders
is either a header/section/band field or an inert extra key in the draft, never a `<a download>`
link. A trivial procedure calling `inventoryFile.gerarTemplate` and creating the header record was
not attempted, because doing so without the download half would silently drop the one thing an
operator actually needs from this screen (the CSV to fill in offline) — a real, honestly-scoped
gap, not a missing afternoon of work.

## Wizard 3 — Recebimento por Arquivo: assessed, not attempted (blocked by REG-75)

Real file upload (`<input type="file">`, read via the browser's File API) -> preview (parse +
resolve each line against reference data, `AnalisarArquivoRecebimento` flow) -> confirm, which for
**each valid line** does: find-or-update-or-create a `Lote` (quantity increment on an existing lot),
re-read fresh `LocalArmazenagem`/`LocalArmazenagemLote` state, call `AlocarRecebimento` to pick bins,
then for each allocation upsert `LocalArmazenagemLote`, flip `LocalArmazenagem.situacao`, and create
an `InventarioArquivoLinha` — four concepts written per CSV line, each write a **partial patch of an
existing record** (increment a quantity, flip one status field) while preserving everything else.

The propose half (upload -> parse -> preview) is achievable with what this session already built:
read the file client-side, paste/pass its text through the same `inputFields` textarea mechanism
(post-REG-76-fix) to a procedure calling `inventoryFile.analisarArquivoRecebimento`. **The commit
half is not** — every one of its per-line writes is exactly the "read an existing record, patch one
field, write it back" shape **REG-75 already named as blocked**: no procedure step unwraps a
`readConcept` result for a `capabilityCall`, and no step constructs/merges a map at all. Building
only the propose half without a working commit would not be a Class A composition — it would be a
preview screen with no confirm, a different and lesser thing than what G3 set out to prove. Not
attempted; the blocker is REG-75, not a new one.

## Verdict

**One of three wizards fully composes and is live-verified (wizard 2)** — the second real Class A
console the plan asked for, proving G3's pattern generalizes rather than being a one-off. The other
two are each assessed precisely rather than silently skipped: wizard 1 is a genuine shape mismatch
(generate+download, not persist), wizard 3 is blocked by the already-named REG-75, not a new,
undiagnosed gap. A second real bug (REG-76) was found and fixed in the course of building wizard 2,
closing a latent hole in the exact mechanism G3 introduced.
