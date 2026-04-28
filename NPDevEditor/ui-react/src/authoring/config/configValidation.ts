import type {
  AuthoringCapabilityBinding,
  AuthoringConfigDocument,
  AuthoringConfigValidationIssue
} from "./configDocumentTypes";

function hasText(value: string | undefined | null): boolean {
  return Boolean(value && value.trim().length > 0);
}

function validateBinding(binding: AuthoringCapabilityBinding, index: number): AuthoringConfigValidationIssue[] {
  const issues: AuthoringConfigValidationIssue[] = [];
  if (!hasText(binding.capability)) {
    issues.push({
      severity: "warning",
      path: `metadata.capabilityBindings[${index}].capability`,
      message: "Capability binding should name the capability it is guiding."
    });
  }
  if (!hasText(binding.target)) {
    issues.push({
      severity: "warning",
      path: `metadata.capabilityBindings[${index}].target`,
      message: "Capability binding should describe the runtime or adapter target."
    });
  }
  return issues;
}

export function validateConfigDocument(document: AuthoringConfigDocument): AuthoringConfigValidationIssue[] {
  const issues: AuthoringConfigValidationIssue[] = [];

  if (document.configVersion !== "1.0") {
    issues.push({
      severity: "error",
      path: "configVersion",
      message: "Config version must remain 1.0 for the current schema."
    });
  }

  if (!hasText(document.scenario.name)) {
    issues.push({
      severity: "error",
      path: "scenario.name",
      message: "Scenario name is required."
    });
  }

  if (!hasText(document.scenario.outputRoot)) {
    issues.push({
      severity: "error",
      path: "scenario.outputRoot",
      message: "Scenario outputRoot is required."
    });
  }

  if (!hasText(document.bootstrap.root)) {
    issues.push({
      severity: "error",
      path: "bootstrap.root",
      message: "Bootstrap root is required."
    });
  }

  if (!hasText(document.artifact.root)) {
    issues.push({
      severity: "error",
      path: "artifact.root",
      message: "Artifact root is required."
    });
  }

  if (!hasText(document.finalExec.root)) {
    issues.push({
      severity: "error",
      path: "finalExec.root",
      message: "FinalExec root is required."
    });
  }

  if (document.database.port < 1 || document.database.port > 65535) {
    issues.push({
      severity: "error",
      path: "database.port",
      message: "Database port must stay between 1 and 65535."
    });
  }

  if (document.runtime.serverPort < 1 || document.runtime.serverPort > 65535) {
    issues.push({
      severity: "error",
      path: "runtime.serverPort",
      message: "Runtime serverPort must stay between 1 and 65535."
    });
  }

  if (!hasText(document.database.database)) {
    issues.push({
      severity: "error",
      path: "database.database",
      message: "Database name is required."
    });
  }

  if (!hasText(document.runtime.springProfile)) {
    issues.push({
      severity: "warning",
      path: "runtime.springProfile",
      message: "Spring profile is empty. A non-empty runtime profile is recommended."
    });
  }

  if (document.runtime.gradleTask !== "bootRun") {
    issues.push({
      severity: "error",
      path: "runtime.gradleTask",
      message: "The current schema only supports bootRun."
    });
  }

  const bindings = document.metadata?.capabilityBindings ?? [];
  bindings.forEach((binding, index) => {
    issues.push(...validateBinding(binding, index));
  });

  if ((document.metadata?.permissionDefaults?.defaultRole ?? "").trim().length === 0) {
    issues.push({
      severity: "warning",
      path: "metadata.permissionDefaults.defaultRole",
      message: "No default role is set for guided preview/authoring expectations."
    });
  }

  return issues;
}
