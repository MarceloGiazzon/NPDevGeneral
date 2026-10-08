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

### Photos: `photoIntake`

A chat channel can also turn a **photo** into a new record. Declare which concept it becomes:

```json
"photoIntake": {
  "concept": "Cap",
  "imageField": "image",
  "captionField": "label",
  "procedure": "IdentifyCapWithAi",
  "defaults": { "status": "SUBMITTED", "rarity": "COMMON", "submittedBy": "$user.username" }
}
```

When a linked user sends a photo (as a photo or as an image file, on Telegram or WhatsApp):

1. The bot fetches the photo — on Telegram the largest size within `imageField`'s `maxSizeBytes`; on
   WhatsApp the one image, refused up front when its declared size is over the limit — and uploads it into
   that `type: file` field (`POST /api/files/{concept}/{field}`), so the field's content types and
   size limit still apply.
2. If `procedure` is set, it runs over the draft `{captionField: <caption>, imageField: <handle>}`
   through the aggregate rooted at `concept` (`POST /api/runtime/aggregate/{aggregate}/invoke/{procedure}`,
   no persistence). Every returned key that names a field of `concept` — top-level, or one map deep
   such as a step `target` — pre-fills the record. This is where an `externalAi.prompts[]` entry with
   an `image` reads the photo (a `capabilityCall externalAi.generate` step in the procedure). Offline
   (no AI vendor configured) the in-process adapter answers with a schema instance, taking each
   property's first `examples` value (else its `default`) — give pattern-constrained properties an
   `examples` entry, e.g. `"dominantColor": { "pattern": "^#[0-9A-Fa-f]{6}$", "examples": ["#C0C0C0"] }`.
3. The draft is assembled, lowest precedence first: `defaults` (`"$user.username"` = the linked
   user), the procedure's answer, the caption, the uploaded image.
4. The bot shows the draft with **Save / Cancel** buttons. Nothing is created until Save, which runs
   `POST /api/concepts/{route}` as the user — a user without create permission gets "not allowed".
   If a required field is still empty (typically the caption), the bot asks for the photo again with
   a caption instead of offering Save.

If the procedure fails (AI quota, vendor down), the bot still offers the draft built from the caption
and defaults, and says why the procedure did not answer. Validation refuses a `photoIntake` with no
chat channel enabled, an `imageField` that is not a `type: file` field, a `procedure` with no
aggregate rooted at `concept`, and defaults naming unknown or sensitive fields. A model with no
`photoIntake` answers photos with a "send text" hint.

## 2. Filling in secrets

The generator writes `secrets/agent-access.env.example` into every app. Copy the lines you need into
`secrets/agent-proxy.env` (the one file the launchers already load):

- An AI vendor key for the built-in chat assistant (`NPDEV_EXTERNALAI_GEMINI_API_KEY` or similar) —
  only needed for Telegram/WhatsApp, never for MCP.
- A Telegram bot token + username (`NPDEV_TELEGRAM_BOT_TOKEN`, `NPDEV_TELEGRAM_BOT_USERNAME`) — create
  a bot by messaging **@BotFather** on Telegram, `/newbot`, follow the prompts. Optional
  `NPDEV_TELEGRAM_API_BASE` (e.g. `http://localhost:8081/bot`) points the bot at a self-hosted Bot API
  server instead of `https://api.telegram.org/bot`.
- For WhatsApp, four secrets plus a display number: `NPDEV_WHATSAPP_PHONE_NUMBER_ID`,
  `NPDEV_WHATSAPP_ACCESS_TOKEN`, `NPDEV_WHATSAPP_APP_SECRET`, `NPDEV_WHATSAPP_VERIFY_TOKEN` (any long
  random text you choose), and `NPDEV_WHATSAPP_PHONE_DISPLAY` (shown on the Connect page). The channel
  starts only when all four secrets are set; the boot log names any that are missing.
- `NPDEV_AGENT_MCP_TOKEN_DAYS` (default 30) — how long a "Create MCP token" button's token lasts.

Restart the app after editing `agent-proxy.env`.

### Setting up WhatsApp

Telegram polls, so it works from a laptop. WhatsApp is the other way round: Meta delivers each
message to the app, so the app needs a **public HTTPS address**. In development that's a tunnel —
`cloudflared tunnel --url http://127.0.0.1:<app port>`, or `npdev host share --app <app dir>`.

1. In the Meta developer console, create an app with the **WhatsApp** product. Copy the
   **phone number id**, a **permanent access token** (system user) and the app's **App secret**
   into the variables above.
2. Under WhatsApp → Configuration → Webhook, set the callback URL to
   `<public address>/api/hooks/agent/whatsapp` and the verify token to your
   `NPDEV_WHATSAPP_VERIFY_TOKEN`. Meta calls the URL once to check it; the app answers only when the
   token matches.
3. Subscribe the webhook to the **messages** field.

Every delivery must carry Meta's `X-Hub-Signature-256` signature over the raw body, made with the
App secret; anything else is refused with `401` before it is read. `GET /api/agent/status` shows
`whatsapp.lastInboundAt` — once that has a time, Meta is reaching the app.

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
| Meta says the callback URL "couldn't be validated" | The app is not reachable at that address, `NPDEV_WHATSAPP_VERIFY_TOKEN` differs from what you typed in Meta, or the channel is not running (`/api/hooks/agent/whatsapp` answers `404` until all four `NPDEV_WHATSAPP_*` secrets are set). |
| WhatsApp messages never get an answer, `lastInboundAt` stays empty | Meta is not delivering: the webhook is not subscribed to **messages**, or the tunnel address changed (a new tunnel means a new callback URL). |
| `lastInboundAt` updates but nothing is answered | Deliveries are refused with `401` (look for "signature verification failed" in the log — wrong `NPDEV_WHATSAPP_APP_SECRET`), or sending fails (`lastSendOk: false` — check `NPDEV_WHATSAPP_ACCESS_TOKEN`). |
| MCP client gets 404 on `/api/mcp` | `agentAccess.channels.mcp.enabled` is `false` or the model declares no `agentAccess` block at all. |

## See also

- [`docs/UI_CONTRACT.md`](../UI_CONTRACT.md) for how `agent-link.html` fits the generated shell.
- `helpers/agent/wire-formats.md` (platform source) for the exact wire shapes MCP/Telegram/WhatsApp use.
