# Config Contract

The config contract describes how one generation run should happen.

It contains scenario metadata, generator switches, bootstrap source, artifact locations, final execution target, database settings, runtime launch settings, and optional trial defaults.

The config is development-time input. Generated apps may receive derived runtime manifests, but they should not depend on the Editor UI.

Canonical schema path: schemas/authoring/config.schema.json.
