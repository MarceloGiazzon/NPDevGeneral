# Backup, restore, and tenant data export (LNCH-9)

Three independent mechanisms, covering the two storage engines and the "just give me my data
back" escape hatch:

## 1. Postgres compose stack — `deploy/backup.sh` / `deploy/restore.sh`

Only emitted for FinalApps generated with `db.engine: Postgres` (see `docs/DEPLOYMENT.md`) — the
scripts live at the app root alongside `docker-compose.yml`.

```bash
# Backup (writes to ./backups/<db>-<UTC timestamp>.sql):
bash deploy/backup.sh

# Restore (DESTRUCTIVE -- replaces the target database's contents; --yes is a
# deliberate confirmation, not a prompt to bypass by accident in a scripted context):
bash deploy/restore.sh backups/<file>.sql --yes
docker compose restart app   # re-validate the app's DatabaseIdentityStartupValidator against it
```

Both scripts run `pg_dump`/`psql` INSIDE the `postgres` container via `docker compose exec`, so
they work identically whether or not a Postgres client is installed on the host. `backup.sh`
dumps with `--clean --if-exists`: a restore target normally already has this app's schema
(Flyway/schema-realization already ran against it), so the dump must include `DROP ... IF
EXISTS` statements for every object or the restore fails with a flood of "already exists" /
duplicate-key errors instead of cleanly replacing the data — confirmed live during this feature's
own verification (see project memory for the exact failure before this fix).

**Proven live**: created 2 records → `backup.sh` → `DROP SCHEMA public CASCADE` (simulating total
data loss, app health goes 503) → `restore.sh` → `docker compose restart app` → both records
present again via the REST API.

## 2. H2Local / H2Server — file-copy semantics

These engines have no separate service to `exec` into — the database is either an embedded file
(`H2Local`) or a file served over TCP by a local `H2Server` process (see the per-app `_ops`
toolbox's `Start-Environment.ps1`). Backup is a **file copy**, with one constraint: H2 must not
have the file open for a consistent copy.

- **Simplest (recommended)**: stop the app (and `H2Server` if used), copy the `.mv.db` file
  (path is `db.definition.json`'s `resolvedDataRoot`/`resolvedDatabaseName`, also printed at
  generation time as `Schema realization: ...`), restart.
- **Online backup (no downtime)**: H2 supports `BACKUP TO 'target.zip'` as a SQL statement,
  executable via any JDBC client connected to the running database — a live database can be
  backed up while running by connecting an H2 client/DBeaver (whose connection details the
  generator already prints as `dbeaverHost`/`dbeaverPort`/`dbeaverDatabase`) and running
  `BACKUP TO 'target.zip';`.
- **Restore**: stop the app, replace the `.mv.db` file with the backed-up copy (or unzip the
  `BACKUP TO` output over it), restart.

This is documented procedure, not scripted — H2Local/H2Server are the platform's dev/test
engines (see `docs/DEPLOYMENT.md`'s engine split), so a restore drill here is a lower bar than
the Postgres-first production path.

## 3. Tenant data export — `GET /api/admin/export`

The user-level "export my data" escape hatch, independent of which storage engine is running.
ADMIN or SUPERUSER only (same gate as `POST /api/admin/seeds/{id}/run`); a Super User may pass
`?tenantId=` to export a different tenant, same restriction as the seed-run endpoint (redirecting
at another tenant requires SUPERUSER, not just ADMIN — an ordinary business ADMIN's context is
already scoped to their own tenant).

```
GET /api/admin/export
GET /api/admin/export?tenantId=other-tenant   (SUPERUSER only)
```

Returns every record of every concept for that tenant in the SAME `{"kind": "raw", "records":
[{"concept", "id", "data"}]}` shape `SeedDataService` already consumes — this is deliberately not
a new format. The export doubles as:

- **A poor-man's tenant clone/migration tool**: export from tenant A, place the JSON under
  `definition/seeds/` as a raw seed, run it against tenant B via
  `POST /api/admin/seeds/{id}/run?tenantId=tenant-b`.
- **The actual data-portability escape hatch** users need before adopting any platform.

**Proven both ways**: a hermetic round-trip test (`TenantExportRoundTripTest`) seeds tenant A via
the seeder, exports it, re-imports the exact export JSON into tenant B via the seeder, and
confirms tenant B ends up with equivalent data while tenant A is untouched. Also proven live via
`bootRun`: created a record, called `GET /api/admin/export`, got back the exact seed-shaped JSON
containing it.

## Known gap

A restore drill wired into the release gate (rather than a documented/scripted manual procedure)
is deferred — the doc's own DoD accepts "a documented manual procedure v1" for this.
