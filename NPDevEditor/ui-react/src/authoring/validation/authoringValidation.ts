import type { ValidationDiagnostic, ValidationSeverity } from "../../types";
import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import { validateConfigDocument } from "../config/configValidation";
import type { AuthoringModelDocument } from "../editors/modelDocumentTypes";
import { validateModelDocument } from "../editors/modelValidation";

function severityFromLocal(value: "error" | "warning"): ValidationSeverity {
  return value;
}

function inferModelLayer(path: string): ValidationDiagnostic["layer"] {
  if (path.includes(".ui.") || path.includes("reference.") || path.includes("enumValues")) {
    return "ux-metadata";
  }
  if (
    path.includes("concepts[") ||
    path.includes("queries[") ||
    path.includes("ruleProfiles[") ||
    path.includes("procedures[") ||
    path.includes("panels[") ||
    path.includes("flows") ||
    path.includes("namespace")
  ) {
    return "semantic";
  }
  return "structural";
}

function extractModelContext(
  document: AuthoringModelDocument,
  path: string
): Pick<ValidationDiagnostic, "concept" | "field" | "section"> {
  const entityMatch = path.match(/^concepts\[(\d+)\]/);
  const fieldMatch = path.match(/^concepts\[(\d+)\]\.fields\[(\d+)\]/);
  const conceptIndex = entityMatch ? Number(entityMatch[1]) : null;
  const fieldIndex = fieldMatch ? Number(fieldMatch[2]) : null;
  const concept = conceptIndex != null ? document.concepts[conceptIndex]?.name : undefined;
  const field =
    conceptIndex != null && fieldIndex != null
      ? document.concepts[conceptIndex]?.fields[fieldIndex]?.name
      : undefined;

  const section = path.startsWith("flows")
    ? "flows"
    : path.startsWith("queries")
      ? "queries"
      : path.startsWith("ruleProfiles")
        ? "ruleProfiles"
        : path.startsWith("procedures")
          ? "procedures"
          : path.startsWith("panels")
            ? "panels"
            : path.startsWith("concepts")
              ? "model"
              : "metadata";

  return {
    concept,
    field,
    section
  };
}

function suggestedFixForPath(path: string): string | undefined {
  if (path.endsWith(".name")) {
    return "Provide a stable unique name for this concept or field.";
  }
  if (path.includes(".reference.target")) {
    return "Choose the concept that this reference should point to.";
  }
  if (path.includes(".enumValues")) {
    return "Add at least one enum option or change the field type.";
  }
  if (path === "namespace") {
    return "Set the DSL namespace used by the generated assets.";
  }
  if (path === "version") {
    return "Provide the model version that should be exported.";
  }
  return undefined;
}

export function buildModelValidationDiagnostics(document: AuthoringModelDocument): ValidationDiagnostic[] {
  return validateModelDocument(document).map((issue, index) => {
    const path = issue.path ?? "$";
    return {
      layer: inferModelLayer(path),
      severity: severityFromLocal(issue.severity),
      code: `authoring-model-${index + 1}`,
      message: issue.message,
      sourceModule: "authoring-model-validation",
      path,
      suggestedFix: suggestedFixForPath(path),
      helpKey: "step36-model-validation",
      ...extractModelContext(document, path)
    };
  });
}

function inferConfigLayer(path: string): ValidationDiagnostic["layer"] {
  if (path.startsWith("metadata.")) {
    return "ux-metadata";
  }
  return "structural";
}

function suggestedFixForConfigPath(path: string): string | undefined {
  if (path.startsWith("scenario.")) {
    return "Complete the scenario identity and output root so projection/export has a stable target.";
  }
  if (path.startsWith("runtime.")) {
    return "Adjust runtime settings to a supported boot profile and port.";
  }
  if (path.startsWith("database.")) {
    return "Fill in the database connection defaults required by the current projection flow.";
  }
  if (path.startsWith("metadata.permissionDefaults")) {
    return "Set a default authoring or preview role so permission-aware explanations have a baseline.";
  }
  return undefined;
}

export function buildConfigValidationDiagnostics(document: AuthoringConfigDocument): ValidationDiagnostic[] {
  return validateConfigDocument(document).map((issue, index) => ({
    layer: inferConfigLayer(issue.path),
    severity: severityFromLocal(issue.severity),
    code: `authoring-config-${index + 1}`,
    message: issue.message,
    sourceModule: "authoring-config-validation",
    path: issue.path,
    section: issue.path.split(".")[0] ?? "config",
    suggestedFix: suggestedFixForConfigPath(issue.path),
    helpKey: "step36-config-validation"
  }));
}
