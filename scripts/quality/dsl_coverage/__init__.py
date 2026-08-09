"""The parts of `check-dsl-coverage.py` that made it a 913-line file (QUAL-1).

Split along the seams the work actually has, one module each:

    constants.py   where the corpus lives, and the flow step types the detectors scan for
    detectors.py   the ~50 feature detectors AND the FEATURE_DETECTORS table that names them
    corpus.py      finding the models, loading the allowlist, merging contexts[] fragments

`check-dsl-coverage.py` keeps the gate itself -- coverage(), calibrate(), main() -- and its CLI is
unchanged. **The bodies moved byte-for-byte.** This is a gate, and a split that silently drops one
detector is worse than a long file: a coverage check that stops covering something is invisible by
construction. The proof is a captured before/after diff of the full corpus output, in both modes.
"""
