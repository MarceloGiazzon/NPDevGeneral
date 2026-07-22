---
name: verify-in-browser
description: Drive a generated NPDev FinalApp in a real browser (Playwright via the ScrapForAI harness) to verify UI/runtime behavior end-to-end. Use when confirming a panel/form/workflow change actually works in the running app, not just in tests. Encodes the durable connection, auth, and locator gotchas proven across many apps.
---

# Verify an NPDev app in a real browser

Use the ScrapForAI harness to drive the running app the way a user would. This is the real-browser
proof for UI/runtime changes; tests alone don't exercise the generated page.

## Harness
- In-repo runner: `NPDevSamples/scripts/browser/scrapforai-harness.ps1` (+ `demonstrate-browser.ps1`).
- External engine: `D:\WorkSpace\ScrapForAILegacy` (Playwright). Start/auth it per its own README.

## Non-negotiable gotchas (each has burned a session before)
- **Navigate to `http://127.0.0.1:<port>`, NOT `localhost`.** The SSRF allowlist + browser context
  resolve `127.0.0.1`; `localhost` is rejected/flaky. (`scrapforai-localhost-127`)
- **Authenticate before asserting content:** fill the `#apiKey` field and reload first. The initial
  unauthenticated page load emits benign `401`s — not failures.
- **`Assert-RoutineGreen` treats any `console.error` as failure**, but two are benign and expected:
  a `theme.css` 404 (apps with no custom theme get this by design) and the pre-auth `401`s above.
  Everything else is a real failure.
- **Use strict, scoped locators.** The shell nav container id is `#sideNav`; concept surfaces render
  under `#concept-<Name>` (create/edit forms are inline there now, not in a `#modalRoot` popup).
- **Search inputs are debounced** — wait after typing before asserting filtered rows.
- **External-DB apps:** confirm the app is pointed at the expected DB file location before reading
  "stale" data as a bug.

## Flow
1. Ensure the app is built and running (see the **rebuild-app** skill; start via the app's `_ops`
   `Start-App.ps1` / `Start-Environment.ps1`).
2. Run the harness against a `browser-routines/*.json` routine (or author a short one).
3. Read `Assert-RoutineGreen` output; discount only the two benign console errors above.
4. Report pass/fail with the concrete steps exercised.

## Source of truth
The durable details live in the maintainer memory entries `scrapforai_browser_testing` and
`sample_browser_verification`, and knowledge card `scrapforai-localhost-127`. If this skill and those
disagree, the memory/card wins — update this skill to match.
