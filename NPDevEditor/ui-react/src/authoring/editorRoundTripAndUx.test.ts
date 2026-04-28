import { describe, expect, it } from "vitest";

import type { AuthoringConfigDocument } from "./config/configDocumentTypes";
import type { AuthoringModelDocument } from "./editors/modelDocumentTypes";
import { buildConfigValidationDiagnostics, buildModelValidationDiagnostics } from "./validation/authoringValidation";
import { buildPreviewManifest, buildInitialPreviewContext, resolveFieldInteractionState } from "./preview/previewManifest";
import {
  applySynchronizedJsonDraft,
  createSynchronizedJsonSnapshot,
  reconcileSynchronizedJsonSnapshot
} from "./json/synchronizedJsonState";
import { validateConfigDocument } from "./config/configValidation";
import { validateModelDocument } from "./editors/modelValidation";
import {
  buildOfficialSampleWorkspaceSeed,
  buildWorkspaceSeed,
  listAuthoringCategoryCards,
  listAuthoringStartModes,
  listOfficialSampleCards
} from "./services/modelLoader";
import {
  buildWorkspaceConfigSourceKey,
  loadWorkspaceConfigDocument,
  serializeConfigDocument
} from "./config/configDocumentService";
import {
  buildWorkspaceSourceKey,
  loadWorkspaceModelDocument,
  serializeModelDocument
} from "./services/modelDocumentService";
import {
  buildAuthoringBundle,
  buildImportedBundleSessions,
  toCanonicalModelDocument
} from "./io/bundleIoService";
import { buildPipelinePackageEntries } from "./pipeline/pipelineHandoff";

function cloneModel(document: AuthoringModelDocument): AuthoringModelDocument {
  return JSON.parse(JSON.stringify(document)) as AuthoringModelDocument;
}

function cloneConfig(document: AuthoringConfigDocument): AuthoringConfigDocument {
  return JSON.parse(JSON.stringify(document)) as AuthoringConfigDocument;
}

describe("Step 46 editor round-trip and UX confidence", () => {
  it("keeps semantic diff empty for empty field list, null optional values, and deeply nested JSONB notes", async () => {
    const emptyFieldShape = {
      namespace: "trial.roundtrip",
      dslVersion: "1.0.0",
      version: "1.0",
      concepts: [
        {
          name: "DraftNote",
          fields: []
        }
      ],
      enums: [],
      flows: [],
      queries: [],
      ruleProfiles: [],
      procedures: [],
      panels: [],
      metadata: {
        notes: {
          deep: {
            jsonb: {
              optional: null
            }
          }
        }
      }
    } as unknown as AuthoringModelDocument;

    const snapshot = createSynchronizedJsonSnapshot(emptyFieldShape, validateModelDocument);
    const result = applySynchronizedJsonDraft(snapshot, snapshot.draftText, validateModelDocument);

    expect(result.snapshot.issues).toEqual([]);
    expect(result.snapshot.lastAppliedCanonicalText).toBe(snapshot.lastAppliedCanonicalText);
  });

  it("surfaces the expected validation issue for an empty concept list without losing draft stability", async () => {
    const emptyConceptShape = {
      namespace: "trial.roundtrip",
      dslVersion: "1.0.0",
      version: "1.0",
      concepts: [],
      enums: [],
      flows: [],
      queries: [],
      ruleProfiles: [],
      procedures: [],
      panels: [],
      metadata: {
        notes: {
          deep: {
            jsonb: {
              optional: null
            }
          }
        }
      }
    } as unknown as AuthoringModelDocument;

    const snapshot = createSynchronizedJsonSnapshot(emptyConceptShape, validateModelDocument);
    const result = applySynchronizedJsonDraft(snapshot, snapshot.draftText, validateModelDocument);

    expect(result.snapshot.issues).toEqual([
      {
        severity: "error",
        path: "concepts",
        message: "At least one concept is required."
      }
    ]);
    expect(result.snapshot.lastAppliedCanonicalText).toBe(snapshot.lastAppliedCanonicalText);
  });

  it("supports form mode to JSON mode round-trip for the canonical demo model", async () => {
    const workspace = buildWorkspaceSeed("canonical-demo");
    const session = await loadWorkspaceModelDocument(workspace);
    const baseline = createSynchronizedJsonSnapshot(session.document, validateModelDocument);

    const result = applySynchronizedJsonDraft(baseline, baseline.draftText, validateModelDocument);

    expect(result.appliedDocument).toEqual(session.document);
    expect(result.snapshot.issues).toEqual([]);
    expect(result.snapshot.lastAppliedCanonicalText).toBe(serializeModelDocument(session.document));
    expect(buildWorkspaceSourceKey(workspace)).toBe("canonical-demo");
  });

  it("supports JSON mode back to form mode round-trip after a raw edit", async () => {
    const workspace = buildWorkspaceSeed("canonical-demo");
    const session = await loadWorkspaceModelDocument(workspace);
    const baseline = createSynchronizedJsonSnapshot(session.document, validateModelDocument);
    const editedDraft = baseline.draftText.replace('"version": "1.0"', '"version": "1.1"');

    const result = applySynchronizedJsonDraft(baseline, editedDraft, validateModelDocument);
    const reloaded = createSynchronizedJsonSnapshot(result.appliedDocument!, validateModelDocument);

    expect(result.appliedDocument?.version).toBe("1.1");
    expect(reloaded.draftText).toBe(result.snapshot.lastAppliedCanonicalText);
    expect(reloaded.hasExternalConflict).toBe(false);
  });

  it("flags external conflicts when a raw JSON draft diverges from a changed form document", async () => {
    const workspace = buildWorkspaceSeed("canonical-demo");
    const session = await loadWorkspaceModelDocument(workspace);
    const baseline = createSynchronizedJsonSnapshot(session.document, validateModelDocument);
    const pendingDraft = {
      ...baseline,
      draftText: baseline.draftText + "\n",
      issues: []
    };
    const externallyChanged = cloneModel(session.document);
    externallyChanged.version = "2.0";

    const reconciled = reconcileSynchronizedJsonSnapshot(pendingDraft, externallyChanged, validateModelDocument);

    expect(reconciled.hasExternalConflict).toBe(true);
    expect(reconciled.draftText).toContain('"version": "1.0"');
  });

  it("keeps import/export bundle content stable across imported sessions", async () => {
    const workspace = buildWorkspaceSeed("canonical-demo");
    const modelSession = await loadWorkspaceModelDocument(workspace);
    const configSession = await loadWorkspaceConfigDocument(workspace);

    const bundle = buildAuthoringBundle(modelSession, configSession);
    const imported = buildImportedBundleSessions(bundle, "canonical-demo");

    expect(serializeModelDocument(imported.modelSession.document)).toBe(serializeModelDocument(modelSession.document));
    expect(serializeConfigDocument(imported.configSession.document)).toBe(serializeConfigDocument(configSession.document));
    expect(buildWorkspaceConfigSourceKey(workspace)).toBe("config:canonical-demo");
  });

  it("keeps canonical governed authoring surfaces deterministic for Editor export and handoff", async () => {
    const workspace = buildOfficialSampleWorkspaceSeed("medium-expense-approval");
    const modelSession = await loadWorkspaceModelDocument(workspace);
    const configSession = await loadWorkspaceConfigDocument(workspace);
    const sourceConceptNames = modelSession.document.concepts.map((concept) => concept.name);

    expect(modelSession.document.concepts.length).toBeGreaterThan(0);
    expect(modelSession.document.queries.length).toBeGreaterThan(0);
    expect(modelSession.document.ruleProfiles.length).toBeGreaterThan(0);
    expect(modelSession.document.procedures.length).toBeGreaterThan(0);
    expect(modelSession.document.panels.length).toBeGreaterThan(0);

    const bundle = buildAuthoringBundle(modelSession, configSession);
    const canonicalModel = toCanonicalModelDocument(bundle.model);
    const canonicalText = serializeModelDocument(canonicalModel);

    expect(canonicalModel.concepts.map((concept) => concept.name)).toEqual(expect.arrayContaining(sourceConceptNames));
    expect(Object.prototype.hasOwnProperty.call(canonicalModel, "entities")).toBe(false);
    expect(canonicalText).toContain('"concepts"');
    expect(canonicalText).toContain('"procedures"');
    expect(canonicalText).toContain('"panels"');
    expect(canonicalText).not.toContain('"entities"');

    const handoffEntries = buildPipelinePackageEntries(bundle, "medium-expense-approval", "handoff", []);
    const handoffModel = handoffEntries.find((entry) => entry.filename === "model.json")?.content ?? "";
    expect(handoffModel).toBe(canonicalText);
    expect(handoffModel).not.toContain('"entities"');

    const legacyImported = buildImportedBundleSessions(
      {
        ...bundle,
        model: {
          ...bundle.model,
          concepts: undefined,
          entities: bundle.model.concepts
        } as unknown as AuthoringModelDocument
      },
      "legacy entities import to canonical concepts export"
    );
    const canonicalLegacyText = serializeModelDocument(toCanonicalModelDocument(legacyImported.modelSession.document));
    expect(canonicalLegacyText).toContain('"concepts"');
    expect(canonicalLegacyText).toContain('"procedures"');
    expect(canonicalLegacyText).toContain('"panels"');
    expect(canonicalLegacyText).not.toContain('"entities"');
  });

  it("builds the canonical demo preview manifest from the current model shape", async () => {
    const session = await loadWorkspaceModelDocument(buildWorkspaceSeed("canonical-demo"));
    const entity = session.document.concepts[0];
    const manifest = buildPreviewManifest(session.document, entity.name);
    const fieldNames = entity.fields.map((field) => field.name);
    const expectedFlow = session.document.flows.find((flow) => flow.input?.concept === entity.name) ?? session.document.flows[0];

    expect(manifest).not.toBeNull();
    expect(manifest?.entity.name).toBe(entity.name);
    expect(manifest?.tabs.length).toBeGreaterThan(0);
    expect(manifest?.tabs.flatMap((tab) => tab.fields).map((field) => field.name)).toEqual(
      expect.arrayContaining(fieldNames)
    );

    const flowAction = manifest?.actions.find((action) => action.title === expectedFlow.name);
    expect(flowAction).toMatchObject({
      kind: "flow",
      label: expectedFlow.action?.label ?? expectedFlow.name
    });
  });

  it("resolves canonical preview interaction state consistently for available fields", async () => {
    const session = await loadWorkspaceModelDocument(buildWorkspaceSeed("canonical-demo"));
    const entityName = session.document.concepts[0].name;
    const manifest = buildPreviewManifest(session.document, entityName);
    const entity = manifest!.entity;
    const previewContext = buildInitialPreviewContext(entity);

    const previewField = manifest!.tabs.flatMap((tab) => tab.fields).find((field) => field.name !== "id");
    expect(previewField).toBeTruthy();

    expect(resolveFieldInteractionState(previewField!, previewContext)).toMatchObject({
      visible: true,
      enabled: true,
      readonly: false
    });
  });

  it("maps model and config validation issues into renderable diagnostics", async () => {
    const invalidModel = cloneModel((await loadWorkspaceModelDocument(buildWorkspaceSeed("canonical-demo"))).document);
    invalidModel.namespace = "";
    invalidModel.concepts[0].fields.push({
      name: "badEnum",
      type: "enum",
      enumValues: []
    });
    invalidModel.concepts[0].fields.push({
      name: "badReference",
      type: "reference",
      reference: {
        target: ""
      }
    });

    const modelDiagnostics = buildModelValidationDiagnostics(invalidModel);
    const modelPaths = modelDiagnostics.map((entry) => entry.path);
    expect(modelPaths).toContain("namespace");
    expect(modelPaths.some((path) => path.endsWith(".enumValues"))).toBe(true);
    expect(modelPaths.some((path) => path.endsWith(".reference.target"))).toBe(true);
    expect(modelDiagnostics.find((entry) => entry.path === "namespace")?.layer).toBe("semantic");
    expect(modelDiagnostics.find((entry) => entry.path.endsWith(".enumValues"))?.layer).toBe("ux-metadata");
    expect(modelDiagnostics.find((entry) => entry.path.endsWith(".reference.target"))?.suggestedFix).toContain("Choose the concept");

    const invalidConfig = cloneConfig((await loadWorkspaceConfigDocument(buildWorkspaceSeed("canonical-demo"))).document);
    invalidConfig.scenario.outputRoot = "";
    invalidConfig.runtime.serverPort = 70000;
    invalidConfig.metadata = {
      capabilityBindings: [{ capability: "", target: "", mode: "guided" }],
      permissionDefaults: { defaultRole: "" }
    };

    const configDiagnostics = buildConfigValidationDiagnostics(invalidConfig);
    expect(configDiagnostics.find((entry) => entry.path === "scenario.outputRoot")?.section).toBe("scenario");
    expect(configDiagnostics.find((entry) => entry.path === "runtime.serverPort")?.layer).toBe("structural");
    expect(configDiagnostics.find((entry) => entry.path === "metadata.permissionDefaults.defaultRole")?.layer).toBe("ux-metadata");
  });

  it("keeps category chooser metadata stable for canonical and official sample paths", () => {
    const startModes = listAuthoringStartModes();
    const categoryCards = listAuthoringCategoryCards();
    const officialSamples = listOfficialSampleCards();
    const expenseSeed = buildOfficialSampleWorkspaceSeed("medium-expense-approval");

    expect(startModes[0]?.id).toBe("canonical-demo");
    expect(startModes[0]?.recommended).toBe(true);
    expect(categoryCards.find((entry) => entry.id === "official-samples")?.beginnerSafe).toBe(true);
    expect(officialSamples.map((entry) => entry.id)).toEqual([
      "simple-user-registry",
      "simple-contact-intake",
      "medium-expense-approval"
    ]);
    expect(expenseSeed).toMatchObject({
      modelSource: "official-samples",
      sampleId: "medium-expense-approval",
      primaryActionLabel: "Open sample in editor shell"
    });
  });
});
