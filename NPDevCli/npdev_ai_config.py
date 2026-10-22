"""The AI configuration every NPDev feature reads: providers, models, profiles, bindings. Stdlib only.

Two layers, one schema (schemas/ai/ai-config.schema.json):

  * shipped : NPDevCli/ai-providers.json -- what NPDev knows (endpoints, curated models, default profiles).
  * mine    : ~/.config/NPDev/ai.json ($XDG_CONFIG_HOME/NPDev, %APPDATA%\\NPDev on Windows; NPDEV_AI_CONFIG
              overrides the path) -- the user's own providers, models and profiles, and field-by-field
              overrides of shipped entries. Per machine (owner decision D6), never in a repo.

Mine always wins. An overlay entry whose key matches a shipped one changes only the fields it sets, and
`disabled: true` hides an entry without deleting it. Entry keys: providers by `id`, models by
`provider` + `id`, profiles by `name`; an overlay binding replaces the shipped one for its feature.

Completeness and every cross-reference are checked on the MERGED view (an overlay may be partial): a
profile naming an unknown model, a provider naming a protocol no client implements, a default model the
provider does not list. A reference to something merely disabled is not an error -- the profile just
becomes unavailable, and `resolve_feature` skips it with a warning.

Neither file ever holds a key. Keys live in the OS store, owned by npdev_providers.
"""
from __future__ import annotations

import copy
import json
import os
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA_VERSION = "npdev-ai-config.v1"
SHIPPED_PATH = Path(__file__).resolve().parent / "ai-providers.json"
SCHEMA_PATH = Path(__file__).resolve().parents[1] / "schemas" / "ai" / "ai-config.schema.json"
EFFORT_LEVELS = ("none", "low", "medium", "high", "xhigh", "max")
FEATURES = ("prompter", "scrap-assistant", "bench", "review", "tester", "routine")
APP_FEATURE_PREFIX = "app:"
SECTIONS = ("providers", "models", "profiles")
REQUIRED_PROVIDER_FIELDS = ("name", "kind", "protocol", "baseUrl", "authStyle", "needsKey")

# One NPDev effort word, mapped per protocol. A level the model does not declare is never sent.
EFFORT_MAPPING = {
    "anthropic-messages": "output_config.effort",
    "openai-chat": "reasoning_effort",
    "gemini-native": "generationConfig.thinkingConfig.thinkingLevel",
}


class AiConfigError(Exception):
    """The configuration is unreadable or inconsistent. Never carries a key (there is none to carry)."""


def mine_path(environ=None) -> Path:
    environ = os.environ if environ is None else environ
    override = (environ.get("NPDEV_AI_CONFIG") or "").strip()
    if override:
        return Path(override)
    base = environ.get("APPDATA") or environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "NPDev" / "ai.json"


def key_env_var(provider_id: str) -> str:
    return f"NPDEV_EXTERNALAI_{provider_id.upper().replace('-', '_')}_API_KEY"


def entry_key(section: str, entry: dict):
    if section == "models":
        return (entry.get("provider"), entry.get("id"))
    return entry.get("name") if section == "profiles" else entry.get("id")


def _read(path: Path, label: str) -> dict:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        raise AiConfigError(f"{label} {path} is unreadable ({type(exc).__name__})") from None
    except json.JSONDecodeError as exc:
        raise AiConfigError(f"{label} {path} is not valid JSON: {exc}") from None
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA_VERSION:
        raise AiConfigError(f"{label} {path} is not an {SCHEMA_VERSION} file")
    return data


def schema_errors(document: dict, label: str) -> list[str]:
    """Structural check against the shared schema. A CLI installed without the repo's schemas/ skips this
    half; the semantic checks below always run, so nothing inconsistent loads either way."""
    if not SCHEMA_PATH.is_file():
        return []
    import npdev_jsonschema

    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    return [f"{label}: {e.get('path') or '/'}: {e.get('message')}" for e in npdev_jsonschema.validate(schema, document)]


# --------------------------------------------------------------------------- merged view

@dataclass
class AiConfig:
    protocols: dict
    providers: dict
    models: dict
    profiles: dict
    bindings: dict
    origin: dict = field(default_factory=dict)      # (section, key) -> {"source", "overrides"}
    spend: dict = field(default_factory=dict)       # {"allowPaid": {feature: bool}, "monthlyCapUsd": n|None}
    mine_file: Path | None = None

    # ---- lookups (active = not disabled)

    def provider(self, provider_id: str) -> dict:
        entry = self.providers.get(provider_id)
        if entry is None or entry.get("disabled"):
            known = ", ".join(sorted(p for p, e in self.providers.items() if not e.get("disabled")))
            state = "disabled" if entry is not None else "unknown"
            raise AiConfigError(f"{state} provider {provider_id!r}; known: {known}")
        return entry

    def active_providers(self) -> list[dict]:
        return [e for e in self.providers.values() if not e.get("disabled")]

    def models_of(self, provider_id: str, include_inactive: bool = False) -> list[dict]:
        return [m for (p, _), m in self.models.items()
                if p == provider_id and (include_inactive or model_active(m))]

    def model(self, provider_id: str, model_id: str) -> dict | None:
        return self.models.get((provider_id, model_id))

    def profile(self, name: str) -> dict:
        entry = self.profiles.get(name)
        if entry is None:
            raise AiConfigError(f"unknown profile {name!r}; known: {', '.join(sorted(self.profiles))}")
        return entry

    def profile_problem(self, name: str) -> str | None:
        """Why this profile cannot be used right now, or None."""
        entry = self.profile(name)
        if entry.get("disabled"):
            return f"profile {name!r} is disabled"
        provider = self.providers.get(entry["provider"])
        if provider is None or provider.get("disabled"):
            return f"profile {name!r}: provider {entry['provider']!r} is disabled"
        model = self.model(entry["provider"], entry["model"])
        if model is not None and not model_active(model):
            return f"profile {name!r}: model {entry['model']!r} is disabled or retired"
        return None

    def binding(self, feature: str) -> list[str]:
        check_feature(feature)
        return list(self.bindings.get(feature) or [])

    def price(self, provider_id: str, model_id: str) -> tuple[float | None, float | None]:
        """USD per million tokens (in, out). Unknown model or field -> None."""
        model = self.model(provider_id, model_id) or {}
        price = model.get("price") or {}
        return price.get("in"), price.get("out")

    def is_paid(self, provider_id: str, model_id: str) -> bool:
        provider = self.provider(provider_id)
        suffix = provider.get("freeModelSuffix")
        return provider["kind"] == "paid" or bool(suffix and not model_id.endswith(suffix))


def model_active(model: dict) -> bool:
    return not model.get("disabled") and model.get("enabled", True) and model.get("status", "available") != "retired"


def check_feature(feature: str) -> None:
    if feature in FEATURES or (feature.startswith(APP_FEATURE_PREFIX) and len(feature) > len(APP_FEATURE_PREFIX)):
        return
    raise AiConfigError(f"unknown feature {feature!r}; features: {', '.join(FEATURES)}, or app:<name>")


def merge(shipped: dict, mine: dict | None = None, mine_file: Path | None = None) -> AiConfig:
    if mine and mine.get("protocols"):
        raise AiConfigError("protocols are shipped only: a protocol is client code, not configuration")
    protocols = {p["id"]: p for p in shipped.get("protocols") or []}
    merged: dict = {}
    origin: dict = {}
    for section in SECTIONS:
        entries: dict = {}
        for layer, document in (("shipped", shipped), ("mine", mine or {})):
            seen = set()
            for raw in document.get(section) or []:
                key = entry_key(section, raw)
                if key in seen:
                    raise AiConfigError(f"{layer}: duplicate {section[:-1]} {key!r}")
                seen.add(key)
                if layer == "shipped" or key not in entries:
                    entries[key] = copy.deepcopy(raw)
                    origin[(section, key)] = {"source": layer, "overrides": {}}
                    continue
                base = entries[key]
                overrides = origin[(section, key)]["overrides"]
                for name, value in raw.items():
                    if base.get(name) != value:
                        overrides.setdefault(name, {"shipped": copy.deepcopy(base.get(name))})["mine"] = value
                    base[name] = copy.deepcopy(value)
                if overrides:
                    origin[(section, key)]["source"] = "overridden"
        merged[section] = entries
    bindings = {k: list(v) for k, v in (shipped.get("bindings") or {}).items()}
    for feature in bindings:
        origin[("bindings", feature)] = {"source": "shipped", "overrides": {}}
    for feature, names in ((mine or {}).get("bindings") or {}).items():
        before = bindings.get(feature)
        bindings[feature] = list(names)
        source = "mine" if before is None else ("overridden" if before != list(names) else "shipped")
        origin[("bindings", feature)] = {"source": source,
                                         "overrides": {} if source != "overridden" else
                                         {"profiles": {"shipped": before, "mine": list(names)}}}
    spend = copy.deepcopy(shipped.get("spend") or {})
    mine_spend = (mine or {}).get("spend") or {}
    spend.setdefault("allowPaid", {}).update(mine_spend.get("allowPaid") or {})
    if "monthlyCapUsd" in mine_spend:
        spend["monthlyCapUsd"] = mine_spend["monthlyCapUsd"]
    return AiConfig(protocols, merged["providers"], merged["models"], merged["profiles"], bindings,
                    origin=origin, spend=spend, mine_file=mine_file)


def semantic_errors(config: AiConfig) -> list[str]:
    errors: list[str] = []
    supported = sorted(config.protocols)
    for pid, entry in config.providers.items():
        missing = [f for f in REQUIRED_PROVIDER_FIELDS if entry.get(f) is None]
        if missing:
            errors.append(f"provider {pid}: missing {', '.join(missing)}")
            continue
        if entry["protocol"] not in config.protocols:
            errors.append(f"provider {pid}: protocol {entry['protocol']!r} is not implemented "
                          f"(supported: {', '.join(supported)})")
        if entry["needsKey"] != (entry["authStyle"] != "none"):
            errors.append(f"provider {pid}: needsKey must be true exactly when authStyle is not 'none'")
        expected = key_env_var(pid) if entry["needsKey"] else None
        if entry.get("keyEnvVar") != expected:
            errors.append(f"provider {pid}: keyEnvVar must be {expected!r}")
        if entry.get("modelInPath") and "{model}" not in entry["baseUrl"]:
            errors.append(f"provider {pid}: modelInPath needs '{{model}}' in baseUrl")
        default = entry.get("defaultModel")
        if default and (pid, default) not in config.models:
            errors.append(f"provider {pid}: defaultModel {default!r} is not one of its models")
    for (pid, mid), model in config.models.items():
        if pid not in config.providers:
            errors.append(f"model {pid}/{mid}: unknown provider {pid!r}")
    for name, profile in config.profiles.items():
        pid, mid = profile.get("provider"), profile.get("model")
        if not pid or not mid:
            errors.append(f"profile {name}: needs provider and model")
            continue
        if pid not in config.providers:
            errors.append(f"profile {name}: unknown provider {pid!r}")
        elif (pid, mid) not in config.models:
            errors.append(f"profile {name}: unknown model {pid}/{mid} (add it under models, or run "
                          f"`npdev ai models {pid} --refresh`)")
        for fallback in profile.get("fallback") or []:
            if fallback == name:
                errors.append(f"profile {name}: falls back to itself")
            elif fallback not in config.profiles:
                errors.append(f"profile {name}: unknown fallback profile {fallback!r}")
    for name in config.profiles:
        if _fallback_cycle(config, name):
            errors.append(f"profile {name}: its fallback chain loops")
    for feature in (config.spend.get("allowPaid") or {}):
        try:
            check_feature(feature)
        except AiConfigError as exc:
            errors.append(f"spend.allowPaid: {exc}")
    for feature, names in config.bindings.items():
        try:
            check_feature(feature)
        except AiConfigError as exc:
            errors.append(f"binding: {exc}")
        for profile_name in names:
            if profile_name not in config.profiles:
                errors.append(f"binding {feature}: unknown profile {profile_name!r}")
    return errors


def _fallback_cycle(config: AiConfig, start: str) -> bool:
    stack, seen = [(start, ())], set()
    while stack:
        name, path = stack.pop()
        for nxt in config.profiles.get(name, {}).get("fallback") or []:
            if nxt == start and path:
                return True
            if nxt in config.profiles and (name, nxt) not in seen:
                seen.add((name, nxt))
                stack.append((nxt, path + (name,)))
    return False


def load(shipped_path: Path | None = None, mine_file: Path | None = None, use_mine: bool = True,
         environ=None) -> AiConfig:
    """The merged view. Raises AiConfigError listing every problem at once."""
    shipped_path = shipped_path or SHIPPED_PATH
    shipped = _read(shipped_path, "shipped catalogue")
    errors = schema_errors(shipped, f"shipped catalogue {shipped_path.name}")
    mine = None
    path = (mine_file or mine_path(environ)) if use_mine else None
    if path is not None and path.exists():
        mine = _read(path, "your AI config")
        errors += schema_errors(mine, f"your AI config {path}")
    if errors:
        raise AiConfigError("\n".join(errors))
    config = merge(shipped, mine, path)
    errors = semantic_errors(config)
    if errors:
        where = f" (with your overlay {path})" if mine is not None else ""
        raise AiConfigError(f"AI config is inconsistent{where}:\n  " + "\n  ".join(errors))
    return config


# --------------------------------------------------------------------------- effort

def effort_request(protocol: str, model: dict | None, effort: str | None) -> tuple[dict, str | None]:
    """The request-body fragment for `effort` on `protocol`, and a warning when it is not sent.
    A level the model entry does not declare is never sent: the server would reject it."""
    if not effort:
        return {}, None
    if effort not in EFFORT_LEVELS:
        raise AiConfigError(f"unknown effort {effort!r}; levels: {', '.join(EFFORT_LEVELS)}")
    if protocol not in EFFORT_MAPPING:
        raise AiConfigError(f"no effort mapping for protocol {protocol!r}")
    levels = list((model or {}).get("effortLevels") or [])
    if effort not in levels:
        label = (model or {}).get("id", "this model")
        accepts = ", ".join(levels) if levels else "no effort parameter"
        return {}, f"{label} does not declare effort {effort!r} (it accepts: {accepts}); not sent"
    if protocol == "anthropic-messages":
        return {"output_config": {"effort": effort}}, None
    if protocol == "openai-chat":
        return {"reasoning_effort": effort}, None
    return {"generationConfig": {"thinkingConfig": {"thinkingLevel": effort}}}, None


# --------------------------------------------------------------------------- the user's overlay

def read_mine(path: Path | None = None) -> dict:
    path = path or mine_path()
    if not path.exists():
        return {"schemaVersion": SCHEMA_VERSION}
    return _read(path, "your AI config")


def write_mine(document: dict, path: Path | None = None, shipped_path: Path | None = None) -> Path:
    """Validate the overlay against the shipped catalogue, then replace the file atomically. A change
    that would leave the merged view inconsistent is refused and the file is left as it was."""
    path = path or mine_path()
    document = {"schemaVersion": SCHEMA_VERSION, **{k: v for k, v in document.items() if k != "schemaVersion"}}
    for section in SECTIONS:
        if section in document and not document[section]:
            del document[section]
    for section in ("bindings", "spend"):
        if section in document and not document[section]:
            del document[section]
    errors = schema_errors(document, "your AI config")
    if errors:
        raise AiConfigError("\n".join(errors))
    shipped = _read(shipped_path or SHIPPED_PATH, "shipped catalogue")
    errors = semantic_errors(merge(shipped, document, path))
    if errors:
        raise AiConfigError("refused, the result would be inconsistent:\n  " + "\n  ".join(errors))
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)
    return path


def upsert_mine(document: dict, section: str, entry: dict) -> dict:
    """Merge `entry`'s fields into the overlay entry with the same key (created if absent)."""
    key = entry_key(section, entry)
    items = document.setdefault(section, [])
    for existing in items:
        if entry_key(section, existing) == key:
            existing.update(entry)
            return document
    items.append(dict(entry))
    return document


def reset_mine(document: dict, section: str, key, fields: list[str] | None = None) -> bool:
    """Drop the overlay for one entry (or only some of its fields), returning it to the shipped value.
    Returns False when the overlay had nothing for it."""
    if section == "bindings":
        return (document.get("bindings") or {}).pop(key, None) is not None
    items = document.get(section) or []
    for index, existing in enumerate(items):
        if entry_key(section, existing) != key:
            continue
        if not fields:
            del items[index]
            return True
        identity = {"provider", "id"} if section == "models" else {"name" if section == "profiles" else "id"}
        removed = [f for f in fields if f in existing and f not in identity]
        for name in removed:
            del existing[name]
        if set(existing) <= identity:
            del items[index]
        return bool(removed)
    return False
