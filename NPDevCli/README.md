# NPDev CLI

`npdev` is the portable root command for core NPDev operations. It uses `NPDEV_ROOT` when set and otherwise resolves the repository root from the checked-out command location.

```bash
./npdev --version
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json
./npdev normalize ai-model NPDevSamples/ai-scenarios/base-ai-loop/ai-model.json
./npdev generate app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json --config NPDevContract/dsl/resources/Models/canonical-demo/config.json --output <a directory outside the repo, e.g. ../npdev-generated>
./npdev report bootstrap
```

## AI provider keys

`npdev providers` is NPDev's own store for AI-provider API keys (Gemini, NVIDIA, Groq, Cerebras, OpenRouter `:free`, Mistral, local Ollama; Anthropic/OpenAI are catalogued as paid). Keys live in the current user's OS store -- on Windows encrypted with DPAPI inside `%APPDATA%\NPDev\providers.vault.json`, on Linux in the desktop keyring (GNOME Keyring / KWallet, via `secret-tool` from `libsecret-tools`), with that JSON file under `~/.config/NPDev/` holding only the index -- never in a repo, Build root or log, and never as plain text: with no store, only environment variables work. `npdev providers list --json` is the key-free listing the Manager reads. The catalogue is `NPDevCli/ai-providers.json` (no keys). A key is typed once and serves both the authoring benchmark and every generated app:

```bash
./npdev providers list
./npdev providers add groq                 # hidden prompt; --key-stdin to pipe
./npdev providers import-env               # adopt existing NPDEV_EXTERNALAI_*_API_KEY variables
./npdev providers test nvidia --chat --model openai/gpt-oss-20b
./npdev providers export-env --app <FinalApp dir> --provider nvidia   # writes secrets/agent-proxy.env
python scripts/ai/bench/run_bench.py run --provider nvidia,gemini      # failover in that order
```

The environment variable wins over the vault. Generated apps can use `appVendor` providers only (gemini, nvidia, anthropic, openai) until their `external-ai-http` adapter learns the others.

## AI providers, models, profiles and bindings

`npdev ai` edits the AI configuration every NPDev feature reads, in four layers: a **provider** says how to reach a server (`protocol`, `baseUrl`, auth style), a **model** carries its facts (effort levels it accepts, price per million tokens), a **profile** is a ready-to-use provider + model + effort, and a **binding** says which profiles a feature (`prompter`, `scrap-assistant` -- which is `npdev ai generate-routine` --, `bench`, `review`, `tester`, `app:<name>`) uses, in failover order. NPDev ships `NPDevCli/ai-providers.json`; your own additions and overrides go to `~/.config/NPDev/ai.json` (`%APPDATA%\NPDev\ai.json` on Windows, `NPDEV_AI_CONFIG` overrides the path), which wins field by field and never holds a key. Both files follow `schemas/ai/ai-config.schema.json`; every edit is checked against the merged view and refused if it would leave a profile pointing at nothing.

```bash
./npdev ai providers                        # source: shipped / mine / overridden, and where each key comes from
./npdev ai providers add lan-llm --name "LLM on the LAN" --kind local --protocol openai-chat \
      --auth-style none --base-url http://192.168.1.20:1234/v1
./npdev ai models add lan-llm qwen3-coder-30b --effort-levels ""
./npdev ai profiles add desk --provider lan-llm --model qwen3-coder-30b
./npdev ai bind prompter desk,deep          # the Prompter tries desk, then deep
./npdev ai providers set gemini --base-url https://proxy.corp.example/gemini/v1
./npdev ai reset provider gemini            # back to the shipped value
./npdev ai validate
./npdev ai detect --add                     # local Ollama / LM Studio / llama.cpp / vLLM / Jan -> keyless providers + offline profile
./npdev ai detect --host 192.168.1.20       # a server on another machine, only when named
./npdev ai models refresh groq              # new models recorded (not enabled), vanished ones marked retired
./npdev ai models enable groq qwen3-32b
```

Effort is one word (`none`, `low`, `medium`, `high`, `xhigh`, `max`) mapped per protocol (`output_config.effort`, `reasoning_effort`, Gemini's thinking level); a level the model does not declare is never sent.

`npdev ai complete` is the one client every developer feature calls (the benchmark already does): it speaks the three catalogue protocols, fails over along a profile's `fallback` chain, retries 429/5xx, refuses to send without a key, and returns text plus token usage -- never the key. Every call appends `{when, feature, provider, model, inTok, outTok, estUsd, ok}` (no prompt, no key) to `ai-usage.jsonl` next to `ai.json` (`NPDEV_AI_USAGE_LOG` overrides). Paid calls are off until opted in per feature; a monthly cap also refuses models of unknown price, since it could not be enforced on them.

```bash
./npdev ai complete --feature prompter --prompt-file q.txt --json   # binding -> profiles -> fallbacks
./npdev ai complete --profile deep --effort low --prompt-file q.txt
./npdev ai test fast                       # one-word prompt through a profile
./npdev ai usage                           # this month: calls, tokens, estimated USD
./npdev ai spend allow-paid review         # let one feature make paid calls; --off to undo
./npdev ai spend cap 20                    # monthly USD cap ('off' removes it)
./npdev providers add gemini --account work   # a second key; profiles pick it with "account": "work"
```
