# RuntimeHost Pack G — Batches 31 and 32

This pack is the refreshed post-Batch-30 final reduction slice.

Why this pack is small:
- the refreshed runtime-footprint report now shows exactly six remaining deadRemoveCandidates services
- there are no controller deadRemoveCandidates
- all six remaining candidates are zero-reference internal-but-needed services

Safety rules:
- run sequentially
- stop immediately if any plan validation, dry-run, apply, or verification fails
- run the full beta release gate only once after the whole pack
- after Batch 32, refresh the footprint again before proposing any further RuntimeHost reduction
