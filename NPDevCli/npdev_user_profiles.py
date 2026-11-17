"""User profiles: who is using NPDev on this machine.

A user profile is the container for everything that belongs to one person. Today it carries two things:

  * keys   -- the person's own provider keys, stored through `npdev providers add <provider> --account <id>`
              (the vault id is `<provider>:<id>`; nothing secret is ever written to this file);
  * aiRoles -- the AI workloads (a feature name: bench, review, tester, ...) that this person's keys pay for.

It is deliberately wider than AI: paths, theme, accessibility and a verified identity (email, password) are meant
to live here later, so unknown keys of a profile are kept untouched on every write. Using NPDev without a profile
stays possible (anonymous: the provider's main key, no role routing); a future identity step may limit that.

Which key a call uses (first match wins):
  1. an explicit account given by the caller;
  2. the ACTIVE profile (NPDEV_AI_USER, else the file's `active`) -- every call on this machine is that person's;
  3. the OWNER of the call's role: the profile whose aiRoles lists the feature;
  4. the account named by the AI profile itself (the existing `account` field);
  5. none: the provider's main key.
An active profile that does not exist is an error, never a silent fallback to the main key: that would spend
somebody else's quota. A role belongs to exactly one profile, so two workloads never share a quota by accident.

File: `user-profiles.json` beside the key vault, %APPDATA%/NPDev (NPDEV_USER_PROFILES overrides the location).
"""
from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path

SCHEMA_VERSION = "npdev-user-profiles.v1"
ACTIVE_ENV = "NPDEV_AI_USER"
PATH_ENV = "NPDEV_USER_PROFILES"
ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")


class UserProfileError(Exception):
    """The profiles file is unreadable or the request is not allowed. Never carries a key."""


def path(environ=None) -> Path:
    environ = os.environ if environ is None else environ
    override = (environ.get(PATH_ENV) or "").strip()
    if override:
        return Path(override)
    # Like the key vault (npdev_providers) and unlike ai.json: NPDEV_AI_CONFIG, which the bench points at a run
    # folder, must not move the profiles away from the keys they own.
    base = environ.get("APPDATA") or environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "NPDev" / "user-profiles.json"


def read(file: Path | None = None, environ=None) -> dict:
    file = file or path(environ)
    if not file.is_file():
        return {"schemaVersion": SCHEMA_VERSION, "active": None, "profiles": {}}
    try:
        data = json.loads(file.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise UserProfileError(f"cannot read {file} ({type(exc).__name__})") from None
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA_VERSION or not isinstance(data.get("profiles"), dict):
        raise UserProfileError(f"{file} is not an {SCHEMA_VERSION} file")
    data.setdefault("active", None)
    return data


def write(document: dict, file: Path | None = None, environ=None) -> Path:
    file = file or path(environ)
    document = {**document, "schemaVersion": SCHEMA_VERSION}
    file.parent.mkdir(parents=True, exist_ok=True)
    handle, tmp = tempfile.mkstemp(dir=file.parent, prefix=".user-profiles-", suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as out:
            json.dump(document, out, indent=2, sort_keys=True)
            out.write("\n")
        os.replace(tmp, file)
    except OSError as exc:
        Path(tmp).unlink(missing_ok=True)
        raise UserProfileError(f"cannot write {file} ({type(exc).__name__})") from None
    return file


def check_id(profile_id: str) -> None:
    if not ID_PATTERN.match(profile_id or ""):
        raise UserProfileError(f"profile id {profile_id!r} must be 1-32 characters: a-z, 0-9, '-' or '_', starting with a letter or digit")


def _profile(document: dict, profile_id: str) -> dict:
    entry = document["profiles"].get(profile_id)
    if entry is None:
        known = ", ".join(sorted(document["profiles"])) or "none yet"
        raise UserProfileError(f"no user profile {profile_id!r} (known: {known}); add it with `npdev ai users add {profile_id}`")
    return entry


def owner_of(document: dict, role: str) -> str | None:
    owners = [name for name, entry in sorted(document["profiles"].items()) if role in (entry.get("aiRoles") or [])]
    if len(owners) > 1:   # a hand-edited file; refuse to guess whose quota pays
        raise UserProfileError(f"role {role!r} is listed by several profiles ({', '.join(owners)}): keep it in one")
    return owners[0] if owners else None


def require_profile(document: dict, profile_id: str) -> None:
    """Raise unless the profile exists (used before storing a key under its name)."""
    _profile(document, profile_id)


def add(document: dict, profile_id: str, display_name: str | None = None) -> None:
    check_id(profile_id)
    if profile_id in document["profiles"]:
        raise UserProfileError(f"user profile {profile_id!r} exists")
    document["profiles"][profile_id] = {"displayName": display_name or profile_id, "aiRoles": []}


def remove(document: dict, profile_id: str) -> None:
    _profile(document, profile_id)
    del document["profiles"][profile_id]
    if document.get("active") == profile_id:
        document["active"] = None


def use(document: dict, profile_id: str | None) -> None:
    """Make a profile the active one; None goes back to anonymous (roles decide)."""
    if profile_id is not None:
        _profile(document, profile_id)
    document["active"] = profile_id


def assign_role(document: dict, profile_id: str, role: str) -> None:
    import npdev_ai_config as aicfg

    try:
        aicfg.check_feature(role)
    except aicfg.AiConfigError as exc:
        raise UserProfileError(str(exc).replace("feature", "role")) from None
    entry = _profile(document, profile_id)
    holder = owner_of(document, role)
    if holder and holder != profile_id:
        raise UserProfileError(f"role {role!r} already belongs to {holder!r}: run `npdev ai users unassign {role}` first")
    roles = entry.setdefault("aiRoles", [])
    if role not in roles:
        roles.append(role)


def unassign_role(document: dict, role: str) -> str | None:
    holder = owner_of(document, role)
    if holder:
        document["profiles"][holder]["aiRoles"] = [r for r in document["profiles"][holder]["aiRoles"] if r != role]
    return holder


def active_name(document: dict, environ=None) -> str | None:
    environ = os.environ if environ is None else environ
    name = (environ.get(ACTIVE_ENV) or "").strip().lower() or document.get("active")
    if name:
        _profile(document, name)   # fail closed: an unknown active profile is never ignored
    return name or None


def resolve_account(feature: str | None, document: dict | None = None, environ=None) -> tuple[str | None, str | None]:
    """(account, why) for a call labelled `feature`: the profile whose keys pay for it, or (None, None)."""
    document = read(environ=environ) if document is None else document
    name = active_name(document, environ)
    if name:
        return name, f"active profile {name}"
    if feature:
        owner = owner_of(document, feature)
        if owner:
            return owner, f"role {feature} belongs to {owner}"
    return None, None
