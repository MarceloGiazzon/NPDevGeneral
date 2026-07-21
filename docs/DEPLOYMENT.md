# Deploying a generated FinalApp (LNCH-7)

Every generated FinalApp ships with a `Dockerfile`, `docker-compose.yml`, `.env.example`, and
`deploy/Caddyfile` at its root (emitted by `DockerDeploymentEmitter`). This is the "run this
outside a Windows dev machine" answer: containerized packaging + orchestration, config entirely
via environment variables, nothing environment-specific baked into the image.

## Two shapes, chosen by the model's `db.definition.json` engine

- **`db.engine: Postgres`** → the **Postgres-first** compose: an `app` service + a `postgres`
  service (`postgres:16-alpine`), both healthchecked, `app` waiting on `postgres` before starting.
  This is the production-grade path — Postgres is a durable, separately-operable service.
- **`InMemory` / `H2Local` / `H2Server`** → a **standalone** compose: just the `app` service. These
  are dev/test storage engines, embedded in the app process itself — there is nothing to run
  alongside them. To get the Postgres-first path, regenerate the app with `db.engine: Postgres` in
  `db.definition.json` (and set `databaseName` explicitly — see below).

Both shapes optionally add a `proxy` service (`docker compose --profile proxy up`) — a Caddy
TLS-terminating reverse proxy in front of the app (see `deploy/Caddyfile`). Generated apps never
terminate TLS themselves.

## First run

```powershell
# From the generated FinalApp root (e.g. <sample>/Output/App):
./gradlew.bat bootJar                     # build the jar first -- Docker packages it, doesn't build it
copy .env.example .env                    # then fill in real secrets in .env (never commit it)
docker compose up -d --build
```

Why the jar is built outside Docker: this platform's generated apps depend on locally-staged
NPDev jars (`runtimehost-libs`, synced via `sync-runtimehost-libs.ps1`), not a published Maven
repository. A Docker-internal Gradle build would need that whole local jar cache copied into the
build stage too — building outside (as the existing `_ops` scripts already do) and packaging the
already-built jar is simpler and matches how every other NPDev build step works.

## Retrieving the Super User (ControlPanel) key

`SuperUserBootstrapper` auto-issues a credential on first boot (if none is active) and writes the
raw key to `SUPER_USER_KEY.txt` in the app's working directory — there is no config property for
it. The compose file mounts a named volume (`app-data:/app`) over the whole app directory so the
key persists across container recreation; retrieve it with:

```
docker compose exec app cat SUPER_USER_KEY.txt
```

Note: `SuperUserBootstrapper` skips issuing a key entirely when there is no physical database
configured (`InMemory` engine) — Super User credentials require `H2Local`/`H2Server`/`Postgres`.
This is existing, correct platform behavior, not a Docker-specific limitation.

## JWT authentication: supplying keys without baking them into the image (REG-9)

`NPDEV_AUTH_MODE=jwt` needs a token **verification** (public) key, and — only if this instance also
**mints** tokens via `POST /api/auth/login` — a **signing** (private) key. Supply both by path via
environment variable so no key material is baked into the image. The generated `docker-compose.yml`
and `.env.example` already carry the four variables (commented out; uncomment and point them at
mounted files):

```
NPDEV_AUTH_MODE=jwt
NPDEV_AUTH_JWT_ISSUER=https://issuer.example.com
NPDEV_AUTH_JWT_AUDIENCE=npdev-runtime
NPDEV_AUTH_JWT_PUBLICKEYPATH=/run/secrets/jwt-public.pem     # verify-only needs ONLY this
NPDEV_AUTH_JWT_PRIVATEKEYPATH=/run/secrets/jwt-private.pem   # add for a full token issuer
```

Mount the key files into the container — e.g. a bind mount or Docker/Compose `secrets:` entry that
lands them at those paths. Two deployment shapes are supported:

- **Verify-only** (e.g. the `external-beta` profile): set only `NPDEV_AUTH_JWT_PUBLICKEYPATH`. The
  app validates externally-issued tokens; `POST /api/auth/login` returns `503
  token_issuance_unavailable`. No private key file is needed anywhere.
- **Full issuer**: set both. The app both mints and validates its own tokens.

**Env-var name gotcha** (identical to `NPDEV_AUTH_APIKEYS`): Spring Boot's relaxed binding strips
hyphens, so `npdev.auth.jwt.private-key-path` binds from `NPDEV_AUTH_JWT_PRIVATEKEYPATH` — **no**
underscore before `KEYPATH`. `NPDEV_AUTH_JWT_PRIVATE_KEY_PATH` silently does nothing.

`StartupValidator` fails fast at boot with a message linking `docs/CONFIGURATION.md#authentication`
if `jwt` mode is active and the public key path (always) or a *set* private key path points at a
file that isn't readable — rather than the old failure modes (an opaque per-request
`jwt_public_key_not_found`, or a raw `NoSuchFileException` deep in `LoginController` bean creation).

## A stable database identity across redeploys

If `db.definition.json`'s `database.databaseName` is left unset, the generator appends a fresh
timestamp to the resolved database name on every regeneration. `DatabaseIdentityStartupValidator`
refuses to start if the database it connects to doesn't match what the model was generated
against ("Configured database is 'X', but connected database is 'Y'"), so an unset `databaseName`
means every regeneration needs a matching new Postgres database. For a stable production identity
across redeploys, set `database.databaseName` explicitly. `.env.example`'s `POSTGRES_DB` default
is generated to match the model's resolved database name either way.

## Object storage (LNCH-14, Postgres-first shape only)

By default the app stores uploaded files via `file-store-inproc` (a local filesystem directory,
`npdev-files:/app/data/files` in the compose file) — fine for a single instance, but not
multi-instance-safe and not externally durable. The `file-store-objectstore` adapter (S3-compatible:
AWS S3, MinIO, Cloudflare R2) is a complete, independently-tested alternative — proven against a
real MinIO instance in its own Testcontainers suite (`S3ObjectStoreFileStoreAdapterMinioLiveTest`).

To switch a Postgres-engine app to it locally via the optional `objectstore` compose profile:

```powershell
docker compose --profile objectstore up -d --build
# MinIO does not auto-create its bucket -- one-time setup after MinIO is up:
docker compose exec minio mc alias set local http://localhost:9000 <MINIO_ROOT_USER> <MINIO_ROOT_PASSWORD>
docker compose exec minio mc mb local/npdev-files
docker compose restart app   # NPDEV_FILESTORE_PROVIDER=objectstore must be set in .env first
```

For a real cloud provider (AWS S3, R2, ...) instead of the local MinIO service, set
`NPDEV_FILESTORE_OBJECTSTORE_ENDPOINT`/`_BUCKET`/`_REGION`/`_ACCESSKEYID`/`_SECRETACCESSKEY` in
`.env` directly and skip the `objectstore` compose profile (no local MinIO container needed).

## TLS

The `proxy` profile's `deploy/Caddyfile` uses `tls internal` by default — Caddy issues its own
locally-trusted self-signed certificate, so HTTPS termination is provable without owning a domain.
Replace `:443` with a real hostname once you have one; Caddy then obtains a real Let's Encrypt
certificate automatically, no further config needed.

## Known gaps (deliberately out of scope for LNCH-7)

- **Backup/restore**: not covered here — see LNCH-9.
- **CI-driven image publishing**: this doc covers building/running locally; a registry-push
  pipeline is not part of LNCH-7.
- **`docs/CONFIGURATION.md`** (referenced by `StartupValidator`'s config-error messages, e.g.
  `#mode-and-profile-contract`, `#postgres-mode-required-variables`) does not exist yet — a
  pre-existing gap, not introduced by this deployment work.
