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

## A stable database identity across redeploys

If `db.definition.json`'s `database.databaseName` is left unset, the generator appends a fresh
timestamp to the resolved database name on every regeneration. `DatabaseIdentityStartupValidator`
refuses to start if the database it connects to doesn't match what the model was generated
against ("Configured database is 'X', but connected database is 'Y'"), so an unset `databaseName`
means every regeneration needs a matching new Postgres database. For a stable production identity
across redeploys, set `database.databaseName` explicitly. `.env.example`'s `POSTGRES_DB` default
is generated to match the model's resolved database name either way.

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
