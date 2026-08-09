"""The one place that knows which database engines exist and how honest we can be about each.

WHY THIS MODULE EXISTS
----------------------
storage/FULL_SUPPORT_PLAN.md W5. Before it, `npdev init` had no `--engine` flag at all: a user who
wanted MySQL had to hand-edit `db.definition.json`, which is exactly the "you must know the internals"
experience the Manager exists to remove. And the Manager -- measured, `NPDevManager/src/*.rs` -- had
no engine awareness whatsoever; it shells to `npdev init`, `npdev setup`, `npdev dev` and
`npdev doctor --json`, so every engine gap is fixable in the CLI first and the Manager inherits it.

**The support status is the load-bearing part.** BREAKING.md says MySQL and SQL Server are
"selectable but NOT supported": the dialects exist, are registered, and pass every conformance vector
that can run -- and no generated APPLICATION had ever booted on either. A dropdown that silently
offers MySQL is the silent-answer defect in UI form, so the status is attached to the engine itself
and surfaced at the point of choice rather than left in a changelog.

    W5.3, requirement 1: the Manager's engine picker is "driven by the CLI's engine list, not a
    hardcoded copy". `npdev engines --json` is that list.

WHAT CHANGES A STATUS
---------------------
`supported` is not a mood. An engine moves from `experimental` to `supported` when a generated app
**boots, serves and persists** on it in CI -- exit criteria E3/E4/E5 -- not when its dialect passes
unit tests. `scripts/quality/check-engine-support-honesty.py` fails the gate when this table and
BREAKING.md disagree, so the claim cannot drift in either direction.
"""
from __future__ import annotations

# Engine keys are the CLI's lower-case spelling. `externalName` is DatabaseEngine.externalName --
# the value db.definition.json's database.engine actually takes, and the one the generator parses.
# `provider` is config.json's scenario-level database.provider. Three spellings for one thing is not
# ideal, but they are all real and all consumed; recording the mapping once beats each caller
# guessing, which is how an app ends up with a db.definition the generator accepts and a config the
# schema rejects.
ENGINES: dict[str, dict] = {
    "h2local": {
        "externalName": "H2Local",
        "provider": "h2-local",
        "port": 0,
        "server": False,
        "status": "supported",
        "summary": "File-backed H2 in the app's own process. No server to install. The default.",
    },
    "h2server": {
        "externalName": "H2Server",
        "provider": "h2-server",
        "port": 9092,
        "server": True,
        "status": "supported",
        "summary": "H2 in TCP server mode, so more than one process can share the database.",
    },
    "inmemory": {
        "externalName": "InMemory",
        "provider": "in-memory",
        "port": 0,
        "server": False,
        "status": "supported",
        "summary": "Nothing is persisted. Everything is lost when the app stops -- tests and demos only.",
    },
    "postgres": {
        "externalName": "Postgres",
        "provider": "postgres",
        "port": 5432,
        "server": True,
        "status": "supported",
        "summary": "PostgreSQL. The engine NPDev's schema engine was built against.",
    },
    "mysql": {
        "externalName": "MySQL",
        "provider": "mysql",
        "port": 3306,
        "server": True,
        "status": "supported",
        "supportedSince": "2026-08-09, CI run 31296993259 -- app proof 4/4 and Tier C 4/4",
        "summary": "MySQL 8.4+. Requires utf8mb4 -- the legacy three-byte 'utf8' silently mangles "
                   "anything outside the BMP.",
        "caveat": "MySQL COMMITS IMPLICITLY ON DDL, so a migration that fails partway CANNOT be "
                  "rolled back: earlier steps are already permanent. NPDev reports this truthfully "
                  "(PartialApplicationTruth) rather than claiming a rollback, but it is a real "
                  "operational difference from Postgres.",
    },
    "sqlserver": {
        "externalName": "SqlServer",
        "provider": "sqlserver",
        "port": 1433,
        "server": True,
        "status": "supported",
        "supportedSince": "2026-08-09, CI run 31296993259 -- app proof 4/4 and Tier C 4/4",
        "summary": "Microsoft SQL Server 2022+.",
        "caveat": "SQL Server has no suffix row cap (TOP is a prefix), so a few internal existence "
                  "probes take a different path. Text columns are mapped to NVARCHAR so unicode "
                  "survives -- plain VARCHAR is non-Unicode there and loses characters silently.",
    },
}

# What each status means, stated once so every renderer says the same thing.
STATUS_MEANING = {
    "supported": "a generated app boots, serves and persists on this engine, proven in CI",
    "experimental": "the dialect is complete and passes conformance against a real engine, but no "
                    "generated application has been proven end to end on it. Usable; not yet "
                    "something to rely on.",
}


def engine_keys() -> list[str]:
    """CLI spellings, in the order a picker should offer them: safest and most proven first."""
    return ["h2local", "h2server", "postgres", "mysql", "sqlserver", "inmemory"]


def resolve(key: str) -> dict:
    """The engine record for a CLI spelling, or a refusal naming the alternatives.

    Case-insensitive, and it also accepts the `externalName` spelling (`H2Local`) because that is
    what db.definition.json contains -- a user copying the value out of their own file and passing it
    to `--engine` is doing something reasonable.
    """
    normalized = (key or "").strip().lower()
    if normalized in ENGINES:
        return {"key": normalized, **ENGINES[normalized]}
    for candidate, record in ENGINES.items():
        if record["externalName"].lower() == normalized:
            return {"key": candidate, **record}
    raise ValueError(
        f"unknown engine '{key}'. Known: {', '.join(engine_keys())}. "
        "(The db.definition.json spellings -- H2Local, Postgres, MySQL, SqlServer, ... -- work too.)"
    )


def db_definition_for(key: str, *, database_name: str, host: str | None = None,
                      port: int | None = None, username: str | None = None,
                      password: str | None = None) -> dict:
    """A VALID `db.definition.json` for this engine.

    "Valid" is meant strictly: user-db-definition.schema.json makes host/port/username/password
    conditionally required per engine, so a server engine written without them produces a file that
    fails its own schema -- and, before W6.1, nothing would have told the user until the app failed
    to boot. Defaults are filled for the fields the chosen engine requires and omitted for the ones
    it does not; an unnecessary `host: localhost` on an H2Local app is noise that reads like
    configuration.
    """
    record = resolve(key)
    database: dict = {
        "engine": record["externalName"],
        "databaseName": database_name,
        "createInternalTables": True,
        "createBusinessTables": True,
    }
    if record["server"]:
        database["host"] = host or "localhost"
        database["port"] = int(port) if port else record["port"]
        database["username"] = username if username is not None else _default_user(record["key"])
        database["password"] = password if password is not None else ""
    elif record["key"] == "h2local":
        # H2Local still requires username/password per the schema (they are the H2 file credentials),
        # and `sa` with an empty password is what every H2Local sample in the corpus uses.
        database["username"] = username if username is not None else "sa"
        database["password"] = password if password is not None else ""

    return {
        "database": database,
        "schemaLifecycle": {
            # KeepExistingIfCompatible, not a recreate strategy: a scaffold that silently drops the
            # user's tables on the first model change would be a trap, and this is the first file
            # they will ever own.
            "strategy": "KeepExistingIfCompatible",
            "allowDestructiveRecreate": False,
            "destructiveRecreateConfirmation": "",
            "scope": "NpdevOwnedTablesOnly",
        },
    }


def _default_user(key: str) -> str:
    return {"postgres": "postgres", "mysql": "root", "sqlserver": "sa", "h2server": "sa"}.get(key, "sa")


def honesty_notice(key: str) -> str | None:
    """What must be said AT THE POINT OF CHOICE, or None when there is nothing to warn about.

    Returned rather than printed so the Manager can render it beside its dropdown and the CLI can
    print it -- one sentence, two surfaces, no second copy to fall out of date.
    """
    record = resolve(key)
    if record["status"] == "supported":
        return None
    lines = [
        f"{record['externalName']} is EXPERIMENTAL: {STATUS_MEANING[record['status']]}",
    ]
    if record.get("caveat"):
        lines.append(record["caveat"])
    return " ".join(lines)


def as_json() -> dict:
    """The machine-readable engine list -- what the Manager's picker is built from."""
    return {
        "schemaVersion": "npdev-engine-list.v1",
        "statusMeaning": STATUS_MEANING,
        "engines": [
            {
                "key": key,
                "externalName": ENGINES[key]["externalName"],
                "provider": ENGINES[key]["provider"],
                "defaultPort": ENGINES[key]["port"],
                "needsServer": ENGINES[key]["server"],
                "status": ENGINES[key]["status"],
                "summary": ENGINES[key]["summary"],
                "caveat": ENGINES[key].get("caveat"),
                "honestyNotice": honesty_notice(key),
            }
            for key in engine_keys()
        ],
    }
