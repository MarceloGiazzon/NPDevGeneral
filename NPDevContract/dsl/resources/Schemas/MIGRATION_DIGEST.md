# Contracts: DSL schema resource bridge Migration Digest

## Purpose
DSL-local schema fixtures expected by standalone DSL tests and IDE imports.

## Canonical Policy
- `model.schema.json` is the current canonical model schema as of 2026-04-23.
- `config.schema.json` is the current canonical config schema as of 2026-04-23.
- Versioned filename aliases remain readable as historical references, but they are deprecated and should migrate to the canonical filenames below.

## Migration Path
- `model-1.0.0.schema.json` -> `model.schema.json` on 2026-04-23
- `config-1.0.schema.json` -> `config.schema.json` on 2026-04-23

## Copied From
- `NPDevContract\schemas`

## Important Note
This bridge keeps standalone DSL tests and IDE imports stable while the repo converges on one canonical schema path per surface.
