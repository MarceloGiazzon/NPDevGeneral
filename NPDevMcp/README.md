# NPDev MCP Server

Lets an external AI **drive** NPDev instead of guessing at its strict JSON. A zero-dependency
Model Context Protocol server (JSON-RPC 2.0 over stdio) that wraps the portable CLI
([`NPDevCli/npdev_cli.py`](../NPDevCli/npdev_cli.py)) and the canonical schemas.

## Tools

| Tool | What it does |
| --- | --- |
| `npdev_validate` | Full structural + semantic validation of a `model.json` **without generating** → typed `npdev-validation-report.v2` report (per-diagnostic `path`/`concept`/`field`/`suggestedFix`). **The authoring self-correction loop.** |
| `npdev_inspect_app` | Read-only summary of an existing model (concepts/fields/flows/events/panels/…). Use to avoid duplicating concepts. |
| `npdev_inspect_bonds` | Bond/anchor/onDelete analysis + migration risks. |
| `npdev_list_schemas` / `npdev_get_schema` | Discover and fetch the exact authoring grammar for an object type (or `model` for the full canonical schema). |
| `npdev_search_examples` | Retrieve real, working example snippets (RAG; reads `build/npdev-ai/rag-index.json` — see `scripts/ai/build_rag_index.py`). |
| `npdev_migration_diff` | Classify a schema change safe-additive vs destructive; dry-run migration plan. |
| `npdev_generate` | Run the real generator. **Slow + writes to disk — gate behind confirmation.** |

## The loop it enables

```
author model.json → npdev_validate → typed errors? → fix → re-validate
                  → npdev_generate → npdev app smoke → done
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
