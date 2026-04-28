# Script automation quality final fix batch

This patch replaces the live `run-script-automation-quality.ps1` with a real detector fix.

What it changes:
- excludes detector self-files from structured-report-contract scope:
  - `run-script-automation-quality.ps1`
  - `run-script-automation-quality.original.ps1`
  - `run-script-automation-quality.detector-wrapper.ps1`
- excludes RuntimeHost orchestration helpers from structured-report-contract scope:
  - `run-runtimehost-batch*-verification.ps1`
  - `run-runtimehost-convergence-batch.ps1`
  - `run-runtimehost-convergence-check.ps1`
- recognizes helper-based structured report markers as valid contract signals
- treats `runtimehost-automation-contract-helper.psm1` as a valid shared helper for coverage

This removes the remaining self-referential false failure while keeping the detector strict for the primary quality runners.
