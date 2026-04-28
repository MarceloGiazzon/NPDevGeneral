# Automation contract cleanup batch

This patch fixes the scripts that currently fail the structured-report-contract check in `run-script-automation-quality.ps1`.

Included:
- shared helper module for structured report emission and reported-command execution
- rewritten `run-runtimehost-batch5-verification.ps1` through `run-runtimehost-batch25-verification.ps1`
- rewritten `run-runtimehost-convergence-batch.ps1`
- rewritten `run-runtimehost-convergence-check.ps1`

Expected effect:
- `usesReportedCommand` becomes true
- `writesStructuredReport` becomes true
- `hasStandardFields` becomes true for the rewritten scripts
- hygiene should stop failing on `script-automation-quality`
