# AI Authoring Example

This example demonstrates the AI-only Beta 0 contract flow.

AI-authored inputs:

- `ai-model.json`
- `ai-config.json`
- `ai-verification-report.json`

Normalizer outputs:

- `normalized/model.json`
- `normalized/config.json`

Compatibility copies:

- `model.json`
- `config.json`

Run from the repository root:

```powershell
pwsh ./scripts/ai/Normalize-AiContract.ps1 `
  -AiModelPath NPDevContract/examples/ai-authoring/ai-model.json `
  -AiConfigPath NPDevContract/examples/ai-authoring/ai-config.json `
  -OutputDirectory NPDevContract/examples/ai-authoring/normalized `
  -ResultPath scripts/reports/out/ai-authoring-normalizer-result.json
```

Beta 0 does not use arbitrary custom procedures, custom panels, or free-form shell execution as authoring examples.
