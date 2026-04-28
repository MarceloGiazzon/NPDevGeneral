# NPDevEditor Project Digest

## Purpose
NPDevEditor is the development-time authoring UI for creating, validating, previewing, and exporting NPDev `model.json` and `config.json`.

## Role In Workspace
- Consumes contract schemas and canonical sample inputs.
- Produces authoring bundles and validated model/config handoff material for the Generator.
- Stays separate from generated-app runtime logic.
- The Editor should not mutate generated files directly.

## Main Deliverables
- React/Vite UI in `ui-react`.
- Form and raw-JSON editing surfaces.
- Validation, preview, onboarding, import/export, and workbench panels.
- Packaged UI artifact through the Gradle wrapper in the project root.

## Key Paths
- `ui-react\src\authoring\app\AuthoringApp.tsx`
- `ui-react\src\authoring\editors`
- `ui-react\src\authoring\json`
- `ui-react\src\authoring\validation`
- `ui-react\src\authoring\preview`
- `ui-react\src\authoring\io`
- `ui-react\src\workbench\ReactWorkbenchApp.tsx`
- `ui-react\ui-boundary.json`
- `ui-react\MIGRATION_DIGEST.md`

## Operational Expectations
- Round-trip between form and JSON remains semantically stable.
- UI boundary inventory is explicit and updated when screens move.
- Frontend gate evidence captures toolchain, fingerprints, output tail, and cleanup.
- Generated residue does not remain in `ui-react` after gates finish.

## Typical Commands
Run from `NPDevEditor`:

```powershell
gradle clean build --no-daemon --console=plain
gradle npmTest --no-daemon --console=plain
gradle npmBuild --no-daemon --console=plain
```

## Current Maturity Focus
- Preserve editor round-trip safety.
- Keep frontend reproducibility evidence stable across repeated runs.
- Enforce source-to-boundary mapping through `ui-react\ui-boundary.json`.
