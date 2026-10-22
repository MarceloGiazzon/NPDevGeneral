"""`npdev ai providers|models|profiles|bind|bindings|reset|validate` -- read and edit the AI configuration.

Every edit goes to the user's overlay (npdev_ai_config.mine_path()), never to the shipped catalogue, and is
validated against the merged view before the file is replaced: a change that would leave a profile
pointing at nothing is refused. The Manager's AI Providers tab drives these same commands with --json.
Keys are not handled here -- `npdev providers add|remove` owns them.
"""
from __future__ import annotations

import json
import sys

import npdev_ai_config as aicfg
from npdev_ai_config import AiConfigError


def _kv_pairs(values: list[str] | None, flag: str) -> dict:
    out = {}
    for item in values or []:
        name, sep, value = item.partition("=")
        if not sep or not name.strip():
            raise AiConfigError(f"{flag} expects name=value, got {item!r}")
        out[name.strip()] = value.strip()
    return out


def _csv(value: str | None) -> list[str] | None:
    return None if value is None else [v.strip() for v in value.split(",") if v.strip()]


def _model_key(text: str) -> tuple[str, str]:
    provider, sep, model = text.partition("/")
    if not sep or not provider or not model:
        raise AiConfigError(f"a model is named <provider>/<model>, got {text!r}")
    return provider, model


def _origin(config, section: str, key) -> dict:
    found = config.origin.get((section, key)) or {"source": "shipped", "overrides": {}}
    return {"source": found["source"], "overrides": found["overrides"]}


# --------------------------------------------------------------------------- listings

def provider_rows(config, key_sources: dict | None = None) -> list[dict]:
    rows = []
    for pid, entry in sorted(config.providers.items()):
        row = {**entry, **_origin(config, "providers", pid), "disabled": bool(entry.get("disabled")),
               "models": len(config.models_of(pid))}
        if key_sources is not None:
            row["keySource"] = key_sources.get(pid, "missing" if entry.get("needsKey") else "none-needed")
        rows.append(row)
    return rows


def model_rows(config, provider_id: str | None = None, include_inactive: bool = False) -> list[dict]:
    rows = []
    for (pid, mid), entry in sorted(config.models.items()):
        if provider_id and pid != provider_id:
            continue
        if not include_inactive and not aicfg.model_active(entry):
            continue
        rows.append({**entry, **_origin(config, "models", (pid, mid)), "active": aicfg.model_active(entry),
                     "paid": config.is_paid(pid, mid) if pid in config.providers and
                     not config.providers[pid].get("disabled") else None})
    return rows


def profile_rows(config) -> list[dict]:
    return [{**entry, **_origin(config, "profiles", name), "problem": config.profile_problem(name)}
            for name, entry in sorted(config.profiles.items())]


def binding_rows(config) -> list[dict]:
    return [{"feature": feature, "profiles": names, **_origin(config, "bindings", feature)}
            for feature, names in sorted(config.bindings.items())]


def _key_sources() -> dict:
    """provider id -> env | vault | missing | none-needed, read without touching any key."""
    import npdev_providers

    try:
        listing = npdev_providers.list_providers(npdev_providers.Vault())
    except Exception:  # noqa: BLE001 -- a listing must still answer when the store index is unreadable
        return {}
    return {row["id"]: row["keySource"] for row in listing["providers"]}


# --------------------------------------------------------------------------- edits

def edit_provider(document: dict, args) -> dict:
    entry = {"id": args.id}
    for flag, name in (("name", "name"), ("kind", "kind"), ("protocol", "protocol"), ("base_url", "baseUrl"),
                       ("auth_style", "authStyle"), ("default_model", "defaultModel"), ("signup_url", "signupUrl"),
                       ("notes", "notes")):
        value = getattr(args, flag, None)
        if value is not None:
            entry[name] = value
    if getattr(args, "auth_style", None) is not None:
        entry["needsKey"] = args.auth_style != "none"
        entry["keyEnvVar"] = aicfg.key_env_var(args.id) if entry["needsKey"] else None
    if getattr(args, "app_vendor", None) is not None:
        entry["appVendor"] = args.app_vendor
    if getattr(args, "model_in_path", None) is not None:
        entry["modelInPath"] = args.model_in_path
    headers = _kv_pairs(getattr(args, "header", None), "--header")
    if headers:
        entry["extraHeaders"] = headers
    params = _kv_pairs(getattr(args, "query", None), "--query")
    if params:
        entry["queryParams"] = params
    return aicfg.upsert_mine(document, "providers", entry)


def edit_model(document: dict, args) -> dict:
    entry = {"provider": args.provider, "id": args.model}
    for flag, name in (("name", "name"), ("context_tokens", "contextTokens"), ("max_output_tokens", "maxOutputTokens"),
                       ("notes", "notes")):
        value = getattr(args, flag, None)
        if value is not None:
            entry[name] = value
    levels = _csv(getattr(args, "effort_levels", None))
    if levels is not None:
        entry["effortLevels"] = levels
    if getattr(args, "price_in", None) is not None or getattr(args, "price_out", None) is not None:
        entry["price"] = {"in": args.price_in, "out": args.price_out}
    entry.setdefault("enabled", True)
    return aicfg.upsert_mine(document, "models", entry)


def edit_profile(document: dict, args) -> dict:
    entry = {"name": args.name}
    for flag, name in (("description", "description"), ("provider", "provider"), ("model", "model"),
                       ("account", "account"), ("effort", "effort"), ("max_tokens", "maxTokens"),
                       ("temperature", "temperature")):
        value = getattr(args, flag, None)
        if value is not None:
            entry[name] = value
    fallback = _csv(getattr(args, "fallback", None))
    if fallback is not None:
        entry["fallback"] = fallback
    return aicfg.upsert_mine(document, "profiles", entry)


def _require_provider(config, provider_id: str) -> None:
    """Known at all (a disabled provider can still be changed or re-enabled)."""
    if provider_id not in config.providers:
        config.provider(provider_id)  # raises with the list of known ids


def set_disabled(document: dict, config, section: str, key, disabled: bool) -> dict:
    if section == "providers":
        _require_provider(config, key)
        entry = {"id": key}
    elif section == "models":
        if key not in config.models:
            raise AiConfigError(f"unknown model {key[0]}/{key[1]}")
        entry = {"provider": key[0], "id": key[1]}
    else:
        config.profile(key)
        entry = {"name": key}
    entry["disabled"] = disabled
    return aicfg.upsert_mine(document, section, entry)


def remove_profile(document: dict, config, name: str) -> dict:
    """A profile of your own is deleted; a shipped one can only be disabled (it comes back with every release)."""
    config.profile(name)
    if config.origin.get(("profiles", name), {}).get("source") in ("shipped", "overridden"):
        raise AiConfigError(f"{name!r} is a shipped profile: use `npdev ai profiles disable {name}` instead")
    aicfg.reset_mine(document, "profiles", name)
    return document


def bind(document: dict, config, feature: str, profiles: list[str]) -> dict:
    aicfg.check_feature(feature)
    if not profiles:
        raise AiConfigError("bind needs at least one profile")
    for name in profiles:
        config.profile(name)
    document.setdefault("bindings", {})[feature] = profiles
    return document


# --------------------------------------------------------------------------- CLI

def add_arguments(ai_sub) -> None:
    def json_flag(p):
        p.add_argument("--json", action="store_true", help="Answer with JSON (the Manager reads this).")

    providers = ai_sub.add_parser("providers", help="List, add or change AI providers (endpoints).")
    json_flag(providers)
    psub = providers.add_subparsers(dest="ai_action")
    for verb in ("add", "set"):
        p = psub.add_parser(verb, help=("Add your own provider (custom endpoint)." if verb == "add"
                                        else "Change fields of a provider (a shipped one is overridden)."))
        p.add_argument("id")
        p.add_argument("--name")
        p.add_argument("--kind", choices=["free", "local", "paid"])
        p.add_argument("--protocol")
        p.add_argument("--base-url")
        p.add_argument("--auth-style", choices=["bearer", "x-api-key", "x-goog-api-key", "api-key", "none"])
        p.add_argument("--default-model")
        p.add_argument("--signup-url")
        p.add_argument("--notes")
        p.add_argument("--header", action="append", metavar="NAME=VALUE", help="Fixed non-secret header (repeatable).")
        p.add_argument("--query", action="append", metavar="NAME=VALUE", help="Fixed non-secret query parameter.")
        p.add_argument("--app-vendor", action=argparse_bool(), default=None)
        p.add_argument("--model-in-path", action=argparse_bool(), default=None)
        json_flag(p)
    for verb in ("disable", "enable"):
        p = psub.add_parser(verb, help=f"{verb.capitalize()} a provider.")
        p.add_argument("id")
        json_flag(p)

    models = ai_sub.add_parser("models", help="List or change the models of the providers.")
    models.add_argument("--provider", dest="provider_filter", metavar="ID", help="Only this provider's models.")
    models.add_argument("--all", action="store_true", help="Include disabled and retired models.")
    json_flag(models)
    msub = models.add_subparsers(dest="ai_action")
    for verb in ("add", "set"):
        p = msub.add_parser(verb, help="Add a model to a provider, or change its facts.")
        p.add_argument("provider")
        p.add_argument("model")
        p.add_argument("--name")
        p.add_argument("--context-tokens", type=int)
        p.add_argument("--max-output-tokens", type=int)
        p.add_argument("--effort-levels", help="Comma-separated levels it accepts; empty for none.")
        p.add_argument("--price-in", type=float, help="USD per million input tokens.")
        p.add_argument("--price-out", type=float, help="USD per million output tokens.")
        p.add_argument("--notes")
        json_flag(p)
    for verb in ("disable", "enable"):
        p = msub.add_parser(verb, help=f"{verb.capitalize()} a model.")
        p.add_argument("provider")
        p.add_argument("model")
        json_flag(p)

    profiles = ai_sub.add_parser("profiles", help="List or change profiles (provider + model + effort).")
    json_flag(profiles)
    prsub = profiles.add_subparsers(dest="ai_action")
    for verb in ("add", "set"):
        p = prsub.add_parser(verb, help="Add a profile, or change one (a shipped one is overridden).")
        p.add_argument("name")
        p.add_argument("--description")
        p.add_argument("--provider")
        p.add_argument("--model")
        p.add_argument("--account", help="Which stored key of the provider (<provider>:<account>).")
        p.add_argument("--effort", choices=list(aicfg.EFFORT_LEVELS))
        p.add_argument("--max-tokens", type=int)
        p.add_argument("--temperature", type=float)
        p.add_argument("--fallback", help="Comma-separated profile names, tried in order.")
        json_flag(p)
    for verb in ("disable", "enable", "remove"):
        p = prsub.add_parser(verb, help=f"{verb.capitalize()} a profile.")
        p.add_argument("name")
        json_flag(p)

    bindings = ai_sub.add_parser("bindings", help="Which profiles each feature uses.")
    json_flag(bindings)
    bind_p = ai_sub.add_parser("bind", help="Set the profiles a feature uses, in failover order.")
    bind_p.add_argument("feature", help=f"{', '.join(aicfg.FEATURES)}, or app:<name>")
    bind_p.add_argument("profiles", help="Comma-separated profile names.")
    json_flag(bind_p)

    reset = ai_sub.add_parser("reset", help="Return an entry (or some fields) to the shipped value.")
    reset.add_argument("section", choices=["provider", "model", "profile", "binding"])
    reset.add_argument("key", help="provider id, <provider>/<model>, profile name, or feature")
    reset.add_argument("--field", action="append", help="Only this field (repeatable).")
    json_flag(reset)

    validate = ai_sub.add_parser("validate", help="Check the shipped catalogue and your overlay together.")
    json_flag(validate)

    def target_flags(p):
        p.add_argument("--profile")
        p.add_argument("--feature", help=f"Resolve through the feature's binding ({', '.join(aicfg.FEATURES)}, app:<name>).")
        p.add_argument("--provider")
        p.add_argument("--model", help="With --provider (default: the provider's defaultModel).")
        p.add_argument("--effort", choices=list(aicfg.EFFORT_LEVELS), help="Override the profile's effort for this run.")
        p.add_argument("--max-tokens", type=int)
        p.add_argument("--account", help="Use this stored key of the provider (<provider>:<account>).")
        p.add_argument("--allow-paid", action="store_true", help="Permit a paid call for this direct run.")
        p.add_argument("--timeout", type=float, default=120.0)
        json_flag(p)

    complete = ai_sub.add_parser("complete", help="Send a prompt through ONE client (failover, retry, spend "
                                                  "guard, usage log). The key never leaves this process.")
    target_flags(complete)
    complete.add_argument("--prompt-file", required=True, help="The prompt ('-' = stdin). A file, not argv: "
                                                               "a prompt on a command line shows in process listings.")
    complete.add_argument("--system-file")
    complete.add_argument("--json-schema", metavar="FILE", help="Ask for one JSON value valid against this schema.")

    test = ai_sub.add_parser("test", help="Send a one-word prompt through a profile (and its fallbacks).")
    test.add_argument("profile")
    test.add_argument("--allow-paid", action="store_true")
    test.add_argument("--timeout", type=float, default=60.0)
    json_flag(test)

    usage = ai_sub.add_parser("usage", help="What the AI calls cost: calls, tokens and estimated USD, never prompts.")
    usage.add_argument("--since", help="ISO date (default: the start of this month, UTC).")
    json_flag(usage)

    spend = ai_sub.add_parser("spend", help="The spend guard: which features may make paid calls, monthly cap.")
    json_flag(spend)
    ssub = spend.add_subparsers(dest="ai_action")
    allow = ssub.add_parser("allow-paid", help="Let a feature make paid calls (--off to take it back).")
    allow.add_argument("feature")
    allow.add_argument("--off", action="store_true")
    json_flag(allow)
    cap = ssub.add_parser("cap", help="Monthly USD cap across all paid calls ('off' removes it).")
    cap.add_argument("usd")
    json_flag(cap)


def argparse_bool():
    import argparse

    return argparse.BooleanOptionalAction


COMMANDS = ("providers", "models", "profiles", "bindings", "bind", "reset", "validate", "complete", "test",
            "usage", "spend")


def run(args) -> int:
    command, action = args.ai_command, getattr(args, "ai_action", None)
    try:
        config = aicfg.load()
        if command == "validate":
            return _answer(args, {"ok": True, "mine": str(aicfg.mine_path()), "providers": len(config.providers),
                                  "models": len(config.models), "profiles": len(config.profiles)},
                           f"ok: {len(config.providers)} providers, {len(config.models)} models, "
                           f"{len(config.profiles)} profiles (overlay: {aicfg.mine_path()})")
        if command == "providers" and action is None:
            rows = provider_rows(config, _key_sources())
            return _answer(args, {"ok": True, "providers": rows}, _table(rows, ("id", "kind", "protocol", "source", "keySource", "baseUrl")))
        if command == "models" and action is None:
            rows = model_rows(config, args.provider_filter, args.all)
            return _answer(args, {"ok": True, "models": rows}, _table(rows, ("provider", "id", "source", "active", "paid", "effortLevels", "price")))
        if command == "profiles" and action is None:
            rows = profile_rows(config)
            return _answer(args, {"ok": True, "profiles": rows}, _table(rows, ("name", "provider", "model", "effort", "fallback", "source", "problem")))
        if command == "bindings":
            rows = binding_rows(config)
            return _answer(args, {"ok": True, "bindings": rows}, _table(rows, ("feature", "profiles", "source")))

        if command in ("complete", "test"):
            return _run_call(args, config)
        if command == "usage":
            import npdev_ai_client

            since = args.since or npdev_ai_client.month_start()
            log = npdev_ai_client.UsageLog()
            summary = {"ok": True, "since": since, "log": str(log.path), **npdev_ai_client.summarize(log.records(since))}
            text = _table(summary["rows"], ("feature", "provider", "model", "calls", "failed", "inTok", "outTok", "estUsd"))
            text += f"\nestimated total since {since}: ${summary['estUsd']:.4f}"
            if summary["paidCallsWithoutPrice"]:
                text += f" (+{summary['paidCallsWithoutPrice']} paid call(s) of unknown price)"
            return _answer(args, summary, text)
        if command == "spend" and action is None:
            spend = {"allowPaid": config.spend.get("allowPaid") or {}, "monthlyCapUsd": config.spend.get("monthlyCapUsd")}
            allowed = ", ".join(f for f, on in spend["allowPaid"].items() if on) or "none"
            cap_text = "none" if spend["monthlyCapUsd"] is None else f"${spend['monthlyCapUsd']:.2f}"
            return _answer(args, {"ok": True, **spend}, f"paid calls allowed for: {allowed}; monthly cap: {cap_text}")

        document = aicfg.read_mine()
        if command == "spend":
            spend = document.setdefault("spend", {})
            if action == "allow-paid":
                aicfg.check_feature(args.feature)
                allow_map = spend.setdefault("allowPaid", {})
                if args.off:
                    allow_map.pop(args.feature, None)
                else:
                    allow_map[args.feature] = True
                if not allow_map:
                    spend.pop("allowPaid")
                subject = f"paid calls for {args.feature} {'off' if args.off else 'on'}"
            else:
                if args.usd.lower() == "off":
                    spend.pop("monthlyCapUsd", None)
                else:
                    try:
                        spend["monthlyCapUsd"] = float(args.usd)
                    except ValueError:
                        raise AiConfigError(f"cap expects a USD amount or 'off', got {args.usd!r}") from None
                subject = f"monthly cap {args.usd}"
        elif command == "providers":
            if action in ("add", "set"):
                if action == "add" and args.id in config.providers:
                    raise AiConfigError(f"provider {args.id!r} exists: use `npdev ai providers set {args.id}`")
                if action == "set":
                    _require_provider(config, args.id)
                edit_provider(document, args)
            else:
                set_disabled(document, config, "providers", args.id, action == "disable")
            subject = f"provider {args.id}"
        elif command == "models":
            key = (args.provider, args.model)
            if action in ("add", "set"):
                config.provider(args.provider)
                if action == "add" and key in config.models:
                    raise AiConfigError(f"model {args.provider}/{args.model} exists: use `npdev ai models set`")
                edit_model(document, args)
            else:
                set_disabled(document, config, "models", key, action == "disable")
            subject = f"model {args.provider}/{args.model}"
        elif command == "profiles":
            if action in ("add", "set"):
                if action == "add" and args.name in config.profiles:
                    raise AiConfigError(f"profile {args.name!r} exists: use `npdev ai profiles set {args.name}`")
                edit_profile(document, args)
            elif action == "remove":
                remove_profile(document, config, args.name)
            else:
                set_disabled(document, config, "profiles", args.name, action == "disable")
            subject = f"profile {args.name}"
        elif command == "bind":
            bind(document, config, args.feature, _csv(args.profiles) or [])
            subject = f"binding {args.feature}"
        elif command == "reset":
            section = {"provider": "providers", "model": "models", "profile": "profiles", "binding": "bindings"}[args.section]
            key = _model_key(args.key) if section == "models" else args.key
            changed = aicfg.reset_mine(document, section, key, args.field)
            if not changed:
                return _answer(args, {"ok": True, "changed": False}, f"nothing of yours to reset for {args.section} {args.key}")
            subject = f"{args.section} {args.key}"
        else:
            raise AiConfigError(f"unknown command ai {command}")
        path = aicfg.write_mine(document)
        return _answer(args, {"ok": True, "changed": True, "file": str(path)}, f"saved {subject} in {path}")
    except AiConfigError as exc:
        if getattr(args, "json", False):
            print(json.dumps({"ok": False, "error": str(exc)}))
        else:
            print(f"error: {exc}", file=sys.stderr)
        return 2


def _read_text(path: str | None) -> str | None:
    if path is None:
        return None
    if path == "-":
        return sys.stdin.read()
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read()
    except OSError as exc:
        raise AiConfigError(f"cannot read {path} ({type(exc).__name__})") from None


def _run_call(args, config) -> int:
    import npdev_ai_client

    try:
        if args.ai_command == "test":
            result = npdev_ai_client.complete(config, "Reply with the single word OK", profile=args.profile,
                                              max_tokens=64, allow_paid=args.allow_paid, timeout=args.timeout,
                                              backoff=())
        else:
            schema_text = _read_text(args.json_schema)
            try:
                schema = json.loads(schema_text) if schema_text is not None else None
            except json.JSONDecodeError as exc:
                raise AiConfigError(f"--json-schema is not valid JSON: {exc}") from None
            result = npdev_ai_client.complete(
                config, _read_text(args.prompt_file) or "", system=_read_text(args.system_file), json_schema=schema,
                profile=args.profile, feature=args.feature, provider=args.provider, model=args.model,
                effort=args.effort, max_tokens=args.max_tokens, account=args.account, allow_paid=args.allow_paid,
                timeout=args.timeout)
    except npdev_ai_client.AiCallError as exc:
        result = {"ok": False, "error": str(exc)}
    if getattr(args, "json", False):
        print(json.dumps(result, indent=2))
    elif result["ok"]:
        for warning in result.get("warnings") or []:
            print(f"warning: {warning}", file=sys.stderr)
        print(result["text"] if args.ai_command == "complete" else
              f"ok: {result['profile'] or result['provider']} -> {result['provider']}/{result['model']} "
              f"answered in {result['seconds']}s")
    else:
        print(f"error: {result['error']}", file=sys.stderr)
    return 0 if result["ok"] else 1


def _answer(args, payload: dict, text: str) -> int:
    print(json.dumps(payload, indent=2) if getattr(args, "json", False) else text)
    return 0


def _table(rows: list[dict], columns: tuple) -> str:
    def cell(value):
        if isinstance(value, dict):
            return ",".join(f"{k}={v}" for k, v in value.items())
        if isinstance(value, list):
            return ",".join(map(str, value)) or "-"
        return "-" if value is None else str(value)

    if not rows:
        return "(none)"
    cells = [[cell(r.get(c)) for c in columns] for r in rows]
    widths = [max(len(c), *(len(row[i]) for row in cells)) for i, c in enumerate(columns)]
    lines = ["  ".join(c.ljust(w) for c, w in zip(columns, widths))]
    lines += ["  ".join(v.ljust(w) for v, w in zip(row, widths)) for row in cells]
    return "\n".join(lines)
