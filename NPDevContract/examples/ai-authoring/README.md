# AI Authoring Bundle

This folder is a machine-readable handoff target for AI tools and CLI workflows. The files here use the same authoring contract as the Editor and Generator, with no UI-only assumptions.

Run the root gate:

```powershell
pwsh -File scripts\quality\run-ai-bundle-gate.ps1
```

The bundle is intentionally domain-neutral. It demonstrates concepts, rule profiles, queries, procedures, and panels without introducing platform-core business vocabulary.
