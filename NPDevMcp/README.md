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
| `npdev_list_schemas` / `npdev_get_schema` | Discover and fetch the exact authoring grammar for an object type (or `model` for the full canonical schema). |
| `npdev_get_constrained_schema` | The same grammar reduced to the subset a structured-output API accepts (`scripts/ai/derive_constrained_schemas.py`, derived on demand into `<Build>/npdev-ai/constrained/`). |
| `npdev_core_context` | The stable, prompt-cacheable authoring prefix — contract + curated schemas + golden models (`scripts/ai/build_core_context.py`). `manifest_only` returns just the content hash. |
| `npdev_app_context` | The volatile per-app half: this model's contents + the constrained schema its output must satisfy. Goes **after** the core-context prefix. |
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

## Notes / follow-ups

- First `npdev_validate` call compiles the DSL module (one-time). Subsequent calls reuse the
  Gradle build; latency is dominated by Gradle startup. A pre-built distribution / long-lived
  validator daemon is the planned fast-path optimization.
- Hardening follow-up: route mutating tools (`npdev_generate`) through the existing controlled
  command policy (`scripts/security/Invoke-ControlledCommand.ps1`).
