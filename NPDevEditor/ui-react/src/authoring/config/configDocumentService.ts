import type { AuthoringWorkspaceSeed } from "../services/modelLoader";
import type { AuthoringConfigDocument, AuthoringConfigSession } from "./configDocumentTypes";

import { getCanonicalSampleEntry } from "../samples/canonicalSampleRegistry";
import { applyStarterTemplateConfigMetadata, describeStarterTemplateForConfig } from "../templates/starterTemplates";

type ConfigRegistryEntry = {
  label: string;
  document: AuthoringConfigDocument;
};

const CONFIG_SCHEMA_ID = "https://npdev.local/schema/npdev-config-v1.schema.json";
const RUNTIME_HOST_ROOT = "NPDevRuntimeHost";

function buildCanonicalOutputRoot(scenarioName: string): string {
  return `NPDevSamples\\${scenarioName}\\Output`;
}

function cloneDocument(document: AuthoringConfigDocument): AuthoringConfigDocument {
  const cloned = JSON.parse(JSON.stringify(document)) as AuthoringConfigDocument;
  const scenarioName = cloned.scenario?.name?.trim() || "new-model";
  const outputRoot = buildCanonicalOutputRoot(scenarioName);
  return {
    ...cloned,
    $schema: CONFIG_SCHEMA_ID,
    scenario: {
      ...cloned.scenario,
      outputRoot
    },
    bootstrap: {
      ...cloned.bootstrap,
      root: RUNTIME_HOST_ROOT
    },
    artifact: {
      ...cloned.artifact,
      root: `${outputRoot}\\ArtifactNP`
    },
    finalExec: {
      ...cloned.finalExec,
      root: `${outputRoot}\\App`
    }
  };
}

function getOfficialSampleConfigEntry(workspace: AuthoringWorkspaceSeed): ConfigRegistryEntry {
  if (workspace.sampleId) {
    const entry = getCanonicalSampleEntry(workspace.sampleId);
    if (entry) {
      return {
        label: `Official sample config: ${workspace.sampleId}`,
        document: entry.config
      };
    }
  }

  return {
    label: "Official sample config: baseline fallback",
    document: buildGuidedBlankConfig(workspace)
  };
}

function slugify(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function buildGuidedBlankConfig(workspace: AuthoringWorkspaceSeed): AuthoringConfigDocument {
  const slug = slugify(workspace.title || workspace.modelSource || "new-model");
  const scenarioName = slug || "new-model";
  const outputRoot = buildCanonicalOutputRoot(scenarioName);

  return {
    $schema: CONFIG_SCHEMA_ID,
    configVersion: "1.0",
    scenario: {
      name: scenarioName,
      description: `Guided authoring config for ${workspace.title}.`,
      outputRoot
    },
    generator: {
      failIfModelMissing: true,
      failIfConfigMissing: true,
      cleanOutputBeforeGenerate: true,
      emitPluginAssets: true,
      emitRuntimeAssets: true,
      emitUiAssets: true
    },
    bootstrap: {
      root: RUNTIME_HOST_ROOT,
      mergeStrategy: "clean-copy"
    },
    artifact: {
      root: `${outputRoot}\\ArtifactNP`,
      generatedFolderName: "npdev-generated",
      libsFolderName: "libs",
      metaFolderName: "npdev-meta"
    },
    finalExec: {
      root: `${outputRoot}\\App`,
      deleteBeforeMount: true
    },
    database: {
      provider: "docker-postgres",
      host: "localhost",
      port: 5432,
      database: scenarioName.replace(/-/g, "_"),
      username: "finalexec",
      password: "finalexec",
      adminDatabase: "postgres",
      resetMode: "reset"
    },
    runtime: {
      springProfile: "default",
      serverPort: 8081,
      javaArgs: [],
      gradleTask: "bootRun"
    },
    metadata: {
      projectionPreset: workspace.modelSource === "official-samples" ? "official-sample" : "authoring-default",
      samplePresetLabel: workspace.sampleId,
      notes: workspace.templateId
        ? describeStarterTemplateForConfig(workspace.templateId)
        : "Guided config seed created by the Step 34 config editor."
    }
  };
}

function resolveConfigEntry(workspace: AuthoringWorkspaceSeed): ConfigRegistryEntry {
  if (workspace.modelSource === "official-samples" && workspace.sampleId) {
    return getOfficialSampleConfigEntry(workspace);
  }

  if (workspace.modelSource === "canonical-demo") {
    return {
      label: "Canonical demo config",
      document: buildGuidedBlankConfig(workspace)
    };
  }

  if (workspace.modelSource === "new-model" && workspace.templateId) {
    return {
      label: `Starter template config: ${workspace.templateTitle ?? workspace.templateId}`,
      document: applyStarterTemplateConfigMetadata(buildGuidedBlankConfig(workspace), workspace.templateId)
    };
  }

  return {
    label: `Guided config: ${workspace.title}`,
    document: buildGuidedBlankConfig(workspace)
  };
}

export function buildWorkspaceConfigSourceKey(workspace: AuthoringWorkspaceSeed): string {
  if (workspace.modelSource === "official-samples" && workspace.sampleId) {
    return `config:${workspace.modelSource}:${workspace.sampleId}`;
  }
  if (workspace.modelSource === "new-model" && workspace.templateId) {
    return `config:${workspace.modelSource}:${workspace.templateId}`;
  }
  return `config:${workspace.modelSource}`;
}

export async function loadWorkspaceConfigDocument(
  workspace: AuthoringWorkspaceSeed
): Promise<AuthoringConfigSession> {
  const entry = resolveConfigEntry(workspace);
  return {
    sourceKey: buildWorkspaceConfigSourceKey(workspace),
    sourceLabel: entry.label,
    document: cloneDocument(entry.document),
    dirty: false,
    lastLoadedLabel: new Date().toLocaleString()
  };
}

export function serializeConfigDocument(document: AuthoringConfigDocument): string {
  return JSON.stringify(document, null, 2);
}

export function downloadConfigDocument(configDocument: AuthoringConfigDocument, filename = "config.json"): void {
  const blob = new Blob([serializeConfigDocument(configDocument)], {
    type: "application/json;charset=utf-8"
  });
  const objectUrl = window.URL.createObjectURL(blob);
  const anchor = window.document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.click();
  window.URL.revokeObjectURL(objectUrl);
}
