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


def classify_backend_error(text: str) -> str:
    """Why a model call failed, for the scorecard: an infrastructure outcome is never an authoring
    failure. quota (429: the free tier is used up) | overload (5xx) | unreachable (timeout, network,
    404 = no such model) | other. full-gemini-1 scored 16.7 % because 25 of these were counted as
    `author` failures: 20 prompts never reached the model at all."""
    head = (text or "")[:200]
    if "HTTP 429" in head or "rate_limit" in head or "usage limit" in head.lower():
        return "quota"
    if any(f"HTTP {code}" in head for code in (500, 502, 503, 504, 529)) or "overloaded" in head.lower():
        return "overload"
    if "HTTP 404" in head or "timed out" in head or "TimeoutError" in head or "URLError" in head \
            or "calling " in head or "model call failed" in head:
        return "unreachable"
    return "other"


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
            entries.append({"id": provider["id"], "kind": provider["kind"], "baseUrl": url})
    return entries


def catalogue_price_per_1k(provider_id: str, model: str) -> tuple[float, float]:
    """USD per 1k tokens from the AI config's model entry (which holds USD per million). An unknown price
    reads as 0 here: the bench only reaches a model without a price on a free/local provider, or under
    --allow-paid, where the cost is reported as untracked."""
    sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
    import npdev_ai_config

    try:
        price_in, price_out = npdev_ai_config.load().price(provider_id, model)
    except npdev_ai_config.AiConfigError:
        return 0.0, 0.0
    return (price_in or 0.0) / 1000.0, (price_out or 0.0) / 1000.0


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
        price = entry.get("pricePer1kTokens")  # the retired standalone allowlist format carried its own
        price_in, price_out = ((float(price.get("input", 0)), float(price.get("output", 0))) if price
                               else catalogue_price_per_1k(entry["id"], model))
        return BackendInfo(entry["id"], entry.get("kind", "free"), base_url, model, price_in, price_out, allow_paid)
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
                                   f"{exc.read().decode('utf-8', 'replace')[:1500]}") from None
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


def _ai_modules():
    sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
    import npdev_ai_client
    import npdev_ai_config
    return npdev_ai_config, npdev_ai_client


def _completion(result: dict, messages: list[dict]) -> Completion:
    if not result["ok"]:
        raise BackendError(result["error"])
    pt = result.get("inputTokens") or _estimate_tokens(messages)
    ct = result.get("outputTokens") or _estimate_tokens([{"content": result["text"]}])
    return Completion(result["text"], int(pt), int(ct), float(result.get("seconds") or 0.0))


def _info(config, provider_id: str, model: str, allow_paid: bool) -> BackendInfo:
    provider = config.provider(provider_id)
    price_in, price_out = config.price(provider_id, model)
    return BackendInfo(provider_id, "paid" if config.is_paid(provider_id, model) else provider["kind"],
                       provider["baseUrl"], model, (price_in or 0.0) / 1000.0, (price_out or 0.0) / 1000.0, allow_paid)


class ClientBackend:
    """One catalogue provider + model, called through `npdev ai`'s one client (npdev_ai_client): the same
    wire, retry, spend guard and usage log every other NPDev feature uses."""

    def __init__(self, config, provider_id: str, model: str, allow_paid: bool, timeout: float,
                 backoff: tuple | None = None, key_resolver=None, opener=None):
        self.info = _info(config, provider_id, model, allow_paid)
        self._config, self._allow_paid, self._timeout, self._backoff = config, allow_paid, timeout, backoff
        self._key_resolver, self._opener = key_resolver, opener

    def complete(self, messages: list[dict]) -> Completion:
        _, client = _ai_modules()
        extra = {"opener": self._opener} if self._opener else {}
        result = client.complete(self._config, "", messages=messages, provider=self.info.id, model=self.info.model,
                                 feature="bench", allow_paid=self._allow_paid, timeout=self._timeout,
                                 backoff=self._backoff, key_resolver=self._key_resolver, **extra)
        return _completion(result, messages)


class ProfileBackend:
    """--profile NAME, or the `bench` feature binding by default: the client walks the profile's fallback
    chain itself. Once a fallback answers, the run sticks to it (a provider whose quota is gone stays
    gone), so one run is one model unless that model becomes unreachable."""

    def __init__(self, config, profile: str | None, allow_paid: bool, timeout: float, key_resolver=None, opener=None):
        _, client = _ai_modules()
        try:
            targets, _ = client.targets_for(config, profile=profile, feature=None if profile else "bench")
        except client.AiCallError as exc:
            raise BackendRefused(str(exc)) from None
        first = targets[0]
        self.info = _info(config, first.provider_id, first.model_id, allow_paid)
        self._config, self._profile, self._allow_paid, self._timeout = config, profile, allow_paid, timeout
        self._key_resolver, self._opener = key_resolver, opener
        self.retired: list[str] = []

    def complete(self, messages: list[dict]) -> Completion:
        _, client = _ai_modules()
        extra = {"opener": self._opener} if self._opener else {}
        result = client.complete(self._config, "", messages=messages, profile=self._profile, feature="bench",
                                 allow_paid=self._allow_paid, timeout=self._timeout,
                                 key_resolver=self._key_resolver, **extra)
        completion = _completion(result, messages)
        if result.get("attempts") and result.get("profile"):
            self.retired += [a["profile"] or a["provider"] for a in result["attempts"]]
            self._profile = result["profile"]
        self.info = _info(self._config, result["provider"], result["model"], self._allow_paid)
        return completion


def build_provider_backends(spec: str, allow_paid: bool, timeout: float, resolve_key=None, config=None, opener=None):
    """Backends for `--provider`: catalogue endpoint + the key from the NPDev vault / environment
    (npdev_providers.resolve_key). One provider -> that backend; several -> FailoverBackend, and every
    backend but the last gives up after a short retry budget so failover is quick. Refusals happen here,
    before any prompt is sent."""
    aicfg, _ = _ai_modules()
    import npdev_providers
    try:
        config = config or aicfg.load()
    except aicfg.AiConfigError as exc:
        raise BackendRefused(str(exc)) from None
    resolve_key = resolve_key or (lambda pid: npdev_providers.resolve_key(pid))
    parsed = parse_provider_spec(spec)
    built = []
    for index, (provider_id, model) in enumerate(parsed):
        try:
            entry = config.provider(provider_id)
        except aicfg.AiConfigError as exc:
            raise BackendRefused(str(exc)) from None
        model = model or entry.get("defaultModel")
        if not model:
            raise BackendRefused(f"provider {provider_id!r} has no default model; give one: --provider {provider_id}=<model>")
        if config.is_paid(provider_id, model) and not allow_paid:
            suffix = entry.get("freeModelSuffix")
            raise BackendRefused(
                f"{provider_id}: model {model!r} is not a free one (must end {suffix!r}); other models on this "
                f"endpoint are billed" if entry["kind"] != "paid" else
                f"provider {provider_id!r} is PAID; no paid API spend is permitted (owner approval required, "
                f"then --allow-paid)")
        key = resolve_key(provider_id)
        if entry.get("needsKey") and not key:
            raise BackendRefused(f"no key for {provider_id}: run `npdev providers add {provider_id}` "
                                 f"(or set {entry['keyEnvVar']})")
        last = index == len(parsed) - 1
        built.append(ClientBackend(config, provider_id, model, allow_paid, timeout,
                                   backoff=None if last else (15, 30),
                                   key_resolver=lambda pid, account=None, _key=key: _key, opener=opener))
    return built[0] if len(built) == 1 else FailoverBackend(built)


def find_claude_cli() -> str | None:
    """NPDEV_CLAUDE_CLI, else `claude` on PATH, else the newest binary bundled with the VS Code extension."""
    explicit = os.environ.get("NPDEV_CLAUDE_CLI", "").strip()
    if explicit:
        return explicit
    import shutil
    on_path = shutil.which("claude")
    if on_path:
        return on_path
    extensions = Path.home() / ".vscode" / "extensions"
    exe = "claude.exe" if os.name == "nt" else "claude"
    found = sorted(extensions.glob(f"anthropic.claude-code-*/resources/native-binary/{exe}"))
    return str(found[-1]) if found else None


def flatten_conversation(messages: list[dict]) -> tuple[str, str]:
    """(system, prompt) for a one-shot CLI: the system message stays the system prompt; the rest of
    the chat (request, earlier replies, error feedback) becomes one transcript, oldest first."""
    system = "\n\n".join(str(m["content"]) for m in messages if m.get("role") == "system")
    turns = [m for m in messages if m.get("role") != "system"]
    if len(turns) == 1:
        return system, str(turns[0]["content"])
    parts = []
    for m in turns:
        label = "YOUR EARLIER REPLY" if m.get("role") == "assistant" else "USER"
        parts.append(f"=== {label} ===\n{m['content']}")
    return system, "\n\n".join(parts)


class ClaudeCliBackend:
    """A simulated EXTERNAL AI: the Claude Code CLI in one-shot mode (`-p`), billed to the owner's
    subscription, not to an API key. Isolated so it sees only what an API caller would send: run in a
    fresh empty directory, no tools, no settings/CLAUDE.md (`--setting-sources ""`), no MCP servers,
    our system prompt instead of Claude Code's. The CLI still adds ~0.9k tokens of its own context per
    call; they are counted (input + cache reads + cache writes), so the figures slightly overstate an
    API call's input."""

    def __init__(self, model: str, timeout: float = 900.0, exe: str | None = None, runner=None):
        self.exe = exe or find_claude_cli()
        if not self.exe:
            raise BackendRefused("Claude CLI not found: set NPDEV_CLAUDE_CLI to claude(.exe)")
        self.info = BackendInfo("claude-cli", "subscription", "claude-cli://" + model, model)
        self.timeout = timeout
        self._run = runner or self._subprocess

    def _subprocess(self, argv: list[str], stdin: str, cwd: str) -> str:
        import subprocess
        try:
            proc = subprocess.run(argv, input=stdin, capture_output=True, text=True, encoding="utf-8",
                                  timeout=self.timeout, cwd=cwd)
        except subprocess.TimeoutExpired:
            raise BackendError(f"model call failed: TimeoutError: claude-cli timed out after {self.timeout}s") from None
        return proc.stdout or proc.stderr or ""

    def complete(self, messages: list[dict]) -> Completion:
        import tempfile
        system, prompt = flatten_conversation(messages)
        started = time.monotonic()
        with tempfile.TemporaryDirectory(prefix="npdev-sim-") as cwd:
            sys_file = Path(cwd) / ".system-prompt.txt"
            sys_file.write_text(system, encoding="utf-8")
            raw = self._run([self.exe, "-p", "--model", self.info.model, "--tools", "",
                             "--system-prompt-file", str(sys_file), "--setting-sources", "",
                             "--strict-mcp-config", "--output-format", "json"], prompt, cwd)
        try:
            payload = json.loads(raw)
        except ValueError:
            raise BackendError(f"model call failed: claude-cli printed no JSON: {raw[:300]}") from None
        if payload.get("is_error"):
            status = payload.get("api_error_status")
            raise BackendError((f"HTTP {status} " if status else "") + f"from claude-cli: {str(payload.get('result'))[:1500]}")
        usage = payload.get("usage") or {}
        prompt_tokens = sum(int(usage.get(k) or 0) for k in
                            ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"))
        return Completion(str(payload.get("result") or ""), prompt_tokens, int(usage.get("output_tokens") or 0),
                          time.monotonic() - started)


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
