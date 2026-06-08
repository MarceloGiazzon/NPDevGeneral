# NPDev Project Digest

## Current architectural doctrine

NPDev's current Phase 0 doctrine is the Box/Object/Truth vision:

```text
docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md
```

Supporting ADRs:

```text
docs/adr/ADR-0002-box-object-truth-model.md
docs/adr/ADR-0003-code-bearing-panel-procedure-objects.md
```

## Product identity

NPDev is a human-centered AI development system.

The goal is maximum creative freedom with maximum truth transparency.

The system should let users create freely while classifying what is generated, custom, human-authored, AI-assisted, experimental, runnable, tested, evidence-backed, and release-approved.

## Core standard

```text
Simple by default.
Deep when needed.
Personalizable everywhere.
Truthful always.
Restrictive only at release time.
```

## Correct hierarchy

```text
Application Box
  Module Box
    Entity Box
      Rule Box
    Integration Box
      Rule Box
    Panel Object
    Procedure Object
    Rule Box
    Evidence Box
  Application-level Rule Box
  Application-level Integration Box
  Evidence Box
  Release Box
```

## Boxes

Boxes are structural, semantic, truth-bearing, evidence-aware, and release-aware units.

Box types:

```text
Application Box
Module Box
Entity Box
Rule Box
Integration Box
Evidence Box
Release Box
```

## Objects

Panel and Procedure are Objects, not Boxes.

They are code-bearing creative and operational surfaces.

Panel Objects support user-authored frontend resources:

```text
HTML
CSS
JavaScript
assets
```

Procedure Objects support user-authored backend resources:

```text
Java source files
Java tests
service logic
```

The manifest describes how NPDev integrates, preserves, validates, evidences, and releases these resources. The manifest does not replace the code.

## Truth and release principle

Truth classification should never block creation.

It only blocks false claims.

Release gates protect public claims. They do not block imagination or experimentation.

Truth levels:

```text
T0 Idea
T1 Declared
T2 Generated
T3 RunsLocally
T4 Tested
T5 EvidenceBacked
T6 ReleaseApproved
```

Promotion stages:

```text
S0 Idea
S1 Declared
S2 Generated
S3 Customized
S4 Runnable
S5 Tested
S6 EvidenceBacked
S7 ReleaseApproved
S8 Released
```

## Customization protection

Generated files can be replaced.

Protected customizations cannot be silently replaced.

The generator must preserve user-authored Panel Object and Procedure Object resources and must report conflicts instead of overwriting protected human work.

## Phase 0 validation

Phase 0 validation script:

```text
scripts/quality/run-box-vision-doc-check.ps1
```

Expected report:

```text
scripts/reports/out/box-vision-doc-check-report.json
```
