import type { AuthoringWorkspaceSeed } from "./modelLoader";
import type {
  AuthoringDocumentSession,
  AuthoringFlow,
  AuthoringModelDocument
} from "../editors/modelDocumentTypes";

import { getCanonicalSampleEntry } from "../samples/canonicalSampleRegistry";
import { DEFAULT_STARTER_TEMPLATE_ID, buildStarterTemplateModel } from "../templates/starterTemplates";

type ModelRegistryEntry = {
  label: string;
  document: AuthoringModelDocument;
};

const CANONICAL_REFERENCE_MODEL = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
const MODEL_SCHEMA_ID = "https://npdev.local/schema/npdev-model-1.0.0.schema.json";

function getOfficialSampleRegistryEntry(sampleId: string): ModelRegistryEntry {
  const entry = getCanonicalSampleEntry(sampleId);
  if (entry) {
    return {
      label: entry.label,
      document: entry.model
    };
  }

  return {
    label: "Official sample: baseline fallback",
    document: CANONICAL_REFERENCE_MODEL
  };
}

function cloneDocument(document: AuthoringModelDocument): AuthoringModelDocument {
  return JSON.parse(JSON.stringify(document)) as AuthoringModelDocument;
}

function normalizeDocument(document: AuthoringModelDocument): AuthoringModelDocument {
  const legacyDocument = document as AuthoringModelDocument & {
    entities?: AuthoringModelDocument["concepts"];
  };
  const concepts = legacyDocument.concepts ?? legacyDocument.entities ?? [];

  return {
    ...document,
    $schema: MODEL_SCHEMA_ID,
    domainTypes: [...(document.domainTypes ?? [])],
    concepts: concepts.map((entity) => ({
      ...entity,
      fields: [...(entity.fields ?? [])],
      invariants: [...(entity.invariants ?? [])],
      lifecycle: entity.lifecycle
        ? {
            ...entity.lifecycle,
            states: [...(entity.lifecycle.states ?? [])],
            transitions: [...(entity.lifecycle.transitions ?? [])]
          }
        : undefined
    })),
    capabilities: [...(document.capabilities ?? [])],
    bindings: [...(document.bindings ?? [])],
    events: [...(document.events ?? [])],
    orchestrationRules: (document.orchestrationRules ?? []).map((rule) => ({
      ...rule,
      actions: [...(rule.actions ?? [])]
    })),
    flows: (document.flows ?? []).map((flow) => ({
      ...flow,
      steps: [...(flow.steps ?? [])]
    })),
    queries: [...(document.queries ?? [])],
    ruleProfiles: [...(document.ruleProfiles ?? [])],
    procedures: (document.procedures ?? []).map((procedure) => ({
      ...procedure,
      parameters: [...(procedure.parameters ?? [])],
      locals: [...(procedure.locals ?? [])],
      variables: [...(procedure.variables ?? [])],
      steps: [...(procedure.steps ?? [])]
    })),
    panels: (document.panels ?? []).map((panel) => ({
      ...panel,
      dataSources: [...(panel.dataSources ?? [])],
      fields: [...(panel.fields ?? [])],
      fieldBindings: [...(panel.fieldBindings ?? [])],
      actions: [...(panel.actions ?? [])]
    })),
    metadata: document.metadata ? { ...document.metadata } : undefined
  };
}

function buildBlankFlow(): AuthoringFlow {
  return {
    name: "NewFlow",
    input: {
      concept: "NewConcept",
      mode: "create"
    },
    steps: [
      {
        name: "return-input",
        type: "return",
        value: "$input"
      }
    ]
  };
}

function buildBlankDocument(workspace: AuthoringWorkspaceSeed): AuthoringModelDocument {
  const namespaceBase = workspace.title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ".")
    .replace(/^\.+|\.+$/g, "");

  return {
    $schema: MODEL_SCHEMA_ID,
    namespace: namespaceBase ? `authoring.${namespaceBase}` : "authoring.newmodel",
    dslVersion: "1.0.0",
    version: "1.0",
    domainTypes: [],
    concepts: [
      {
        name: "NewConcept",
        ui: {
          label: "New concept",
          description: "Starter concept for a guided authoring session."
        },
        fields: [
          {
            name: "id",
            type: "uuid",
            id: true,
            required: true
          }
        ],
        invariants: []
      }
    ],
    capabilities: [
      {
        name: "persistence",
        type: "PersistenceCapability",
        operations: ["save", "findById"]
      }
    ],
    bindings: [
      {
        capability: "persistence",
        adapter: "repository"
      }
    ],
    events: [],
    orchestrationRules: [],
    flows: [buildBlankFlow()],
    queries: [],
    ruleProfiles: [],
    procedures: [],
    panels: []
  };
}

function loadWorkspaceBaseDocument(workspace: AuthoringWorkspaceSeed): ModelRegistryEntry {
  if (workspace.modelSource === "official-samples" && workspace.sampleId) {
    return getOfficialSampleRegistryEntry(workspace.sampleId);
  }

  if (workspace.modelSource === "canonical-demo") {
    return {
      label: "Canonical demo",
      document: CANONICAL_REFERENCE_MODEL
    };
  }

  if (workspace.modelSource === "new-model" && workspace.templateId) {
    return {
      label: `Starter template: ${workspace.templateTitle ?? workspace.templateId}`,
      document: buildStarterTemplateModel(workspace.templateId)
    };
  }

  return {
    label: workspace.title,
    document: buildBlankDocument(workspace)
  };
}

export function buildWorkspaceSourceKey(workspace: AuthoringWorkspaceSeed): string {
  if (workspace.modelSource === "official-samples" && workspace.sampleId) {
    return `${workspace.modelSource}:${workspace.sampleId}`;
  }
  if (workspace.modelSource === "new-model" && workspace.templateId) {
    return `${workspace.modelSource}:${workspace.templateId}`;
  }
  return workspace.modelSource;
}

export async function loadWorkspaceModelDocument(
  workspace: AuthoringWorkspaceSeed
): Promise<AuthoringDocumentSession> {
  const entry = loadWorkspaceBaseDocument(workspace);
  return {
    sourceKey: buildWorkspaceSourceKey(workspace),
    sourceLabel: entry.label,
    document: normalizeDocument(cloneDocument(entry.document)),
    dirty: false,
    lastLoadedLabel: new Date().toLocaleString()
  };
}

export function serializeModelDocument(document: AuthoringModelDocument): string {
  return JSON.stringify(document, null, 2);
}

export function downloadModelDocument(modelDocument: AuthoringModelDocument, filename = "model.json"): void {
  const blob = new Blob([serializeModelDocument(modelDocument)], {
    type: "application/json;charset=utf-8"
  });
  const objectUrl = window.URL.createObjectURL(blob);
  const anchor = window.document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.click();
  window.URL.revokeObjectURL(objectUrl);
}
