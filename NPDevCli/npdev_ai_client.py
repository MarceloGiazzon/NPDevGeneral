"""ONE AI client for every NPDev developer and Manager feature (`npdev ai complete`). Stdlib only.

It speaks the closed list of protocols the catalogue declares (openai-chat, anthropic-messages,
gemini-native), resolves keys through npdev_providers (environment first, then the OS store), and owns
what every caller used to re-implement: failover across a profile's fallback chain, 429/5xx retry with
backoff, the spend guard, and the usage log.

Rules it keeps:
  * A key goes into one request header and nowhere else -- never a URL, a result, a log line, an error.
  * No key means nothing is sent (fail closed); a paid call needs an explicit opt-in (spend guard).
  * An effort level the model does not declare is not sent (npdev_ai_config.effort_request).
  * The usage log records who/what/how much -- never a prompt, a reply or a key.
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import npdev_ai_config as aicfg

RETRYABLE_HTTP = {429, 500, 502, 503, 504}
DEFAULT_BACKOFF = (20, 40, 80, 160, 300, 300)   # ~15 min of patience on the last target
FAILOVER_BACKOFF = (15, 30)                     # every target but the last gives up quickly
DEFAULT_MAX_TOKENS = 4096
# A "reply OK" smoke call still needs room: thinking models (Gemini 2.5+/3.x) spend a small budget on
# reasoning and come back with finish_reason=length and no text at all.
TEST_MAX_TOKENS = 1024
# Some provider edges (Groq's Cloudflare: HTTP 403, error 1010) refuse urllib's default Python-urllib agent.
USER_AGENT = "npdev-cli"
USAGE_SCHEMA = "npdev-ai-usage.v1"


class AiCallError(Exception):
    """A failed call. Never carries a key."""


@dataclass
class Target:
    provider: dict
    model_id: str
    model: dict | None
    profile: str | None = None
    effort: str | None = None
    max_tokens: int | None = None
    temperature: float | None = None
    account: str | None = None

    @property
    def provider_id(self) -> str:
        return self.provider["id"]


# --------------------------------------------------------------------------- targets

def targets_for(config, *, profile: str | None = None, feature: str | None = None, provider: str | None = None,
                model: str | None = None, effort: str | None = None, max_tokens: int | None = None,
                account: str | None = None, temperature: float | None = None) -> tuple[list[Target], list[str]]:
    """The ordered list of targets to try, and warnings for the profiles skipped on the way. A profile or a
    provider names what to call; without either, the feature's binding does (with one, `feature` only
    labels the call for the spend guard and the usage log). effort, max_tokens and temperature override
    every target."""
    if profile and provider:
        raise AiCallError("name either --profile or --provider, not both")
    if not (profile or provider or feature):
        raise AiCallError("name what to call: --profile, --feature or --provider")
    warnings: list[str] = []
    if provider:
        entry = config.provider(provider)
        model_id = model or entry.get("defaultModel")
        if not model_id:
            raise AiCallError(f"{provider} has no default model; pass --model "
                              f"(or run `npdev ai models refresh {provider}`)")
        return [Target(entry, model_id, config.model(provider, model_id), None, effort, max_tokens, temperature,
                       account)], warnings
    names = [profile] if profile else config.binding(feature)
    if feature and not names:
        raise AiCallError(f"feature {feature!r} has no binding; run `npdev ai bind {feature} <profile>`")
    order: list[str] = []
    stack = list(reversed(names))
    while stack:
        name = stack.pop()
        if name in order:
            continue
        order.append(name)
        stack.extend(reversed(config.profile(name).get("fallback") or []))
    targets = []
    for name in order:
        problem = config.profile_problem(name)
        if problem:
            warnings.append(f"skipped: {problem}")
            continue
        entry = config.profile(name)
        targets.append(Target(config.provider(entry["provider"]), entry["model"],
                              config.model(entry["provider"], entry["model"]), name,
                              effort or entry.get("effort"), max_tokens or entry.get("maxTokens"),
                              temperature if temperature is not None else entry.get("temperature"),
                              account or entry.get("account")))
    if not targets:
        raise AiCallError("no usable profile: " + "; ".join(warnings))
    return targets, warnings


# --------------------------------------------------------------------------- wire

def _deep_merge(base: dict, extra: dict) -> dict:
    for key, value in extra.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            _deep_merge(base[key], value)
        else:
            base[key] = value
    return base


def endpoint(provider: dict, model_id: str, path: str) -> str:
    base = provider["baseUrl"].rstrip("/")
    if provider.get("modelInPath"):
        base = base.replace("{model}", urllib.parse.quote(model_id, safe=""))
    url = base + path
    params = provider.get("queryParams") or {}
    return url + ("?" + urllib.parse.urlencode(params) if params else "")


def auth_headers(provider: dict, key: str | None) -> dict:
    headers = {"Content-Type": "application/json", **(provider.get("extraHeaders") or {})}
    style = provider.get("authStyle", "bearer")
    if style == "none" or not key:
        return headers
    name = {"bearer": "Authorization", "x-api-key": "x-api-key", "x-goog-api-key": "x-goog-api-key",
            "api-key": "api-key"}[style]
    headers[name] = ("Bearer " + key) if style == "bearer" else key
    return headers


def conversation(prompt: str, system: str | None = None, messages: list[dict] | None = None) -> tuple[str | None, list[dict]]:
    """(system text, user/assistant turns). `messages` (chat shape, system entries allowed anywhere) wins
    over `prompt`; its system entries are joined into one system text, as two of the protocols need."""
    if not messages:
        return system, [{"role": "user", "content": prompt}]
    systems = [system] if system else []
    turns = []
    for message in messages:
        role, content = message.get("role"), str(message.get("content") or "")
        if role == "system":
            systems.append(content)
        elif role in ("user", "assistant"):
            turns.append({"role": role, "content": content})
        else:
            raise AiCallError(f"unknown message role {role!r}")
    if not turns or turns[0]["role"] != "user":
        raise AiCallError("a conversation starts with a user turn")
    return ("\n\n".join(systems) or None), turns


def build_request(target: Target, prompt: str, system: str | None = None, json_schema: dict | None = None,
                  messages: list[dict] | None = None) -> tuple[str, dict, list[str]]:
    """(path-and-query URL, body, warnings) for one call. Headers are added at send time, with the key."""
    system, turns = conversation(prompt, system, messages)
    protocol = target.provider["protocol"]
    model = target.model or {}
    warnings: list[str] = []
    effort_body, warning = aicfg.effort_request(protocol, target.model or {"id": target.model_id}, target.effort)
    if warning:
        warnings.append(warning)
    max_tokens = target.max_tokens or DEFAULT_MAX_TOKENS
    structured = bool(json_schema) and model.get("structuredOutput")
    if json_schema and not structured:
        system = ((system + "\n\n") if system else "") + (
            "Answer with one JSON value only, valid against this JSON Schema:\n" + json.dumps(json_schema))
    if protocol == "openai-chat":
        chat = ([{"role": "system", "content": system}] if system else []) + turns
        body = {"model": target.model_id, "messages": chat, "max_tokens": max_tokens, "stream": False}
        if target.temperature is not None:
            body["temperature"] = target.temperature
        if structured:
            body["response_format"] = {"type": "json_schema",
                                       "json_schema": {"name": "answer", "schema": json_schema, "strict": True}}
        url = endpoint(target.provider, target.model_id, "/chat/completions")
    elif protocol == "anthropic-messages":
        body = {"model": target.model_id, "max_tokens": max_tokens, "messages": turns}
        if system:
            body["system"] = system
        if structured:
            body["output_config"] = {"format": {"type": "json_schema", "schema": json_schema}}
        if target.temperature is not None:
            warnings.append("temperature is not sent on anthropic-messages (the API rejects it)")
        url = endpoint(target.provider, target.model_id, "/messages")
    elif protocol == "gemini-native":
        body = {"contents": [{"role": "model" if t["role"] == "assistant" else "user", "parts": [{"text": t["content"]}]}
                             for t in turns],
                "generationConfig": {"maxOutputTokens": max_tokens}}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        if target.temperature is not None:
            body["generationConfig"]["temperature"] = target.temperature
        if structured:
            body["generationConfig"].update(responseMimeType="application/json", responseJsonSchema=json_schema)
        url = endpoint(target.provider, target.model_id,
                       "/models/" + urllib.parse.quote(target.model_id, safe="") + ":generateContent")
    else:
        raise AiCallError(f"protocol {protocol!r} is not implemented by this client")
    return url, _deep_merge(body, effort_body), warnings


def parse_reply(protocol: str, payload: dict) -> tuple[str, int | None, int | None]:
    """(text, input tokens, output tokens). Raises AiCallError for a reply with no answer in it."""
    try:
        if protocol == "openai-chat":
            text = payload["choices"][0]["message"]["content"] or ""
            usage = payload.get("usage") or {}
            return text, usage.get("prompt_tokens"), usage.get("completion_tokens")
        if protocol == "anthropic-messages":
            if payload.get("stop_reason") == "refusal":
                category = (payload.get("stop_details") or {}).get("category")
                raise AiCallError(f"the model declined the request (refusal{', ' + category if category else ''})")
            text = "".join(b.get("text", "") for b in payload.get("content") or [] if b.get("type") == "text")
            usage = payload.get("usage") or {}
            return text, usage.get("input_tokens"), usage.get("output_tokens")
        if protocol == "gemini-native":
            parts = payload["candidates"][0]["content"]["parts"]
            text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
            usage = payload.get("usageMetadata") or {}
            return text, usage.get("promptTokenCount"), usage.get("candidatesTokenCount")
    except (KeyError, IndexError, TypeError):
        raise AiCallError(f"malformed {protocol} reply (no answer in it)") from None
    raise AiCallError(f"protocol {protocol!r} is not implemented by this client")


def _send(url: str, headers: dict, body: dict | None, timeout: float, opener: Callable) -> dict:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT, **headers},
                                     method="POST" if body is not None else "GET")
    with opener(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _describe(exc: Exception, url: str) -> str:
    host = urllib.parse.urlsplit(url).netloc
    if isinstance(exc, urllib.error.HTTPError):
        try:
            # 1500, not 300: a free tier names WHICH quota ran out (per-minute tokens vs requests per day)
            # only after the first few hundred characters of its 429 body
            detail = exc.read().decode("utf-8", "replace")[:1500]
        except Exception:  # noqa: BLE001 -- a broken error body must not hide the status code
            detail = ""
        return f"HTTP {exc.code} from {host}" + (f": {detail}" if detail else "")
    return f"{type(exc).__name__} calling {host}: {str(exc)[:200]}"


def call(target: Target, key: str | None, prompt: str, system: str | None = None, json_schema: dict | None = None,
         timeout: float = 120.0, backoff: tuple = DEFAULT_BACKOFF, opener: Callable = urllib.request.urlopen,
         sleep: Callable = time.sleep, messages: list[dict] | None = None) -> dict:
    """One target, with retry on 429/5xx. Returns {text, inputTokens, outputTokens, warnings}."""
    if target.provider.get("needsKey") and not key:
        raise AiCallError(f"no key for {target.provider_id}: run `npdev providers add {target.provider_id}`"
                          + (f" --account {target.account}" if target.account else "")
                          + f" (or set {target.provider['keyEnvVar']})")
    url, body, warnings = build_request(target, prompt, system, json_schema, messages)
    headers = auth_headers(target.provider, key)
    for attempt in range(len(backoff) + 1):
        try:
            payload = _send(url, headers, body, timeout, opener)
            break
        except urllib.error.HTTPError as exc:
            if exc.code in RETRYABLE_HTTP and attempt < len(backoff):
                sleep(backoff[attempt])
                continue
            raise AiCallError(_describe(exc, url)) from None
        except (urllib.error.URLError, OSError, ValueError) as exc:
            raise AiCallError(_describe(exc, url)) from None
    text, input_tokens, output_tokens = parse_reply(target.provider["protocol"], payload)
    return {"text": text, "inputTokens": input_tokens, "outputTokens": output_tokens, "warnings": warnings}


def list_models(provider: dict, key: str | None, timeout: float = 30.0,
                opener: Callable = urllib.request.urlopen) -> list[str]:
    """The model ids the server lists for this key."""
    if provider.get("needsKey") and not key:
        raise AiCallError(f"no key for {provider['id']}")
    url = endpoint(provider, "", "/models")
    try:
        payload = _send(url, auth_headers(provider, key), None, timeout, opener)
    except (urllib.error.URLError, OSError, ValueError) as exc:
        raise AiCallError(f"{provider['id']}: could not list models ({_describe(exc, url)})") from None
    if provider["protocol"] == "gemini-native":
        return sorted(m["name"].removeprefix("models/") for m in payload.get("models") or [] if m.get("name"))
    return sorted(m["id"] for m in payload.get("data") or [] if m.get("id"))


# --------------------------------------------------------------------------- usage + spend

def usage_path(environ=None) -> Path:
    """Next to the user's ai.json, not under the (ephemeral) Build root: the monthly cap reads it, and a
    wiped Build root must not silently reset a month's spend."""
    environ = os.environ if environ is None else environ
    override = (environ.get("NPDEV_AI_USAGE_LOG") or "").strip()
    return Path(override) if override else aicfg.mine_path(environ).parent / "ai-usage.jsonl"


@dataclass
class UsageLog:
    path: Path = field(default_factory=usage_path)

    def append(self, record: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"schema": USAGE_SCHEMA, **record}, sort_keys=True) + "\n")

    def records(self, since: str | None = None) -> list[dict]:
        if not self.path.exists():
            return []
        out = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if since is None or record.get("when", "") >= since:
                out.append(record)
        return out


def month_start(now: _dt.datetime | None = None) -> str:
    now = now or _dt.datetime.now(_dt.timezone.utc)
    return now.strftime("%Y-%m-01T00:00:00Z")


def summarize(records: list[dict]) -> dict:
    total, unknown, rows = 0.0, 0, {}
    for record in records:
        key = (record.get("feature") or "-", record.get("provider"), record.get("model"))
        row = rows.setdefault(key, {"feature": key[0], "provider": key[1], "model": key[2], "calls": 0, "failed": 0,
                                    "inTok": 0, "outTok": 0, "estUsd": 0.0, "unpriced": 0})
        row["calls"] += 1
        row["failed"] += 0 if record.get("ok") else 1
        row["inTok"] += record.get("inTok") or 0
        row["outTok"] += record.get("outTok") or 0
        if record.get("estUsd") is None:
            row["unpriced"] += 1 if record.get("paid") else 0
            unknown += 1 if record.get("paid") else 0
        else:
            row["estUsd"] = round(row["estUsd"] + record["estUsd"], 6)
            total += record["estUsd"]
    return {"estUsd": round(total, 6), "paidCallsWithoutPrice": unknown, "rows": list(rows.values())}


def estimate_usd(config, target: Target, input_tokens: int | None, output_tokens: int | None) -> float | None:
    price_in, price_out = config.price(target.provider_id, target.model_id)
    if not config.is_paid(target.provider_id, target.model_id):
        return 0.0
    if price_in is None or price_out is None or input_tokens is None or output_tokens is None:
        return None
    return round(input_tokens / 1e6 * price_in + output_tokens / 1e6 * price_out, 6)


def spend_problem(config, target: Target, feature: str | None, allow_paid: bool, usage: UsageLog) -> str | None:
    """Why the spend guard refuses this target, or None. A paid call needs `--allow-paid` (direct calls) or
    the feature's opt-in in ai.json `spend.allowPaid`; with a monthly cap, a model of unknown price is
    refused (the cap could not be enforced) and so is any call once the month's estimate reaches it."""
    if not config.is_paid(target.provider_id, target.model_id):
        return None
    opted_in = allow_paid or bool(feature and (config.spend.get("allowPaid") or {}).get(feature))
    if not opted_in:
        how = (f"`npdev ai spend allow-paid {feature}`" if feature else "--allow-paid")
        return f"{target.provider_id}/{target.model_id} is PAID and paid calls are off (opt in with {how})"
    cap = config.spend.get("monthlyCapUsd")
    if cap is None:
        return None
    price_in, price_out = config.price(target.provider_id, target.model_id)
    if price_in is None or price_out is None:
        return (f"{target.provider_id}/{target.model_id} has no known price, so the monthly cap cannot be "
                f"enforced; set it with `npdev ai models set {target.provider_id} {target.model_id} "
                f"--price-in .. --price-out ..`")
    spent = summarize(usage.records(month_start()))["estUsd"]
    if spent >= cap:
        return f"this month's estimated spend ${spent:.2f} has reached the cap ${cap:.2f}"
    return None


# --------------------------------------------------------------------------- complete

def complete(config, prompt: str, *, system: str | None = None, json_schema: dict | None = None,
             messages: list[dict] | None = None,
             profile: str | None = None, feature: str | None = None, provider: str | None = None,
             model: str | None = None, effort: str | None = None, max_tokens: int | None = None,
             temperature: float | None = None, account: str | None = None, allow_paid: bool = False,
             key_resolver: Callable | None = None,
             usage: UsageLog | None = None, timeout: float = 120.0, opener: Callable = urllib.request.urlopen,
             sleep: Callable = time.sleep, backoff: tuple | None = None, clock: Callable = time.monotonic) -> dict:
    """Try each target in order until one answers. The result names what actually answered and every
    attempt that did not -- and never the key."""
    if key_resolver is None:
        import npdev_providers

        def key_resolver(provider_id, account=None):
            return npdev_providers.resolve_key(provider_id, account=account)
    usage = usage or UsageLog()
    targets, warnings = targets_for(config, profile=profile, feature=feature, provider=provider, model=model,
                                    effort=effort, max_tokens=max_tokens, account=account, temperature=temperature)
    attempts = []
    for index, target in enumerate(targets):
        label = {"profile": target.profile, "provider": target.provider_id, "model": target.model_id}
        refusal = spend_problem(config, target, feature, allow_paid, usage)
        if refusal:
            attempts.append({**label, "error": refusal, "refused": True})
            continue
        last = index == len(targets) - 1
        started = clock()
        try:
            key = key_resolver(target.provider_id, target.account)
            answer = call(target, key, prompt, system, json_schema, timeout,
                          backoff if backoff is not None else (DEFAULT_BACKOFF if last else FAILOVER_BACKOFF),
                          opener, sleep, messages)
        except AiCallError as exc:
            attempts.append({**label, "error": str(exc)})
            usage.append(_usage_record(config, target, feature, None, None, False, clock() - started))
            continue
        est = estimate_usd(config, target, answer["inputTokens"], answer["outputTokens"])
        usage.append(_usage_record(config, target, feature, answer["inputTokens"], answer["outputTokens"], True,
                                   clock() - started, est))
        return {"ok": True, "text": answer["text"], **label, "feature": feature,
                "inputTokens": answer["inputTokens"], "outputTokens": answer["outputTokens"], "estUsd": est,
                "seconds": round(clock() - started, 2), "attempts": attempts,
                "warnings": warnings + answer["warnings"]}
    return {"ok": False, "error": "every target failed: " + " | ".join(
        f"{a['profile'] or a['provider']}: {a['error']}" for a in attempts), "feature": feature,
        "attempts": attempts, "warnings": warnings}


def _usage_record(config, target: Target, feature, input_tokens, output_tokens, ok: bool, seconds: float,
                  est: float | None = None) -> dict:
    return {"when": _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "feature": feature,
            "profile": target.profile, "provider": target.provider_id, "model": target.model_id,
            "account": target.account, "inTok": input_tokens, "outTok": output_tokens, "estUsd": est,
            "paid": config.is_paid(target.provider_id, target.model_id), "ok": ok, "seconds": round(seconds, 2)}
