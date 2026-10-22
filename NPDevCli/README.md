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

`npdev providers` is NPDev's own store for AI-provider API keys (Gemini, NVIDIA, Groq, Cerebras, OpenRouter `:free`, Mistral, local Ollama; Anthropic/OpenAI are catalogued as paid). Keys live in `%APPDATA%\NPDev\providers.vault.json`, encrypted per Windows user with DPAPI -- never in a repo, Build root or log. The catalogue is `NPDevCli/ai-providers.json` (no keys). A key is typed once and serves both the authoring benchmark and every generated app:

```bash
./npdev providers list
./npdev providers add groq                 # hidden prompt; --key-stdin to pipe
./npdev providers import-env               # adopt existing NPDEV_EXTERNALAI_*_API_KEY variables
./npdev providers test nvidia --chat --model openai/gpt-oss-20b
./npdev providers export-env --app <FinalApp dir> --provider nvidia   # writes secrets/agent-proxy.env
python scripts/ai/bench/run_bench.py run --provider nvidia,gemini      # failover in that order
```

The environment variable wins over the vault. Generated apps can use `appVendor` providers only (gemini, nvidia, anthropic, openai) until their `external-ai-http` adapter learns the others.
