# Post-Beta0 Human Action Register

This register separates AI-executable maturity work from actions that require repository administration, independent review, product judgment, or real human participation. These items are not represented as completed by automation.

| Action | Owner | Status | Evidence path | Blocking status | Notes |
| --- | --- | --- | --- | --- | --- |
| Checkpoint 1 approval gate | Human roadmap owner | open | `D:\WorkSpace\NPDev_General__OutsideRepo\temp\last-roadmap\checkpoint-result.json` | blocking-for-next-checkpoint | Codex/Cursor must not proceed to Checkpoint 1 until Checkpoint 0 is reviewed and approved. |
| New checkpoint approval | Human roadmap owner | standing-required | `docs/ROADMAP_BOUNDARY_POLICY.md` | blocking-for-scope-change | Any checkpoint addition, removal, rename, split, merge, or reorder requires explicit human approval. |
| Branch protection required Linux job | Human repository owner | open | `.github/workflows/npdev-ci-validation.yml` | non-blocking-for-cp0 | Configure GitHub branch protection after the relevant workflow changes are accepted and observed in GitHub Actions. |
| Independent audit sign-off | Human owner / independent reviewer | open | `scripts/reports/out/maturity-max-roadmap-boundary-report.json` | non-blocking-for-cp0 | Arrange review outside Codex/Cursor. Automation can register the need but cannot perform independent sign-off. |
| Real participant sessions | Human product/research owner | open | `docs/POST_BETA0_HUMAN_ACTION_REGISTER.md` | non-blocking-for-cp0 | Schedule real participant validation sessions after technical closure. Automation cannot substitute for participant feedback. |

No human-only action is represented as AI-completed. If a human owner chooses to make any non-blocking item release-blocking later, that must be recorded as a separate human decision rather than as an automatic roadmap expansion.
