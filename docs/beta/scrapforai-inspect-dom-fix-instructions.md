# Fix: ScrapForAI `inspect-dom` fails with `ReferenceError: __name is not defined`

**Target repo:** `D:\WorkSpace\ScrapForAILegacy` (standalone — this fix needs nothing from any other project).
**Audience:** an AI coding agent (Cursor). Everything needed is in this file; do not re-derive.
**Estimated size:** ~6 lines of product code + 1 small regression test. Low risk.

---

## 0. Repo facts you need (don't re-investigate)

- Node ESM project (`"type": "module"`). Imports use **`.js` extensions** that resolve to `.ts` under `tsx` (e.g. `import ... from '../config/env.js'`).
- Runtime is **`tsx`** (see `package.json`): `dev` = `tsx watch src/server.ts`; tests = `tsx --test test/**/*.test.ts`.
- HTTP API: `POST /v1/explorations/run`, `POST /v1/explorations/inspect-dom`, `GET /v1/schema` (no auth), `GET /health` (no auth). All other routes need `Authorization: Bearer <SCRAPFORAI_API_KEY>`.
- Security: target URLs must be in `ALLOWED_TARGET_ORIGINS` (origin allowlist) or the request is rejected. `SCRAPFORAI_API_KEY` must be ≥16 chars. Config is read once at import time in `src/config/env.ts`.

## 1. The bug (exact root cause — confirmed, do not second-guess)

`POST /v1/explorations/inspect-dom` returns HTTP 500 with:

```
page.evaluate: ReferenceError: __name is not defined
```

Why: `src/runner/inspectDom.ts` → `extractDomSnapshot()` passes a **compiled function** to Playwright's `page.evaluate(() => { ...many named inner functions... })`. `tsx` transpiles with **esbuild `keepNames` enabled**, which rewrites named functions/classes as `__name(fn, "fn")` to preserve `.name`. The `__name` helper is emitted in **Node module scope**. When Playwright serializes the function and runs it **in the browser**, that helper is absent, so the injected `__name(...)` calls throw `ReferenceError`.

Scope of impact — important so you fix the right thing:
- **Affected:** any `page.evaluate(<compiled function>)`. In this codebase that is **only** `inspectDom.ts`'s `extractDomSnapshot`.
- **NOT affected:** the routine runner (`src/runner/runExploration.ts`) — its step actions use Playwright **locators**, and its `evaluate`/`watch` steps pass a **string** (`buildEvaluateExpression`) that esbuild never transforms. `collectEvidence.ts` uses `locator.innerText`, not `evaluate`. So `POST /v1/explorations/run` already works; do not touch it.

## 2. The fix (do this)

Install a one-line **`__name` no-op shim in every browser document**, at the single place every browser context is created, so it covers `inspect-dom` and any future compiled `page.evaluate`.

**File:** `src/runner/playwrightContext.ts`
**Where:** immediately after `const context = await browser.newContext({...});` and **before** `const page = await context.newPage();`.

Add:

```ts
  // tsx transpiles with esbuild `keepNames`, which injects `__name(fn, "fn")`
  // calls into functions to preserve their .name. That helper lives in Node
  // module scope, so a compiled function passed to page.evaluate (e.g. the DOM
  // snapshot in inspectDom.ts) throws "ReferenceError: __name is not defined" in
  // the browser. Define a no-op shim in every new document. Use a raw string
  // (the { content } form) so esbuild never transforms the shim itself.
  await context.addInitScript({
    content: 'globalThis.__name = globalThis.__name || function (value) { return value; };',
  });
```

The file should end up like this (context for the insertion point):

```ts
  const context = await browser.newContext({
    viewport: { width: 1366, height: 768 },
    ignoreHTTPSErrors: false,
  });

  // <-- INSERT the addInitScript({ content: ... }) block here -->

  const page = await context.newPage();
```

### Why this exact form
- `addInitScript` runs **before any page script, on every document/navigation**, in the **main world** — the same world `page.evaluate` uses by default — so `globalThis.__name` is defined before the snapshot runs and persists across `goto`.
- The **`{ content: '...' }` (raw string)** form is mandatory here: a function passed to `addInitScript` would itself be esbuild-transformed and could re-introduce a `__name` reference (chicken-and-egg). A string is passed through untouched.
- Placing it in `createPlaywrightContext` (not in `inspectDom.ts`) fixes it **once** for every caller.

## 3. Do NOT do these

- Do **not** rewrite `extractDomSnapshot` to dodge `keepNames` by renaming/removing inner functions — fragile and unnecessary.
- Do **not** pass a **function** to `addInitScript` (see above). Use `{ content }`.
- Do **not** change `runExploration.ts` — it is not affected.
- Do **not** disable security (`ALLOW_EVALUATE`, allowlists) to "make it work".

## 4. Manual verification (run these exact steps)

`example.com` is a universally reachable, allowlisted-by-you target. Talk to the server on **`127.0.0.1`** (the server may bind IPv4-only; `localhost` can resolve to IPv6 `::1` and fail to connect).

**Start the server with `example.com` allowlisted** (the inline env var wins; dotenv does not override an already-set env var, so `.env` is untouched):

PowerShell:
```powershell
$env:ALLOWED_TARGET_ORIGINS='https://example.com'; $env:ALLOWED_RESOURCE_ORIGINS='https://example.com'; npm run dev
```
bash:
```bash
ALLOWED_TARGET_ORIGINS=https://example.com ALLOWED_RESOURCE_ORIGINS=https://example.com npm run dev
```

**Then, in a second shell, call inspect-dom** (use the `SCRAPFORAI_API_KEY` from `.env`):

PowerShell:
```powershell
$key = (Select-String -Path .\.env -Pattern '^SCRAPFORAI_API_KEY=(.+)$').Matches.Groups[1].Value
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:3000/v1/explorations/inspect-dom' `
  -Headers @{ Authorization = "Bearer $key" } -ContentType 'application/json' `
  -Body '{"targetUrl":"https://example.com/"}' |
  Select-Object status, title, @{n='controls';e={ $_.allControls.Count }}
```

bash (with `jq`):
```bash
KEY=$(grep '^SCRAPFORAI_API_KEY=' .env | cut -d= -f2-)
curl -s http://127.0.0.1:3000/v1/explorations/inspect-dom \
  -H "Authorization: Bearer $KEY" -H 'content-type: application/json' \
  -d '{"targetUrl":"https://example.com/"}' | jq '{status, title, controls: (.allControls|length), error}'
```

**Expected AFTER the fix:** HTTP 200, `status: "passed"`, `title: "Example Domain"`, `allControls` length ≥ 1 (the "More information..." link). No `error`.

**Expected BEFORE the fix (sanity, to confirm you reproduced it):** HTTP 500, `status: "failed"`, `error.message` contains `__name is not defined`.

## 5. Regression test (add this — the bug existed because inspect-dom had no test)

Create **`test/inspect-dom.smoke.ts`**:

```ts
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import type { AddressInfo } from 'node:net';

// Serve a tiny fixture on 127.0.0.1 and allowlist its EXACT origin BEFORE importing
// inspectDom: src/config/env.ts parses process.env once at import time, and an
// explicitly-allowlisted origin bypasses the private-IP block in validateTargetUrl.
test('inspect-dom returns a DOM snapshot (regression: esbuild __name ReferenceError)', async () => {
  const server = http.createServer((_req, res) => {
    res.setHeader('content-type', 'text/html');
    res.end(
      '<!doctype html><html><head><title>Fixture</title></head>' +
        '<body><form id="f"><input name="email" type="email"></form>' +
        '<button id="go">Go</button></body></html>',
    );
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  const origin = `http://127.0.0.1:${port}`;

  process.env.SCRAPFORAI_API_KEY = process.env.SCRAPFORAI_API_KEY ?? 'test-key-1234567890';
  process.env.ALLOWED_TARGET_ORIGINS = origin;
  process.env.ALLOWED_RESOURCE_ORIGINS = origin;

  const { inspectDom } = await import('../src/runner/inspectDom.js');
  try {
    const result = await inspectDom({
      targetUrl: `${origin}/`,
      waitUntil: 'load',
      timeoutMs: 10000,
      includeRenderedHtml: false,
      includeHiddenControls: true,
      includeConsole: true,
      includeNetwork: false,
      redactSensitiveValues: true,
    });
    assert.equal(result.status, 'passed', `inspect-dom failed: ${JSON.stringify(result.error)}`);
    assert.equal(result.title, 'Fixture');
    assert.ok(result.allControls.some((c) => c.name === 'email'), 'expected the email input in the snapshot');
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
```

Run it:
```bash
npx tsx --test test/inspect-dom.smoke.ts
```
It must **fail before** the §2 fix (with the `__name` error surfaced via the `status:"passed"` assertion) and **pass after**. Requires Playwright chromium to be installed (`npx playwright install chromium` if missing).

## 6. Acceptance criteria

- [ ] §2 edit applied in `src/runner/playwrightContext.ts` using the `{ content }` string form.
- [ ] Manual check (§4) returns `status:"passed"` + `title:"Example Domain"` (was 500 + `__name` error).
- [ ] `test/inspect-dom.smoke.ts` (§5) added and passing.
- [ ] `npm test` and `npm run test:smoke` still green (no regression to the routine runner).
- [ ] `npm run typecheck` clean.
- [ ] No changes to `runExploration.ts` and no security relaxations.

## 7. Optional follow-up (not required for this fix)

The deeper hygiene fix is to run compiled output instead of `tsx` in production: `npm run build` (tsc → `dist/`, which does not inject `__name`) and run `node dist/server.js` (`start` already does this). That removes the whole class of esbuild-helper-in-the-browser issue, but requires the TypeScript build to be clean and changes the run model — keep it as a separate task. The §2 shim is the correct minimal fix and is also harmless under a `dist` build.
