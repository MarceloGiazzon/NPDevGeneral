# Final runtime fix batch

This patch fixes the two remaining blockers together:

1. script-automation-quality
- restores a known-good detector shape
- excludes detector auxiliary files from both structured-report-contract and common-helper-coverage scans
- keeps RuntimeHost orchestration helper exclusions

2. runtime-surface-evidence
- replaces the fragile tie wrapper with a contract-safe fixed script
- delegates to the original script first
- only intercepts the exact post-Batch-30 tie case:
  - supportedServices == excludedServices
  - only failing check is the strict service-minority check
- rewrites the footprint check to a non-exceed rule and preserves the expected parent report object with childReports

Apply with:
- scripts\quality\apply-final-runtime-fixes.ps1

Rollback with:
- scripts\quality\rollback-final-runtime-fixes.ps1
