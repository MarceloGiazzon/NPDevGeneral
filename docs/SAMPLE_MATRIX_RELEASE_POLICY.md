# Sample Matrix Release Policy

The sample matrix is release evidence, not a convenience smoke test. A beta release claim requires every policy-defined release sample to generate, assemble, and pass runtime verification in one matrix run.

## Policy File

The machine-readable policy lives at:

```text
scripts\policy\sample-matrix-policy.json
```

Release samples are catalog entries whose `kind` is one of:

- `canonical-demo`
- `official-sample`
- `tenant-sample`

`test-model` entries remain fixtures and are excluded from release matrix coverage.

The current policy requires:

- `100%` release sample coverage.
- At least `5` release samples total.
- At least `1` canonical demo, `3` official samples, and `1` tenant sample.

## Commands

Run the release matrix:

```powershell
pwsh -File scripts\quality\run-sample-matrix.ps1
```

Run a local partial diagnostic matrix:

```powershell
pwsh -File scripts\quality\run-sample-matrix.ps1 -SampleIds simple-contact-intake -AllowPartialMatrix
```

Partial diagnostic runs are allowed for fast local feedback, but they are not release evidence. Without `-AllowPartialMatrix`, a subset run fails preflight because it does not cover the full release matrix.

## Evidence Contract

The matrix report is:

```text
scripts\reports\out\sample-matrix-report.json
```

The report records:

- The sample matrix policy version and path.
- Release and excluded sample kinds.
- Coverage by sample kind.
- Missing release samples, if any.
- Input fingerprints for selected sample `model.json`, `config.json`, and optional `manifest.json`.
- Per-sample generation/test duration and output summary.
- A `releaseEvidence.eligible` flag.

Do not treat a focused sample matrix report as a release decision. The aggregate beta gate remains the source of truth.
