# REG-12 Slice 3 — Server-Side Document / PDF Export — Phased Plan

> **STATUS: HISTORICAL** — last changed 2026-07-22; its completion state has **not** been re-verified. Treat nothing here as an open commitment: check `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (authoritative) or `docs/archive/programme-history/OPEN_ITEMS_SNAPSHOT.md` before acting on any item.


> **Status:** DONE (2026-07-22) — executed via `docs/archive/programme-history/FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md` Part A. See
> `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.4 and `NPDev_General__OutsideRepo/reg12-slice3-evidence/`
> for what shipped and the live verification record.
> **Was:** APPROVED PLAN — not started. Greenlit by the owner (Marcelo) 2026-07-22 ("in scope now").
> **Written:** 2026-07-22, against `main` at the `beta1.1` tag.
> **Origin.** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.4 (REG-12 / LNCH-10). Slice 1 (streaming CSV
> export) is DONE; Slice 2 (print stylesheet / print render mode) is a separate small frontend item.
> Slice 3 — **server-side rendered documents (PDF), a `document` object kind** — is the `XL` piece
> that always deserved its own plan, the way LNCH-1 got one. This is that plan.
> **Audience.** An AI implementation session (or human) with no project history. Follow it phase by
> phase. Every claim that names a file/method is either grounded (cited) or marked **VERIFY** — check
> real code before acting; line numbers drift.
> **This plan does not start implementation.** It is the design + phasing to be reviewed, then built.

---

## 1. The core design decision (do NOT re-derive)

**A generated document is HTML+CSS rendered to PDF by a pluggable adapter. Slice 2 and Slice 3 are the
same pipeline.** The print stylesheet Slice 2 produces for a declared panel *is* the CSS the PDF
renderer consumes. So:

```
declared panel/document + data query  ──►  HTML (Slice 2's print template + print.css)
                                        ──►  DocumentRenderContract adapter  ──►  PDF bytes  ──►  streamed response
```

This is why Slice 2 (print HTML/CSS) should land first or alongside: Slice 3 renders the *same* HTML
server-side instead of relying on the browser's print dialog. One template, two outputs (browser
print + server PDF). Do not design a second, separate PDF-only templating path.

**Why an adapter, not a capability.** The register records that NPDev capabilities are pure functions
(a documented boundary). PDF rendering is I/O- and library-heavy — it is **not** pure. So the renderer
is a **pluggable port/adapter pair** in the kernel-adapter style (`file-store-inproc` /
`file-store-objectstore`, `mail-inproc` / `mail-smtp` are the precedents), never a capability.

**Why mirror the CSV precedent.** Slice 1 already streams a concept's filtered/sorted view as CSV via
`NPDevRuntimeHost/.../api/ConceptQueryController.java` — `exportCsv(...)`,
`@GetMapping("/{concept}/export.csv")`, with `csvFilename`/`toCsvRow`/`csvEscape`, streaming to avoid a
half-written response. The document endpoint mirrors this exactly: same filtered/sorted data source,
same streaming discipline, a sibling `@GetMapping("/{document}/render.pdf")`.

---

## 2. Orientation (VERIFY line numbers before editing)

| File / area | What matters here |
|---|---|
| `NPDevRuntimeHost/.../api/ConceptQueryController.java` — `exportCsv` (`export.csv` mapping) | The streaming-export precedent Slice 3 mirrors: how the filtered/sorted rows are obtained and streamed, how the filename/headers are set before any body is written. |
| `NPDevKernel/adapters/*` — e.g. `file-store-inproc` / `file-store-objectstore`, `mail-inproc` / `mail-smtp` | The port/adapter-pair pattern the renderer follows: a `*Contract` port in the kernel, a default in-proc adapter, an optional production adapter. Copy this structure for `DocumentRenderContract`. |
| `NPDevKernel/kernel/.../ports/` (VERIFY exact package) | Where `FileStoreContract`/`MailCapabilityAdapter`-style ports live — add `DocumentRenderContract` here. |
| `NPDevContract/schemas/model.schema.json` **(4 copies — see guardrail #3)** | Where the new `document` object kind is declared. |
| `NPDevGenerator/.../npdev-templates/business-ui-app.mustache` | The grid toolbar where the CSV export button lives — add a "Download PDF" affordance next to it (VERIFY location). |
| `NPDevGenerator/.../emitters/` (the controller/UI emitters — VERIFY which) | Where the `/{document}/render.pdf` endpoint and the document template are emitted. |
| `NPDevRuntimeHost/.../config/NpdevPluginConfig.java` | Where adapters are wired into the runtime (this is the file the CI mail-adapter fix touched) — the new renderer adapter registers here. |
| Slice 2's print template + `print.css` (once it exists) | The HTML/CSS the renderer consumes. If Slice 2 isn't done yet, P2 below produces a minimal print template first. |

---

## 3. Guardrails (binding)

1. **Build output → `D:\WorkSpace\NPDev\Build`; evidence → `NPDev_General__OutsideRepo`.** Never in the repo.
2. **Adapter, not capability** (§1). The renderer is a `*-inproc` / `*-<lib>` pair, wired like `mail-*`.
3. **`model.schema.json` is duplicated in 4 places** — any schema edit mirrors to all four
   (`NPDevContract/schemas/model.schema.json`, `.../schemas/authoring/model.schema.json`,
   `NPDevContract/dsl/src/main/resources/schema/model.schema.json`,
   `NPDevContract/dsl/resources/Schemas/model.schema.json`). There is a conformance test pinning this.
4. **New adapter jars must be added to the packaged-app test adapter lists.** The CI grind proved this:
   `NpdevPluginConfig` importing an adapter whose jar isn't staged breaks the generated app's compile
   (that was the `mail-inproc`/`mail-smtp` failure). If the renderer adapter is referenced by the
   RuntimeHost template, add `:adapters:document-render-*:jar` to the three
   `*PackagedGeneratedAppRuntimeProofTest` adapter lists **and** the sync/build-local-jars path.
5. **No hardcoded dev paths in anything that ships into a FinalApp.** The `D:/` `projectcachedir`
   portability bug (fixed this session) is the cautionary tale — the renderer and any template must be
   path-portable (Linux CI now enforces this).
6. **Live > suite.** A PDF export is verified by a *real generated app producing a real PDF a human
   opens* (ScrapForAI / manual), not just a unit test asserting non-empty bytes.
7. **Cross-platform.** The chosen PDF library must run headless on Linux (CI) with no native/display
   deps — rule out anything needing a browser engine or X server unless containerized deliberately.

---

## 4. Owner questions (batch at P0)

| # | Question | Suggested default |
|---|---|---|
| Q1 | **PDF library?** Pure-JVM HTML→PDF (e.g. OpenHTMLtoPDF / Flying Saucer — no native deps, Linux-clean) vs. an external service (headless Chromium) for richer CSS. | **Pure-JVM HTML→PDF** — Linux-clean, no service to run, matches the "self-hosted, no telemetry" posture (ADR-0007). Accept the CSS-subset limitation for v1. |
| Q2 | **`document` as a new PAGE kind, or a procedure kind?** A PAGE (like `workspace::Menu`) renders a declared template; a procedure could compose arbitrary data. | **A new PAGE-style `document` kind** for v1 — declarative, bound to a concept query like a panel. Arbitrary-composition documents are a later slice. |
| Q3 | **First target document.** A printable *panel/grid* (pick list / packing slip — the GeneXus/WMS need the register names), or an arbitrary free-form template? | **A printable declared panel** first — reuses Slice 2's template + the CSV data path; highest real-world value for the WMS-migration audience. |
| Q4 | **Delivery.** Inline stream (`render.pdf` GET, like `export.csv`), stored file object (via the file-store adapter), or both? | **Inline stream** for v1 (mirrors CSV exactly); storing to the file-store is a trivial later add. |

Do not block P1 (the port + adapter) on these — it's independent. Q2/Q3 gate P2's schema shape.

---

## 5. Phase map

| Phase | Delivers | Depends on |
|---|---|---|
| P0 | Owner answers (Q1–Q4); pick the library; a spike proving it renders HTML→PDF headless on Linux | — |
| P1 | `DocumentRenderContract` port + `document-render-inproc` (default JVM) adapter + a stub/second adapter for the pair | P0/Q1 |
| P2 | The `document` object kind in the schema (4-copy mirror) + generator emission of the `render.pdf` endpoint and the document template | P0/Q2,Q3; Slice 2's print template (or a minimal one) |
| P3 | Runtime wiring: endpoint queries data (CSV path), builds HTML from the print template, calls the adapter, streams PDF; register the adapter in `NpdevPluginConfig`; add jars to the test/sync lists (guardrail #4) | P1, P2 |
| P4 | Live verification — a real generated app renders a real pick-list/packing-slip PDF, opened and checked; CI packaged-app tests still green | P3 |
| P5 | Docs (`docs/DSL_REFERENCE.md` for the `document` kind, a how-to, register update REG-12 → DONE) | P4 |

**Minimum bar if truncated:** P0→P1→P3 with a single hardcoded template (skip the declarative
`document` kind) still yields *a real generated app streaming a real PDF of a grid* — the core user
value — and P2's schema work can follow. Do not ship P1 alone (a renderer nothing calls proves
nothing).

---

## 6. Phase detail (concise — expand per phase when implementing)

**P0 — Decide + spike.** Get Q1–Q4. Then a throwaway spike (in `NPDev_General__OutsideRepo`): feed a
sample HTML+CSS (a grid with a header, borders, page-break CSS) to the chosen library, produce a PDF,
open it, run the same code on a Linux container. **Kill the library now if it needs native/display
deps** (guardrail #7). Record the spike outcome as evidence.

**P1 — Port + adapter pair.** Add `DocumentRenderContract` (input: HTML string + options like page
size/margins; output: PDF `byte[]`/stream) to the kernel ports package (VERIFY where `FileStoreContract`
lives). Implement `document-render-inproc` (the JVM library) as the default; add a second adapter to
honor the pair convention (a minimal/stub or the external-service variant). Unit-test the adapter
directly (HTML in → non-empty valid PDF out) — RED-first.

**P2 — Schema + emission.** Add the `document` kind to `model.schema.json` (mirror to all 4 copies,
keep the conformance test green). Decide its declared shape per Q2/Q3 (a document bound to a concept +
a template + page options). Emit, from the generator: the `@GetMapping("/{document}/render.pdf")`
controller (sibling to `export.csv`) and the document's HTML template (reusing Slice 2's print
stylesheet — if Slice 2 isn't done, emit a minimal print template here and let Slice 2 supersede it).

**P3 — Runtime wiring.** In the emitted endpoint: obtain the filtered/sorted rows via the same path
`exportCsv` uses; render them into the print template → HTML; pass HTML to `DocumentRenderContract` →
PDF; stream with `Content-Type: application/pdf` and a `Content-Disposition` filename, headers set
before any body (the CSV discipline). Register `document-render-inproc` in `NpdevPluginConfig`.
**Guardrail #4:** add `:adapters:document-render-*:jar` to the three packaged-app test adapter lists
and the runtimehost-libs sync/build-local-jars list, or the generated app won't compile on clean CI.

**P4 — Live verification.** Generate a real app with a `document` on a grid (a WMS-style pick list is
the canonical target). Boot it, hit `/{document}/render.pdf`, open the PDF, confirm it's a correct,
readable rendering of the filtered data with page breaks. Verify via ScrapForAI + a human eye. Re-run
the CI packaged-app proof tests (guardrail #4 territory) — must stay green.

**P5 — Docs + close.** Document the `document` kind in `docs/DSL_REFERENCE.md`, add a how-to
(declare a printable document on a panel), and flip REG-12 → DONE in the register with the live PDF as
evidence. Note any CSS-subset limitations of the chosen library honestly (a real boundary, not a bug).

---

*Companion documents: `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §2.4 (REG-12) ·
`NPDevRuntimeHost/.../api/ConceptQueryController.java` (the CSV precedent) ·
`docs/adr/ADR-0007` (self-hosted / no-telemetry posture, relevant to Q1) ·
Slice 2's print-stylesheet work (the shared template/CSS).*
