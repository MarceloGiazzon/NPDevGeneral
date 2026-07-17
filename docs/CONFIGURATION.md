# Configuration reference (LNCH-22)

Every generated FinalApp validates its own configuration at startup
(`StartupValidator`/`DatabaseIdentityStartupValidator`/`StrictExecutionValidator`) and fails fast
with a message pointing at this document if something's missing or contradictory. **The section
headings below use the exact anchor IDs those error messages link to** — do not rename them.

## Mode-and-profile contract

Two related but genuinely separate knobs control what a generated app does at runtime — mixing
them up is the single most common startup failure:

- **`npdev.runtime.mode`** (`inproc` | `postgres`, default `inproc`) — checked by
  `StartupValidator`. Must agree with whether the Spring profile `postgres` is active: setting
  `npdev.runtime.mode=postgres` without activating the `postgres` Spring profile (or vice versa)
  fails startup immediately with a message telling you which side is missing. These are
  deliberately independent knobs (the Spring profile selects which `application-*.properties`
  file loads; `runtime.mode` selects which adapter beans get wired) — the platform is checking
  that you set both consistently, not deriving one from the other.
- **`npdev.storage.mode`** (`in-memory` | `jdbc`, default `in-memory`) — a separate switch
  (`NpdevRuntimeModeConfig`) that picks each adapter bean (event store, flow-instance store,
  audit log, concept store, trace store, etc.). Not directly validated against
  `npdev.runtime.mode`, but in practice a Postgres deployment needs both `npdev.runtime.mode=
  postgres` and `npdev.storage.mode=jdbc` set together.
- **`npdev.database.engine`** — baked in at *generation* time from the model's `db.definition.json`
  (`InMemory`/`H2Local`/`H2Server`/`Postgres`), not swappable via environment variables at
  runtime. Setting `npdev.runtime.mode=postgres` against an app generated with a non-Postgres
  engine will still fail — there's no DataSource bean to satisfy it, regardless of what's in your
  `.env`.

**Gotcha: environment-variable name mangling.** Spring Boot's relaxed env-var binding strips
hyphens from property names entirely rather than mapping them to underscores. `npdev.auth.
api-keys` binds from the environment variable `NPDEV_AUTH_APIKEYS` — **not** the more intuitive
`NPDEV_AUTH_API_KEYS`, which silently no-ops (the property stays unset, and you get a confusing
"api-keys must define at least one mapping" error even though you *did* set something, just under
the wrong name). Any hyphenated `npdev.*` property has this same trap — when in doubt, check the
generated `docker-compose.yml` for the exact env var name the platform itself uses.

## Request-and-runtime safety limits

All default to a sane value and must be `> 0` if you override them (`StartupValidator`):

| Property | Default | What it limits |
|---|---|---|
| `npdev.api.max-body-bytes` | `262144` | Max HTTP request body size accepted. |
| `npdev.api.max-json-depth` | `128` | Max nesting depth of a JSON request body. |
| `npdev.capability.circuit.open-after-failures` | `5` | Failures before a capability's circuit breaker opens. |
| `npdev.capability.circuit.open-seconds` | `30` | How long an open circuit stays open before retrying. |
| `npdev.capability.bulkhead.max-concurrent-default` | `8` | Max concurrent in-flight calls per capability. |
| `npdev.capability.idempotency.max-bytes` | `16384` | Max stored size of an idempotency-key response body. |

## Scheduler settings

Only checked when `npdev.scheduler.enabled` (default `true`) — see `docs/SCHEDULED_FLOWS.md` for
what a scheduled flow actually does.

| Property | Default | Notes |
|---|---|---|
| `npdev.scheduler.enabled` | `true` | Set `false` to disable cron-triggered flows entirely. |
| `npdev.scheduler.batch-limit` | falls back to `npdev.resume.limit`, then `1000` | Must be `> 0` when the scheduler is enabled. |
| `npdev.scheduler.tick-millis` | falls back to `npdev.resume.pollMs`, then `2000` | Must be `> 0` when the scheduler is enabled. |

The scheduler also hard-requires a real `EventStore` and `FlowInstanceStore` bean (not the
`FlowInstanceStore.noop()` stub) — this normally follows automatically from `npdev.storage.mode`,
not something you set directly.

## Authentication

Only checked when `npdev.auth.enabled` (default `true`).

- **`npdev.auth.mode`** (`apikey` | `jwt`, default `apikey`).

### `apikey` mode

- **`npdev.auth.api-keys`** — required, must contain at least one `key=tenantOrRole` mapping.
  Remember the env-var name-mangling gotcha above: the environment variable is
  `NPDEV_AUTH_APIKEYS`, not `NPDEV_AUTH_API_KEYS`.

### `jwt` mode

Two *different* consumers each require their own key path — missing either one breaks something,
but only one of them fails with a message pointing here:

| Property | Required by | Validated at startup with a clear message? |
|---|---|---|
| `npdev.auth.jwt.issuer` | token verification (`JwtBearerAuthFilter`) | Yes — `StartupValidator` |
| `npdev.auth.jwt.audience` | token verification | Yes — `StartupValidator` |
| `npdev.auth.jwt.public-key-path` | token verification | Yes — `StartupValidator` |
| `npdev.auth.jwt.private-key-path` | token *signing* (`LoginController`, mints new tokens on login) | **No** — missing this fails with a raw Spring placeholder-resolution error at bean creation, not a docs-linked message. If login is broken but everything else about JWT config looks right, check this property first. |
| `npdev.auth.jwt.expiry-seconds` | token signing | No (defaults to `28800`, i.e. 8 hours) |

`StartupValidator` also rejects obviously-placeholder values for `issuer`/`audience`/
`public-key-path` — anything containing `example.com`, `your-auth-provider`, `changeme`,
`change-me`, `replace-me`, `set-me`, `<`, or `todo` is treated as "you forgot to fill this in,"
not a real value.

Login also needs to know which table/columns hold credentials (defaults match the identity pack's
own schema, override only if you're bonding to a differently-named concept — see
`docs/PASSWORD_RESET.md`):

| Property | Default |
|---|---|
| `npdev.auth.login.credential-table` | `usuarios` |
| `npdev.auth.login.credential-user-id-column` | `user_id` |
| `npdev.auth.login.credential-password-column` | `senha_hash` |

## Postgres-mode required variables

Only checked when running in Postgres mode (see "Mode-and-profile contract" above).

- `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` — all
  required; no DataSource bean means startup fails immediately with a message telling you which
  is missing.
- Beyond presence, `StartupValidator` also runs a live connectivity check (`SELECT 1`) and
  confirms Flyway actually has a schema-history table with at least one successful migration —
  a reachable-but-empty database (e.g. a fresh container nobody ran migrations against yet) fails
  just as loudly as an unreachable one.
- Separately, `DatabaseIdentityStartupValidator` checks that the database you actually connected
  to matches what the model's `db.definition.json`/`resolved-db-plan.json` expected (by name) —
  this catches "pointed at the wrong Postgres instance" even when the connection itself succeeds.
  Set `npdev.trial.database-override=true` to deliberately skip this check for local/trial
  profiles where you intentionally want a different database than what was resolved at generation
  time.

## Strict execution (the `npdev-generated/` hash guard)

Not part of the mode/auth/postgres contract above, but its own set of startup-time checks — see
`docs/architecture/APP_UPGRADE_CONTRACT.md` for the full mechanism (SHA-256 hash of the entire
`npdev-generated/` tree, checked on every boot in governed mode).

| Property | Default | Notes |
|---|---|---|
| `npdev.strict-execution.enabled` | `true` | Cannot be set `false` while `npdev.execution.mode=governed` — startup refuses that combination outright. |
| `npdev.strict-execution.generated-root` | `${user.dir}/npdev-generated` | The directory whose contents get hashed and verified. |
| `npdev.execution.mode` | `governed` | `governed` or `relaxed`. `relaxed` (used by the `dev` profile) skips the hash check entirely — never use it in a real deployment. |
| `npdev.runtime.surface-profile` | `supported-core` | In governed mode, must be exactly `supported-core`. |
| `npdev.runtime.supported-surface-enforced` | `true` | Must stay `true` in governed mode. |

**Never hand-edit a file under `npdev-generated/`** — see
`docs/architecture/APP_UPGRADE_CONTRACT.md` for what's platform-owned vs. app-owned, and where
your own customizations (`web/` assets) actually belong instead.

## File storage / email (adapter-specific, not validated by `StartupValidator`)

These gate which capability adapter gets bound, not checked by the validators above — an unset
value here fails later, at first use, not at boot:

| Property (env var) | Purpose |
|---|---|
| `NPDEV_FILESTORE_PROVIDER` | `inproc` (default) or `objectstore` (S3/MinIO-compatible). |
| `NPDEV_FILESTORE_OBJECTSTORE_BUCKET` / `_ENDPOINT` / `_REGION` / `_ACCESSKEYID` / `_SECRETACCESSKEY` | Required when `NPDEV_FILESTORE_PROVIDER=objectstore`. See `docs/DEPLOYMENT.md`. |
| `NPDEV_MAIL_SMTP_HOST` | SMTP host when the `mail-smtp` adapter is bound instead of `mail-inproc`. See `docs/EMAIL_NOTIFICATIONS.md`. |

## Where these come from

Every property above ultimately resolves through Spring's normal
`application.properties`/`application-<profile>.yml`/environment-variable layering. Generated
apps ship several profile files (`application-default`, `application-dev`, `application-external-
beta`, `application-wmsoffice`-style examples, etc.) — check
`NPDevRuntimeHost/src/main/resources/application*.{properties,yml}` for what a specific profile
already sets before assuming you need to set something yourself.
