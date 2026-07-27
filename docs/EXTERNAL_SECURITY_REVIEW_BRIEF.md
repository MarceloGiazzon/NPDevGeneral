# External Security Review — brief for an independent reviewer

> **STATUS: ACTIVE** — this is the one open item that cannot be closed from inside the project.
> Prepared 2026-07-25. Hand this to a reviewer who did **not** write NPDev.
>
> **2026-07-27 update:** briefly misfiled to `docs/archive/programme-history/` during that day's doc
> reorganization (the same mistake `docs/DECISION_BRIEFS_2026-07.md` had — an archive pass reading
> zero inbound references as "history" rather than checking for `STATUS: ACTIVE"); restored here.
> C1 (`docs/DECISION_BRIEFS_2026-07.md`) has now been decided: the repository is to be made public,
> which is this brief's own precondition for being handed to an actual outside reviewer rather than
> staying theoretical. As of this writing the GitHub visibility switch itself has not yet been
> flipped — that is a distinct, separate action from the decision.

## 0. Why you are reading this

Every adversarial review of this platform so far was performed by the same kind of agent that wrote
the code, reading it with the same assumptions. That found real bugs — a CRITICAL and a HIGH, both
shipping — but it is structurally incapable of finding the flaws that live in those shared
assumptions. **You are here to break assumptions we cannot see.**

Two facts to calibrate on, stated plainly rather than defensively:

1. **The base rate is not zero.** Round 2 found a CRITICAL authorization bypass. Round 3 found a
   complete authorization bypass on a *different* write surface, in adjacent code, days later. Two
   careful looks, two serious authz bugs, both in code that had been shipping.
2. **"Reviewed" here means read by an attacker-minded agent, not proven.** No formal verification, no
   fuzzing, no external pentest has happened.

If you find nothing, that is a genuine result and worth recording. If you find something, it is worth
more than anything in this repository.

## 1. Scope — where the value is

Ranked by our own estimate of blast radius. You are not bound by this order, and disagreeing with the
ranking is itself useful signal.

| Rank | Surface | Why it matters | Entry point |
|---|---|---|---|
| 1 | **Generated app code** (not the generator) | A flaw reproduces into *every* app built on the platform. Both bugs we found were here | `NPDevGenerator/generator/src/main/resources/npdev-templates/service-base.mustache`; generate an app and read the **emitted** Java |
| 2 | **Row-level authorization** (`access.read` / `access.write`) | The core multi-tenant + per-row data-scoping guarantee | `NPDevKernel/.../concepts/DefaultConceptGateway.java`, `ConfiguredConceptGatewaySemanticPolicy` |
| 3 | **Tenant isolation** | Cross-tenant read/write is the worst outcome available | `ExecutionContext`, `TenantIsolationPolicy`, every `*-postgres` adapter |
| 4 | **Flow `await` / resume** | Durable state + identity across suspension | `DefaultProcedureExecutor`, `DefaultExecutionAuthorizationPolicy.canResumeExecution` |
| 5 | **Conversion hooks** | Operator SQL that may destroy data **without** an acknowledgment token (by design — ADR-0008) | `com.finalexec.db.ConversionHookRunner` |
| 6 | **Export/PDF** | Untrusted content rendering; bulk data egress | export path + `ProposedConversionSql` |

## 2. What we already believe — please try to falsify these

These are our claims. Each is a target.

| # | Claim | How we "proved" it | The weakness in that proof |
|---|---|---|---|
| C1 | Row-level `access.write` cannot be bypassed | Runtime attack suite, both store adapters | Constructs `DefaultConceptGateway` directly; does **not** drive real HTTP against a booted app |
| C2 | `access.read` is enforced on every read path | Same suite + one live E2E run | The read-side twin of C1 shipped broken once and was only caught live |
| C3 | Tenant is part of the **key**, not just a filter | Code review + adapter tests | Review, not proof |
| C4 | A hook-resolved destructive item needs no token (intended) | ADR-0008, owner-approved | The *policy* is deliberate. Whether the **implementation** honours its own limits is fair game |
| C5 | Identifiers in hand-written SQL are safe by construction | Two whitelists, reviewed | If you can reach a non-whitelisted identifier, that is a finding |
| C6 | `authorizeWrite` fails closed by default | Default method throws | Look for an implementation that overrides it permissively |

## 3. Known-and-accepted — not findings unless you can weaponise them

Please read [`ACCEPTED_BOUNDARIES.md`](../../ACCEPTED_BOUNDARIES.md) first (17 items). The ones most likely
to look like bugs:

- **Row-level authz is check-then-act** (TOCTOU), not atomic. Accepted: needs a second actor who
  already has write access. *If you can exploit it without that precondition, it is a finding.*
- **`crud.kernelControlled: false` disables authorization** — now a generation-time **error**, so a
  model cannot reach that state. *If you can reach it anyway, that is a finding.*
- **Flow resume with a null `actorId` is tenant-scoped only.** Accepted for anonymous/cron flows.
  *If you can cause a user-owned flow to have a null actorId, that is a finding.*
- **H2 has no transactional DDL**, so a failed hook `verifySql` cannot roll back DDL on H2.
- **Single-instance migration** — concurrency is detect-and-refuse, not a lock.

## 4. Getting it running

```bash
git clone <repo> && cd NPDev_General
# Generate + build + run a sample app (H2, no external services):
pwsh -File scripts/appgen/Build-NpdevApp.ps1 -AppFolder superuser-admin-console
```
Full setup: [`GETTING_STARTED.md`](../../GETTING_STARTED.md). Two known-open CI items, so you do not
re-discover them: the Windows `LegacyModelMigrationToolTest` failure, and the Linux
`npdev report bootstrap` failure.

**For authorization testing you need two real identities in one tenant** — that is the configuration
that exposed both bugs we found. A single-user session will not show them.

## 5. How to report

One finding per entry. What we need: **the concrete attack** (inputs, actor, sequence), the
**impact**, and your **severity**. Please do *not* fix anything — a recorded failure is worth more
than a silent patch, and we would rather triage your raw observation.

File to `docs/EXTERNAL_REVIEW_FINDINGS_<date>.md`, or any format you prefer. Existing findings
documents (`REG16_*_ADVERSARIAL_REVIEW.md`) show our house style, but do not feel bound by it.

**Also worth reporting:** anything in this brief that turned out to be false, any claim in §2 you
could not evaluate, and anything that took you more than 15 minutes to figure out. A brief that
misleads a reviewer is itself a defect.

## 6. What "done" looks like

Surfaces 1–3 attempted by someone outside the project, results recorded with a date, and each finding
either fixed-and-reconfirmed or filed as a dated register row. At that point — and not before — the
claim can move from *"every surface has had an internal adversarial review"* to *"an independent
reviewer has attacked the core surfaces."*
