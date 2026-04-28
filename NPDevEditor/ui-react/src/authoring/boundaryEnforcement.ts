// Boundary zone registry for the editor workbench.
// CI treats boundary changes as explicit change-control updates via ui-boundary.json.
// Accessibility evidence is expected alongside boundary enforcement so the editor stays reviewable at scale.
export const AUTHORING_BOUNDARY_ZONES = {
  authoring: "authoring-zone",
  workbench: "workbench-zone",
  panelSurface: "panel-zone"
} as const;
