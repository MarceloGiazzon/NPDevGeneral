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

## What we have already reviewed

Adversarial review history and known accepted boundaries:
[`docs/NPDEV_OPEN_ITEMS_REGISTER.md`](docs/NPDEV_OPEN_ITEMS_REGISTER.md),
[`docs/SECURITY_PATTERN_SWEEP_2026-07.md`](docs/SECURITY_PATTERN_SWEEP_2026-07.md).
