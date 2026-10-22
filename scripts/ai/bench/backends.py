"""Pluggable model backends for the AI authoring benchmark (BENCH-2). Stdlib only.

Owner decision 2026-10-19: NO PAID API SPEND. So:
  * the only real backend is an OpenAI-compatible chat-completions adapter configured purely by env
    vars (NPDEV_BENCH_BASE_URL, NPDEV_BENCH_MODEL, NPDEV_BENCH_API_KEY) -- works against local Ollama
    (http://localhost:11434/v1) and free-tier hosted endpoints;
  * the runner refuses any base URL that is not on NPDevCli/ai-providers.json (free/local providers) unless
    --allow-paid is passed;
  * the API key is read from the environment, sent as a Bearer header, and NEVER printed or logged.
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

REPO_ROOT = Path(__file__).resolve().parents[3]
ALLOWLIST_PATH = REPO_ROOT / "NPDevCli" / "ai-providers.json"   # the provider catalogue IS the allowlist
RETRYABLE_HTTP = {429, 500, 502, 503, 504}
RETRY_BACKOFF_SECONDS = (20, 40, 80, 160, 300, 300)  # ~15 min of patience per call


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
    """Free and local providers of the catalogue (NPDevCli/ai-providers.json); 'paid' entries are not
    allowlisted. A file with a top-level "backends" list (the retired standalone format) still loads."""
    data = json.loads((path or ALLOWLIST_PATH).read_text(encoding="utf-8"))
    if "backends" in data:
        return list(data["backends"])
    entries = []
    for provider in data.get("providers") or []:
        if provider.get("kind") not in ("free", "local"):
            continue
        for url in [provider["baseUrl"], *(provider.get("aliasBaseUrls") or [])]:
            entries.append({"id": provider["id"], "kind": provider["kind"], "baseUrl": url,
                            "pricePer1kTokens": provider.get("pricePer1kTokens") or {}})
    return entries


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
                 temperature: float = 0.2, backoff: tuple = None):
        self.info = info
        self._backoff = RETRY_BACKOFF_SECONDS if backoff is None else tuple(backoff)
        self._api_key = api_key if api_key is not None else os.environ.get("NPDEV_BENCH_API_KEY", "")
        self.timeout = timeout
        self.temperature = temperature
        self._sleep = time.sleep

    def complete(self, messages: list[dict]) -> Completion:
        url = self.info.base_url.rstrip("/") + "/chat/completions"
        body = json.dumps({"model": self.info.model, "messages": messages,
                           "temperature": self.temperature, "stream": False}).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = "Bearer " + self._api_key
        request = urllib.request.Request(url, data=body, headers=headers, method="POST")
        started = time.monotonic()
        payload = None
        for attempt in range(len(self._backoff) + 1):
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    payload = json.loads(response.read().decode("utf-8"))
                break
            except urllib.error.HTTPError as exc:
                if exc.code in RETRYABLE_HTTP and attempt < len(self._backoff):
                    # free tiers answer 429 (quota) / 503 (overload) in bursts: wait and retry
                    self._sleep(self._backoff[attempt])
                    continue
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


class FailoverBackend:
    """Try providers in order. A provider whose call still fails after its own retries is retired for the
    rest of the run (its quota is gone), and the next one answers. `info` is the provider that served the
    last call, so every prompt's result.json names the model that actually authored it. Providers are
    NOT mixed for load-spreading: one run is one model unless that model becomes unreachable."""

    def __init__(self, backends_in_order: list):
        if not backends_in_order:
            raise ValueError("FailoverBackend needs at least one backend")
        self._alive = list(backends_in_order)
        self._active = self._alive[0]
        self.retired: list[str] = []

    @property
    def info(self) -> BackendInfo:
        return self._active.info

    def complete(self, messages: list[dict]) -> Completion:
        last: BackendError | None = None
        while self._alive:
            self._active = self._alive[0]
            try:
                return self._active.complete(messages)
            except BackendError as exc:
                last = exc
                if len(self._alive) == 1:
                    break
                self.retired.append(self._active.info.id)
                self._alive.pop(0)
        raise last if last else BackendError("no backend available")


def parse_provider_spec(spec: str) -> list[tuple[str, str | None]]:
    """'nvidia,gemini=gemini-3.5-flash' -> [('nvidia', None), ('gemini', 'gemini-3.5-flash')]."""
    out = []
    for part in spec.split(","):
        part = part.strip()
        if part:
            provider_id, _, model = part.partition("=")
            out.append((provider_id.strip(), model.strip() or None))
    if not out:
        raise BackendRefused("--provider is empty")
    return out


def build_provider_backends(spec: str, allow_paid: bool, timeout: float, resolve_key=None,
                            catalogue: list[dict] | None = None):
    """Backends for `--provider`: catalogue endpoint + the key from the NPDev vault / environment
    (npdev_providers.resolve_key). One provider -> that backend; several -> FailoverBackend, and every
    backend but the last gives up after a short retry budget so failover is quick."""
    sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
    import npdev_providers
    catalogue = npdev_providers.load_catalogue() if catalogue is None else catalogue
    resolve_key = resolve_key or (lambda pid: npdev_providers.resolve_key(pid, catalogue=catalogue))
    parsed = parse_provider_spec(spec)
    built = []
    for index, (provider_id, model) in enumerate(parsed):
        try:
            entry = npdev_providers.get_provider(provider_id, catalogue)
        except npdev_providers.ProviderError as exc:
            raise BackendRefused(str(exc)) from None
        if entry["kind"] == "paid" and not allow_paid:
            raise BackendRefused(f"provider {provider_id!r} is PAID; no paid API spend is permitted "
                                 f"(owner approval required, then --allow-paid)")
        model = model or entry.get("defaultModel")
        if not model:
            raise BackendRefused(f"provider {provider_id!r} has no default model; give one: --provider {provider_id}=<model>")
        suffix = entry.get("freeModelSuffix")
        if suffix and not model.endswith(suffix) and not allow_paid:
            raise BackendRefused(f"{provider_id}: model {model!r} is not a free one (must end {suffix!r}); "
                                 f"other models on this endpoint are billed")
        key = resolve_key(provider_id)
        if entry.get("keyEnvVar") and not key:
            raise BackendRefused(f"no key for {provider_id}: run `npdev providers add {provider_id}` "
                                 f"(or set {entry['keyEnvVar']})")
        price = entry.get("pricePer1kTokens") or {}
        info = BackendInfo(provider_id, entry["kind"], entry["baseUrl"], model,
                           float(price.get("input", 0)), float(price.get("output", 0)), allow_paid)
        last = index == len(parsed) - 1
        built.append(OpenAICompatBackend(info, api_key=key or "", timeout=timeout,
                                         backoff=None if last else (15, 30)))
    return built[0] if len(built) == 1 else FailoverBackend(built)


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
