# RuntimeHost Batch 8

Purpose:
- continue RuntimeHost convergence after Batch 7
- remove only the two zero-reference internal services explicitly listed as dead/remove candidates

Risk profile:
- slightly higher than Batches 6 and 7 because this touches internal services
- still very conservative because the slice is only two services and both were reported with referenceHitCount 0

Flow:
1. validate the plan
2. dry-run the reduction
3. apply the reduction
4. verify runtime surface evidence and RuntimeHost gate
5. run the full beta release gate only once after the batch is verified
