import { afterEach, describe, expect, it, vi } from "vitest";
import type { ValidationDiagnostic } from "../types";
import {
  createServerValidationRunner,
  mergeValidationDiagnostics,
  requestServerValidation
} from "./validation/useServerValidation";

describe("Step 6.3 editor semantic validation", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("debounces repeated validation requests and keeps only the latest model payload", async () => {
    vi.useFakeTimers();

    const fetchValidation = vi.fn(async (modelJson: string) => ({
      valid: modelJson === "latest-model",
      diagnostics: []
    }));
    const onPendingChange = vi.fn();
    const onResultChange = vi.fn();
    const runner = createServerValidationRunner(fetchValidation, {
      onPendingChange,
      onResultChange
    });

    runner.validate("older-model");
    runner.validate("latest-model");

    expect(fetchValidation).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(349);
    expect(fetchValidation).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);

    expect(fetchValidation).toHaveBeenCalledTimes(1);
    expect(fetchValidation).toHaveBeenCalledWith("latest-model", expect.any(AbortSignal));
    expect(onPendingChange).toHaveBeenCalledWith(true);
    expect(onPendingChange).toHaveBeenLastCalledWith(false);
    expect(onResultChange).toHaveBeenLastCalledWith({
      valid: true,
      diagnostics: []
    });
  });

  it("keeps broken-reference diagnostics renderable in the editor loop", () => {
    const serverDiagnostic: ValidationDiagnostic = {
      layer: "semantic",
      severity: "error",
      code: "unknown_reference_target",
      message: "Entity WorkItem field ownerRef: reference target not found: TeamProfile",
      sourceModule: "dsl:semantic-validator",
      path: "concepts[WorkItem].fields[ownerRef]",
      concept: "WorkItem",
      field: "ownerRef",
      section: "model",
      suggestedFix: "Point the reference at an existing concept 'TeamProfile' or fix the target concept name.",
      helpKey: "validation.semantic.unknown_reference_target"
    };

    const merged = mergeValidationDiagnostics(
      [
        {
          layer: "structural",
          severity: "warning",
          code: "missing_description",
          message: "Field description is recommended.",
          sourceModule: "authoring",
          path: "concepts[WorkItem].fields[ownerRef]"
        }
      ],
      [serverDiagnostic, serverDiagnostic]
    );

    expect(merged).toHaveLength(2);
    expect(merged.find((entry) => entry.code === "unknown_reference_target")).toMatchObject({
      concept: "WorkItem",
      field: "ownerRef",
      severity: "error"
    });
  });

  it("swallows offline validation failures so local editing can continue", async () => {
    const result = await requestServerValidation(
      "{\"namespace\":\"trial.offline\"}",
      async () => {
        throw new TypeError("Failed to fetch");
      }
    );

    expect(result).toBeNull();
  });

  it("rejects invalid model payloads before save when semantic validation reports blocking errors", async () => {
    const invalidBeforeSave = await requestServerValidation(
      "{\"namespace\":\"trial.invalid\"}",
      async () => ({
        valid: false,
        diagnostics: [
          {
            layer: "semantic",
            severity: "error",
            code: "invalid_before_save",
            message: "Invalid model rejected before save.",
            sourceModule: "validation",
            path: "concepts[0]"
          }
        ]
      })
    );

    expect(invalidBeforeSave?.valid).toBe(false);
    expect(invalidBeforeSave?.diagnostics[0]?.message).toContain("before save");
  });
});
