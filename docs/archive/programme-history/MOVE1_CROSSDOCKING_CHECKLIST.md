# Move 1 — `crossdocking.html` behaviour checklist

> Frozen 2026-07-29 from `AppGen/apps/_official/WmsOffice/web/crossdocking.original.html` (12,748 B,
> 262 lines), **before** authoring the panel. This is the acceptance test for Step 4/5 of
> `MOVE1_CONSOLE_CONVERSION_PLAN.md`. Each item gets marked **works / works differently /
> cannot-express** after the browser pass, with the line number that grounds it.

| # | Behaviour | Source line(s) |
|---|---|---|
| C1 | On load, fetches `recebimentos`, `expedicaos`, `produtos`, `crossDockings` in parallel (`Promise.all`), then renders selectors + table together | 121-136 |
| C2 | Status bar shows `carregando...` while loading, then `N cross-docking(s)` on success, or `erro: <message>` on failure | 122, 131, 133 |
| C3 | `recebimentoSel` lists only recebimentos where `estagio !== 'Armazenado'`; option label `id.slice(0,8)+"..." (estagio)` | 143-147 |
| C4 | `expedicaoSel` lists only expedicaos where `estagio !== 'SaidaConfirmada'`; same label format | 148-153 |
| C5 | `produtoSel` lists **all** produtos, unfiltered; option label = `nome` | 154-159 |
| C6 | `quantidade` input defaults to `1`, `min=1` | 61 |
| C7 | `dataAtivacao` input defaults to today's date on page load | 273 |
| C8 | "Ativar Cross-Docking" click requires recebimento + expedicao + produto all selected; if not, logs `Selecione Recebimento, Expedicao e Produto antes de ativar.` (red) and makes **no** API call | 206-209 |
| C9 | Ativar success path: `POST /api/flows/AtivarCrossDocking/execute` with `{recebimentoId, expedicaoId, produtoId, quantidade, dataAtivacao, situacao:'Ativo'}` | 211-213 |
| C10 | After the flow succeeds, the screen **itself** (not the flow) does two more writes: `PUT /api/recebimentos/{id}` and `PUT /api/expedicaos/{id}` setting `crossDockingAtivo:true` on each — client-side orchestration beyond the single flow call | 217-221 |
| C11 | On any failure in the Ativar sequence, logs `Erro ao ativar cross-docking: <message>` (red); input fields are **not** cleared | 223-225 |
| C12 | On success, logs two lines (`ativado (id=...)`, `Flags crossDockingAtivo marcadas...`) then reloads all data | 215, 221-222 |
| C13 | Cross-Dockings table: one row per record, columns Situacao (colored badge), Recebimento (truncated id), Expedicao (truncated id), Produto (name resolved via local lookup against the `produtos` list), Qtd, Acoes | 167-198 |
| C14 | Row action buttons (Concluir / Cancelar / "Testar bloqueio") render **only** when `situacao === 'Ativo'`; Concluido/Cancelado rows show no buttons | 173, 183-195 |
| C15 | Concluir: `POST /api/flows/ConcluirCrossDocking/execute` with `{...xd, situacao:'Concluido'}` (full row object spread, not just an id), then on success two more writes (`clearFlags`: PUT recebimento + expedicao `crossDockingAtivo:false`), then reload | 228-238, 252-256 |
| C16 | Cancelar: same shape as Concluir, flow `CancelarCrossDocking`, `situacao:'Cancelado'` | 240-250 |
| C17 | "Testar bloqueio": PUTs the linked Recebimento's `situacao` to `Cancelado` directly (bypassing any flow) while the cross-docking is Ativo, **expecting the write to be rejected** by a business rule; logs green "OK ... corretamente bloqueado" if it fails, red "INESPERADO ... NAO foi bloqueado (bug?)" if it unexpectedly succeeds — this is a diagnostic probe of a concept-level invariant, not a normal user action | 259-268 |
| C18 | Log panel prepends timestamped entries, green for success, red for error | 91-96 |
| C19 | "Recarregar" button in the top bar re-runs the full load | 44, 270 |
| C20 | No polling — data only reloads on an explicit reload or after an action completes | (absence of `setInterval`) |

## Out of scope for this checklist

- Shell-managed API base / token bar (C-bar): platform chrome, not console-specific behaviour.
- C17 ("Testar bloqueio") is a manual regression probe for a business invariant, not a feature the
  console exists to provide — noted for completeness but not counted as blocking if a declared Panel
  can't reproduce it; the invariant itself lives on the `Recebimento` concept, not this screen.
