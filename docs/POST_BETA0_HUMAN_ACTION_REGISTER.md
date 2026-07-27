# Post-Beta0 Human Action Register

This register separates AI-executable maturity work from actions that require repository administration, independent review, product judgment, or real human participation. These items are not represented as completed by automation.

**AI-delegable? column (added per ADR-0009, P9).** Whether an external AI (no repo/filesystem/shell/network
access, verdict recorded as `external-ai-verdict`, never `independent-human-role`) could stand in for
this row. ❌ is permanent for E5/E6/E7-shaped items — see `docs/adr/ADR-0009-external-ai-delegation.md`
§"honesty contract" items 1-2. A ✅/⚠️ here is not a claim any of these rows have actually been
delegated; it only records whether the mechanism could apply.

| Action | Owner | Status | Evidence path | Blocking status | AI-delegable? | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Checkpoint 1 approval gate | Human roadmap owner | open | `D:\WorkSpace\NPDev_General__OutsideRepo\temp\last-roadmap\checkpoint-result.json` | blocking-for-next-checkpoint | ❌ permanently (E6 — checkpoint approval/authority) | Codex/Cursor must not proceed to Checkpoint 1 until Checkpoint 0 is reviewed and approved. |
| New checkpoint approval | Human roadmap owner | standing-required | `docs/ROADMAP_BOUNDARY_POLICY.md` | blocking-for-scope-change | ❌ permanently (E6 — checkpoint approval/authority) | Any checkpoint addition, removal, rename, split, merge, or reorder requires explicit human approval. |
| Branch protection required Linux job | Human repository owner | open | `.github/workflows/npdev-ci-validation.yml` | non-blocking-for-cp0 | ❌ permanently (E6 — repo admin) | Configure GitHub branch protection after the relevant workflow changes are accepted and observed in GitHub Actions. |
| Independent audit sign-off | Human owner / independent reviewer | open — **review now done, sign-off still owner's** | `scripts/reports/out/maturity-max-roadmap-boundary-report.json`; M6's real run: `docs/external-ai-review/runs/M6-AUDIT-VERDICT.json`, verdict `NPDev_General__OutsideRepo/external-ai-review/packs/M6-AUDIT-VERDICT/*-nvidia-verdict.json` | non-blocking-for-cp0 | ⚠️ AI does the **review** (E4, mission M6 — actually run 2026-07-27 against a real evidence bundle, appropriately skeptical result); the **sign-off** stays the owner's | Arrange review outside Codex/Cursor. Automation can register the need but cannot perform independent sign-off. |
| Real participant sessions | Human product/research owner | open | `docs/POST_BETA0_HUMAN_ACTION_REGISTER.md` | non-blocking-for-cp0 | ❌ permanently (E5 — an AI persona is not a participant) | Schedule real participant validation sessions after technical closure. Automation cannot substitute for participant feedback. |

No human-only action is represented as AI-completed. If a human owner chooses to make any non-blocking item release-blocking later, that must be recorded as a separate human decision rather than as an automatic roadmap expansion.
