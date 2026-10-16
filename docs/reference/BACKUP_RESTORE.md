# Backup, restore, and tenant data export (LNCH-9)

Three independent mechanisms, covering the two storage engines and the "just give me my data
back" escape hatch:

## 1. Postgres and MySQL compose stack — `deploy/backup.sh` / `deploy/restore.sh`

Emitted for FinalApps generated with `db.engine: Postgres` or `MySQL` (see `docs/DEPLOYMENT.md`) —
the scripts live under `deploy/` alongside `docker-compose.yml`. SQL Server has no backup script
yet (its engine profile declares no backup command).

```bash
# Backup (writes to ./backups/<db>-<UTC timestamp>.sql):
bash deploy/backup.sh

# Restore (DESTRUCTIVE -- replaces the target database's contents; --yes is a
# deliberate confirmation, not a prompt to bypass by accident in a scripted context):
bash deploy/restore.sh backups/<file>.sql --yes
docker compose restart app   # re-validate the app's DatabaseIdentityStartupValidator against it
```

Both scripts run `pg_dump`/`psql` INSIDE the `database` container via `docker compose exec`, so
they work identically whether or not a Postgres client is installed on the host. `backup.sh`
dumps with `--clean --if-exists`: a restore target normally already has this app's schema
(Flyway/schema-realization already ran against it), so the dump must include `DROP ... IF
EXISTS` statements for every object or the restore fails with a flood of "already exists" /
duplicate-key errors instead of cleanly replacing the data — confirmed live during this feature's
own verification (see project memory for the exact failure before this fix).

**Scheduled backups and the restore drill** (opt-in compose profile `backup`):

```bash
docker compose --profile backup up -d backup          # nightly sidecar (deploy/backup-sidecar.sh)
docker compose --profile backup run --rm backup-verify  # restore drill (deploy/backup-verify.sh)
```

The sidecar dumps every `BACKUP_INTERVAL_SECONDS` (default 86400, nightly) into `./backups/` and
keeps the newest `BACKUP_RETENTION_COUNT` dumps (default 14); set both in `.env`. `backup-verify`
restores the newest dump into a scratch database and checks it, so a restore is proven before you
need it.

**Proven live**: created 2 records → `backup.sh` → `DROP SCHEMA public CASCADE` (simulating total
data loss, app health goes 503) → `restore.sh` → `docker compose restart app` → both records
present again via the REST API.

## 2. H2Local / H2Server — nightly in-app backup

These engines have no separate service to `exec` into, so the app backs itself up
(`ScheduledH2Backup`, OPS-10). Every night at 03:00 it runs H2's `BACKUP TO` — an online, consistent
copy with no downtime — into `data/backups/db-<UTC timestamp>.zip` and keeps the newest 14. `data/`
is one of the directories regeneration never deletes, so the backups survive a rebuild. Nothing to
set up; it is on by default for `H2Local` and `H2Server` and never runs for `InMemory` or server
engines. Tune it through properties or environment variables:

| Property | Environment variable | Default |
|---|---|---|
| `npdev.backup.enabled` | `NPDEV_BACKUP_ENABLED` | `true` |
| `npdev.backup.cron` | `NPDEV_BACKUP_CRON` | `0 0 3 * * *` |
| `npdev.backup.retention-count` | `NPDEV_BACKUP_RETENTION_COUNT` | `14` |
| `npdev.backup.dir` | `NPDEV_BACKUP_DIR` | `data/backups` |

A failed backup is logged at SEVERE (`OPS-10: scheduled database backup FAILED`) and the next
night tries again. For `H2Server` the path is written by the H2 server process, so keep the app and
the H2 server on the same machine (the default `_ops` toolbox does).

**Restore**: stop the app (and `H2Server` if used), unzip the chosen `data/backups/db-*.zip` over
the database directory under `data/` (replacing the `.mv.db` file), start the app again.

You can still take an extra backup by hand: stop the app and copy the `.mv.db` file, or run
`BACKUP TO 'target.zip';` from any JDBC client.

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
