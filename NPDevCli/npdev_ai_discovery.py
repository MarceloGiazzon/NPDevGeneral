"""Find what is out there instead of typing it: local LLM servers (`npdev ai detect`) and a provider's
current model list (`npdev ai models refresh`). Stdlib only.

Both only ever WRITE to the user's overlay, and only what was asked:
  * detect probes the known local servers on loopback (1-second timeout), plus any --host given
    explicitly -- never a scan of the network. --add records each server as a keyless `kind: local`
    provider, its models, and points the `offline` profile at the first one found.
  * refresh merges the server's list with the facts NPDev knows: a model the server lists and the
    catalogue does not is recorded as `enabled: false` (you enable the ones you want); a known model the
    server no longer lists becomes `status: retired`, so a dead model never sits in a binding unnoticed.
    Facts the server does not report (price, effort levels) stay unknown.
"""
from __future__ import annotations

import datetime as _dt
import json
import re
import urllib.error
import urllib.request
from typing import Callable

import npdev_ai_config as aicfg

KNOWN_LOCAL_SERVERS = (
    {"server": "ollama", "name": "Ollama", "port": 11434, "nativeList": "/api/tags"},
    {"server": "lmstudio", "name": "LM Studio", "port": 1234},
    {"server": "llamacpp", "name": "llama.cpp server", "port": 8080},
    {"server": "vllm", "name": "vLLM", "port": 8000},
    {"server": "jan", "name": "Jan", "port": 1337},
)
LOOPBACK = ("127.0.0.1", "localhost")


def _get_json(url: str, timeout: float, opener: Callable):
    request = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
    with opener(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _model_ids(payload) -> list[str]:
    if isinstance(payload, dict) and isinstance(payload.get("data"), list):
        return sorted({m["id"] for m in payload["data"] if isinstance(m, dict) and m.get("id")})
    if isinstance(payload, dict) and isinstance(payload.get("models"), list):
        return sorted({m.get("name") or m.get("model") for m in payload["models"]
                       if isinstance(m, dict) and (m.get("name") or m.get("model"))})
    return []


def provider_id_for(server: str, host: str) -> str:
    if host in LOOPBACK:
        return server
    return f"{server}-" + re.sub(r"[^a-z0-9]+", "-", host.lower()).strip("-")


def detect(hosts: list[str] | None = None, timeout: float = 1.0,
           opener: Callable = urllib.request.urlopen) -> list[dict]:
    """Every known local server answering on the given hosts (default loopback), with its models."""
    found = []
    for host in hosts or ["127.0.0.1"]:
        for known in KNOWN_LOCAL_SERVERS:
            base = f"http://{host}:{known['port']}"
            models: list[str] = []
            answered = False
            for path in filter(None, (known.get("nativeList"), "/v1/models")):
                try:
                    models = _model_ids(_get_json(base + path, timeout, opener))
                    answered = True
                except (urllib.error.URLError, OSError, ValueError):
                    continue
                if models:
                    break
            if answered:
                found.append({"server": known["server"], "name": known["name"], "host": host,
                              "provider": provider_id_for(known["server"], host), "baseUrl": base + "/v1",
                              "models": models})
    return found


def add_detected(document: dict, config, found: list[dict], picks: set[str] | None = None) -> list[str]:
    """Record detected servers and models in the overlay; returns '<provider>/<model>' for each added
    model. `picks` limits it to those '<provider>/<model>' names."""
    today = _dt.date.today().isoformat()
    added = []
    for server in found:
        pid = server["provider"]
        chosen = [m for m in server["models"] if not picks or f"{pid}/{m}" in picks]
        if not chosen:
            continue
        if pid not in config.providers:
            aicfg.upsert_mine(document, "providers", {
                "id": pid, "name": f"{server['name']} ({server['host']})", "kind": "local", "protocol": "openai-chat",
                "baseUrl": server["baseUrl"], "authStyle": "none", "needsKey": False, "keyEnvVar": None,
                "appVendor": False, "notes": f"Added by `npdev ai detect` on {today}."})
        for model in chosen:
            if (pid, model) not in config.models:
                aicfg.upsert_mine(document, "models", {"provider": pid, "id": model, "effortLevels": [],
                                                       "price": {"in": 0, "out": 0}, "enabled": True,
                                                       "status": "available", "discovered": today})
            added.append(f"{pid}/{model}")
    if added and config.profile_problem("offline") is None and _offline_reachable(config, found):
        return added
    if added:
        pid, model = added[0].split("/", 1)
        aicfg.upsert_mine(document, "profiles", {"name": "offline", "provider": pid, "model": model})
    return added


def _offline_reachable(config, found: list[dict]) -> bool:
    """Does the current `offline` profile point at a model one of the detected servers actually serves?"""
    offline = config.profile("offline")
    return any(server["provider"] == offline["provider"] and offline["model"] in server["models"] for server in found)


def refresh(document: dict, config, provider_id: str, server_models: list[str], only: str | None = None) -> dict:
    """Merge one provider's server model list into the overlay. Returns {new, retired, back, known}."""
    today = _dt.date.today().isoformat()
    listed = set(server_models)
    known = {mid: entry for (pid, mid), entry in config.models.items() if pid == provider_id}
    report = {"new": [], "retired": [], "back": [], "known": sorted(listed & set(known))}
    for model in sorted(listed - set(known)):
        if only and only not in model:
            continue
        aicfg.upsert_mine(document, "models", {"provider": provider_id, "id": model, "enabled": False,
                                               "status": "available", "discovered": today})
        report["new"].append(model)
    if not listed:
        return report  # an empty answer proves nothing about what was retired
    for model, entry in sorted(known.items()):
        status = entry.get("status", "available")
        if model not in listed and status != "retired":
            aicfg.upsert_mine(document, "models", {"provider": provider_id, "id": model, "status": "retired"})
            report["retired"].append(model)
        elif model in listed and status == "retired":
            aicfg.upsert_mine(document, "models", {"provider": provider_id, "id": model, "status": "available"})
            report["back"].append(model)
    return report
