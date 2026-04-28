# NPDev Pack K — Finish Traceability and Final RuntimeHost Slice

This pack is designed for direct execution without Cursor/Codex.

It includes:

1. RuntimeHost Batch 33
   - removes the final 3 zero-reference RuntimeHost service candidates from the latest footprint:
     - CompilerCandidateDiffService.java
     - CompilerDependencyGraphService.java
     - GraphWeightedImpactScorer.java

2. Explicit traceable release helper
   - run-explicit-traceable-release-and-statezip.ps1
   - use this when the workspace is not a Git worktree but you can provide a trustworthy commit SHA and branch.

Important:
- Do not claim officialReleaseEligible=true unless either:
  - the workspace is a real Git worktree and run-traceable-local-release.ps1 discovers it, or
  - you provide explicit trusted source metadata through the helper script.
- The current uploaded state is diagnostic-green but not official-release-eligible because Git discovery reported not-a-git-worktree.

Suggested order:
1. Apply the zip into D:\WorkSpace\NPDev_General.
2. Run Batch 33 plan, dry-run, apply, verification.
3. Run beta gate and hygiene.
4. If you have trusted source metadata, run explicit traceable release.
5. Generate the state zip with ExistingEvidenceRoot last.
