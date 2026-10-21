"""Pluggable model backends for the AI authoring benchmark (BENCH-2). Stdlib only.

Owner decision 2026-10-19: NO PAID API SPEND. So:
  * the only real backend is an OpenAI-compatible chat-completions adapter configured purely by env
    vars (NPDEV_BENCH_BASE_URL, NPDEV_BENCH_MODEL, NPDEV_BENCH_API_KEY) -- works against local Ollama
    (http://localhost:11434/v1) and free-tier hosted endpoints;
  * the runner refuses any base URL that is not on scripts/policy/ai-bench-backends.json unless
    --allow-paid is passed;
  * the API key is read from the environment, sent as a Bearer header, and NEVER printed or logged.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

REPO_ROOT = Path(__file__).resolve().parents[3]
ALLOWLIST_PATH = REPO_ROOT / "scripts" / "policy" / "ai-bench-backends.json"


class BackendRefused(Exception):
    """The configured base URL is not on the free/local allowlist and --allow-paid was not given."""


class BackendError(Exception):
    """The model call itself failed (network, HTTP status, malformed reply)."""


@dataclass
class Completion:
    text: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
    seconds: float = 0.0


@dataclass
class BackendInfo:
    id: str
    kind: str                      # local | free | paid-unlisted | fake
    base_url: str
    model: str
    price_in_per_1k: float = 0.0
    price_out_per_1k: float = 0.0
    allow_paid: bool = False

    def describe(self) -> dict:
        return {"id": self.id, "kind": self.kind, "baseUrl": self.base_url, "model": self.model,
                "pricePer1kTokens": {"input": self.price_in_per_1k, "output": self.price_out_per_1k},
                "allowPaid": self.allow_paid}

    def cost(self, prompt_tokens: int, completion_tokens: int) -> float:
        return round(prompt_tokens / 1000.0 * self.price_in_per_1k
                     + completion_tokens / 1000.0 * self.price_out_per_1k, 6)


def load_allowlist(path: Path | None = None) -> list[dict]:
    data = json.loads((path or ALLOWLIST_PATH).read_text(encoding="utf-8"))
    return list(data.get("backends") or [])


def _norm(url: str):
    parts = urlsplit(url.strip())
    try:
        port = parts.port or (443 if parts.scheme == "https" else 80)
    except ValueError:
        return "", "", -1, ""        # malformed URL: matches nothing, so it is refused
    return parts.scheme.lower(), (parts.hostname or "").lower(), port, parts.path.rstrip("/")


def match_allowlist(base_url: str, entries: list[dict]) -> dict | None:
    """First entry whose scheme+host+port equal base_url's and whose path is a prefix of it."""
    scheme, host, port, path = _norm(base_url)
    for entry in entries:
        e_scheme, e_host, e_port, e_path = _norm(entry["baseUrl"])
        if (scheme, host, port) == (e_scheme, e_host, e_port) and (path == e_path or path.startswith(e_path + "/")):
            return entry
    return None


def resolve_backend(base_url: str, model: str, allow_paid: bool, entries: list[dict] | None = None) -> BackendInfo:
    """Raise BackendRefused for an unlisted URL unless allow_paid."""
    if not base_url:
        raise BackendRefused("NPDEV_BENCH_BASE_URL is not set (and --dry-run was not given)")
    if not model:
        raise BackendRefused("NPDEV_BENCH_MODEL is not set")
    entries = load_allowlist() if entries is None else entries
    entry = match_allowlist(base_url, entries)
    if entry is not None:
        price = entry.get("pricePer1kTokens") or {}
        return BackendInfo(entry["id"], entry.get("kind", "free"), base_url, model,
                           float(price.get("input", 0)), float(price.get("output", 0)), allow_paid)
    if not allow_paid:
        raise BackendRefused(
            f"base URL {base_url!r} is not on the free/local allowlist ({ALLOWLIST_PATH.name}); "
            f"no paid API spend is permitted. Add it to the allowlist if it is a free tier, or pass --allow-paid.")
    return BackendInfo("unlisted-paid", "paid-unlisted", base_url, model, 0.0, 0.0, True)


def _estimate_tokens(messages: list[dict]) -> int:
    return sum(len(str(m.get("content", ""))) for m in messages) // 4


class OpenAICompatBackend:
    """POST {base_url}/chat/completions. Key from NPDEV_BENCH_API_KEY (optional for local Ollama)."""

    def __init__(self, info: BackendInfo, api_key: str | None = None, timeout: float = 900.0,
                 temperature: float = 0.2):
        self.info = info
        self._api_key = api_key if api_key is not None else os.environ.get("NPDEV_BENCH_API_KEY", "")
        self.timeout = timeout
        self.temperature = temperature

    def complete(self, messages: list[dict]) -> Completion:
        url = self.info.base_url.rstrip("/") + "/chat/completions"
        body = json.dumps({"model": self.info.model, "messages": messages,
                           "temperature": self.temperature, "stream": False}).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = "Bearer " + self._api_key
        request = urllib.request.Request(url, data=body, headers=headers, method="POST")
        started = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            # never echo request headers (they carry the key); cap the response body
            raise BackendError(f"HTTP {exc.code} from {self.info.base_url}: "
                               f"{exc.read().decode('utf-8', 'replace')[:300]}") from None
        except (urllib.error.URLError, OSError, ValueError) as exc:
            raise BackendError(f"model call failed: {type(exc).__name__}: {str(exc)[:200]}") from None
        try:
            text = payload["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError, TypeError):
            raise BackendError("malformed chat-completions reply (no choices[0].message.content)") from None
        usage = payload.get("usage") or {}
        pt = int(usage.get("prompt_tokens") or _estimate_tokens(messages))
        ct = int(usage.get("completion_tokens") or _estimate_tokens([{"content": text}]))
        return Completion(text, pt, ct, time.monotonic() - started)


class FakeBackend:
    """Deterministic --dry-run backend: always replies with a known-good model.json (the frozen
    canary), so the whole pipeline is testable with no model. `replies` lets a test script a sequence
    (e.g. a broken model first, then the good one) to exercise the repair loop."""

    FAKE_PROMPT_TOKENS = 1000
    FAKE_COMPLETION_TOKENS = 500

    def __init__(self, good_model_path: Path, replies: list[str] | None = None):
        self.info = BackendInfo("fake-dry-run", "fake", "fake://dry-run", "fake-canary", 0.0, 0.0, False)
        self._good = good_model_path
        self._replies = list(replies or [])
        self.calls: list[list[dict]] = []

    def complete(self, messages: list[dict]) -> Completion:
        self.calls.append(messages)
        if self._replies:
            text = self._replies.pop(0)
        else:
            text = "Here is the model.\n```json\n" + self._good.read_text(encoding="utf-8") + "\n```\n"
        return Completion(text, self.FAKE_PROMPT_TOKENS, self.FAKE_COMPLETION_TOKENS, 0.0)
