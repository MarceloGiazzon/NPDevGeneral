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

**Gotcha: environment-variable name mangling — narrower than it first looks (REG-154).** Spring
Boot's relaxed env-var binding strips hyphens from property names, so `npdev.auth.api-keys` binds
from `NPDEV_AUTH_APIKEYS`. The generated `docker-compose.yml`/`.env.example` use exactly this
canonical form, so treat it as the one to set. **This note used to claim the more intuitive
`NPDEV_AUTH_API_KEYS` (underscore before `KEYS`) "silently no-ops" — tested directly against a
running app (Windows/PowerShell, Java 17) on 2026-08-13 and found FALSE for this property: both
spellings correctly override the default.** Likely mechanism (not independently verified against
Spring Framework source): `npdev.auth.api-keys` is read via a plain `@Value("${npdev.auth.
api-keys:}")`, not a `@ConfigurationProperties` binding, and the underlying
`SystemEnvironmentPropertySource` tries several name transformations on its own, independently of
the documented relaxed-binding rule. Not re-checked on Linux, so a real platform-specific gap for
THIS property can't be fully ruled out — and other hyphenated `npdev.*` properties (e.g. the JWT
key-path ones below) have not been re-tested at all, so treat their own "silently no-ops" claims as
still standing until someone checks. When in doubt, check the generated `docker-compose.yml` for
the exact env var name the platform itself uses, and prefer it regardless of which spelling
happens to also work.

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

- **`npdev.auth.api-keys`** — required, must contain at least one `key=tenantOrRole` mapping. The
  canonical environment variable is `NPDEV_AUTH_APIKEYS` (see the gotcha above — `NPDEV_AUTH_API_KEYS`
  also works for this specific property, but prefer the canonical form).

### `jwt` mode

`jwt` mode has **two legitimate deployment shapes**, and which keys you need depends on which:

- **Full issuer** — this instance both *mints* its own tokens (via `POST /api/auth/login`) and
  *validates* them. Needs **both** the private (signing) and public (verification) key.
- **Verify-only** — this instance only *validates* externally-issued tokens (e.g. from a central
  identity provider) and never mints its own. The `external-beta` profile is exactly this. Needs
  **only the public key**; the login endpoint returns `503 token_issuance_unavailable` if called.

| Property | Required by | Startup validation (REG-9, 2026-07-21) |
|---|---|---|
| `npdev.auth.jwt.issuer` | token verification (`JwtBearerAuthFilter`) | Required in jwt mode; placeholder-rejected. |
| `npdev.auth.jwt.audience` | token verification | Required in jwt mode; placeholder-rejected. |
| `npdev.auth.jwt.public-key-path` | token verification (both shapes) | Required; placeholder-rejected; **the key file must actually be readable** or startup fails fast with a `#authentication`-linked message (was: opaque per-request `jwt_public_key_not_found`). |
| `npdev.auth.jwt.private-key-path` | token *signing* (`LoginController`) | **Optional** — blank = verify-only. If set, the file **must be readable** or startup fails fast with a `#authentication`-linked message. (Before REG-9 a set-but-missing path crashed the whole context with a raw Spring placeholder / `NoSuchFileException` at bean creation; a blank path *also* crashed, making verify-only impossible.) |
| `npdev.auth.jwt.expiry-seconds` | token signing | No (defaults to `28800`, i.e. 8 hours) |
| `npdev.auth.jwt.reject-tokens-without-tv-after` | token revocation (both claim→context paths) | **Optional (default off).** An ISO-8601 instant (e.g. `2026-08-01T00:00:00Z`); once reached, legacy JWTs carrying **no `tv` (token-version) claim** are rejected. Set it to `≥ max token lifetime` after the `tv` feature shipped, so no legitimate tv-less token can still exist. Unset/blank = today's lenient behavior. A malformed value fails startup fast. (REG-23) |

`StartupValidator` also rejects obviously-placeholder values for `issuer`/`audience`/
`public-key-path` — anything containing `example.com`, `your-auth-provider`, `changeme`,
`change-me`, `replace-me`, `set-me`, `<`, or `todo` is treated as "you forgot to fill this in,"
not a real value.

**Supplying keys by environment variable (no key file baked into the image).** The generated
Docker Compose / `.env.example` expose the key paths as env vars so a container can mount its keys
as secrets. Mind the **same relaxed-binding hyphen-stripping** as `NPDEV_AUTH_APIKEYS`: the
property `npdev.auth.jwt.private-key-path` binds from `NPDEV_AUTH_JWT_PRIVATEKEYPATH` (hyphens
removed — **no** underscore before `KEYPATH`), *not* the intuitive `NPDEV_AUTH_JWT_PRIVATE_KEY_PATH`
(which silently no-ops). Likewise `NPDEV_AUTH_JWT_PUBLICKEYPATH`. `issuer`/`audience` have no
internal hyphens, so `NPDEV_AUTH_JWT_ISSUER` / `NPDEV_AUTH_JWT_AUDIENCE` bind as written.

| Property | Environment variable |
|---|---|
| `npdev.auth.jwt.issuer` | `NPDEV_AUTH_JWT_ISSUER` |
| `npdev.auth.jwt.audience` | `NPDEV_AUTH_JWT_AUDIENCE` |
| `npdev.auth.jwt.public-key-path` | `NPDEV_AUTH_JWT_PUBLICKEYPATH` |
| `npdev.auth.jwt.reject-tokens-without-tv-after` | `NPDEV_AUTH_JWT_REJECTTOKENSWITHOUTTVAFTER` |
| `npdev.auth.jwt.private-key-path` | `NPDEV_AUTH_JWT_PRIVATEKEYPATH` |

Login also needs to know which table/columns hold credentials (defaults match the identity pack's
own schema, override only if you're bonding to a differently-named concept — see
`docs/PASSWORD_RESET.md`):

| Property | Default |
|---|---|
| `npdev.auth.login.credential-table` | `usuarios` |
| `npdev.auth.login.credential-user-id-column` | `user_id` |
| `npdev.auth.login.credential-password-column` | `senha_hash` |

### ControlPanel Super User key

The Super User key is **issued, not supplied**: `SuperUserBootstrapper` generates it on first boot
(when none is active), persists it hashed, and writes the raw value once to `SUPER_USER_KEY.txt` in
the working directory. There is deliberately **no** env var to seed a known key at boot (REG-9 /
Q1 default 2026-07-21 — a WONTFIX preserving the issued-not-supplied trust model; revisit if an
operator-supplied key is ever wanted). Retrieve the issued key from the file / mounted volume after
first boot; see `docs/DEPLOYMENT.md`.

- **`npdev.superuser.force-reissue`** (default `false`) — set `true` for one boot to revoke the
  current key and issue a fresh one (see `Reissue-SuperUserKey.ps1`). **Relaxed-binding gotcha,
  same as the JWT/apikey ones above:** the environment variable is `NPDEV_SUPERUSER_FORCEREISSUE`
  (hyphen stripped — no underscore before `REISSUE`), *not* `NPDEV_SUPERUSER_FORCE_REISSUE`.

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

## Persistence capability binding (checked at boot)

A model whose flows persist data — `createConcept`/`updateConcept`/`saveConcept` steps, or a direct
`persistence.*` capability call — must declare a `persistence` binding in `model.json`'s `bindings`
array (see `docs/NPDEV_USER_MANUAL.md` section 5.1 for the `capabilities`/`bindings` block shape). A
binding can legitimately come from a built-in pack instead of the model's own `bindings` array, so
model *validation* cannot assume its absence is an error — but a generated app that reaches runtime
with flows that persist and **no bound adapter for `persistence`** (neither `persistence-inproc` for
dev nor `persistence-postgres` for prod) refuses to boot with an `IllegalStateException` naming the
offending flow, instead of failing opaquely on the first `createConcept` call (LEDGER-1).

## Identity pack freshness (checked at boot)

An app can carry its **own copy** of a built-in pack (a local `$ref` under its model root) instead of
composing `NPDevContract/packs/<alias>/pack.json` fresh at every generation (the normal path, via
`BuiltinPackComposer`). When the platform's pack later gains a field that platform code then reads
**unconditionally**, every app whose copy predates that addition breaks at runtime — and breaks
*misleadingly*: a missing-column `SQLException` gets caught by generic error handling and reported as
something else entirely (REG-39 — a stale copy of the identity pack's `token_version` column made
`LoginController` fail every login with `invalid_credentials`, not a schema error).

If the generated model declares an `identity::User` concept (i.e. this app uses the built-in identity
pack, by whatever mechanism), `StartupValidator` requires it to carry the `tokenVersion` field the
platform's identity pack has had since LNCH-4. A stale copy refuses to boot with an
`IllegalStateException` naming the missing field and the fix (regenerate so the app composes the
platform's current pack, or update a locally-committed copy to match) — instead of surfacing later as an
unrelated-looking auth failure. An app that doesn't use the identity pack at all has no `identity::User`
concept and skips this check entirely.

## Where these come from

Every property above ultimately resolves through Spring's normal
`application.properties`/`application-<profile>.yml`/environment-variable layering. Generated
apps ship several profile files (`application-default`, `application-dev`, `application-prod`,
`application-external-beta`, `application-wmsoffice`-style examples, etc.) — check
`NPDevRuntimeHost/src/main/resources/application*.{properties,yml}` for what a specific profile
already sets before assuming you need to set something yourself.

**`dev` is no longer the implicit Spring default profile (R7 Stage B, SEC-1).** Booting the jar
with no `--spring.profiles.active` flag at all now activates Spring's own reserved `default`
profile (`application-default.properties` — governed execution mode, no seeded auth keys) rather
than silently running as `dev`, so an un-profiled boot fails closed at `#authentication` instead of
quietly picking up `application-dev.yml`'s known key. Every supported launcher passes an explicit
profile: `_ops/Run-FinalApp.ps1` defaults to `-Profile dev` (pass `-Profile prod` to boot against
`application-prod.properties` instead), and `Build-NpdevApp.ps1`/`Build-ClaudeApp.ps1` already pass
`--spring.profiles.active=dev,step0,trial` explicitly.
