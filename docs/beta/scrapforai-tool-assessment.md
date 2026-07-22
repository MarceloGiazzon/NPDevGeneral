# ScrapForAI — independent tool/product assessment

**Context:** an independent engineering opinion on Marcelo's `D:\WorkSpace\ScrapForAILegacy`
project, written 2026-06-21 after reading the actual source (`src/server.ts`,
`src/runner/runExploration.ts`, `src/runner/inspectDom.ts`,
`src/security/validateTargetUrl.ts`, the Zod schemas) rather than the README pitch.
Companion to `sample-browser-verification-methodology.md`, which covers how NPDev
uses it.

---

## What it actually is (vs. what the name says)

"ScrapForAI" undersells it. It's not really a scraper — it's a **hardened,
declarative, agent-facing gateway over Playwright**. The real thesis is visible in
three design choices:

1. **Routines are JSON, not code** — ~29 declarative step types validated against a
   Zod schema, with `GET /v1/schema` publishing that contract. That's deliberately
   built so an *LLM* (or CI) can author and validate a multi-step browser job
   without you having to execute arbitrary Playwright TypeScript.
2. **Structured evidence is the return value** — not an HTML report for humans, but
   machine-branchable signals: `consoleErrors`, `pageErrors`, `networkFailures`,
   `unexpectedExternalRequests`, `domText`, screenshots, per-step pass/fail. It's
   opinionated about *what counts as a signal*.
3. **Security is a first-class axis** — SSRF allowlist + private-IP/metadata/localhost
   blocking, three-layer secret redaction (request, result, logs), `evaluate`/`watch`
   gated behind a flag, caps on steps/body/parallelism/timeouts/evidence size,
   single-key auth, helmet/CORS/rate-limit.

So: a **perceive (`inspect-dom`) + act (`run`) loop, behind a safe boundary,
returning evidence an agent can reason over.**

## How innovative

**Moderate-to-high on framing and security; low on raw browser tech** (it's
Playwright underneath — no new capability there).

What's genuinely distinctive:

- **Security-first as the design center.** This is the standout. Most "let an LLM
  drive a browser" tools are cavalier about SSRF, secret leakage, and arbitrary
  `eval`. ScrapForAI treats the caller as a potentially hostile input boundary.
  That's the maturity most of the field is missing, and it's the hardest part to
  retrofit.
- **Validated-routine-as-contract.** "Agent reads schema → emits validated JSON →
  gets deterministic, replayable evidence" is a clean, debuggable, regression-stable
  contract. The determinism/replayability is underrated and is exactly why it
  slotted into NPDev's sample methodology so well.

What's *not* novel, deliberately: no vision/coordinate acting, no natural-language
"click the login button," no autonomous planning, no self-healing selectors. It's a
**deterministic executor, not an agent** — the intelligence stays with the caller.
For reproducible verification that's a feature, not a gap.

## How much it can help

For NPDev specifically: it's close to ideal. It's the only thing that exercises the
generated vanilla-JS UI the way a user's browser does, its evidence model
immediately caught a class of bug HTTP tests structurally can't, and deterministic
routines make it regression-grade. The SSRF model means it's safe to aim at
arbitrary generated apps.

Generally, it's a solid building block for: agentic web QA, guard-railed
"computer-use over web," synthetic correctness monitoring, and provenance-aware
scraping. The schema-driven contract makes it a natural MCP-server / tool-call
target.

## Alternatives / other ways to do the same

- **Playwright Test** (official runner): more power (fixtures, parallelism, trace
  viewer) *if a human writes code*. No remote JSON API, no security boundary for
  untrusted callers. ScrapForAI trades power for a safe, declarative,
  remotely-callable surface.
- **Playwright MCP** (Microsoft): the closest "official" analog and the one with
  ecosystem momentum — exposes browser actions as MCP tools to an LLM. ScrapForAI's
  edges: routine-as-batch (one validated job vs many chatty round-trips), the
  security hardening, and the evidence bundle. Its disadvantage: not a standard,
  smaller ecosystem.
- **Browser Use / Stagehand / Skyvern / LaVague**: higher-level agents that *decide*
  via NL + vision/DOM. More autonomous, far less deterministic/replayable, generally
  weaker security, heavier. Different point on the curve — they decide; ScrapForAI
  executes.
- **Browserbase / Steel / Hyperbrowser**: managed cloud browser infra — you'd still
  need the routine/evidence/security layer ScrapForAI is, on top.
- **Checkly / synthetic monitoring**: overlaps the "is it up and correct" use case,
  but SaaS and human-authored.
- **Plain curl + unit tests**: what NPDev already had — explicitly can't see the
  rendered UI. That gap is ScrapForAI's whole reason to exist.

## Honest weaknesses (as a product, not just for us)

- **Thin coverage of its own public surface.** The `__name`/tsx bug we hit isn't
  cosmetic — `inspect-dom`, a core feature, is broken in the documented run mode,
  which means the smoke tests don't exercise it. Running TS via `tsx` in "prod"
  while shipping `page.evaluate` compiled functions is fragile; it wants a real
  `dist` build + a test that actually calls inspect-dom.
- **Brittle to DOM churn.** No vision/semantic acting means it can't recover when a
  selector moves; the caller must re-inspect. Fine for stable generated DOMs (our
  case), weaker for arbitrary third-party sites or canvas UIs.
- **Personal-scale, not multi-tenant infra.** Single node, in-process job counter,
  ephemeral artifacts, one shared API key, no queue/history/persistence/quotas.
  Great internal tool; not yet a product you'd expose to many callers.
- **No stateful session across calls.** Each job is a fresh browser context —
  excellent isolation for verification, but it means interactive agent loops must
  cram everything into one routine (re-nav/re-auth each time).
- **"Legacy" in the folder name** hints this may be a frozen cut — worth knowing if
  a newer iteration exists.

## Verdict / positioning

As an independent project, it's **well-architected and genuinely differentiated on
the dimension the AI-browser field most neglects: security + determinism.** The core
idea — validated JSON routines + structured evidence + SSRF/secret hardening,
schema-published for LLM authoring — is a sound, slightly-ahead-of-the-curve thesis.
Execution is strong on security and API design, weak on build hygiene/test coverage
and scale.

It will not out-feature Playwright MCP or Browser Use on *their* axes, and it
shouldn't try. Its defensible niche is **deterministic, safe, evidence-producing
browser verification** — an underserved slot, and exactly the one NPDev needed.

If you ever want it to be more than an internal tool, I'd: (1) fix the build/run
hygiene (ship `dist`, test inspect-dom), (2) lean *hard* into the security+determinism
story as the differentiator, (3) consider also exposing it as an MCP server to ride
that ecosystem rather than competing with it, and (4) rename — "agentic browser
verification gateway" is what it is; "ScrapForAI" buries the lede.

Net: a sharp, opinionated tool that's punching above its weight on the parts that are
hard to get right, held back mainly by test/build maturity rather than by its ideas.
