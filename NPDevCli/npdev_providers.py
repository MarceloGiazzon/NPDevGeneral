"""`npdev providers` -- NPDev's own store for AI-provider API keys. Stdlib only.

One place holds every provider key, so the key is typed once and then serves both the AI authoring
benchmark (scripts/ai/bench) and every generated app's agent proxy (`npdev providers export-env`).

  * Catalogue  : the merged AI config (npdev_ai_config: NPDevCli/ai-providers.json + the user's ai.json; no
                 keys, ever) -- id, endpoint, protocol, free/paid/local, signup link. A provider the user
                 added gets a key slot like a shipped one.
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

import argparse
import base64
import getpass
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import Callable

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
    """The active providers of the merged AI config. `path` loads that one catalogue file alone, with no
    user overlay."""
    import npdev_ai_config

    try:
        return npdev_ai_config.load(path, use_mine=path is None).active_providers()
    except npdev_ai_config.AiConfigError as exc:
        raise ProviderError(str(exc)) from None


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


def account_key_id(provider_id: str, account: str | None) -> str:
    """The vault id of one stored key: `<provider>` for the default key, `<provider>:<account>` for another
    (owner decision D5: several keys per provider)."""
    return f"{provider_id}:{account}" if account else provider_id


def account_env_var(key_env_var: str, account: str) -> str:
    """NPDEV_EXTERNALAI_<ID>_API_KEY -> NPDEV_EXTERNALAI_<ID>_<ACCOUNT>_API_KEY."""
    return key_env_var[: -len("_API_KEY")] + "_" + account.upper().replace("-", "_") + "_API_KEY"


def resolve_key(provider_id: str, vault: Vault | None = None, environ=None,
                catalogue: list[dict] | None = None, account: str | None = None) -> str | None:
    """Environment variable first (CI / one-off override), then the vault. None for a keyless provider
    or when no key is stored anywhere. With `account`, that account's own variable and vault entry."""
    environ = os.environ if environ is None else environ
    entry = get_provider(provider_id, catalogue)
    var = entry.get("keyEnvVar")
    if not var:
        return None
    if account:
        var = account_env_var(var, account)
    value = (environ.get(var) or "").strip()
    if value:
        return value
    vault = vault or Vault()
    try:
        return vault.get(account_key_id(provider_id, account))
    except VaultUnavailable:
        return None


# --------------------------------------------------------------------------- network
# The wire lives in npdev_ai_client (one client, every protocol); these keep the `npdev providers` verbs.

def list_models(provider_id: str, key: str | None, timeout: float = 30.0,
                opener: Callable = urllib.request.urlopen) -> list[str]:
    import npdev_ai_client

    try:
        return npdev_ai_client.list_models(get_provider(provider_id), key, timeout, opener)
    except npdev_ai_client.AiCallError as exc:
        raise ProviderError(str(exc)) from None


def check_provider(provider_id: str, key: str | None, model: str | None = None, chat: bool = False,
                   timeout: float = 60.0, opener: Callable = urllib.request.urlopen,
                   allow_paid: bool = False) -> dict:
    """Does the key authenticate? With chat=True also send a one-word prompt to `model` (default: the
    catalogue's defaultModel) -- refused for a paid provider unless allow_paid. Returns a result dict;
    the key is never part of it."""
    import npdev_ai_client

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
        if entry["kind"] == "paid" and not allow_paid:
            result.update(ok=False, chatOk=False, error=f"{provider_id} is PAID: a test chat needs --allow-paid")
            return result
        target = npdev_ai_client.Target(entry, chosen, None, max_tokens=16)
        try:
            npdev_ai_client.call(target, key, "Reply with the single word OK", timeout=timeout, backoff=(),
                                 opener=opener)
            result["chatOk"] = True
        except npdev_ai_client.AiCallError as exc:
            result.update(ok=False, chatOk=False, error=f"chat call to {chosen} failed ({exc})")
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
        if not entry.get("keyEnvVar"):
            continue  # a keyless (local) provider needs no line
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


def app_env_vars(app_dir: Path) -> set[str]:
    """The variable NAMES <app>/secrets/agent-proxy.env sets (inside or outside the managed block),
    never a value."""
    target = Path(app_dir) / "secrets" / "agent-proxy.env"
    if not target.is_file():
        return set()
    names = set()
    for line in target.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped and stripped.split("=", 1)[1].strip():
            names.add(stripped.split("=", 1)[0].strip())
    return names


def app_ai_status(app_dir: Path, model_path: Path, vault: Vault, environ=None) -> dict:
    """AIP-4 (4.4): the AI vendors a generated app's model opted into (externalAi.vendors) and, for each,
    whether the app will find a key -- set in its secrets/agent-proxy.env, or not needed -- and whether
    your key store could supply one (`export-env`), and where the key comes from when the Manager starts the
    app (`keyAtManagerStart`: file / vault / env / none-needed / missing; 3.4). Names and sources only,
    never a key."""
    model_path = Path(model_path)
    try:
        model = json.loads(model_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ProviderError(f"cannot read the app's model {model_path} ({type(exc).__name__})") from None
    external = model.get("externalAi") or {}
    sources = {row["id"]: row["keySource"] for row in list_providers(vault, environ)["providers"]}
    in_env = app_env_vars(app_dir)
    catalogue = {entry["id"]: entry for entry in load_catalogue()}
    vendors = []
    for vendor in external.get("vendors") or []:
        vendor_id = str(vendor).strip().lower()
        entry = catalogue.get(vendor_id)
        var = entry.get("keyEnvVar") if entry else None
        known = bool(entry and entry.get("appVendor"))
        app_has_key = None if (known and not var) else (var in in_env if var else False)
        in_store = sources.get(vendor_id, "missing")
        # 3.4: the Manager's Start (`npdev monitor ops run-finalapp`) adds store keys, the file still wins.
        at_start = ("none-needed" if app_has_key is None else "file" if app_has_key
                    else in_store if in_store in ("vault", "env") else "missing")
        vendors.append({"id": vendor_id, "name": entry["name"] if entry else vendor_id, "appVendor": known,
                        "kind": entry.get("kind") if entry else None, "keyEnvVar": var,
                        "keyInApp": app_has_key, "keyInStore": in_store,
                        "ready": known and (app_has_key is None or app_has_key),
                        "keyAtManagerStart": at_start if known else "missing",
                        "readyAtManagerStart": known and at_start != "missing"})
    env_file = Path(app_dir) / "secrets" / "agent-proxy.env"
    return {"app": str(app_dir), "model": str(model_path), "egress": external.get("egress", "denied"),
            "providerSwitch": "NPDEV_EXTERNALAI_PROVIDER" in in_env, "envFile": str(env_file),
            "providerSwitchAtManagerStart": "NPDEV_EXTERNALAI_PROVIDER" in in_env or bool(vendors),
            "envFileExists": env_file.is_file(), "vendors": vendors}


def list_providers(vault: Vault, environ=None) -> dict:
    """The catalogue with, per provider, where its key would come from -- never the key itself."""
    environ = os.environ if environ is None else environ
    stored = vault.ids() if vault.path.exists() else {}
    accounts: dict[str, list[str]] = {}
    for key_id in stored:
        provider_id, sep, account = key_id.partition(":")
        if sep:
            accounts.setdefault(provider_id, []).append(account)
    rows = []
    for entry in load_catalogue():
        var = entry.get("keyEnvVar")
        source = ("none-needed" if not var else "env" if (environ.get(var) or "").strip()
                  else "vault" if entry["id"] in stored else "missing")
        rows.append({"id": entry["id"], "name": entry["name"], "kind": entry["kind"], "keyEnvVar": var,
                     "keySource": source, "addedAt": stored.get(entry["id"]) or None,
                     "signupUrl": entry.get("signupUrl"), "appVendor": entry.get("appVendor"),
                     "accounts": sorted(accounts.get(entry["id"], []))})
    return {"store": vault.store_status(), "providers": rows}


# --------------------------------------------------------------------------- CLI

APP_COMPILED_MODEL = Path("npdev-generated") / "src" / "main" / "resources" / "npdev" / "compiled-model.json"


def app_vendors(app_dir: Path) -> list[str]:
    """The vendors a generated app's model opted into, read from the compiled model the app ships
    (<app>/npdev-generated/.../compiled-model.json). An app with egress denied, or none declared, needs none."""
    path = Path(app_dir) / APP_COMPILED_MODEL
    try:
        external = json.loads(path.read_text(encoding="utf-8")).get("externalAi") or {}
    except (OSError, json.JSONDecodeError) as exc:
        raise ProviderError(f"cannot read the app's compiled model {path} ({type(exc).__name__}); "
                            "name the vendors with --provider") from None
    if external.get("egress", "denied") == "denied":
        return []
    return [str(v).strip().lower() for v in external.get("vendors") or []]


def app_key_env(app_dir: Path, provider_ids: list[str] | None = None, vault: Vault | None = None,
                environ=None) -> tuple[dict, dict]:
    """AIP-3 (3.4, owner decision D3: dev starts only): the environment variables that give a generated app
    its vendors' keys from your key store, plus a report of NAMES only. Nothing is written: the caller puts
    the variables into the one process it starts. Vendors default to the app's own (`app_vendors`); a vendor
    with no key anywhere is reported as missing, not an error -- the app's agent proxy fails closed for it,
    and its secrets/agent-proxy.env, which the launcher loads after this, still wins."""
    ids = provider_ids if provider_ids else app_vendors(app_dir)
    env, keys, missing = {}, [], []
    for provider_id in ids:
        entry = get_provider(provider_id)
        if not entry.get("appVendor"):
            raise ProviderError(f"{provider_id}: generated apps cannot use this provider "
                                f"(supported: {', '.join(e['id'] for e in load_catalogue() if e.get('appVendor'))})")
        if not entry.get("keyEnvVar"):
            continue  # keyless (local)
        try:
            key = resolve_key(provider_id, vault, environ)
        except ProviderError as exc:
            missing.append({"provider": provider_id, "reason": str(exc)})
            continue
        if key:
            env[entry["keyEnvVar"]] = key
            keys.append({"provider": provider_id, "env": entry["keyEnvVar"]})
        else:
            missing.append({"provider": provider_id, "reason": f"no key stored -- run `npdev providers add {provider_id}`"})
    if ids:
        env["NPDEV_EXTERNALAI_PROVIDER"] = "http"
    return env, {"app": str(app_dir), "providers": ids, "keys": keys, "missing": missing}


def describe_key_env(report: dict) -> str:
    """One log-safe line: variable names and provider ids, never a value."""
    if not report["providers"]:
        return "provider keys: none needed (the app declares no AI vendor)"
    loaded = ", ".join(f"{k['env']} ({k['provider']})" for k in report["keys"]) or "none"
    line = f"provider keys from the key store: {loaded}"
    if report["missing"]:
        line += "; missing: " + ", ".join(m["provider"] for m in report["missing"])
    return line


def run_env(app_dir: Path, command: list[str], provider_ids: list[str] | None = None, vault: Vault | None = None,
            environ=None, runner=subprocess.call) -> int:
    """`npdev providers run-env`: start `command` with the app's provider keys in its environment, writing
    no key file. Returns the command's exit code."""
    if not command:
        raise ProviderError("run-env needs a command after `--`")
    base = dict(os.environ if environ is None else environ)
    env, report = app_key_env(app_dir, provider_ids, vault, base)
    print(describe_key_env(report), file=sys.stderr, flush=True)
    return runner(command, env={**base, **env})


def add_arguments(subparsers) -> None:
    parser = subparsers.add_parser(
        "providers", help="Manage AI-provider API keys in NPDev's own vault (one key serves the "
                          "benchmark and every generated app).")
    sub = parser.add_subparsers(dest="providers_command")

    listing = sub.add_parser("list", help="Catalogue of known providers with kind and whether a key is available.")
    listing.add_argument("--json", action="store_true", help="Machine-readable (the Manager reads this); no key in it.")

    add = sub.add_parser("add", help="Store a provider's key in the vault (hidden prompt; never argv).")
    add.add_argument("provider")
    add.add_argument("--account", help="Store a second key for this provider under this name (default: the main key).")
    add.add_argument("--key-stdin", action="store_true", help="Read the key from stdin instead of prompting.")
    add.add_argument("--json", action="store_true", help="Answer with one JSON object (the Manager reads this).")

    remove = sub.add_parser("remove", help="Delete a provider's key from the vault.")
    remove.add_argument("provider")
    remove.add_argument("--account")
    remove.add_argument("--json", action="store_true", help="Answer with one JSON object.")

    test = sub.add_parser("test", help="Check that the stored key authenticates (lists models).")
    test.add_argument("provider")
    test.add_argument("--chat", action="store_true", help="Also send a one-word prompt.")
    test.add_argument("--model", default=None)
    test.add_argument("--account")
    test.add_argument("--allow-paid", action="store_true", help="Permit the one-word test chat on a paid provider.")

    models = sub.add_parser("models", help="List the model ids this key can see.")
    models.add_argument("provider")
    models.add_argument("--account")

    sub.add_parser("import-env", help="Copy NPDEV_EXTERNALAI_*_API_KEY environment variables into the vault.")

    export = sub.add_parser("export-env", help="Write chosen providers' keys into <app>/secrets/agent-proxy.env.")
    export.add_argument("--app", required=True, metavar="DIR")
    export.add_argument("--provider", action="append", required=True, dest="providers")

    run_env_p = sub.add_parser("run-env", help="Start a command with an app's provider keys in its environment, "
                                               "writing no key file (dev starts; e.g. -- pwsh _ops/Start-App.ps1).")
    run_env_p.add_argument("--app", required=True, metavar="DIR")
    run_env_p.add_argument("--provider", action="append", dest="providers",
                           help="Vendor to supply (repeatable; default: the app's own externalAi.vendors).")
    run_env_p.add_argument("run_command", nargs=argparse.REMAINDER, metavar="-- COMMAND",
                           help="The command to start, and its arguments.")


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
            vault.set(account_key_id(args.provider, args.account), key)
            if args.json:
                print(json.dumps({"ok": True, "provider": args.provider, "account": args.account,
                                  "store": vault.store.name}))
            else:
                print(f"stored {args.provider} in the {vault.store.name} store (current user; index {vault.path})")
            return 0
        if command == "remove":
            get_provider(args.provider)
            removed = vault.remove(account_key_id(args.provider, args.account))
            if args.json:
                print(json.dumps({"ok": True, "provider": args.provider, "account": args.account, "removed": removed}))
            else:
                print("removed" if removed else "not in the vault")
            return 0
        if command in ("test", "models"):
            key = resolve_key(args.provider, vault, account=args.account)
            if command == "models":
                print("\n".join(list_models(args.provider, key)))
                return 0
            result = check_provider(args.provider, key, args.model, args.chat, allow_paid=args.allow_paid)
            print(json.dumps(result, indent=2))
            return 0 if result["ok"] else 1
        if command == "import-env":
            ids = import_env(vault)
            print("imported: " + (", ".join(ids) if ids else "nothing (no NPDEV_EXTERNALAI_*_API_KEY set)"))
            return 0
        if command == "run-env":
            argv = args.run_command[1:] if args.run_command[:1] == ["--"] else args.run_command
            return run_env(Path(args.app), argv, args.providers, vault)
        if command == "export-env":
            print(json.dumps(export_env(Path(args.app), args.providers, vault), indent=2))
            return 0
    except ProviderError as exc:
        if getattr(args, "json", False) or command in ("test", "export-env"):  # JSON-answering commands
            print(json.dumps({"ok": False, "error": str(exc)}))
        else:
            print(f"error: {exc}", file=sys.stderr)
        return 2
    print("usage: npdev providers {list,add,remove,test,models,import-env,export-env,run-env}", file=sys.stderr)
    return 2
