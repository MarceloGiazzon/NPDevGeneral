# Editor: React UI Migration Digest

## Purpose
Current React workbench and authoring UI source.

## Boundary Zones
- `workbench-zone`: top-level shell, route handoff, and operator panels.
- `authoring-zone`: guided authoring, model/config editing, preview, validation, and onboarding.
- `panel-zone`: isolated workbench panels that plug into the workbench without importing authoring internals directly.

## Boundary Policy
- `ui-boundary.json` is the explicit change-control file for every `.tsx` entry.
- Boundary updates are intentional and should happen in the same change as any new screen or moved screen.
- Zone crossings should be mediated through shared state/services, not ad hoc component imports.

## Copied From
- `D:\WorkSpace\NPDev_General\ui-react`

## Current State
This split now tracks boundary and zone ownership explicitly, but it is still the internal React workbench rather than the runtime-served canonical UI.

