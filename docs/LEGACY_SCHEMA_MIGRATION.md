# Legacy Schema Migration

Active validation uses `NPDevContract/schemas/model.schema.json` and `NPDevContract/schemas/config.schema.json`.

Older model files that use root `entities` must be migrated to root `concepts` before they are accepted by the official DSL parser.

```sh
./npdev migrate legacy-model --input old.json --output new.json
./npdev validate model new.json
```

The migration command preserves supported fields, rewrites the model schema target to the canonical model schema, and validates the migrated output before writing it.
