"""`npdev ai providers|models|profiles|bind|bindings|reset|validate` -- read and edit the AI configuration.

Every edit goes to the user's overlay (npdev_ai_config.mine_path()), never to the shipped catalogue, and is
validated against the merged view before the file is replaced: a change that would leave a profile
pointing at nothing is refused. The Manager's AI Providers tab drives these same commands with --json.
Keys are not handled here -- `npdev providers add|remove` owns them.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

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
        return aicfg.upsert_mine(document, section, {"provider": key[0], "id": key[1], "enabled": not disabled})
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
    refresh = msub.add_parser("refresh", help="Ask the provider's server for its model list: new ones are "
                                              "recorded (not enabled), vanished ones marked retired.")
    refresh.add_argument("provider")
    refresh.add_argument("--only", help="Record only new model ids containing this text.")
    refresh.add_argument("--account")
    json_flag(refresh)
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

    detect = ai_sub.add_parser("detect", help="Find local LLM servers (Ollama, LM Studio, llama.cpp, vLLM, Jan) "
                                              "on this machine, or on --host, and list their models.")
    detect.add_argument("--host", action="append", help="Also probe this host (repeatable). Default: loopback only.")
    detect.add_argument("--add", action="store_true", help="Record what was found as keyless local providers, "
                                                           "and point the offline profile at it.")
    detect.add_argument("--pick", action="append", metavar="PROVIDER/MODEL", help="With --add: only these models.")
    detect.add_argument("--timeout", type=float, default=1.0)
    json_flag(detect)

    app = ai_sub.add_parser("app", help="A generated app's AI vendors (externalAi.vendors) and whether it "
                                        "will find a key for each. Never shows a key.")
    app.add_argument("--app-dir", required=True, help="The generated app (the folder holding secrets/).")
    app.add_argument("--model", required=True, help="The app's model.json.")
    json_flag(app)

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

    context = ai_sub.add_parser("context", help="The authoring context to paste into a chat AI that has no MCP "
                                                "tools: contract, value reference, golden models. Compact by default.")
    context.add_argument("--full", action="store_true", help="Same as --detail full: the full bundle (adds the interview "
                                                             "and tool-loop sections an MCP agent uses), ~25 %% larger.")
    context.add_argument("--detail", choices=["lean", "standard", "full", "auto"], help="How much to teach: lean = a one-shot simple "
                                                                                 "CRUD app (about a fifth of standard), standard = "
                                                                                 "the default, full = everything, auto = pick from "
                                                                                 "--ask-file's text.")
    context.add_argument("--ask-file", help="With --detail auto: what the user wants ('-' = stdin).")
    context.add_argument("--with-catalog", metavar="AREA", help="Also append the catalog lines (one per reusable pack) of this area "
                                                                "(sales, finance, people, ...; 'all' = every area), so the AI reuses "
                                                                "a pack instead of re-modelling it. Needs scripts/pack-repo/build-ai-catalog.py run once.")
    context.add_argument("--output", help="Write it to this file instead of stdout.")
    json_flag(context)

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
    cap = ssub.add_parser("cap", help="Monthly USD cap across all paid calls (default $10; 'off' means no cap).")
    cap.add_argument("usd")
    json_flag(cap)

    import npdev_relay  # `npdev ai relay ...`: the browser-chat engine (prompt, ingest)
    npdev_relay.add_arguments(ai_sub)

    users = ai_sub.add_parser("users", help="User profiles: whose keys pay for which AI work. Without a subcommand: "
                                            "list them and say which key each role uses.")
    json_flag(users)
    usub = users.add_subparsers(dest="ai_action")
    p = usub.add_parser("add", help="Create a user profile and, right away, store its provider keys (hidden prompt). "
                                    "Without --key, a terminal asks which providers to store a key for.")
    p.add_argument("id", help="Letters, digits, '-' or '_' (also the key account name).")
    p.add_argument("--name", help="Display name.")
    p.add_argument("--key", action="append", metavar="PROVIDER",
                   help="Store this profile's key for PROVIDER (repeatable), e.g. --key gemini --key groq.")
    json_flag(p)
    p = usub.add_parser("key", help="Store or replace one provider key of an existing profile (hidden prompt).")
    p.add_argument("id")
    p.add_argument("provider")
    json_flag(p)
    p = usub.add_parser("remove", help="Delete a user profile (its stored keys stay in the vault).")
    p.add_argument("id")
    json_flag(p)
    p = usub.add_parser("use", help="Make a profile the active one: every AI call on this machine uses its keys. "
                                    f"({'NPDEV_AI_USER'} overrides it for one shell.)")
    p.add_argument("id")
    json_flag(p)
    p = usub.add_parser("anonymous", help="No active profile: the roles decide, otherwise the main key.")
    json_flag(p)
    p = usub.add_parser("assign", help="Give a role (a feature: bench, review, ...) to a profile: its keys pay for that work.")
    p.add_argument("id")
    p.add_argument("role")
    json_flag(p)
    p = usub.add_parser("unassign", help="Take a role away from whoever holds it.")
    p.add_argument("role")
    json_flag(p)


def argparse_bool():
    import argparse

    return argparse.BooleanOptionalAction


COMMANDS = ("providers", "models", "profiles", "bindings", "bind", "reset", "validate", "complete", "test",
            "usage", "spend", "detect", "app", "context", "relay", "users")


def run(args) -> int:
    command, action = args.ai_command, getattr(args, "ai_action", None)
    if command == "relay":
        import npdev_relay

        return npdev_relay.run(args)
    try:
        if command == "context":
            return _run_context(args)
        if command == "users":
            return _run_users(args, action)
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

        if command == "app":
            import npdev_providers

            try:
                status = npdev_providers.app_ai_status(Path(args.app_dir), Path(args.model), npdev_providers.Vault())
            except npdev_providers.ProviderError as exc:
                raise AiConfigError(str(exc)) from None
            text = "\n".join(f"{v['id']}: {'ready' if v['ready'] else 'NOT READY'} (in app env: {v['keyInApp']}, "
                             f"your store: {v['keyInStore']})" for v in status["vendors"]) or "no externalAi vendors"
            return _answer(args, {"ok": True, **status}, f"egress {status['egress']}\n{text}")
        if command == "detect":
            import npdev_ai_discovery

            found = npdev_ai_discovery.detect(args.host, args.timeout)
            payload = {"ok": True, "servers": found}
            text = "\n".join(f"{s['name']} at {s['baseUrl']}: {', '.join(s['models']) or '(no models)'}"
                             for s in found) or "no local LLM server answered"
            if args.add and found:
                document = aicfg.read_mine()
                added = npdev_ai_discovery.add_detected(document, config, found, set(args.pick or []) or None)
                if added:
                    payload.update(added=added, file=str(aicfg.write_mine(document)))
                    text += f"\nadded {len(added)} model(s); offline profile -> {aicfg.load().profile('offline')['model']}"
            return _answer(args, payload, text)
        if command == "models" and action == "refresh":
            import npdev_ai_client
            import npdev_providers

            provider = config.provider(args.provider)
            try:
                key = npdev_providers.resolve_key(args.provider, account=args.account)
                listed = npdev_ai_client.list_models(provider, key)
            except (npdev_ai_client.AiCallError, npdev_providers.ProviderError) as exc:
                raise AiConfigError(str(exc)) from None
            import npdev_ai_discovery

            document = aicfg.read_mine()
            report = npdev_ai_discovery.refresh(document, config, args.provider, listed, args.only)
            changed = bool(report["new"] or report["retired"] or report["back"])
            path = aicfg.write_mine(document) if changed else aicfg.mine_path()
            return _answer(args, {"ok": True, "provider": args.provider, "listed": len(listed), **report,
                                  "file": str(path)},
                           f"{args.provider}: {len(listed)} listed; new {len(report['new'])} (not enabled: "
                           f"`npdev ai models enable {args.provider} <model>`), retired {len(report['retired'])}, "
                           f"back {len(report['back'])}")

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
                    spend["monthlyCapUsd"] = None  # explicit: absent means the $10 default
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


def catalog_section(area: str) -> str:
    """FORM P7-T3: the L0 catalog lines of one area (or all), under a heading that tells the AI what to do with them."""
    import npdev_ai_common  # scripts/ai is on sys.path by the caller

    l0_path = npdev_ai_common.build_root() / "npr-catalog" / "catalog" / "l0.txt"
    if not l0_path.is_file():
        raise AiConfigError(f"the pack catalog is not built yet: run python scripts/pack-repo/build-ai-catalog.py (expected at {l0_path})")
    wanted = area.strip().lower()
    lines = [ln for ln in l0_path.read_text(encoding="utf-8").splitlines() if ln.count("|") >= 5]
    chosen = lines if wanted == "all" else [ln for ln in lines if ln.split("|")[2].lower() == wanted]
    if not chosen:
        areas = sorted({ln.split("|")[2] for ln in lines})
        raise AiConfigError(f"no pack in the catalog has the area '{area}' (areas: {', '.join(areas)}, or 'all')")
    return ("\n\n## Pack catalog (" + wanted + ")\n"
            "Reuse a catalog pack instead of re-modelling what it already covers. One line per pack: "
            "id|kind|area|trust|need|requires (trust: draft < verified < certified). To use one, add it to the model's `packs[]`; "
            "its card (what it provides, the exact line to consume it, its main limit) is the file catalog/cards/<id>.json "
            "or the MCP tool npdev_pack_card.\n"
            "First match every part of the request to a catalog line; for each match put that pack in `packs[]` (copy the `from` "
            "exactly) and do NOT re-declare its concepts - reference them as `<packId>::<Concept>`. Model inline only what no line "
            "covers. Copy the card's `use` line whole and bind EVERY role in its `provides.roleBindings` to one of your roles.\n\n"
            + "\n".join(chosen) + "\n")


DETAIL_SIGNALS_PATH = Path(__file__).resolve().parent / "ai-detail-signals.json"


def suggest_detail(ask: str) -> tuple[str, str]:
    """(level, why) for `npdev ai context --detail auto`: how much of the authoring context this request needs.
    Words live in ai-detail-signals.json. Unsure means standard -- lean is only for a short request that is just records to keep."""
    import re

    signals = json.loads(DETAIL_SIGNALS_PATH.read_text(encoding="utf-8"))
    text = (ask or "").lower()

    def hit(words: list[str]) -> str | None:
        for word in words:
            if re.search(r"(?<!\w)" + re.escape(word.lower()), text):
                return word
        return None

    word = hit(signals["full"])
    if word:
        return "full", f"the request mentions '{word}'"
    word = hit(signals["standard"])
    if word:
        return "standard", f"the request mentions '{word}'"
    if len(text) > int(signals["longAskChars"]):
        return "standard", "a long request usually hides rules"
    if not text.strip():
        return "standard", "no request given"
    return "lean", "just records to keep: no roles, states, reports or integrations mentioned"


def _run_context(args) -> int:
    """The core-context bundle the AI bench measures (scripts/ai/build_core_context.py), for a chat AI
    reached by copy-paste. Compact by default: a one-shot chat cannot use the interview and tool-loop
    sections, and the compact bundle passed the same bench prompts at ~25 % fewer tokens. The MCP tool
    npdev_core_context keeps serving the full one to agents that do run a tool loop."""
    scripts_ai = str(Path(__file__).resolve().parents[1] / "scripts" / "ai")
    if scripts_ai not in sys.path:
        sys.path.insert(0, scripts_ai)
    import build_core_context

    if args.full and args.detail not in (None, "full"):
        raise AiConfigError("--full and --detail disagree: use one of them")
    detail = "full" if args.full else (args.detail or "standard")
    chosen_because = None
    if detail == "auto":
        if not args.ask_file:
            raise AiConfigError("--detail auto needs --ask-file (what the user wants; '-' = stdin)")
        ask_text = sys.stdin.read() if args.ask_file == "-" else Path(args.ask_file).read_text(encoding="utf-8")
        detail, chosen_because = suggest_detail(ask_text)
    variant = "compact" if detail == "standard" else detail
    try:
        bundle, manifest = build_core_context.build_bundle(detail=detail)
    except (OSError, ValueError) as exc:
        raise AiConfigError(f"cannot build the authoring context: {exc}") from None
    payload = {"ok": True, "variant": variant, "bytes": manifest["bytes"], "contentSha256": manifest["contentSha256"]}
    if chosen_because:
        payload["detailChosen"] = detail
        payload["detailBecause"] = chosen_because
    if getattr(args, "with_catalog", None):
        bundle += catalog_section(args.with_catalog)
        payload["bytes"] = len(bundle.encode("utf-8"))
        payload["catalogArea"] = args.with_catalog.strip().lower()
    if args.output:
        target = Path(args.output)
        try:
            target.write_bytes(bundle.encode("utf-8"))  # bytes: no CRLF translation, matches `bytes` above
        except OSError as exc:
            raise AiConfigError(f"cannot write {target} ({type(exc).__name__})") from None
        return _answer(args, {**payload, "file": str(target.resolve())},
                       f"wrote the {variant} authoring context ({manifest['bytes']} bytes) to {target}")
    if args.json:
        return _answer(args, {**payload, "markdown": bundle}, "")
    # A Windows console's code page cannot encode every character of the bundle; send UTF-8 bytes.
    stream = getattr(sys.stdout, "buffer", None)
    if stream is None:
        sys.stdout.write(bundle)
    else:
        sys.stdout.flush()
        stream.write(bundle.encode("utf-8"))
        stream.flush()
    return 0


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
                                              max_tokens=npdev_ai_client.TEST_MAX_TOKENS, allow_paid=args.allow_paid, timeout=args.timeout,
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


def _run_users(args, action) -> int:
    """`npdev ai users ...` -- user profiles (npdev_user_profiles). Keys are never read, written or shown here."""
    import npdev_providers
    import npdev_user_profiles as users

    try:
        document = users.read()
        if action == "add":
            wanted = list(dict.fromkeys(args.key or []))
            if not wanted and not getattr(args, "json", False) and sys.stdin.isatty():
                wanted = _ask_providers()
            for provider_id in wanted:   # check every provider before anything is created or stored
                _key_provider(provider_id)
            users.add(document, args.id, args.name)
            stored = _store_user_keys(args.id, wanted)
            subject = (f"added user profile {args.id}; keys stored: {', '.join(stored) or 'none'}"
                       + ("" if stored else f" (add one: npdev ai users key {args.id} <provider>)"))
        elif action == "key":
            _key_provider(args.provider)
            users.require_profile(document, args.id)
            _store_user_keys(args.id, [args.provider])
            subject = f"stored the {args.provider} key of {args.id}"
            return _answer(args, {"ok": True, "changed": True}, subject)
        elif action == "remove":
            users.remove(document, args.id)
            subject = f"removed user profile {args.id}"
        elif action == "use":
            users.use(document, args.id)
            subject = f"{args.id} is now the active profile: every AI call on this machine uses its keys"
        elif action == "anonymous":
            users.use(document, None)
            subject = "no active profile: roles decide, otherwise the main key"
        elif action == "assign":
            users.assign_role(document, args.id, args.role)
            subject = f"role {args.role} now uses the keys of {args.id}"
        elif action == "unassign":
            holder = users.unassign_role(document, args.role)
            subject = f"role {args.role} " + (f"taken from {holder}" if holder else "had no holder")
        else:
            active = users.active_name(document)
            rows = [{"id": name, "displayName": entry.get("displayName"), "active": name == active,
                     "aiRoles": entry.get("aiRoles") or []} for name, entry in sorted(document["profiles"].items())]
            payload = {"ok": True, "file": str(users.path()), "active": active, "profiles": rows}
            text = _table(rows, ("id", "displayName", "active", "aiRoles")) + \
                f"\nactive: {active or 'none (anonymous: roles decide, otherwise the main key)'}"
            return _answer(args, payload, text)
        file = users.write(document)
        return _answer(args, {"ok": True, "changed": True, "file": str(file)}, f"{subject} ({file})")
    except (users.UserProfileError, npdev_providers.ProviderError) as exc:
        if getattr(args, "json", False):
            print(json.dumps({"ok": False, "error": str(exc)}))
        else:
            print(f"error: {exc}", file=sys.stderr)
        return 2


def _key_provider(provider_id: str) -> dict:
    """The catalogue entry of a provider that takes a key, or an error that names the known ones."""
    import npdev_providers

    entry = npdev_providers.get_provider(provider_id)
    if not entry.get("keyEnvVar"):
        raise npdev_providers.ProviderError(f"{provider_id} needs no key")
    return entry


def _ask_providers() -> list[str]:
    print("Providers to store a key for (e.g. gemini, groq). Enter alone to finish.")
    chosen: list[str] = []
    while True:
        answer = input("provider> ").strip().lower()
        if not answer:
            return chosen
        if answer not in chosen:
            chosen.append(answer)


def _store_user_keys(profile_id: str, providers: list[str]) -> list[str]:
    """Hidden prompt per provider, then the vault under `<provider>:<profile_id>`. The key is never printed."""
    import getpass

    import npdev_providers as prov

    if not providers:
        return []
    vault = prov.Vault()
    stored = []
    for provider_id in providers:
        entry = _key_provider(provider_id)
        key = getpass.getpass(f"{entry['name']} API key for {profile_id} (input hidden; get one at {entry['signupUrl']}): ").strip()
        vault.set(prov.account_key_id(provider_id, profile_id), key)
        stored.append(provider_id)
    return stored


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
