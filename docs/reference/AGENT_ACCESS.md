# Agent Access — talk to your app from Telegram, WhatsApp, or any MCP agent

Agent Access turns a generated app into something an AI can use: Claude Code / Claude Desktop / any
MCP client can call its data as tools, and (optionally) a Telegram or WhatsApp bot lets your users
chat with the app in plain language. **The agent never gets more power than the user.** Every tool
call goes through the app's own REST API as that user (or a linked user), so the existing role
permissions and row-level `access` rules decide what actually happens — Agent Access only decides
what is *offered* to the agent.

## 1. Turning it on: the `agentAccess` model block

```json
"agentAccess": {
  "assistant": {
    "name": "Pizza Bot",
    "instructions": "Answer in Portuguese. Staff manage stock; customers order pizzas.",
    "language": "pt-BR"
  },
  "channels": {
    "mcp": { "enabled": true },
    "telegram": { "enabled": true }
  },
  "expose": [
    {
      "concept": "StockEntry",
      "operations": ["list", "get", "update"],
      "roles": ["Staff"],
      "description": "Pizza ingredients and how much of each is in stock.",
      "confirmWrites": true
    },
    {
      "flow": "PlaceOrder",
      "description": "Place a new pizza order for the current customer."
    }
  ]
}
```

- `assistant` — only used by chat channels (Telegram/WhatsApp); an MCP client brings its own model.
- `channels` — `mcp` / `telegram` / `whatsapp`, each `{ "enabled": true|false }`. Off by default.
  `telegram`/`whatsapp` additionally need `auth.mode: jwt` and the identity pack composed (account
  linking stores the Telegram/WhatsApp id in `identity::ExternalIdentity`) — the generator refuses to
  build otherwise, naming exactly what's missing. MCP works in every auth mode.
- `expose` — one entry per concept or flow. A concept entry defaults to `operations: ["list", "get"]`
  (read-only) and every non-sensitive field; narrow either with `operations`/`fields`. `roles` filters
  who is even OFFERED the tool (absent = every linked user) — the app's permissions still enforce every
  call regardless. `confirmWrites` (default `true`) makes chat channels pause for a human Confirm
  before a create/update/delete/flow run actually executes.

## 2. Filling in secrets

The generator writes `secrets/agent-access.env.example` into every app. Copy the lines you need into
`secrets/agent-proxy.env` (the one file the launchers already load):

- An AI vendor key for the built-in chat assistant (`NPDEV_EXTERNALAI_GEMINI_API_KEY` or similar) —
  only needed for Telegram/WhatsApp, never for MCP.
- A Telegram bot token + username (`NPDEV_TELEGRAM_BOT_TOKEN`, `NPDEV_TELEGRAM_BOT_USERNAME`) — create
  a bot by messaging **@BotFather** on Telegram, `/newbot`, follow the prompts.
- `NPDEV_AGENT_MCP_TOKEN_DAYS` (default 30) — how long a "Create MCP token" button's token lasts.

Restart the app after editing `agent-proxy.env`.

## 3. How a user connects

Every app with `agentAccess` gets a **`/agent-link.html`** page (jwt-mode apps only — it needs a
signed-in session). From there a user can:

- **Connect Telegram**: press the button, get a one-time code (10-minute TTL), open the bot via the
  deep link it shows, send `/start <code>`. The bot replies "Connected! You are `<username>`."
- **Connect WhatsApp**: same code, sent as a `link <code>` message to the configured number.
- **Create an MCP token**: shown once, with a ready `claude mcp add --transport http ...` command to
  paste straight into Claude Code.

## 4. Connecting an MCP agent

```
claude mcp add --transport http my-app http://localhost:8080/api/mcp --header "Authorization: Bearer <token>"
```

Or point the **MCP Inspector** (`npx @modelcontextprotocol/inspector`) or Claude Desktop (via
`mcp-remote`) at the same `/api/mcp` URL with the same bearer token. `tools/list` returns exactly the
tools the token's own roles are offered; `tools/call` executes through the app's real API.

## 5. What the assistant can never do

- More than the authenticated/linked user could do by hand in the web UI — the generated controller's
  permission checks, row-level `access` rules and audit trail run on every call, unchanged.
- See a `sensitive: true` field, ever — it is never offered, even if listed in `fields`.
- Execute a confirm-gated write without a human pressing Confirm in the chat.
- Read another user's data by asking differently — Telegram/WhatsApp only ever acts as the specific
  user linked to that chat.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| App refuses to generate, naming `agentAccess.channels.telegram` | `auth.mode` is not `jwt`, or the identity pack is not composed. Both are required for Telegram/WhatsApp. |
| Telegram bot never answers | Check the boot log for `"Telegram channel NOT started"` — token missing/wrong, or `getMe` failed. |
| Every chat answer is an error | No AI vendor key configured (`secrets/agent-proxy.env`) — `GET /api/agent/status` (SUPERUSER) reports `ai.keyPresent: false`. |
| `409 Conflict` in the boot log | Another copy of this app (or a Telegram webhook) is polling with the same bot token. |
| `401` and the Telegram channel stops | The bot token was rejected — check it was pasted correctly, no extra whitespace. |
| MCP client gets 404 on `/api/mcp` | `agentAccess.channels.mcp.enabled` is `false` or the model declares no `agentAccess` block at all. |

## See also

- [`docs/UI_CONTRACT.md`](../UI_CONTRACT.md) for how `agent-link.html` fits the generated shell.
- `helpers/agent/wire-formats.md` (platform source) for the exact wire shapes MCP/Telegram/WhatsApp use.
