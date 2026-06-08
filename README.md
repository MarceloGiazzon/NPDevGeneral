# NPDev

NPDev is evolving into a human-centered AI development system where users can create freely while the platform keeps truth, evidence, customization, and release status visible.

The current architectural doctrine is defined in:

```text
docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md
```

Related Architecture Decision Records:

```text
docs/adr/ADR-0002-box-object-truth-model.md
docs/adr/ADR-0003-code-bearing-panel-procedure-objects.md
```

## Core direction

NPDev's new vision is:

```text
Simple by default.
Deep when needed.
Personalizable everywhere.
Truthful always.
Restrictive only at release time.
```

The system is based on Boxes and code-bearing Objects.

Boxes provide structure, ownership, truth, evidence, and release boundaries.

Panel Objects and Procedure Objects are the primary places where users directly code.

Panel Objects support frontend resources:

```text
HTML
CSS
JavaScript
assets
```

Procedure Objects support backend resources:

```text
Java source files
Java tests
service logic
```

The generator must integrate these user-authored resources into the final generated application while preserving protected customizations.

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

## Truth principle

Truth classification must protect freedom, not block it.

Users can create freely at low truth levels.

NPDev becomes strict only when promoting claims to tested, evidence-backed, release-approved, or tag-safe status.

## Phase 0 check

Run:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' `
  -NoProfile `
  -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev\NPDev_General\scripts\quality\run-box-vision-doc-check.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev\NPDev_General'
```

Expected report:

```text
scripts\reports\out\box-vision-doc-check-report.json
```
