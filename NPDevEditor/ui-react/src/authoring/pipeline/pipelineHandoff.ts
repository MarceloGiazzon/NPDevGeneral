import type { ValidationDiagnostic } from "../../types";
import { serializeJsonDocument } from "../services/serialization/jsonSerialization";
import type { AuthoringBundle } from "../io/bundleTypes";
import { toCanonicalModelDocument } from "../io/bundleIoService";

export type AuthoringPipelineManifest = {
  contractVersion: "1.0";
  exportedAt: string;
  modelPath: string;
  configPath: string;
  validationReportPath: string;
  bundleLabel: string;
  scenarioName: string;
  namespace: string;
  artifactRoot: string;
  recommendedHandoffDir: string;
  files: {
    model: string;
    config: string;
    validationReport: string;
    manifest: string;
    readme: string;
    helperScript: string;
  };
};

export type AuthoringValidationReport = {
  contractVersion: "1.0";
  status: "passed" | "warning" | "failed";
  diagnostics: ValidationDiagnostic[];
  summary: {
    errorCount: number;
    warningCount: number;
    checkedFiles: string[];
    notes: string[];
  };
};

type PipelinePackageEntry = {
  filename: string;
  content: string;
  contentType: string;
};

type FileSystemWindow = Window & {
  showDirectoryPicker?: () => Promise<{
    getFileHandle: (
      name: string,
      options: { create: boolean }
    ) => Promise<{
      createWritable: () => Promise<{
        write: (content: string) => Promise<void>;
        close: () => Promise<void>;
      }>;
    }>;
  }>;
};

export function buildRecommendedHandoffDir(bundle: AuthoringBundle): string {
  return `${bundle.config.scenario.outputRoot}\\AuthoringHandoff`;
}

export function buildPipelineCommandPreview(handoffDir: string, artifactRoot: string): string {
  const modelPath = `${handoffDir}\\model.json`;
  const configPath = `${handoffDir}\\config.json`;
  return `powershell -ExecutionPolicy Bypass -Command "Set-Location '.\\NPDevGenerator'; .\\gradlew.bat :generator:run --args='--config \\"${configPath}\\" --model \\"${modelPath}\\"'"`;
}

function buildPipelineManifest(
  bundle: AuthoringBundle,
  bundleLabel: string,
  handoffDir: string
): AuthoringPipelineManifest {
  return {
    contractVersion: "1.0",
    exportedAt: new Date().toISOString(),
    modelPath: "model.json",
    configPath: "config.json",
    validationReportPath: "validation-report.json",
    bundleLabel,
    scenarioName: bundle.config.scenario.name,
    namespace: bundle.model.namespace,
    artifactRoot: bundle.config.artifact.root,
    recommendedHandoffDir: handoffDir,
    files: {
      model: "model.json",
      config: "config.json",
      validationReport: "validation-report.json",
      manifest: "authoring-handoff-manifest.json",
      readme: "README-authoring-handoff.txt",
      helperScript: "RUN-NP-HANDOFF.ps1"
    }
  };
}

function buildValidationReport(
  diagnostics: ValidationDiagnostic[],
  bundle: AuthoringBundle
): AuthoringValidationReport {
  const errorCount = diagnostics.filter((entry) => entry.severity === "error").length;
  const warningCount = diagnostics.filter((entry) => entry.severity === "warning").length;

  return {
    contractVersion: "1.0",
    status: errorCount > 0 ? "failed" : warningCount > 0 ? "warning" : "passed",
    diagnostics,
    summary: {
      errorCount,
      warningCount,
      checkedFiles: ["model.json", "config.json"],
      notes: [
        `Scenario ${bundle.config.scenario.name} exported from the canonical Editor handoff path.`,
        "Generator should be able to validate and compile the exported model without Editor internals."
      ]
    }
  };
}

function buildReadme(bundle: AuthoringBundle, handoffDir: string): string {
  return [
    "NPDev authoring handoff package",
    "",
    `Scenario: ${bundle.config.scenario.name}`,
    `Namespace: ${bundle.model.namespace}`,
    `Artifact root: ${bundle.config.artifact.root}`,
    "",
    "This package is meant to feed the normal NP export pipeline.",
    "It does not write directly into platform source folders.",
    "",
    "Suggested command:",
    buildPipelineCommandPreview(handoffDir, bundle.config.artifact.root),
    "",
    "The exported config remains the source of truth for Output\\ArtifactNP and Output\\App assembly.",
    "",
    "Files in this handoff package:",
    "- model.json",
    "- config.json",
    "- validation-report.json",
    "- authoring-handoff-manifest.json",
    "- RUN-NP-HANDOFF.ps1"
  ].join("\r\n");
}

function buildHelperScript(handoffDir: string, artifactRoot: string): string {
  // REG-144: this used to emit `$NPDevRoot = 'D:\WorkSpace\NPDev_General'` -- the author's machine,
  // and not even a real path on it (the checkout is under D:\WorkSpace\NPDev\). Anyone running the
  // generated helper got a confusing failure deep inside Gradle instead of being told what to pass.
  // The handoff package is unpacked outside the repo, so the root cannot be derived from
  // $PSScriptRoot; fall back to NPDEV_ROOT (the convention docs/GETTING_STARTED.md already
  // documents) and otherwise stop immediately with an actionable message.
  return [
    "param(",
    "  [string]$NPDevRoot = $env:NPDEV_ROOT",
    ")",
    "",
    "if ([string]::IsNullOrWhiteSpace($NPDevRoot)) {",
    "  throw \"Pass -NPDevRoot <path to your NPDev checkout>, or set the NPDEV_ROOT environment \" +",
    "        \"variable. Example: .\\RUN-NP-HANDOFF.ps1 -NPDevRoot C:\\src\\NPDevGeneral\"",
    "}",
    "if (-not (Test-Path -LiteralPath (Join-Path $NPDevRoot 'NPDevGenerator'))) {",
    "  throw \"'$NPDevRoot' does not look like an NPDev checkout (no NPDevGenerator directory).\"",
    "}",
    "",
    "$generatorRoot = Join-Path $NPDevRoot 'NPDevGenerator'",
    "$modelPath = Join-Path $PSScriptRoot 'model.json'",
    "$configPath = Join-Path $PSScriptRoot 'config.json'",
    "$argLine = \"--config `\"$configPath`\" --model `\"$modelPath`\"\"",
    "Push-Location $generatorRoot",
    "try {",
    "  & .\\gradlew.bat ':generator:run' \"--args=$argLine\"",
    "}",
    "finally {",
    "  Pop-Location",
    "}"
  ].join("\r\n");
}

export function buildPipelinePackageEntries(
  bundle: AuthoringBundle,
  bundleLabel: string,
  handoffDir: string,
  diagnostics: ValidationDiagnostic[]
): PipelinePackageEntry[] {
  const manifest = buildPipelineManifest(bundle, bundleLabel, handoffDir);
  const validationReport = buildValidationReport(diagnostics, bundle);
  return [
    {
      filename: "model.json",
      content: serializeJsonDocument(toCanonicalModelDocument(bundle.model)),
      contentType: "application/json;charset=utf-8"
    },
    {
      filename: "config.json",
      content: serializeJsonDocument(bundle.config),
      contentType: "application/json;charset=utf-8"
    },
    {
      filename: "validation-report.json",
      content: JSON.stringify(validationReport, null, 2),
      contentType: "application/json;charset=utf-8"
    },
    {
      filename: "authoring-handoff-manifest.json",
      content: JSON.stringify(manifest, null, 2),
      contentType: "application/json;charset=utf-8"
    },
    {
      filename: "README-authoring-handoff.txt",
      content: buildReadme(bundle, handoffDir),
      contentType: "text/plain;charset=utf-8"
    },
    {
      filename: "RUN-NP-HANDOFF.ps1",
      content: buildHelperScript(handoffDir, bundle.config.artifact.root),
      contentType: "text/plain;charset=utf-8"
    }
  ];
}

export function downloadPipelineHandoffPackage(
  bundle: AuthoringBundle,
  bundleLabel: string,
  handoffDir: string,
  diagnostics: ValidationDiagnostic[]
): void {
  for (const entry of buildPipelinePackageEntries(bundle, bundleLabel, handoffDir, diagnostics)) {
    const blob = new Blob([entry.content], { type: entry.contentType });
    const objectUrl = window.URL.createObjectURL(blob);
    const anchor = window.document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = entry.filename;
    anchor.click();
    window.URL.revokeObjectURL(objectUrl);
  }
}

export async function savePipelineHandoffPackageToChosenDirectory(
  bundle: AuthoringBundle,
  bundleLabel: string,
  handoffDir: string,
  diagnostics: ValidationDiagnostic[]
): Promise<"saved" | "unsupported"> {
  const fsWindow = window as FileSystemWindow;
  if (!fsWindow.showDirectoryPicker) {
    return "unsupported";
  }

  const directoryHandle = await fsWindow.showDirectoryPicker();
  for (const entry of buildPipelinePackageEntries(bundle, bundleLabel, handoffDir, diagnostics)) {
    const handle = await directoryHandle.getFileHandle(entry.filename, { create: true });
    const writable = await handle.createWritable();
    await writable.write(entry.content);
    await writable.close();
  }

  return "saved";
}

export function summarizePipelinePreflight(diagnostics: ValidationDiagnostic[]): {
  errorCount: number;
  warningCount: number;
  ready: boolean;
} {
  const errorCount = diagnostics.filter((entry) => entry.severity === "error").length;
  const warningCount = diagnostics.filter((entry) => entry.severity === "warning").length;
  return {
    errorCount,
    warningCount,
    ready: errorCount === 0
  };
}
