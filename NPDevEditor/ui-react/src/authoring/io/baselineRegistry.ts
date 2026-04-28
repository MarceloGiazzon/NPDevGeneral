import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import type { AuthoringModelDocument } from "../editors/modelDocumentTypes";
import type { AuthoringBundle } from "./bundleTypes";

import { getCanonicalSampleEntry } from "../samples/canonicalSampleRegistry";
import { DEFAULT_STARTER_TEMPLATE_ID, buildStarterTemplateModel } from "../templates/starterTemplates";

export type BaselineOption = {
  id: string;
  label: string;
};

const CONFIG_SCHEMA_ID = "https://npdev.local/schema/npdev-config-v1.schema.json";

function requireCanonicalSampleEntry(sampleId: string) {
  const entry = getCanonicalSampleEntry(sampleId);
  if (entry) {
    return entry;
  }

  throw new Error(`Missing canonical sample entry for ${sampleId}.`);
}

function buildBaselineConfig(): AuthoringConfigDocument {
  const outputRoot = "NPDevSamples\\canonical-demo\\Output";
  return {
    $schema: CONFIG_SCHEMA_ID,
    configVersion: "1.0",
    scenario: {
      name: "canonical-demo",
      description: "Generic canonical baseline for generated authoring comparison.",
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
      root: "NPDevRuntimeHost",
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
      database: "canonical_demo",
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
      projectionPreset: "canonical-baseline",
      samplePresetLabel: "canonical-demo",
      notes: "Generic canonical baseline generated from the starter template."
    }
  };
}

const BASELINE_REGISTRY: Record<string, AuthoringBundle> = {
  "canonical-demo": {
    model: buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID),
    config: buildBaselineConfig()
  },
  "official-samples/simple-user-registry": {
    model: requireCanonicalSampleEntry("simple-user-registry").model as AuthoringModelDocument,
    config: requireCanonicalSampleEntry("simple-user-registry").config as AuthoringConfigDocument
  },
  "official-samples/simple-contact-intake": {
    model: requireCanonicalSampleEntry("simple-contact-intake").model as AuthoringModelDocument,
    config: requireCanonicalSampleEntry("simple-contact-intake").config as AuthoringConfigDocument
  },
  "official-samples/medium-expense-approval": {
    model: requireCanonicalSampleEntry("medium-expense-approval").model as AuthoringModelDocument,
    config: requireCanonicalSampleEntry("medium-expense-approval").config as AuthoringConfigDocument
  }
};

export function listBaselineOptions(): BaselineOption[] {
  return [
    { id: "canonical-demo", label: "Canonical demo" },
    { id: "official-samples/simple-user-registry", label: "Official sample: simple-user-registry" },
    { id: "official-samples/simple-contact-intake", label: "Official sample: simple-contact-intake" },
    { id: "official-samples/medium-expense-approval", label: "Official sample: medium-expense-approval" }
  ];
}

export function getBaselineBundle(baselineId: string): AuthoringBundle | null {
  const bundle = BASELINE_REGISTRY[baselineId];
  return bundle ? (JSON.parse(JSON.stringify(bundle)) as AuthoringBundle) : null;
}
