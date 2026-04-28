import { describe, expect, it } from "vitest";
import type { ValidationDiagnostic } from "../types";
import { buildDiagnosticNavigationTarget, buildPipelineSteps, START_HERE_PATHS } from "./navigation/authoringStep48Ux";
import { parseAuthoringHash } from "./routes/authoringRoutes";

describe("Step 7.2 likely confusion-point fixes", () => {
  it("keeps three explicit Start here paths with the safest option first", () => {
    expect(START_HERE_PATHS).toHaveLength(3);
    expect(START_HERE_PATHS[0]).toMatchObject({
      id: "canonical-demo",
      recommended: true
    });
    expect(START_HERE_PATHS.map((entry) => entry.id)).toEqual([
      "canonical-demo",
      "official-samples",
      "new-model"
    ]);
  });

  it("maps validation diagnostics to deep links into the editor", () => {
    const diagnostic: ValidationDiagnostic = {
      layer: "semantic",
      severity: "error",
      code: "unknown_reference_target",
      message: "Entity WorkItem field ownerRef: reference target not found: TeamProfile",
      sourceModule: "dsl:semantic-validator",
      path: "concepts[WorkItem].fields[ownerRef]",
      concept: "WorkItem",
      field: "ownerRef",
      section: "model",
      suggestedFix: "Point the reference at an existing concept."
    };

    const target = buildDiagnosticNavigationTarget(diagnostic);
    const parsed = parseAuthoringHash(target.hash);

    expect(target.routeId).toBe("model-editor");
    expect(target.focusSection).toBe("references");
    expect(parsed.route.id).toBe("model-editor");
    expect(parsed.params).toMatchObject({
      concept: "WorkItem",
      field: "ownerRef",
      section: "references"
    });
  });

  it("builds a visible pipeline with the active validation step highlighted", () => {
    const steps = buildPipelineSteps("validation");

    expect(steps.map((step) => step.label)).toEqual(["Edit", "Validate", "Preview", "Export", "Build", "Run"]);
    expect(steps.find((step) => step.id === "validate")?.active).toBe(true);
    expect(steps.find((step) => step.id === "build")?.routeId).toBeUndefined();
    expect(steps.find((step) => step.id === "run")?.routeId).toBeUndefined();
  });
});
