# Contract Versioning

Use explicit contractVersion fields for handoff, generator, runtime, plugin, and kernel payloads.

Breaking changes should create a new schema version and migration notes. Compatible additive changes may keep the same major version if older consumers can safely ignore the new fields.

Do not silently change model/config meaning without updating the corresponding docs and examples.
