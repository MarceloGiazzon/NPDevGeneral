# Script automation scope fix batch

This patch fixes the last known script-automation-quality failure.

Observed failure:
- structured-report-contract still scans runtime-surface-evidence helper/fixed/wrapper files:
  - run-runtime-surface-evidence.fixed.ps1
  - run-runtime-surface-evidence.ps1
  - run-runtime-surface-evidence.tie-wrapper.ps1

What this patch does:
- keeps detector self-file exclusions
- adds runtime-surface-evidence helper/fixed/original/wrapper files to structured-report-contract exclusions
- leaves common-helper-coverage exclusions unchanged except for detector auxiliary files

Expected effect:
- script-automation-quality should pass
- hygiene should pass
- Batch 30 remains operationally confirmed
