import { describe, expect, it } from "vitest";

import { buildWorkspaceSeed } from "../services/modelLoader";
import { loadWorkspaceModelDocument, serializeModelDocument } from "../services/modelDocumentService";
import { loadWorkspaceConfigDocument, serializeConfigDocument } from "../config/configDocumentService";
import { serializeJsonDocument } from "../services/serialization/jsonSerialization";
import {
  buildAuthoringBundle,
  buildImportedBundleSessions,
  importBundleFromFiles,
  toCanonicalModelDocument
} from "./bundleIoService";

/**
 * docs/RECORD_SURFACES_PLAN.md P5: ImportExportWorkspace.tsx wires its "Import bundle" button
 * directly to bundleIoService.importBundleFromFiles -- the ONE path in the editor that takes
 * caller-supplied bytes (a File the user picked) back into an authoring session. A regression here
 * silently corrupts a user's model, which is why this exercises the real File.text() boundary
 * (Node 22 exposes File/Blob as globals, no jsdom needed) rather than only the in-memory bundle
 * copy editorRoundTripAndUx.test.ts already covers.
 */

function modelFileFor(bundleModel: ReturnType<typeof toCanonicalModelDocument>): File {
  return new File([serializeJsonDocument(toCanonicalModelDocument(bundleModel))], "model.json", {
    type: "application/json"
  });
}

describe("ImportExportWorkspace bundle round-trip (bundleIoService, the button's real logic)", () => {
  it("round-trips the canonical demo model+config through export text -> File -> import byte-identically", async () => {
    const workspace = buildWorkspaceSeed("canonical-demo");
    const modelSession = await loadWorkspaceModelDocument(workspace);
    const configSession = await loadWorkspaceConfigDocument(workspace);
    const bundle = buildAuthoringBundle(modelSession, configSession);

    // Exactly what downloadBundle() writes and what a user would re-select as model.json/config.json.
    const modelFile = modelFileFor(bundle.model);
    const configFile = new File([serializeConfigDocument(bundle.config)], "config.json", { type: "application/json" });

    const imported = await importBundleFromFiles(modelFile, configFile);
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;

    expect(imported.modelFileName).toBe("model.json");
    expect(imported.configFileName).toBe("config.json");

    const sessions = buildImportedBundleSessions(imported.bundle, "roundtrip");
    expect(serializeModelDocument(toCanonicalModelDocument(sessions.modelSession.document))).toBe(
      serializeJsonDocument(toCanonicalModelDocument(bundle.model))
    );
    expect(serializeConfigDocument(sessions.configSession.document)).toBe(serializeConfigDocument(bundle.config));
  });

  it("maps a legacy `entities` export back to `concepts` through the same File import path", async () => {
    const workspace = buildWorkspaceSeed("canonical-demo");
    const modelSession = await loadWorkspaceModelDocument(workspace);
    const configSession = await loadWorkspaceConfigDocument(workspace);
    const bundle = buildAuthoringBundle(modelSession, configSession);

    const canonical = toCanonicalModelDocument(bundle.model);
    const { concepts, ...rest } = canonical;
    const legacyShape = { ...rest, entities: concepts };
    const modelFile = new File([JSON.stringify(legacyShape, null, 2)], "model.json", { type: "application/json" });
    const configFile = new File([serializeConfigDocument(bundle.config)], "config.json", { type: "application/json" });

    const imported = await importBundleFromFiles(modelFile, configFile);
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;

    expect(imported.bundle.model.concepts.map((concept) => concept.name)).toEqual(
      concepts.map((concept) => concept.name)
    );
    expect(Object.prototype.hasOwnProperty.call(imported.bundle.model, "entities")).toBe(false);
  });

  it("rejects when either file is missing, without touching the caller's sessions", async () => {
    const configFile = new File(["{}"], "config.json", { type: "application/json" });

    const missingModel = await importBundleFromFiles(null, configFile);
    expect(missingModel).toEqual({
      ok: false,
      message: "Choose both model.json and config.json before importing."
    });

    const missingConfig = await importBundleFromFiles(new File(["{}"], "model.json"), null);
    expect(missingConfig.ok).toBe(false);
  });

  it("surfaces a parse-error message instead of throwing on malformed model JSON", async () => {
    const modelFile = new File(["{ not valid json"], "model.json", { type: "application/json" });
    const configFile = new File(["{}"], "config.json", { type: "application/json" });

    const imported = await importBundleFromFiles(modelFile, configFile);
    expect(imported.ok).toBe(false);
    if (imported.ok) return;
    expect(imported.message).toContain("Model import failed:");
  });

  it("surfaces a parse-error message instead of throwing on malformed config JSON", async () => {
    const modelFile = new File(["{}"], "model.json", { type: "application/json" });
    const configFile = new File(["not json at all"], "config.json", { type: "application/json" });

    const imported = await importBundleFromFiles(modelFile, configFile);
    expect(imported.ok).toBe(false);
    if (imported.ok) return;
    expect(imported.message).toContain("Config import failed:");
  });
});
