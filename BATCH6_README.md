# RuntimeHost Batch 6

Purpose:
- correct the Batch 5 path mistake
- perform the first real deletion slice
- remove only zero-reference transitional UI controllers under `com/finalexec/api/experimental`

Flow:
1. validate the plan
2. dry-run the removal
3. apply the removal
4. verify runtime surface evidence and RuntimeHost gate
5. run the full beta release gate only once after the batch is verified
