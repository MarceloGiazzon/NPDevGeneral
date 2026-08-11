# Security Policy

## Supported versions

NPDev is **pre-1.0**; only the latest tag (currently `beta1.2`) receives fixes. See `BREAKING.md`
for what changes between tags.

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private vulnerability reporting instead: go to this
repository's **Security** tab → **Report a vulnerability**.

Please include: the affected version/commit, a reproduction, and the impact you believe it has.
We aim to acknowledge within 5 working days.

## Scope

In scope: the platform itself (generator, kernel, adapters, runtime host) and the code it
generates — in particular authorization, tenant isolation, schema migration, and the external-AI
delegation surface (`docs/adr/ADR-0009-external-ai-delegation.md`).

Out of scope: the sample applications under `NPDevSamples/`, `AppGen/apps` definitions, and
committed **test** fixtures (see "Test keys" below).

## Test keys

Files matching `**/src/test/resources/**/test-jwt-*.pem` (and similarly named test-only key
fixtures) are throwaway RSA keypairs generated for unit tests. They protect nothing, are used by no
deployment, and are committed deliberately so tests run without any setup. Reports about them will
be closed as out of scope.

## Credentials in agent sessions

**Never paste a token, password, or key into an AI agent's chat.** A chat transcript is not a secret
store — it persists for as long as the session/log does, is not access-controlled the way a secrets
manager is, and anything typed into it should be treated as disclosed. This applies even when the
agent's own use of the credential is legitimate and short-lived (e.g. reading CI run data via `gh`):
the exposure is the act of pasting it, not whether it was misused.

If an agent needs a credential (a `GH_TOKEN` for `gh` CLI calls, a deploy key, etc.), set it in the
environment once — a shell profile variable, `gh auth login`, a `.env` file that is itself gitignored
— and let the agent reference it by name. If a credential is ever pasted into a chat by mistake,
**rotate it**; do not treat "nothing bad happened" as a reason to leave it live. (This convention was
written after exactly that happened on this project — see `docs/CLOSEOUT_PLAN.md` G1.)

## What we have already reviewed

Adversarial review history and known accepted boundaries:
[`docs/archive/programme-history/NPDEV_OPEN_ITEMS_REGISTER.md`](docs/archive/programme-history/NPDEV_OPEN_ITEMS_REGISTER.md),
[`docs/SECURITY_PATTERN_SWEEP_2026-07.md`](docs/SECURITY_PATTERN_SWEEP_2026-07.md).
