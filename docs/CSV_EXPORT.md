# CSV export (LNCH-10 slice 1)

`GET /api/concepts/{conceptName}/export.csv` (also under `/api/v1/concepts/...`) streams a
concept's tenant-scoped rows as CSV, an "Export CSV" button on every generated grid.

## The endpoint

Backed by `ConceptQueryController.exportCsv` — reuses the exact same `sort`/`direction`/
structured-filter query parameters (and `parseConceptQuery` parsing) the paged
`GET /{concept}/page` endpoint already uses (LNCH-5), so a structured `where`-shaped filter that
works for the grid works identically for the export. Streams page-by-page through
`ConceptGateway#query` at `ConceptQuery.MAX_LIMIT` (1000) rows per page, flushing the HTTP
response after each page, rather than materializing the whole result set — a 100k-row concept
never holds more than one page of records in the JVM at once. Proven by
`ConceptQueryControllerExportCsvVolumeTest`: 100k rows, exactly 100 bounded `query()` calls (never
one unbounded fetch-all), correct row/column count in the output.

The concept and any filter/sort field are validated against the concept's declared schema
**before** any response header is written (the first page is fetched first; only once that
succeeds does the response become `text/csv` with a `Content-Disposition: attachment`
header) — a bad request always gets a clean 400, never a half-written CSV with a 200 status.

Column order: `id` first, then every field in `ConceptRecord.data()`'s own key order (JDBC:
`SELECT *` column order; InMemory: declared-field order). Values are RFC4180-quoted (comma/quote/
newline triggers quoting, internal quotes doubled); `null` becomes an empty field.

## The free-text filter gap (a known, pre-existing boundary — not new here)

The generated grid's search box is a **free-text, OR-across-every-filterable-field substring
match** — LNCH-5 already documented that this has no SQL-pushdown equivalent (`GeneratedConceptCrudController.list()`'s own comment: "Free-text `filter` ... has no query-tree equivalent so it
deliberately keeps the legacy fetch-all fallback"). The generic export endpoint's query contract
only supports **structured** filters (the same `ConceptQuery.Filter` shape the paged endpoint
pushes to SQL), none of which the grid's UI currently exposes as a control.

Consequence: the Export CSV button reuses the grid's current **sort**, but not its free-text
search box value — exporting the free-text-filtered view would either silently ignore the filter
(wrong data, worse than an error) or require fetching everything into memory to filter
client-side (defeats the whole point of a streaming export). Rather than either, the button:

- Only carries `sort`/`direction` into the export URL.
- Shows an inline notice under the toolbar whenever the search box has a value: *"Export CSV
  exports every {concept} row sorted as shown — it does not apply the search box filter above."*

A future slice that gives the grid a structured filter control (dropdown/column-header filters
instead of free text) would close this gap for free, since the export endpoint already speaks
that query shape.

## Verification

- `ConceptQueryControllerTest.csvRowQuotesOnlyValuesThatNeedIt` — the RFC4180 quoting helper.
- `ConceptQueryControllerExportCsvVolumeTest` — the DoD's own bar: 100k rows for one tenant,
  H2-backed, real `DefaultConceptGateway`/`JdbcBusinessConceptStore`, asserts exactly
  `ceil(100000/1000) = 100` bounded `query()` calls and a correct `100001`-line CSV body (header +
  every row) — proves both "exports the full filtered view" and "without OOM" (bounded per-call
  page size) deterministically, not by a timing heuristic.
- Live end-to-end (real `bootJar`+boot against `simple-contact-intake`): created two
  `ContactMessage` rows including one with a comma and an embedded quote in its fields, curled
  `/api/concepts/ContactMessage/export.csv`, got a real `200` with `Content-Disposition:
  attachment; filename="ContactMessage.csv"` and correctly RFC4180-quoted CSV. **Found+fixed a
  real bug this way**: the first header line repeated `id` twice (`id,email,id,message,...`) —
  `ConceptRecord.data()` already carries its own `"id"` entry (`JdbcBusinessConceptStore.toRecord`
  puts every `SELECT *` column, including the id column, into `data()`), and `resolveColumns` was
  separately prepending `"id"` on top of that. The 100k-row hermetic test's synthetic model
  happened not to reproduce this (worth noting as a gap in that fixture, not chased further this
  session) — only the live curl against a real generated app caught it. Fixed with a
  `LinkedHashSet` dedupe.
- `node --check` on the generated `npdev-business-ui/app.js` bundle (the Export CSV button's
  actual emitted JS) confirms it's syntactically valid after the `business-ui-app.mustache`
  change; the button and its `exportCsvUrl(concept)`/notice logic were confirmed present in the
  regenerated output.
