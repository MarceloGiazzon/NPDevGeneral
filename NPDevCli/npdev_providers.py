"""`npdev providers` -- NPDev's own store for AI-provider API keys. Stdlib only.

One place holds every provider key, so the key is typed once and then serves both the AI authoring
benchmark (scripts/ai/bench) and every generated app's agent proxy (`npdev providers export-env`).

  * Catalogue  : NPDevCli/ai-providers.json (no keys, ever) -- id, endpoint, free/paid/local, signup link.
  * Vault      : providers.vault.json under %APPDATA%\\NPDev (Windows) or $XDG_CONFIG_HOME/NPDev, default
                 ~/.config/NPDev (Linux). It is the index -- which providers have a key, since when, in
                 which store -- and the secret goes to the OS store for the current user:
                   - Windows: encrypted with DPAPI (CryptProtectData) and kept in the file as a blob, so a
                     copy of the file on another machine or account is unreadable;
                   - Linux: the desktop keyring (GNOME Keyring / KWallet, the freedesktop Secret Service)
                     through `secret-tool` (package libsecret-tools); the file holds no secret at all.
                 Fail closed: with no store nothing is saved, and the NPDEV_EXTERNALAI_<ID>_API_KEY
                 environment variables remain the only source. Never a plain-text fallback.
  * Resolution : the environment variable wins (CI, one-off override), then the vault.
  * Hygiene    : a key is read from a hidden prompt or stdin, never from argv; it is never printed,
                 logged, or written anywhere except the vault and an app's own (gitignored) secrets dir.
"""
from __future__ import annotations

import base64
import getpass
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable

CATALOGUE_PATH = Path(__file__).resolve().parent / "ai-providers.json"
VAULT_SCHEMA = "npdev-provider-vault.v1"
DPAPI_ENTROPY = b"npdev-provider-vault.v1"
SECRET_SERVICE_ATTRIBUTE = ("service", "npdev-providers")
NO_STORE_HINT = ("set the NPDEV_EXTERNALAI_<ID>_API_KEY environment variables instead, or on Linux install "
                 "a keyring CLI (sudo apt install libsecret-tools) so keys go to the desktop keyring")
MANAGED_BEGIN = "# >>> npdev providers (managed block; rewritten by `npdev providers export-env`)"
MANAGED_END = "# <<< npdev providers"


class ProviderError(Exception):
    """A user-facing failure of an `npdev providers` operation (never carries a key)."""


class VaultUnavailable(ProviderError):
    """No secure store on this machine; keys can only come from environment variables."""


# --------------------------------------------------------------------------- catalogue

def load_catalogue(path: Path | None = None) -> list[dict]:
    data = json.loads((path or CATALOGUE_PATH).read_text(encoding="utf-8"))
    return list(data.get("providers") or [])


def get_provider(provider_id: str, catalogue: list[dict] | None = None) -> dict:
    for entry in (load_catalogue() if catalogue is None else catalogue):
        if entry["id"] == provider_id:
            return entry
    known = ", ".join(e["id"] for e in (load_catalogue() if catalogue is None else catalogue))
    raise ProviderError(f"unknown provider {provider_id!r}; known: {known}")


# --------------------------------------------------------------------------- protection

class DpapiProtector:
    """Windows DPAPI, current-user scope, via ctypes (no third-party package)."""

    def __init__(self) -> None:
        if sys.platform != "win32":
            raise VaultUnavailable("the key vault needs Windows DPAPI; on this OS set the "
                                   "NPDEV_EXTERNALAI_<ID>_API_KEY environment variables instead")

    @staticmethod
    def _blob(data: bytes):
        import ctypes
        from ctypes import wintypes

        class DataBlob(ctypes.Structure):
            _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

        buffer = ctypes.create_string_buffer(data, len(data))
        return DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char))), buffer

    def _call(self, data: bytes, protect: bool) -> bytes:
        import ctypes
        crypt32, kernel32 = ctypes.windll.crypt32, ctypes.windll.kernel32
        source, keep_alive = self._blob(data)
        entropy, keep_alive_2 = self._blob(DPAPI_ENTROPY)
        out = type(source)()
        function = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
        args = [ctypes.byref(source), None, ctypes.byref(entropy), None, None, 0x01, ctypes.byref(out)]
        ok = function(*args)
        del keep_alive, keep_alive_2
        if not ok:
            raise ProviderError("Windows DPAPI refused the operation (was the vault written by another "
                                "Windows user or machine?)")
        try:
            return ctypes.string_at(out.pbData, out.cbData)
        finally:
            kernel32.LocalFree(out.pbData)

    def protect(self, data: bytes) -> bytes:
        return self._call(data, True)

    def unprotect(self, data: bytes) -> bytes:
        return self._call(data, False)


# --------------------------------------------------------------------------- stores
# A store keeps the secret; the vault file only records that it exists. `put` returns the fields the
# vault entry carries (a blob for DPAPI, nothing for a keyring), `take` gets the key back from them.

class BlobStore:
    """The secret is encrypted by a protector and kept in the vault file (Windows DPAPI)."""

    name = "dpapi"

    def __init__(self, protector) -> None:
        self.protector = protector

    def put(self, provider_id: str, key: str) -> dict:
        return {"blob": base64.b64encode(self.protector.protect(key.encode("utf-8"))).decode("ascii")}

    def take(self, provider_id: str, entry: dict) -> str | None:
        return self.protector.unprotect(base64.b64decode(entry["blob"])).decode("utf-8")

    def drop(self, provider_id: str, entry: dict) -> None:
        pass


class SecretServiceStore:
    """The freedesktop Secret Service (GNOME Keyring, KWallet) via `secret-tool`. The key travels on
    stdin/stdout only, never argv, so it does not show up in a process listing."""

    name = "secret-service"

    def __init__(self, tool: str | None = None, run: Callable = subprocess.run) -> None:
        self.tool = tool or shutil.which("secret-tool")
        if not self.tool:
            raise VaultUnavailable("no keyring CLI (secret-tool) on this machine; " + NO_STORE_HINT)
        self._run = run

    def _call(self, args: list[str], stdin: str | None = None):
        try:
            return self._run([self.tool, *args], input=stdin, capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise ProviderError(f"the desktop keyring did not answer ({type(exc).__name__}); is it "
                                f"unlocked?") from None

    @staticmethod
    def _attributes(provider_id: str) -> list[str]:
        return [*SECRET_SERVICE_ATTRIBUTE, "provider", provider_id]

    def put(self, provider_id: str, key: str) -> dict:
        done = self._call(["store", f"--label=NPDev provider key: {provider_id}",
                           *self._attributes(provider_id)], stdin=key)
        if done.returncode != 0:
            raise ProviderError(f"the desktop keyring refused the key (exit {done.returncode}); is it unlocked?")
        return {}

    def take(self, provider_id: str, entry: dict) -> str | None:
        done = self._call(["lookup", *self._attributes(provider_id)])
        value = (done.stdout or "").strip() if done.returncode == 0 else ""
        return value or None

    def drop(self, provider_id: str, entry: dict) -> None:
        self._call(["clear", *self._attributes(provider_id)])


def default_store():
    if sys.platform == "win32":
        return BlobStore(DpapiProtector())
    if sys.platform.startswith("linux"):
        return SecretServiceStore()
    raise VaultUnavailable(f"no key store for {sys.platform} yet; " + NO_STORE_HINT)


# --------------------------------------------------------------------------- vault

def default_vault_path() -> Path:
    override = os.environ.get("NPDEV_VAULT_PATH", "").strip()
    if override:
        return Path(override)
    base = (os.environ.get("APPDATA") or os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config"))
    return Path(base) / "NPDev" / "providers.vault.json"


class Vault:
    """`protector` is shorthand for a BlobStore around it (what the tests and DPAPI use)."""

    def __init__(self, path: Path | None = None, protector=None, store=None) -> None:
        self.path = path or default_vault_path()
        self._store = store or (BlobStore(protector) if protector is not None else None)

    @property
    def store(self):
        if self._store is None:
            self._store = default_store()
        return self._store

    def store_status(self) -> dict:
        """Which store this machine uses, or why there is none. Touches no key."""
        try:
            return {"available": True, "store": self.store.name, "vault": str(self.path)}
        except VaultUnavailable as exc:
            return {"available": False, "store": None, "vault": str(self.path), "reason": str(exc)}

    def _store_for(self, provider_id: str, entry: dict):
        # Entries written before stores existed carry only a blob: they are DPAPI.
        recorded = entry.get("store") or "dpapi"
        if recorded != self.store.name:
            raise ProviderError(f"the {provider_id} key was stored in '{recorded}', which this machine does not "
                                f"use ('{self.store.name}'); add it again here")
        return self.store

    def _read(self) -> dict:
        if not self.path.exists():
            return {"schema": VAULT_SCHEMA, "providers": {}}
        data = json.loads(self.path.read_text(encoding="utf-8"))
        if data.get("schema") != VAULT_SCHEMA:
            raise ProviderError(f"{self.path} is not an {VAULT_SCHEMA} file")
        return data

    def _write(self, data: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, self.path)

    def set(self, provider_id: str, key: str) -> None:
        if not key or key != key.strip() or "\n" in key:
            raise ProviderError("the key is empty or has surrounding whitespace / a newline")
        data = self._read()
        fields = self.store.put(provider_id, key)
        data["providers"][provider_id] = {"store": self.store.name, **fields,
                                          "addedAt": time.strftime("%Y-%m-%dT%H:%M:%S")}
        self._write(data)

    def get(self, provider_id: str) -> str | None:
        entry = self._read()["providers"].get(provider_id)
        if not entry:
            return None
        return self._store_for(provider_id, entry).take(provider_id, entry)

    def remove(self, provider_id: str) -> bool:
        data = self._read()
        entry = data["providers"].get(provider_id)
        if entry is None:
            return False
        try:
            self._store_for(provider_id, entry).drop(provider_id, entry)
        except VaultUnavailable:
            pass  # nothing reachable to clear; forgetting the entry is still what was asked
        del data["providers"][provider_id]
        self._write(data)
        return True

    def ids(self) -> dict[str, str]:
        """provider id -> addedAt. Reads no key, so it works without a store."""
        return {pid: e.get("addedAt", "") for pid, e in self._read()["providers"].items()}


def resolve_key(provider_id: str, vault: Vault | None = None, environ=None,
                catalogue: list[dict] | None = None) -> str | None:
    """Environment variable first (CI / one-off override), then the vault. None for a keyless provider
    or when no key is stored anywhere."""
    environ = os.environ if environ is None else environ
    entry = get_provider(provider_id, catalogue)
    var = entry.get("keyEnvVar")
    if not var:
        return None
    value = (environ.get(var) or "").strip()
    if value:
        return value
    vault = vault or Vault()
    try:
        return vault.get(provider_id)
    except VaultUnavailable:
        return None


# --------------------------------------------------------------------------- network

def _request(url: str, key: str | None, body: dict | None, timeout: float, opener: Callable):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if body else "GET")
    with opener(request, timeout=timeout) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


def _http_failure(exc: Exception) -> str:
    if isinstance(exc, urllib.error.HTTPError):
        return f"HTTP {exc.code}"
    return f"{type(exc).__name__}"


def list_models(provider_id: str, key: str | None, timeout: float = 30.0,
                opener: Callable = urllib.request.urlopen) -> list[str]:
    entry = get_provider(provider_id)
    try:
        _, payload = _request(entry["baseUrl"].rstrip("/") + "/models", key, None, timeout, opener)
    except (urllib.error.URLError, OSError, ValueError) as exc:
        raise ProviderError(f"{provider_id}: could not list models ({_http_failure(exc)})") from None
    return sorted(m.get("id", "") for m in (payload.get("data") or []) if m.get("id"))


def check_provider(provider_id: str, key: str | None, model: str | None = None, chat: bool = False,
                   timeout: float = 60.0, opener: Callable = urllib.request.urlopen) -> dict:
    """Does the key authenticate? With chat=True also send a one-word prompt to `model` (default: the
    catalogue's defaultModel). Returns a result dict; the key is never part of it."""
    entry = get_provider(provider_id)
    result: dict = {"provider": provider_id, "ok": False, "keyPresent": bool(key) or not entry.get("keyEnvVar")}
    if entry.get("keyEnvVar") and not key:
        result["error"] = f"no key: set {entry['keyEnvVar']} or run `npdev providers add {provider_id}`"
        return result
    started = time.monotonic()
    try:
        models = list_models(provider_id, key, timeout, opener)
        result["models"] = len(models)
    except ProviderError as exc:
        result["error"] = str(exc)
        return result
    result["ok"] = True
    if chat:
        chosen = model or entry.get("defaultModel")
        result["model"] = chosen
        if not chosen:
            result.update(ok=False, error="no model given and the catalogue has no defaultModel")
            return result
        try:
            _, reply = _request(entry["baseUrl"].rstrip("/") + "/chat/completions", key,
                                {"model": chosen, "max_tokens": 16, "stream": False,
                                 "messages": [{"role": "user", "content": "Reply with the single word OK"}]},
                                timeout, opener)
            result["chatOk"] = bool(reply.get("choices"))
        except (urllib.error.URLError, OSError, ValueError) as exc:
            result.update(ok=False, chatOk=False, error=f"chat call to {chosen} failed ({_http_failure(exc)})")
    result["seconds"] = round(time.monotonic() - started, 2)
    return result


# --------------------------------------------------------------------------- env bridges

def import_env(vault: Vault, environ=None) -> list[str]:
    """Copy every NPDEV_EXTERNALAI_<ID>_API_KEY already in the environment into the vault. Leaves the
    environment variables alone. Returns the provider ids imported."""
    environ = os.environ if environ is None else environ
    imported = []
    for entry in load_catalogue():
        var = entry.get("keyEnvVar")
        value = (environ.get(var) or "").strip() if var else ""
        if value:
            vault.set(entry["id"], value)
            imported.append(entry["id"])
    return imported


def export_env(app_dir: Path, provider_ids: list[str], vault: Vault | None = None, environ=None) -> dict:
    """Write the chosen providers' keys into <app>/secrets/agent-proxy.env as one managed block (the file
    `_ops\\Start-App.ps1` loads). Lines outside the block are preserved. Only vendors the generated apps'
    external-ai-http adapter speaks (catalogue appVendor) are accepted."""
    app_dir = Path(app_dir)
    if not (app_dir / "secrets").is_dir():
        raise ProviderError(f"{app_dir} has no secrets/ directory -- is it a generated app?")
    lines = ["NPDEV_EXTERNALAI_PROVIDER=http"]
    for provider_id in provider_ids:
        entry = get_provider(provider_id)
        if not entry.get("appVendor"):
            raise ProviderError(f"{provider_id}: generated apps cannot use this provider yet "
                                f"(supported: {', '.join(e['id'] for e in load_catalogue() if e.get('appVendor'))})")
        key = resolve_key(provider_id, vault, environ)
        if not key:
            raise ProviderError(f"{provider_id}: no key stored -- run `npdev providers add {provider_id}`")
        lines.append(f"{entry['keyEnvVar']}={key}")
    target = app_dir / "secrets" / "agent-proxy.env"
    existing = target.read_text(encoding="utf-8").splitlines() if target.exists() else []
    kept, skipping = [], False
    for line in existing:
        if line.strip() == MANAGED_BEGIN:
            skipping = True
        elif skipping and line.strip() == MANAGED_END:
            skipping = False
        elif not skipping:
            kept.append(line)
    managed_vars = {line.split("=", 1)[0] for line in lines}
    shadowed = sorted({ln.split("=", 1)[0] for ln in kept
                       if "=" in ln and not ln.lstrip().startswith("#") and ln.split("=", 1)[0] in managed_vars})
    while kept and not kept[-1].strip():
        kept.pop()
    body = kept + ([""] if kept else []) + [MANAGED_BEGIN, *lines, MANAGED_END]
    target.write_text("\n".join(body) + "\n", encoding="utf-8")
    return {"file": str(target), "providers": provider_ids, "shadowedByLinesOutsideBlock": shadowed}


def list_providers(vault: Vault, environ=None) -> dict:
    """The catalogue with, per provider, where its key would come from -- never the key itself."""
    environ = os.environ if environ is None else environ
    stored = vault.ids() if vault.path.exists() else {}
    rows = []
    for entry in load_catalogue():
        var = entry.get("keyEnvVar")
        source = ("none-needed" if not var else "env" if (environ.get(var) or "").strip()
                  else "vault" if entry["id"] in stored else "missing")
        rows.append({"id": entry["id"], "name": entry["name"], "kind": entry["kind"], "keyEnvVar": var,
                     "keySource": source, "addedAt": stored.get(entry["id"]) or None,
                     "signupUrl": entry.get("signupUrl"), "appVendor": entry.get("appVendor")})
    return {"store": vault.store_status(), "providers": rows}


# --------------------------------------------------------------------------- CLI

def add_arguments(subparsers) -> None:
    parser = subparsers.add_parser(
        "providers", help="Manage AI-provider API keys in NPDev's own vault (one key serves the "
                          "benchmark and every generated app).")
    sub = parser.add_subparsers(dest="providers_command")

    listing = sub.add_parser("list", help="Catalogue of known providers with kind and whether a key is available.")
    listing.add_argument("--json", action="store_true", help="Machine-readable (the Manager reads this); no key in it.")

    add = sub.add_parser("add", help="Store a provider's key in the vault (hidden prompt; never argv).")
    add.add_argument("provider")
    add.add_argument("--key-stdin", action="store_true", help="Read the key from stdin instead of prompting.")
    add.add_argument("--json", action="store_true", help="Answer with one JSON object (the Manager reads this).")

    remove = sub.add_parser("remove", help="Delete a provider's key from the vault.")
    remove.add_argument("provider")
    remove.add_argument("--json", action="store_true", help="Answer with one JSON object.")

    test = sub.add_parser("test", help="Check that the stored key authenticates (lists models).")
    test.add_argument("provider")
    test.add_argument("--chat", action="store_true", help="Also send a one-word prompt.")
    test.add_argument("--model", default=None)

    models = sub.add_parser("models", help="List the model ids this key can see.")
    models.add_argument("provider")

    sub.add_parser("import-env", help="Copy NPDEV_EXTERNALAI_*_API_KEY environment variables into the vault.")

    export = sub.add_parser("export-env", help="Write chosen providers' keys into <app>/secrets/agent-proxy.env.")
    export.add_argument("--app", required=True, metavar="DIR")
    export.add_argument("--provider", action="append", required=True, dest="providers")


def run(args) -> int:
    command = getattr(args, "providers_command", None)
    vault = Vault()
    try:
        if command == "list":
            listing = list_providers(vault)
            if args.json:
                print(json.dumps(listing, indent=2))
                return 0
            for row in listing["providers"]:
                source = "n/a (no key needed)" if row["keySource"] == "none-needed" else row["keySource"]
                print(f"{row['id']:<11} {row['kind']:<6} key={source:<22} {row['name']}")
            status = listing["store"]
            print(f"\nkey store: {status['store']} ({status['vault']})" if status["available"]
                  else f"\nkey store: none -- {status['reason']}")
            return 0
        if command == "add":
            entry = get_provider(args.provider)
            if not entry.get("keyEnvVar"):
                raise ProviderError(f"{args.provider} needs no key")
            if entry["kind"] == "paid":
                print(f"NOTE: {args.provider} is a PAID provider; the benchmark will refuse it without --allow-paid.",
                      file=sys.stderr if args.json else sys.stdout)
            key = (sys.stdin.readline().strip() if args.key_stdin
                   else getpass.getpass(f"{entry['name']} API key (input hidden; get one at {entry['signupUrl']}): ").strip())
            vault.set(args.provider, key)
            if args.json:
                print(json.dumps({"ok": True, "provider": args.provider, "store": vault.store.name}))
            else:
                print(f"stored {args.provider} in the {vault.store.name} store (current user; index {vault.path})")
            return 0
        if command == "remove":
            removed = vault.remove(args.provider)
            if args.json:
                print(json.dumps({"ok": True, "provider": args.provider, "removed": removed}))
            else:
                print("removed" if removed else "not in the vault")
            return 0
        if command in ("test", "models"):
            key = resolve_key(args.provider, vault)
            if command == "models":
                print("\n".join(list_models(args.provider, key)))
                return 0
            result = check_provider(args.provider, key, args.model, args.chat)
            print(json.dumps(result, indent=2))
            return 0 if result["ok"] else 1
        if command == "import-env":
            ids = import_env(vault)
            print("imported: " + (", ".join(ids) if ids else "nothing (no NPDEV_EXTERNALAI_*_API_KEY set)"))
            return 0
        if command == "export-env":
            print(json.dumps(export_env(Path(args.app), args.providers, vault), indent=2))
            return 0
    except ProviderError as exc:
        if getattr(args, "json", False) or command in ("test", "export-env"):  # JSON-answering commands
            print(json.dumps({"ok": False, "error": str(exc)}))
        else:
            print(f"error: {exc}", file=sys.stderr)
        return 2
    print("usage: npdev providers {list,add,remove,test,models,import-env,export-env}", file=sys.stderr)
    return 2
