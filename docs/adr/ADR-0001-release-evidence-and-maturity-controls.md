# ADR-0001 Release Evidence And Maturity Controls

## Status
Accepted - 2026-04-23

## Context
NPDev release evidence, maturity controls, and state packaging must agree on one truthful readiness decision. The beta release gate is the authoritative source, and maturity suites must report normalized pass/fail evidence without inventing separate release truth.

## Decision
- `scripts/quality/run-beta-release-gate.ps1` remains the authoritative release gate.
- `release-ready-summary.json` derives `releaseReady` only from the aggregate beta gate status.
- Maturity suites emit normalized reports and surface waivers, but do not silently convert failures to passes.
- State packaging must fail closed when authoritative aggregate evidence is missing or invalid.

## Consequences
- False-positive release-ready artifacts are treated as governance defects.
- Focused gates and maturity reports remain useful diagnostics, but they do not override aggregate release truth.
- Future changes to release evidence or maturity-report contracts should add or update ADRs in this folder.
