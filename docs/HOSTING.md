# Hosting a generated app (HOST-1)

`npdev host` takes a generated app from private (only you can reach it) to hosted for real users,
in five rungs. Every rung is a real, usable stopping point — you do not have to climb to rung 4.

| Rung | What it means | Typical target |
|---|---|---|
| 0 | Private — nobody but you can reach it | (default; nothing to do) |
| 1 | Shared by link — a tunnel; the address changes each time you open one | `cloudflared`, `ngrok` |
| 2 | Permanent address, your own box — a domain you point at this machine | `shared-ingress` (this box's own Caddy) |
| 3 | External free tier — a managed platform, usually with real constraints (sleep, no persistent disk) | `render-neon`, `koyeb-tidb`, `render-h2` |
| 4 | External paid host — no sleep, no cold starts, you administer the machine | `vps` |

Full target facts (required engine, memory ceiling, sleep behaviour, persistent disk, caveats) live
in `scripts/policy/hosting-targets.json` — read that file, or run `npdev host plan`, rather than
trusting a table here to stay current.

## The verbs

- **`npdev host plan --app <dir>`** — author `host.definition.json`: which rung, which target, who
  may reach it. Interactive by default (`--yes --rung N [--target ID]` to script it). Reads
  `_ops/resolved-db-plan.json` first and refuses — writing nothing — if the app has not been
  generated yet, or if the chosen target's required engine does not match this app's (the engine is
  baked in at generation time; no environment variable changes it).
- **`npdev host check --app <dir> [--fix]`** — the 12-check catalogue: what would a real user hit
  right now. Every non-`ok` row states the consequence before the property. `--fix` repairs whatever
  can be repaired unattended, then re-runs the checks so the output is the new state.
- **`npdev host check --target <url>`** — the same idea against a URL you have already deployed:
  reachability, TLS, whether forwarded headers actually work end to end, whether health details
  leak, whether auth rejects an unkeyed call. `unknown` is a valid answer here — a fact this command
  cannot see from outside is reported as unknown, never guessed.
- **`npdev host explain --app <dir>`** — the provenance table: every hosting-relevant setting, its
  value now, where it came from (`npdev` resolved it, or `you` must supply it), and what breaks if
  it is wrong. Terminal-only twin of the generated `static/hosting.html` screen.
- **`npdev host share --app <dir> [--provider cloudflared|ngrok]`** — preflight, then a tunnel
  pointed at the **shared ingress** (not at this one app), so one tunnel serves every healthy app on
  this box. `npdev host down`/`status` stop it and report what is live.
- **`npdev host keys --app <dir> [--new] [--superuser]`** — list the API keys this app accepts
  (masked), mint a new one, or surface the ControlPanel super-user key.
- **`npdev host deploy --app <dir>`** — writes the deployment manifest for the app's target
  (`render.yaml`/`koyeb.yaml` + `.env.<target>.example`). Refuses — writing nothing — on an
  engine/target mismatch, naming the three fix steps. On success, prints the split: which variables
  NPDev already resolved, and which the operator must supply.

## The three files

| File | Who writes it | Where | Survives regeneration |
|---|---|---|---|
| `host.definition.json` | you, via `npdev host plan` | the app **definition** directory, beside `db.definition.json` | yes — never inside the generated app |
| `_ops/host-plan.json` | the generator, every run | inside the generated app | no, and it must not be hand-edited — it is derived |
| `data/host-state.json` | `npdev host share`/`down` | inside the generated app | yes — `data/` is one of the three directories regeneration spares |

`host.definition.json` is the analogue of `db.definition.json`: decisions only. `_ops/host-plan.json`
is the analogue of `_ops/resolved-db-plan.json`: decisions merged with resolved facts (engine, port,
the full `requiredEnv` contract) — every hosting artifact and `npdev host check` read this file and
nothing else.

## The environment contract

`requiredEnv` (in `_ops/host-plan.json`, and what `npdev host explain`/`deploy` print) names every
variable a given hosting shape needs, and for each: a concrete value when NPDev already resolved it
(`source: npdev`), or `null` when an operator must supply it (`source: you`). Two variables move as
a pair and must agree, or `StartupValidator` refuses to boot naming the missing side:

```
SPRING_PROFILES_ACTIVE=prod,postgres     # selects which properties file loads
NPDEV_RUNTIME_MODE=postgres              # selects which adapter beans wire
```

Both are resolved automatically from the app's baked-in engine — never hand-set unless you know
why. Environment variable names strip the hyphen from their property name; they do NOT gain an
underscore (`npdev.auth.api-keys` → `NPDEV_AUTH_APIKEYS`, not `NPDEV_AUTH_API_KEYS`).

## Deploying with no persistent disk

Most free tiers (`render-neon`, `koyeb-tidb`, `render-h2`) have no persistent disk. Two
consequences, both surfaced by `npdev host check`:

- **The super-user key.** By default it is issued on first boot and written to `SUPER_USER_KEY.txt`
  — which disappears the moment the container restarts on an ephemeral disk. Supply it instead:
  `npdev.superuser.bootstrap-key-hash` (env `NPDEV_SUPERUSER_BOOTSTRAPKEYHASH`), the SHA-256 hash of
  a key you mint with `npdev admin hash-key` — the app never sees or prints the raw value. See
  `docs/CONFIGURATION.md`'s "ControlPanel Super User key" section for the full property set.
- **Application data**, on a target whose `databaseKind` is `ephemeral` (`render-h2`): everything is
  erased on restart. `npdev host plan` will not let this pass silently — it records the
  acknowledgement in words in `host.definition.json`, and `npdev host check`/the publish gate block
  until it is there.

## Relationship to `docs/DEPLOYMENT.md`

`DEPLOYMENT.md` stays the reference for the Docker/compose path this repo has always shipped
(`docker-compose.yml`, `Dockerfile`, `deploy/Caddyfile`) — running a FinalApp yourself, on your own
infrastructure. This document is the ladder above that: sharing a running app with someone else, and
the platforms (Render, Koyeb, a VPS) that host it for you. `npdev host deploy`'s manifests build on
the SAME `Dockerfile.build` self-building image `DockerDeploymentEmitter` already emits.
