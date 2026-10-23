# AI providers: how NPDev knows, reaches and pays for an AI model

Every NPDev feature that calls an AI model uses one configuration, one key store and one client:

| Feature | Uses |
|---|---|
| the Manager's Prompter, Scrap assistant | `npdev ai complete` / `npdev ai generate-routine` |
| the authoring benchmark (`scripts/ai/bench`), the external review, `npdev tester` | the same client |
| a generated app (`externalAi` prompts, the Telegram/WhatsApp assistant) | the `external-ai-http` adapter, built from the same catalogue |

No feature ships a key, and nothing sends a prompt until you configure a provider that has a key, or a
local LLM that needs none.

## The configuration: providers, models, profiles, bindings

Two files, one schema (`schemas/ai/ai-config.schema.json`, `npdev-ai-config.v1`):

- **Shipped catalogue:** `NPDevCli/ai-providers.json`, which lists what NPDev knows. It holds providers
  (endpoint, protocol, auth style, key variable), curated models with prices in USD per million tokens,
  the default profiles and which feature uses which profile.
- **Your overlay:** `~/.config/NPDev/ai.json`. On Linux this honours `$XDG_CONFIG_HOME/NPDev/`; on
  Windows it is `%APPDATA%\NPDev\ai.json`. `NPDEV_AI_CONFIG` overrides the path. It holds your own
  providers, models and profiles, plus field-by-field changes to shipped entries. Your entry always
  wins, and `disabled: true` hides an entry without deleting it. The overlay lives on your machine and
  never goes in a repository.

Neither file ever holds a key.

| Term | Meaning |
|---|---|
| provider | an endpoint plus one of three protocols: `openai-chat`, `anthropic-messages`, `gemini-native` |
| model | a provider's model, with its price, context size and effort levels |
| profile | a provider, a model, an effort and a token limit, optionally followed by fallback profiles tried in order |
| binding | the profiles a feature uses (`prompter`, `scrap-assistant`, `bench`, `review`, `tester`, `app:<name>`) |

Edit it with `npdev ai` (`providers`, `models`, `profiles`, `bind`, `reset`, `validate`), or in the
Manager's **AI Providers** tab, which runs the same commands. `npdev ai detect` finds local LLM servers
(Ollama, LM Studio, llama.cpp, vLLM, Jan), and `npdev ai detect --add` records them as keyless local
providers. `npdev ai models refresh <provider>` reads a provider's own model list.

Adding an OpenAI-compatible provider, such as a self-hosted server, needs no code: add it to your
overlay. A provider speaking a fourth protocol needs a client in `npdev_ai_client.py` **and** in
`ExternalAiCatalogue.PROTOCOLS`. Twin-pair rule `ai-protocols-three-place` fails the gate when one of
those three places lacks a protocol id that another one has.

## Keys

`npdev providers` owns the keys. It keeps them in the OS store: DPAPI on Windows, the Secret Service
(desktop keyring) on Linux. Each key is found in this order:

1. the environment variable named by the provider's `keyEnvVar`, for example
   `NPDEV_EXTERNALAI_GEMINI_API_KEY` (CI or a one-off override);
2. the OS store (`npdev providers add <id>`, or **Add key…** in the Manager).

A second account of the same provider uses `<VAR>_<ACCOUNT>` and the store entry `<id>:<account>`. No
command prints a key back. Listings say only where a key would come from: `env`, `vault`, `missing` or
`none-needed`.

## Spending

- A **paid** provider or model is refused unless the calling feature has opted in, with
  `npdev ai spend allow-paid <feature>` or the **Paid** box in the Manager's Uses view. A direct
  `npdev ai complete` or `test` call can opt in with `--allow-paid` instead.
- Under a **monthly cap** -- **$10 by default**; change it with `npdev ai spend cap <usd>`, or `off` for
  no cap -- a paid call is refused once this month's estimate reaches the cap. A paid model with no
  known price is also refused, because the cap could not be enforced for it.
- Every call is appended to the **usage log** (`ai-usage.jsonl` next to your `ai.json`;
  `NPDEV_AI_USAGE_LOG` overrides the path). The log records the feature, provider, model, tokens,
  estimated USD and time, never a prompt. `npdev ai usage` summarises it.

## Generated apps

- A model opts in with `externalAi`: `egress` plus `vendors`. Every vendor must be a catalogue provider
  marked `appVendor`, or one of the model's own `externalAi.providers` (below). An unknown id is a
  validation error that names the known ones. A prompt on a
  local LLM server (`ollama`) validates with a warning, because a deployed app reaches it only if the
  server runs on the app's own host.
- A prompt (`externalAi.prompts[]`) names either a `vendor` (optionally with a `model`) or a catalogue
  `profile` such as `fast` or `claude`. The generator resolves a profile to its provider, model and
  effort, and that provider must still be listed in `vendors`. A profile's fallback list is not used:
  an app calls one vendor per prompt. `effort` (`low` / `medium` / `high`) overrides the profile's. It
  is sent only to a vendor that has such a setting (Anthropic) and dropped for the others. The audit
  row of each call records the profile and the effort.
- The `external-ai-http` jar bundles the shipped catalogue as `npdev/ai-providers.json`. The app builds
  one vendor profile per app vendor from it and calls the catalogue's default model unless a prompt
  names one. Your overlay is **not** bundled: a generated app sees only shipped providers, plus the
  ones its own model declares.
- **An app's own endpoint** (owner decision D4: the operator vouches for it), such as a company gateway
  or a self-hosted OpenAI-compatible server, is declared in the model, so the app stays reproducible from
  its model alone:

  ```json
  "externalAi": { "egress": "apiEnabled", "vendors": ["acme-gw"], "providers": [
    { "id": "acme-gw", "protocol": "openai-chat", "baseUrl": "https://ai.acme.example/v1",
      "defaultModel": "acme-large", "price": { "in": 0.5, "out": 1.5 } } ] }
  ```

  `protocol` is `openai-chat`, `anthropic-messages` or `gemini-native`. `baseUrl` must be https, or
  plain http only to a loopback host, and must not carry a query, a fragment or credentials. The id may
  not be any shipped catalogue id. The key is always `NPDEV_EXTERNALAI_<ID>_API_KEY` (here
  `NPDEV_EXTERNALAI_ACME_GW_API_KEY`), and `needsKey: false` makes the provider keyless. There is no
  field to name a key variable, so a model cannot aim its endpoint at another provider's key or at any
  other secret. `price` is USD per million tokens for every model of the provider. A provider declared
  in your own `ai.json` with the same id lends its stored key to `run-env` and the Manager's Start.
- **Keys.** The app reads the same `NPDEV_EXTERNALAI_<ID>_API_KEY` variables, with
  `NPDEV_EXTERNALAI_PROVIDER=http` selecting the real adapter. Without it the app answers from an
  offline stand-in. Two ways to get them in:
  - **On your machine, no key file.** The Manager's **Start** button (`npdev monitor ops run-finalapp`)
    passes the keys of the app's own vendors from your key store into the app's process. From a
    terminal: `npdev providers run-env --app <dir> -- pwsh -File <dir>/_ops/Start-App.ps1` (vendors
    default to the app's `externalAi.vendors`, read from its compiled model; `--provider <id>` names
    them instead). Only names are printed or logged, never a key.
  - **Anywhere else (a server, a service, Docker):** `_ops` loads `<app>/secrets/agent-proxy.env`. Write
    its managed block with `npdev providers export-env --app <dir> --provider <id>...`, or with the
    button in the Monitor inspector's **AI providers** section. The file is loaded after the store
    keys, so a line in it wins.

  The inspector section shows, per vendor, where the app's key will come from
  (`npdev ai app --app-dir <dir> --model <model.json>`).
- **Operator properties:**
  - `npdev.externalai.http.<id>.model` and `.apiKeyEnvVar` override the model and the key variable.
  - `npdev.externalai.price.<id>.inputUsdPerMTok` and `.outputUsdPerMTok` override the catalogue
    price. Each call is otherwise priced for the model it used. The price feeds
    `externalAi.limits.monthlyCostCapUsd`.
  - `npdev.externalai.prompt.<prompt>.profile`, `.model` and `.effort` change one prompt per call,
    without regenerating. They apply only when the model declares them in `properties[]` (type
    `string`, with the `settableAt` scopes an operator may use). Set them through `/api/properties`,
    for example per tenant. A profile replaces the vendor, the model and the effort, and an `effort`
    override still wins over it. A `model` override keeps the vendor. An unset, null or blank value
    leaves the compiled prompt alone. The vendor must still be in `externalAi.vendors`. An unknown
    profile, a profile set together with a model, or an effort other than `low` / `medium` / `high`
    denies the call (`EXTERNAL_AI_OVERRIDE_INVALID`) rather than falling back. The audit row records
    the vendor, model and effort actually used, and an `overridden` list.
- **The chat assistant** uses `NPDEV_AGENT_VENDOR` if set. Otherwise it uses the catalogue profile
  named in `agentAccess.assistant.profile`. Failing both, it uses the model's first `externalAi.vendors`
  entry (`gemini` when the model lists none). Its model is `NPDEV_AGENT_MODEL`, else the profile's model
  (only when the profile's vendor is the one in use), else the vendor's catalogue default model.
  `agentAccess.assistant.effort`, or the profile's effort, is sent where the vendor supports it.

An app's own budget (`externalAi.limits`) is enforced inside the app and is separate from the developer
spend guard above.

## Where the code is

| Concern | File |
|---|---|
| catalogue + overlay, merge and validation | `NPDevCli/npdev_ai_config.py` |
| the one client: protocols, failover, retry, spend guard, usage log | `NPDevCli/npdev_ai_client.py` |
| `npdev ai` commands | `NPDevCli/npdev_ai.py` |
| local LLM discovery | `NPDevCli/npdev_ai_discovery.py` |
| key store, `export-env`, `npdev ai app` status | `NPDevCli/npdev_providers.py` |
| generated-app vendor profiles | `NPDevKernel/adapters/external-ai-http/.../ExternalAiCatalogue.java` |
| validation of `externalAi.vendors` | `NPDevContract/dsl/.../validation/ExternalAiValidation.java` |
| the Manager tab | `NPDevManager/ui/providers.js` (calls `ai_command` / `providers_*` in `src/main.rs`) |
