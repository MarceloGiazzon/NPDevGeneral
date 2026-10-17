# CSV and Excel export and import

Every generated grid has **Export CSV**, **Export Excel** and **Import CSV** (which also takes an
`.xlsx`) buttons. They work on the rows the grid is showing.

## What the buttons export

Both export buttons are built in the browser (`exportPanelCsv` / `exportPanelXlsx` in the business
UI). They walk the grid's own list endpoint (`GET /api/concepts/{Concept}`) page by page, 100 rows
per page up to 50,000 rows, with the same free-text search, column filters (`where`) and sort the
grid just used. So the file is exactly the filtered, sorted view on screen; while a filter is active
the grid says so ("Export CSV exports exactly these rows, sorted as shown"). The columns are the
grid's visible columns, headed by their labels.

Cells that start with `=`, `+`, `-`, `@` (or a tab / carriage return) get a leading `'` so a
spreadsheet cannot run them as a formula; a plain number such as `-42` is left alone. The same rule is
applied in the CSV writer, in the Excel writer, and in the server-side `CsvCells` helper.

## CSV

RFC 4180: a value containing a comma, quote or newline is quoted (inner quotes doubled); `null`
becomes an empty field.

## Excel (.xlsx)

**Export Excel** writes a real `.xlsx` with one sheet named after the concept: strings as text,
numbers and booleans as numbers/booleans. The file is built by the small `npdev-xlsx.js` helper that
every app serves; there is no library and no server round trip.

**Import** accepts a `.csv` or an `.xlsx`. For an `.xlsx` the first worksheet is read and turned into
the same text the paste box takes, so preview, per-row validation and the import itself are the
ordinary CSV pipeline. The first row must be the header row (the same labels Export writes).

- Excel stores dates as serial numbers. Format date columns as **Text** before saving, or use CSV;
  otherwise date fields fail validation in the preview.
- Only `.xlsx` is read. An old binary `.xls` or a password-protected workbook shows an error in the
  dialog instead of importing.
- Reading needs a current browser (it uses the built-in `DecompressionStream`: Chrome 80+,
  Firefox 113+, Safari 16.4+). Export works everywhere.

## The server endpoint

`GET /api/concepts/{conceptName}/export.csv` (also under `/api/v1/concepts/...`) still exists
(`ConceptQueryController.exportCsv`) and streams a concept's tenant-scoped rows as CSV, but the grid
no longer calls it. It understands `sort`/`direction` and bare `field=value` equality filters only;
`filter` and `where` are reserved and ignored there. Use the grid's buttons, or the list endpoint,
when you need the filtered view. It streams page by page (`ConceptQuery.MAX_LIMIT` rows per page), so
a large concept is never held in memory; a bad request gets a clean 400 before any CSV byte is
written. Column order is `id` first, then the stored columns.

## Verification

- `ConceptQueryControllerTest.csvRowQuotesOnlyValuesThatNeedIt` and
  `ConceptQueryControllerExportCsvVolumeTest` cover the server endpoint (quoting; 100k rows in 100
  bounded queries).
- `BusinessUiEmitterXlsxTest` checks the helper is emitted, loaded before `app.js`, wired into the grid,
  and that the writer produces a valid zip with accents intact, formulas neutralised and numbers
  numeric.
