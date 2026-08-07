import { describe, expect, it } from "vitest";
import type { ValidationDiagnostic } from "../../types";
import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import { buildStarterTemplateModel, DEFAULT_STARTER_TEMPLATE_ID } from "../templates/starterTemplates";
import { buildDiagnosticLinkItems } from "./diagnosticLinking";

function diagnostic(overrides: Partial<ValidationDiagnostic>): ValidationDiagnostic {
  return {
    layer: "structural",
    severity: "warning",
    code: "TEST_CODE",
    message: "test message",
    sourceModule: "authoring",
    ...overrides
  };
}

function minimalConfig(overrides: Partial<AuthoringConfigDocument["metadata"]> = {}): AuthoringConfigDocument {
  const base = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
  void base; // model isn't part of config, but keeps this fixture builder symmetric with the model one below
  return {
    configVersion: "1",
    scenario: { name: "test-scenario", outputRoot: "./output" },
    generator: {
      failIfModelMissing: true,
      failIfConfigMissing: true,
      cleanOutputBeforeGenerate: true,
      emitPluginAssets: true,
      emitRuntimeAssets: true,
      emitUiAssets: true
    },
    bootstrap: { root: "./", mergeStrategy: "clean-copy" },
    artifact: { root: "./artifact", generatedFolderName: "generated", libsFolderName: "libs", metaFolderName: "meta" },
    finalExec: { root: "./finalexec", deleteBeforeMount: true },
    database: {
      provider: "postgres",
      host: "localhost",
      port: 5432,
      database: "test",
      username: "test",
      password: "test",
      adminDatabase: "postgres",
      resetMode: "preserve"
    },
    runtime: { springProfile: "dev", serverPort: 8080, javaArgs: [], gradleTask: "bootRun" },
    metadata: overrides
  };
}

describe("buildDiagnosticLinkItems", () => {
  it("maps each validation layer to its own expectation text", () => {
    const model = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
    model.flows = []; // isolate diagnostic-derived items from the template's own default flow(s)
    const items = buildDiagnosticLinkItems(model, null, [
      diagnostic({ layer: "ux-metadata", message: "ux problem" }),
      diagnostic({ layer: "semantic", message: "semantic problem" }),
      diagnostic({ layer: "structural", message: "structural problem" })
    ]);

    expect(items).toHaveLength(3);
    expect(items[0]).toMatchObject({
      title: "ux problem",
      expectation: "This should surface as an authoring-time metadata problem before generation."
    });
    expect(items[1]).toMatchObject({
      title: "semantic problem",
      expectation: "This should be reflected in semantic validation and may affect generated runtime behavior."
    });
    expect(items[2]).toMatchObject({
      title: "structural problem",
      expectation: "This should fail or warn during structural validation before handoff."
    });
  });

  it("prefers the diagnostic's path over its code as the source, falling back when path is absent", () => {
    const model = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
    const items = buildDiagnosticLinkItems(model, null, [
      diagnostic({ code: "HAS_PATH", path: "concepts[0].fields[1]" }),
      diagnostic({ code: "NO_PATH", path: undefined })
    ]);

    expect(items[0].source).toBe("concepts[0].fields[1]");
    expect(items[1].source).toBe("NO_PATH");
  });

  it("picks a flow-oriented evidence hint for flows-section diagnostics, a config hint for config-module diagnostics, and a generic hint otherwise", () => {
    const model = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
    const items = buildDiagnosticLinkItems(model, null, [
      diagnostic({ section: "flows" }),
      diagnostic({ section: "panels", sourceModule: "authoring/config/loader" }),
      diagnostic({ section: "panels", sourceModule: "authoring/editors" })
    ]);

    expect(items[0].evidenceHint).toMatch(/flow-oriented validation output/);
    expect(items[1].evidenceHint).toMatch(/export or runtime configuration issues/);
    expect(items[2].evidenceHint).toMatch(/authoring validation warnings/);
  });

  it("caps diagnostic-derived items at 8 even when more diagnostics are supplied", () => {
    const model = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
    model.flows = []; // isolate diagnostic-derived items from the template's own default flow(s)
    const diagnostics = Array.from({ length: 12 }, (_, index) => diagnostic({ message: `problem ${index}` }));

    const items = buildDiagnosticLinkItems(model, null, diagnostics);

    expect(items).toHaveLength(8);
    expect(items.map((item) => item.title)).toEqual([
      "problem 0",
      "problem 1",
      "problem 2",
      "problem 3",
      "problem 4",
      "problem 5",
      "problem 6",
      "problem 7"
    ]);
  });

  it("appends up to 4 flow trace-expectation items after the diagnostic items, in document order", () => {
    const model = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
    model.flows = [
      { name: "SubmitOrder", steps: [{ name: "validate", type: "validate" }, { name: "persist", type: "call" }] },
      { name: "CancelOrder", steps: [] },
      { name: "ThirdFlow", steps: [{ name: "onlyStep", type: "call" }] },
      { name: "FourthFlow" },
      { name: "FifthFlowShouldBeDropped", steps: [{ name: "s", type: "call" }] }
    ];

    const items = buildDiagnosticLinkItems(model, null, []);

    expect(items).toHaveLength(4);
    expect(items[0]).toMatchObject({
      title: "Trace expectation for SubmitOrder",
      source: "SubmitOrder",
      evidenceHint: "Look for execution/tracing evidence around validate, persist."
    });
    expect(items[1].evidenceHint).toBe("Look for execution/tracing evidence around its declared steps.");
    expect(items[3].title).toBe("Trace expectation for FourthFlow");
  });

  it("appends up to 4 capability-binding items only when config declares them", () => {
    const model = buildStarterTemplateModel(DEFAULT_STARTER_TEMPLATE_ID);
    model.flows = [];

    expect(buildDiagnosticLinkItems(model, null, [])).toHaveLength(0);
    expect(buildDiagnosticLinkItems(model, minimalConfig({}), [])).toHaveLength(0);

    const configWithBindings = minimalConfig({
      capabilityBindings: [
        { capability: "persistence", target: "postgres", mode: "sync" },
        { capability: "notification", target: "", mode: "async" }
      ]
    });
    const items = buildDiagnosticLinkItems(model, configWithBindings, []);

    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ title: "Capability binding: persistence", source: "postgres" });
    // an empty target falls back to the binding's mode as the source.
    expect(items[1]).toMatchObject({ title: "Capability binding: notification", source: "async" });
  });
});
