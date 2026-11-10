# Authoring with AI

NPDev's model is a JSON document with a schema and a semantic validator that rejects an invalid
spec before anything is generated — which makes it a tractable, checkable target for an AI to
author directly. There are two ways to use an AI for this, depending on what the AI client can do.

## With an MCP-capable client (Claude Code, Claude Desktop, ...)

**1. Install.**

```sh
./npdev mcp install --client claude-code       # writes/merges .mcp.json in the current directory
./npdev mcp install --client claude-desktop    # writes/merges your platform's Claude Desktop config
./npdev mcp install --print                    # prints the config block for any other MCP client
```

This resolves the Python interpreter and `NPDevMcp/server.py`'s own absolute path for you — the
two things most likely to be wrong in a hand-written config — and never overwrites an existing
config file: if one is already there, it is parsed, backed up (`<file>.bak-<timestamp>`), and only
the `mcpServers.npdev` key is touched. If parsing the existing file fails, nothing is written; fix
it by hand or use `--print` and paste the block in yourself.

**2. Restart your client.** MCP servers are only discovered at startup.

**3. Confirm the tools are there**, then paste this opening prompt:

> I'm using NPDev, a spec-driven app generator (a JSON model → a real Spring Boot app). You have
> MCP tools available (`npdev_validate`, `npdev_generate`, `npdev_inspect_app`, `npdev_get_schema`,
> and others) — use them instead of guessing at the schema. Call `npdev_core_context` first: it
> returns the authoring contract (the eight questions to ask me before drafting anything, and the
> non-negotiable rules — exactly one `id` field per concept, `renamedFrom` for any rename, start
> with the smallest model that runs), every allowed value, and verified example models. Ask me
> those questions, draft `model.json`, validate it with `npdev_validate` and fix every error
> before generating.

**4. Use it.** Open the generated app's URL, and tell the AI what's wrong or what to add next in
plain language — "members should have a phone number", "two bookings can't share a time slot". It
edits the model, validates, regenerates. You review a running app, not JSON.

## Without MCP (a plain chat, no tool-calling)

Chat-only AI use is real and unserved by the above — not every client can call tools. The loop is
the same in substance, just relayed by hand:

1. Write the authoring context to a file and paste its contents into the chat, then describe
   what you want built:
   ```sh
   ./npdev ai context --output npdev-context.md
   ```
   It is the contract plus a reference of every allowed value and three small verified example
   models (~14 KB), the same prefix NPDev's AI bench measures. `--full` adds the interview and
   tool-loop sections an MCP agent uses; on the bench a one-shot chat passed the same prompts
   without them, at ~25 % fewer tokens.
2. The AI drafts `model.json`. Save it, then run the check yourself:
   ```sh
   ./npdev validate path/to/model.json
   ```
   If it reports shape errors, `./npdev normalize desire-paths path/to/model.json --output
   path/to/model.json` first rewrites the shapes AI authors commonly guess into canonical form and
   lists every fix on stderr -- paste that list back too.
3. Paste the JSON report back to the AI. Each diagnostic carries `path`, `message`, and
   `suggestedFix` — the AI does not need MCP to read structured errors, only your copy-paste.
4. Repeat step 2-3 until `"status": "passed"`, then generate it yourself:
   ```sh
   ./npdev generate app --model path/to/model.json --config path/to/config.json --output <dir>
   ```
   See `docs/YOUR_FIRST_APP.md` for the full build/run steps after that.

**The only difference from the MCP path is who runs the commands.** The contract the AI follows
(the non-negotiables, `renamedFrom`) is identical either way — it lives in one place,
`docs/content/authoring-for-ai.yml` (rendered as `docs/ai/AUTHORING_FOR_AI.md`), served by the
`npdev_core_context` tool in one path and by `npdev ai context` in the other.

## Without MCP, the easy way — npdev ai relay

`npdev ai relay` carries the measured prompt to ANY chat AI in a browser (ChatGPT, Gemini, Claude,
Grok, Kimi, DeepSeek, Google AI Mode, ...) and reads its answer back. No API key and no MCP. The
Manager's **Use a browser AI** screen runs these same commands, so a terminal user and the Manager
send the same text and classify an answer the same way.

```sh
echo "A workshop app with customers, vehicles and work orders" > ask.txt
./npdev ai relay start --intent create --ask-file ask.txt --new-app-name workshop   # prints the prompt: paste it into a NEW chat
./npdev ai relay ingest --code K7Q2 --text-file -                                  # K7Q2 = the code in the prompt; paste the AI's reply, then Ctrl+D (Ctrl+Z, Enter on Windows)
./npdev ai relay apply --code K7Q2 --parent-dir ~/apps                             # create the app (a change names its warnings with --confirm-warning)
./npdev ai relay undo --code K7Q2                                                  # change/fix only: put the previous model back
```

Every answer ends in exactly one outcome and one next step. `READY` comes with a preview in plain
words and is applied with a backup. `NEEDS_FIX`, `TRUNCATED`, `ELIDED`, `BAD_JSON`, `PARTIAL`,
`NO_FILES` and `FILE_LINK_ONLY` come with a short message to paste into the same chat (the errors
one per line). `LIMIT_REACHED` and an AI that repeats the same mistake twice come with a
self-contained prompt for a new chat or another AI. `QUESTIONS`, `STALE_ROUND`, `WRONG_SESSION` and
`PROMPT_ECHO` mean the AI needs the person, or the wrong text was copied.

Nothing is written unless the model passes the validator and, for a change, the authoring gate: a
rename, a removal or a security change the AI did not declare is refused, and each one it did
declare needs the owner's explicit yes (`--confirm-warning <code>`, the codes are in the preview).
A removed kind of record or field also drops a table or a column, and the app's own schema guard then
refuses to start until it gets a separate, itemized acknowledgment of that data (see
`docs/SCHEMA_EVOLUTION.md`, "Acknowledging destructive changes"). The preview says so
(`needsSchemaAcknowledgment`), and the Manager, which cannot give that acknowledgment, shows such an
answer but does not offer to apply it; `relay apply` itself still writes the model, for someone who will
acknowledge the change when they deploy it.
Conversations are kept in the per-user NPDev folder (`ai-relay/<code>/`) and survive restarts:
`relay list`, `relay status --code`, `relay abort --code`; `relay targets` lists the chat AIs it knows, and
`relay stats --csv` prints one row per round (which AI, how many rounds it took, how the answer came
back) -- the rows a calibration run for a chat AI needs, read from the conversations on this computer.
An answer the AI saved as a file works
too: `relay ingest --code K7Q2 --file model.json` (or a `.zip`, which is read in memory and never
extracted).

## Verifying the connection yourself

`npdev mcp install` cannot confirm your GUI client actually loaded the config — that needs a
restart and a human look. What it (and this repo's harness) verify mechanically is that the server
itself is real and answers the MCP handshake: spawn `python NPDevMcp/server.py`, send `initialize`
then `tools/list` over stdio, and get back at least 15 tools. If your client still doesn't see
NPDev's tools after a restart, that split tells you where to look: the server side is proven to
work, so the problem is the client not finding the config file this command wrote.
