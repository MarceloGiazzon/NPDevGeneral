# REG-16 — Adversarial review of the tenant-isolation (LNCH-2) + auth (LNCH-4) surface

> **Status:** Tier A COMPLETE (R0–R2) — 2026-07-21. Independent, attack-first review of the standing
> tenant/auth surface, per `docs/REGISTER_CLOSURE_PLAN.md` §11.1. This document is R1's findings +
> R2's triaged remediation plan. It is *not* a review of a diff — it examines code that already
> shipped, which is exactly REG-16's point ("a review that hasn't happened yet").
>
> **Precondition satisfied:** run after REG-2/P2, so `TenantIsolationE2EIT` + `JwtAuthExternalBetaIT`
> actually execute (10/10 green on real Postgres) rather than being dark.
>
> **Headline:** **no CRITICAL or HIGH finding.** The tenant-isolation core is genuinely
> defense-in-depth hardened (gateway *and* store both enforce `tenant_id`; seed/export route through
> the enforced gateway; no SUPERUSER bypass of business data; SQL-injection-safe query filters). The
> residual findings are **5 MEDIUM + 3 LOW + 1 informational**, all in the login/throttle/actuator
> hardening band — real, worth fixing, none a data-breach or auth-bypass. Per the plan's triage rules
> (§11.1 R2), with no CRITICAL/HIGH the mandatory Tier-B work is empty; the MEDIUM/LOW findings are
> filed as new dated register items (REG-18…REG-26) rather than silently dropped.

---

## R0 — Scope actually read

**Auth (`com.finalexec.auth`, `com.finalexec.controlpanel`, `com.finalexec.config`):**
`JwtBearerAuthFilter`, `JwtSigner`, `IdentityAwareContextResolver`, `LoginController` (via REG-9),
`PasswordResetController`, `PasswordHasher`, `LoginThrottle`, `TenantStatusFilter`,
`SuperUserCredentialAuthFilter`, `ActuatorAdminGuardFilter`, `CredentialRegistryService.resolve`.

**Tenant isolation + data path:** `DefaultConceptGateway` (`enforceTenant`, `normalizeTenant`),
`DefaultTenantIsolationPolicy` / `TenantIsolationPolicy`, `JdbcBusinessConceptStore`
(findById/findAll/query/update/delete SQL), `TenantExportController` + `TenantExportService`,
`DataSeedAdminController` + `SeedDataService` write path.

**Revocation coverage sweep:** every consumer of `tv` / `token_version` and of the JWT
`CLAIMS_ATTRIBUTE` (`IdentityAwareContextResolver`, `GeneratedCrudRuntimeSupport`, `TenantStatusFilter`,
`ActuatorAdminGuardFilter`, `SuperUserCredentialAuthFilter`).

**Existing tests noted (coverage baseline, not re-run here except the E2E precondition):**
`TenantIsolationAttackTest`, `TenantIsolationE2EIT`, `JdbcBusinessConceptStoreTenantIsolationTest`,
`TenantRegistryServiceTest`, `TenantExportRoundTripTest`, `TenantPartitioningGovernanceTest`,
`PublicationChainTenantReferenceValidationTest`, `RowLevelAuthorizationAttackTest`,
`JwtAuthExternalBetaIT`, `IdentityAwareContextResolverTest`, `LoginThrottleTest`,
`PasswordResetControllerTest`.

---

## What is solid (recorded so the review is honest about the baseline)

- **JWT algorithm confusion is blocked.** `JwtBearerAuthFilter` rejects any `alg` other than `RS256`
  *before* verifying, and only ever verifies with the configured RSA public key — `none`/`HS256`
  confusion is not reachable. Signature is over `header.payload`; `iss`/`aud`/`iat`/`exp`/`nbf`
  validated with a 60s clock skew; `exp <= iat` rejected.
- **Password hashing** is PBKDF2-HMAC-SHA256, **210 000 iterations**, 16-byte per-password salt,
  256-bit output, self-describing format, **constant-time** compare. Meets current OWASP guidance.
- **Password-reset tokens**: 256-bit `SecureRandom`, **SHA-256-hashed at rest**, **single-use**
  (`used_at`), **30-min expiry**, **tenant-scoped** lookup, **enumeration-resistant** (identical
  generic response whether or not the account/email/mail-capability exists), and **bumps
  `token_version`** on success so live JWTs minted under the old password die immediately.
- **Tenant isolation is defense-in-depth.** `DefaultConceptGateway.enforceTenant` denies unless
  `context.tenant == requested.tenant` on *every* read/list/query/save/delete — **with no SUPERUSER
  exception** (a super-user cannot read another tenant's business data through the gateway). The
  `JdbcBusinessConceptStore` *independently* filters `WHERE tenant_id = ?` on every SQL op, and
  **stamps `tenant_id` from the enforced context on write** (no mass-assignment of tenant via the
  request body). Cross-tenant IDOR is prevented at both layers.
- **Seed and export both route through the enforced gateway** with the caller's context; the
  cross-tenant override (`?tenantId=`) requires SUPERUSER in both `DataSeedAdminController` and
  `TenantExportController`. A seed file cannot inject cross-tenant rows.
- **Query filters are SQL-injection-safe**: filter/sort columns pass `requireColumn(shape, …)`
  (whitelist against the concept shape), operators map through a fixed enum, values are bound params.
- **Token revocation (`tv`)** is checked on both primary claim→context resolution paths
  (`IdentityAwareContextResolver` for RuntimeHost, `GeneratedCrudRuntimeSupport` for generated CRUD).
- **Super-user key** is hash-at-rest, resolved **live per request** (only `status=ACTIVE` issued
  credentials), SUPERUSER-role-required, and independent of business `auth.mode`.

---

## R1 — Findings → severity map

| ID | Sev | Area | One-line |
|---|---|---|---|
| REG16-F1 | **MEDIUM** | login | Login timing side-channel enables username enumeration |
| REG16-F2 | **MEDIUM** | throttle | `LoginThrottle` map is unbounded → memory-exhaustion DoS |
| REG16-F3 | **MEDIUM** | throttle | No defense against password-spraying (per-username-only limiter) |
| REG16-F4 | **MEDIUM** | reset | Password-reset *request* is unthrottled (email-bomb / token spam) |
| REG16-F5 | **MEDIUM** | authz | Filter-level role gates trust JWT claim-roles without live re-resolution/revocation |
| REG16-F6 | LOW | revocation | `tv`-less tokens are never revocation-checked (backward-compat, by design) |
| REG16-F7 | LOW | tenancy | `"default"` tenant sentinel collides with a real tenant named `default` |
| REG16-F8 | LOW | tenancy | Tenant match is case-sensitive while other layers lowercase → bucket fragmentation |
| REG16-F9 | INFO | disclosure | Granular JWT error codes disclose *why* validation failed |

### REG16-F1 — Login timing side-channel enables username enumeration · MEDIUM
- **Where:** `LoginController.login` — returns `unauthorized(...)` on `!rs.next() || !rs.getBoolean("active")`
  *before* any password comparison; a **known** user reaches `PasswordHasher.verify` (a ~tens-of-ms
  PBKDF2 with 210k iterations).
- **Why it matters:** the response-time gap between "no such user" (fast) and "user exists, wrong
  password" (slow) is a reliable oracle for **which usernames exist in a tenant**. Enumerated
  usernames feed F3 (spraying).
- **Failure scenario:** attacker POSTs `/api/auth/login` with `{username:"alice", password:"x"}` and
  `{username:"nosuchuser", password:"x"}` and measures latency. `alice` responds materially slower →
  confirmed to exist. The per-username throttle (F3) does not stop this: counters are independent per
  username, and a handful of timed attempts per name is enough.
- **Fix:** on the no-user / inactive-user path, perform a **dummy `PasswordHasher.verify` against a
  fixed decoy hash** so both branches spend comparable time, then return the same uniform 401.

### REG16-F2 — `LoginThrottle` map is unbounded → memory-exhaustion DoS · MEDIUM
- **Where:** `LoginThrottle.windowsByKey` (`ConcurrentHashMap<String,Window>`). Entries are only
  removed on `recordSuccess` or overwritten on the next failure for the *same* key; **expired windows
  are never evicted**.
- **Why it matters:** an *unauthenticated* attacker controls the key space `(tenant,username)`. Failed
  logins with distinct random usernames create unbounded map entries that never get cleaned up.
- **Failure scenario:** a script sends millions of `/api/auth/login` attempts, each with a fresh random
  username → the map grows to millions of `Window` objects → OOM / GC-thrash on a single-instance deploy.
- **Fix:** bound the map — a periodic sweep of expired windows, a size cap with LRU/oldest-window
  eviction, or a `Caffeine`-style expiring cache. Eviction of an expired window is semantically free
  (it's already "expired" for lock purposes).

### REG16-F3 — No defense against password-spraying · MEDIUM
- **Where:** `LoginThrottle` keys strictly on `(tenant, username)`; there is no per-IP or global
  failed-attempt limiter anywhere in the login path.
- **Why it matters:** password-spraying (one common password against many usernames) never trips any
  per-username counter — each username has its own 10-attempt budget — so the lockout provides no
  protection against the most common credential-attack shape. With F1 supplying valid usernames, this
  is an efficient path to a foothold.
- **Failure scenario:** attacker enumerates 500 usernames (F1), tries `Summer2026!` against each once;
  no counter exceeds 1; any weak account is compromised, silently.
- **Fix:** add a **per-source (IP) and/or global** failed-attempt limiter alongside the per-username
  one (e.g. N failures/window/IP → 429). Keep it bounded (see F2). Note the documented "single-instance
  in-memory v1" posture is fine for the *storage*; the gap is the *dimension* (username-only).

### REG16-F4 — Password-reset *request* is unthrottled · MEDIUM
- **Where:** `PasswordResetController.requestReset` (`/api/auth/password-reset/request`) — no rate limit;
  each call to a valid account sends an email and inserts a reset-token row.
- **Why it matters:** an attacker who knows one victim username can **email-bomb** that account and
  create unbounded valid reset-token rows (each single-use/expiring, so not an auth bypass, but real
  abuse + a mail-cost/DoS vector).
- **Failure scenario:** loop `requestReset({username:"alice"})` thousands of times → thousands of reset
  emails to Alice + thousands of token rows for her user.
- **Fix:** throttle reset requests per `(tenant, username)` and/or per IP (reuse the F2/F3 limiter);
  cap concurrent live tokens per user.

### REG16-F5 — Filter-level role gates trust JWT claim-roles without live re-resolution/revocation · MEDIUM
- **Where:** `ActuatorAdminGuardFilter.doFilterInternal` checks `claims.get("roles")` for `SUPERUSER`
  directly off the request's `CLAIMS_ATTRIBUTE`. This attribute can be set by `JwtBearerAuthFilter`
  (roles straight from the token), and the filter runs *before* — and independently of —
  `IdentityAwareContextResolver`, which is the only component that re-resolves roles live and checks `tv`.
- **Why it matters:** the actuator metrics/prometheus gate (internal counts, tenant tags, capability/flow
  names — info disclosure) is passed by **any** claims carrying a `SUPERUSER` role, and a **revocation
  or role change is not reflected** at this filter (stale claim-roles / no `tv` check). It conflates the
  intended super-key path with any JWT-borne role.
- **Failure scenario:** a principal was granted `SUPERUSER` and then had it revoked (role removed, or
  `token_version` bumped); their still-unexpired JWT keeps passing `ActuatorAdminGuardFilter` because the
  filter reads the token's embedded roles and never re-resolves. (Exploitability of the "business JWT
  carries SUPERUSER" angle depends on whether an app assigns `SUPERUSER` as a business identity role —
  the super-key path itself *is* live-checked.)
- **Fix:** gate actuator specifically on the **super-key credential** (e.g. require the principal to have
  arrived via `SuperUserCredentialAuthFilter`), or re-resolve roles live + check `tv` in the filter
  rather than trusting claim-roles.

### REG16-F6 — `tv`-less tokens are never revocation-checked · LOW (backward-compat, by design)
- **Where:** `IdentityAwareContextResolver.rejectIfTokenRevoked` returns early when the token has no `tv`
  claim (documented as forward-only revocation for pre-feature tokens).
- **Why it matters:** any validly-signed token *without* a `tv` claim is permanently immune to
  revocation. Bounded: minting requires the private key, and every current mint path (`JwtSigner.sign`)
  stamps `tv`, so a `tv`-less token can only be a genuine pre-feature artifact.
- **Fix (future):** after a dated cutover past which no pre-`tv` tokens can still be valid (≥ max token
  lifetime), treat a missing `tv` as version 0 and enforce it.

### REG16-F7 — `"default"` sentinel collides with a real tenant named `default` · LOW
- **Where:** `TenantIsolationPolicy.normalize` / `DefaultTenantIsolationPolicy.normalize` map
  null/blank → `"default"`.
- **Why it matters:** a principal with a blank tenant claim shares the isolation bucket of a real tenant
  literally named `default`. Connects to the already-tracked platform gap (**#15 — tenant `"default"`
  is a reserved sentinel**). Not a cross-tenant escalation for two *distinct* named tenants.
- **Fix:** reserve `default` as un-registerable, or use a sentinel that cannot be a valid tenant id.

### REG16-F8 — Case-sensitive tenant match vs. lowercased elsewhere · LOW (data-consistency)
- **Where:** `sameTenant` compares with `.equals()` (case-sensitive); `LoginThrottle.key` lowercases;
  tenant ids are not canonicalised at registration.
- **Why it matters:** **not** a cross-tenant bypass (an attacker cannot make `a == b` via case). But the
  same logical tenant referenced as `Acme` vs `acme` fragments into two isolation buckets — a silent
  data-partitioning hazard.
- **Fix:** define one canonical tenant-id casing, enforced at registration and comparison.

### REG16-F9 — Granular JWT error codes · INFORMATIONAL
- **Where:** `JwtBearerAuthFilter.unauthorized(...)` returns `invalid_jwt_issuer` / `expired_jwt` /
  `invalid_jwt_signature` / … Standard and useful for debugging; discloses *why* a token failed. No
  change recommended; recorded for completeness.

---

## R2 — Triage + remediation plan

Per `REGISTER_CLOSURE_PLAN.md` §11.1 R2 triage rules:

- **CRITICAL / HIGH — must fix inside this plan's R3:** **none.** This is the substantive result of the
  review: the tenant-isolation and auth core, after LNCH-1/2/4/13's rounds, has no
  data-breach/auth-bypass hole reachable from this reading. Tier B therefore has **no mandatory blocking
  work** — an honest, valuable Tier-A conclusion, not an empty one.
- **MEDIUM — fix in R3 if budget allows, else log as new dated register entries:** F1–F5, logged as
  **REG-18 … REG-22** (see below). Two are quick, self-contained wins recommended first: **F1**
  (decoy-hash on the no-user path) and **F2** (bound the throttle map).
- **LOW — always logged as new dated entries:** F6 → **REG-23**, F7 → **REG-24**, F8 → **REG-25**.
- **INFORMATIONAL:** F9 → **REG-26** (documentation-only; likely WONTFIX).

### New register items filed (dated 2026-07-21)

| New item | From | Sev | Fix sketch |
|---|---|---|---|
| REG-18 | F1 | MED | Dummy `PasswordHasher.verify` against a fixed decoy hash on the no/inactive-user login path. Test: assert both paths do a PBKDF2 (or timing within tolerance). |
| REG-19 | F2 | MED | Evict/cap `LoginThrottle.windowsByKey` (periodic sweep or size-bounded eviction). Test: N-distinct-username spray leaves the map bounded. |
| REG-20 | F3 | MED | Add a per-IP/global failed-attempt limiter beside the per-username one. Test: spraying one password across many usernames trips the global/IP limit. |
| REG-21 | F4 | MED | Throttle `password-reset/request` per (tenant,username)+IP; cap live tokens/user. Test: Nth request in a window is refused; token count bounded. |
| REG-22 | F5 | MED | Gate actuator on the super-key path (or re-resolve roles+`tv` in the filter). Test: a revoked-role / `tv`-bumped JWT is refused at `/actuator/metrics`. |
| REG-23 | F6 | LOW | Post-cutover, treat missing `tv` as 0 and enforce. (Deferred; needs a dated cutover ≥ max token lifetime.) |
| REG-24 | F7 | LOW | Reserve `default` as un-registerable / change the sentinel. (Coordinate with platform gap #15.) |
| REG-25 | F8 | LOW | Canonical tenant-id casing at registration + comparison. |
| REG-26 | F9 | INFO | Decide keep-verbose vs. collapse-to-generic JWT errors. Likely WONTFIX. |

### Tier B outcome (2026-07-21)

Tier B was executed the same day. **All five MEDIUM findings are fixed** (REG-18, REG-19, REG-20,
REG-21, REG-22 — commits `b29bf4d`, `0182007`), each RED-first with a regression test, verified live
on the assembled app (auth + config packages green). Of the LOW/INFO: **REG-24** was found already
comprehensively guarded (every tenant-insert path reserves `default`) so it needed no change;
**REG-26** is WONTFIX (the JWT error codes name the validation reason, not any secret); **REG-23**
(tv-less enforcement) and **REG-25** (tenant-casing canonicalisation) are DEFERRED with rationale —
the former needs a dated cutover and a consistent dual-path flip, the latter a real `tenant_id` data
migration, both disproportionate to a latent LOW. See the REG-18…26 table in
`docs/NPDEV_OPEN_ITEMS_REGISTER.md` for per-item status.

### Recommended Tier-B (R3) order if/when scheduled

1. **REG-18 + REG-19** (quick wins, no new infra): decoy-hash + bounded throttle map. RED-first: a test
   that measures the no-user vs wrong-password path, and a spray test that asserts map size stays bounded.
2. **REG-20 + REG-21** (shared limiter): introduce one bounded per-IP/global failed-attempt limiter and
   reuse it for login-spray and reset-request. RED-first: spray + reset-flood tests.
3. **REG-22** (actuator gate): tighten to the super-key path or live re-resolution. RED-first: a
   revoked-role JWT reaching `/actuator/metrics`.
4. LOW items as capacity allows.

### Verification bar for any Tier-B fix (per §11.1)

Live rehearsal against real Postgres with two live tenants; RED-reproduced-first for every fix; small
bounded commits; a verification ledger under `NPDev_General__OutsideRepo`. None of the current findings
requires a schema change, so the multi-tenant cross-tenant-attack rehearsal (already green via
`TenantIsolationE2EIT`) remains the isolation safety net.

---

## Honest statement of what Tier A did and did not establish

- **Did:** an independent, attack-first read of the whole tenant/auth surface named in the register,
  with the E2E safety nets executing; produced 9 findings with concrete failure scenarios; triaged them;
  filed the MEDIUM/LOW ones as dated register items so none is dropped. **REG-16's actual problem
  statement — "zero adversarial review of this surface" — is resolved by this document existing.**
- **Did not:** implement fixes (Tier B). With no CRITICAL/HIGH finding, there is no *mandatory* Tier-B
  work; the MEDIUM/LOW remediations are scheduled as REG-18…REG-26. A reviewer's read has blind spots a
  running exploit would not — the findings above are code-reading-derived, not each individually
  weaponised; REG-18…REG-22 should be RED-reproduced before fixing, per the guardrails.
