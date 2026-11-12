# NPDev MCP Server

Lets an external AI **drive** NPDev instead of guessing at its strict JSON. A zero-dependency
Model Context Protocol server (JSON-RPC 2.0 over stdio) that wraps the portable CLI
([`NPDevCli/npdev_cli.py`](../NPDevCli/npdev_cli.py)) and the canonical schemas.

## Tools

| Tool | What it does |
| --- | --- |
| `npdev_validate` | Full structural + semantic validation of a `model.json` **without generating** → typed `npdev-validation-report.v2` report (per-diagnostic `path`/`concept`/`field`/`suggestedFix`). **The authoring self-correction loop.** |
| `npdev_validate_structural` | The fast half of the above: JSON-Schema shape only, **no Gradle** — for the tight edit loop. Cannot see cross-reference errors, so finish with `npdev_validate`. |
| `npdev_inspect_app` | Read-only summary of an existing model (concepts/fields/flows/events/panels/…). Use to avoid duplicating concepts. |
| `npdev_inspect_bonds` | Bond/anchor/onDelete analysis + migration risks. |
| `npdev_list_schemas` / `npdev_get_schema` | Discover and fetch an exact grammar by name. `model` is the canonical `model.json` schema — the only dialect to author. `ai-model` (the older ai-model.v1 `app`/`entities`/`operation` dialect) is deprecated: still served by explicit name, with a deprecation `$comment`. |
| `npdev_get_constrained_schema` | A `schemas/ai` grammar (e.g. `custom-panel`) reduced to the subset a structured-output API accepts (`scripts/ai/derive_constrained_schemas.py`, derived on demand into `<Build>/npdev-ai/constrained/`). `model` has no constrained form (recursive, ~160 KB). |
| `npdev_core_context` | The stable, prompt-cacheable authoring prefix — contract + canonical value reference + golden models (`scripts/ai/build_core_context.py`). `manifest_only` returns just the content hash. |
| `npdev_app_context` | The volatile per-app half: this model's contents, plus a constrained schema only when `schema_name` is passed (no default — it used to default to the deprecated `ai-model`). Goes **after** the core-context prefix. |
| `npdev_search_examples` | Retrieve real, working example snippets (RAG; reads `npdev-ai/rag-index.json` under the build root — `NPDEV_BUILD_ROOT`, else `<repo-parent>/Build` — see `scripts/ai/build_rag_index.py`). |
| `npdev_graph_explain` | "Why does this element exist?" — purely from a generated app's `npdev/semantic-graph.json`: which elements reference it and what it references. |
| `npdev_graph_reuse` | "What would I reuse for this intent?" — keyword-ranks existing elements in that same graph against a free-text intent, with each candidate's own wiring. |
| `npdev_search_fix` | Given a validator diagnostic, retrieve precedent fixes keyed by a normalized failure signature (identifiers templated out). |
| `npdev_check_support` | Cheap pre-check before committing to a model shape: is a feature a known gap, constraint or already-lifted boundary? Takes a ledger id (`REG-244`) or free text. |
| `npdev_author_tier` | Ranks reuse > specialization > new concept > untrusted extension for an authoring intent, and explains each choice. |
| `npdev_author_diff_gate` | AI Authoring Contract check (no archival): verifies a candidate `model.json` against its previous version and a submission manifest. |
| `npdev_author_submit` | The same check, and on a pass archives the previous model so the next iteration can diff against it. The manifest is required. |
| `npdev_impact` | One change-preview report for a baseline/current pair: migration classification, xref usage and the other composed checks. |
| `npdev_migration_diff` | Classify a schema change safe-additive vs destructive; dry-run migration plan. |
| `npdev_generate` | Run the real generator. **Slow + writes to disk — gate behind confirmation.** |
| `npdev_build_and_run` | Generate -> build -> boot -> ready in one call, returning structured diagnostics (`npdev_generate` stops at generate). Slow. |
| `npdev_close_loop` | Natural-language intent -> validated semantic model -> generated app -> runtime proof in one call (wraps `npdev loop run`). |
| `npdev_build_review_pack` | ADR-0009: builds a redacted, chunked external-AI review pack for one mission, written locally; fails closed if the sanitizer finds a secret. |
| `npdev_ingest_review_verdict` | ADR-0009: validates an external-AI verdict file and, only if it passes, records a run entry under `docs/external-ai-review/runs/`. |

## The loop it enables

```
author model.json → npdev_validate → typed errors? → fix → re-validate
                  → npdev_generate → npdev_build_and_run (or `npdev run app`) → done
```

`npdev_validate` returning `status: "failed"` is a **successful** tool call that returns a
report — the model failed, the tool did not. Loop on the `diagnostics` array.

## Run / register

Requires Python 3.9+ and a JDK 17 toolchain (the validator runs the generator's DSL module via
Gradle). No `pip install` needed.

```bash
python NPDevMcp/server.py       # speaks MCP on stdio
```

Register with an MCP client (e.g. Claude Code) — `claude mcp add`:

```bash
claude mcp add npdev -- python /abs/path/to/NPDev_General/NPDevMcp/server.py
```

Or as a raw client config entry:

```json
{
  "mcpServers": {
    "npdev": {
      "command": "python",
      "args": ["D:/WorkSpace/NPDev/NPDev_General/NPDevMcp/server.py"],
      "env": { "NPDEV_ROOT": "D:/WorkSpace/NPDev/NPDev_General" }
    }
  }
}
```

`NPDEV_ROOT` is optional (defaults to the repo root inferred from this file's location).

## HTTP mode (validation service)

`server.py` speaks MCP on stdio for a client that runs on this computer. For an **online** AI there is a
second, separate program: `validation_service.py` (`python NPDevMcp/validation_service.py [--mcp]
[--port N]`, or `npdev validator serve`). It takes the *content* of one `model.json`, runs the real
validator through the warm `npdev-ai-tools.jar` (it refuses to start without it; `npdev validator doctor`
says why) and answers `npdev-validation-service.v1` (`schemas/ai/npdev-validation-service-response.schema.json`).

- **Path A** (any AI that drives a browser, and you): `GET /` is a self-contained page,
  `POST /api/validate` the REST call, `GET /api/health`, `GET /api/schema/model`. Binds `127.0.0.1`.
- **Path B** (`--mcp`): stateless Streamable HTTP at `/mcp/<secret>` with a six-tool **read-only allowlist**
  (`npdev_validate`, `npdev_core_context`, `npdev_search_examples`, `npdev_search_fix`,
  `npdev_check_support`, `npdev_get_schema`) -- never the stdio tool table. The secret is in
  `<Build>/validation-service/token.txt` (`npdev validator rotate-token`). To reach it from the internet:
  `pwsh -NoProfile -File scripts/ai/Start-ValidatorTunnel.ps1 -NgrokDomain <your free ngrok domain>` (ngrok: one
  fixed address, remembered in `tunnel.json`, so the connector is added once; without a domain it falls back to a
  cloudflared quick tunnel whose address changes every run; the script never downloads a tunnel program and only
  stops processes it started).
- Safety: no body or token is ever logged (`<Build>/validation-service/logs/access.log`), the machine path
  never leaves the process, the validation journal is off unless `--capture`, 2 MB body cap, at most two
  validations at once, Origin check and a per-client rate limit on `/mcp/`.
- Tests: `python -m unittest NPDevMcp.tests.test_validation_service -v` (the validator is injected; one test
  uses the real jar). This module prints nothing on import -- `server.py` must never import it at top level.

## Notes / follow-ups

- First `npdev_validate` call compiles the DSL module (one-time). Subsequent calls reuse the
  Gradle build; latency is dominated by Gradle startup. A pre-built distribution / long-lived
  validator daemon is the planned fast-path optimization.
- Hardening follow-up: route mutating tools (`npdev_generate`) through the existing controlled
  command policy (`scripts/security/Invoke-ControlledCommand.ps1`).
