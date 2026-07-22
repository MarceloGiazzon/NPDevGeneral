# Sample Matrix Release Policy

The sample matrix is release evidence, not a convenience smoke test. For AI-only Beta0 it uses strict release-sample semantics: every policy-defined release sample is release-blocking. Fixture-only samples must be reported explicitly without silently counting as release coverage.

## Policy File

The machine-readable policy lives at:

```text
scripts\policy\sample-matrix-policy.json
```

The current policy classifications are:

- `release-sample`: release-blocking; included in release coverage.
- `fixture-only`: low-level fixtures; excluded from release coverage and release eligibility.

`user-minimal` is explicitly classified as `fixture-only`; it is not release-blocking.

The current release-blocking samples are:

- `canonical-demo`
- `simple-user-registry`
- `simple-contact-intake`
- `medium-expense-approval`
- `restaurant-saas-multitenant`

The current policy requires:

- `100%` coverage of release samples.
- At least `5` release samples.
- No unclassified catalog samples.
- Fixture-only findings to be listed as non-blocking with reasons.

## Commands

Run the release matrix:

```powershell
pwsh -File scripts\quality\run-sample-matrix.ps1
```

Run a local partial diagnostic matrix:

```powershell
pwsh -File scripts\quality\run-sample-matrix.ps1 -SampleIds simple-contact-intake -AllowPartialMatrix
```

Partial diagnostic runs are allowed for fast local feedback, but they are not release evidence. Without `-AllowPartialMatrix`, a subset run fails preflight because it does not cover the full release matrix. With `-AllowPartialMatrix`, the report uses `overallStatus = diagnostic`, so the aggregate release gate cannot treat it as a passing release report.

## Evidence Contract

The matrix report is:

```text
scripts\reports\out\sample-matrix-report.json
```

The report records:

- The sample matrix policy version and path.
- The policy semantics and blocking/non-blocking classifications.
- Coverage over release samples.
- Missing or invalid blocking sample inputs, if any.
- Non-blocking issues for fixture-only samples.
- Input fingerprints for selected sample `model.json`, `config.json`, `manifest.json`, `expected-behavior.md`, and `expected-endpoints.md`.
- Per-sample verification command metadata, output summary, log path, per-sample verification report path, and generation marker evidence.
- A full `releaseEvidence.eligible` flag. It is `true` only when every release-blocking sample passes strict input-contract validation and the canonical generation/runtime verifier.
- A separate `inputContractEvidence.eligible` flag for strict sample input-contract evidence.
- Blocking and non-blocking issue counts.

Do not treat a focused sample matrix report as a release decision. The aggregate beta gate remains the source of truth.
