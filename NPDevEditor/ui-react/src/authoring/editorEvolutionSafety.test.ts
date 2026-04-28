import { describe, expect, it } from "vitest";

import type { AuthoringConfigDocument } from "./config/configDocumentTypes";
import type {
  AuthoringEnumOption,
  AuthoringModelDocument
} from "./editors/modelDocumentTypes";
import { loadWorkspaceConfigDocument } from "./config/configDocumentService";
import { buildImportedBundleSessions } from "./io/bundleIoService";
import { buildPreviewManifest } from "./preview/previewManifest";
import { buildWorkspaceSeed } from "./services/modelLoader";
import { loadWorkspaceModelDocument, serializeModelDocument } from "./services/modelDocumentService";
import { validateModelDocument } from "./editors/modelValidation";

function cloneModel(document: AuthoringModelDocument): AuthoringModelDocument {
  return JSON.parse(JSON.stringify(document)) as AuthoringModelDocument;
}

function cloneConfig(document: AuthoringConfigDocument): AuthoringConfigDocument {
  return JSON.parse(JSON.stringify(document)) as AuthoringConfigDocument;
}

describe("Step 47 regeneration and evolution safety", () => {
  it("reopens an evolved canonical model cleanly through imported authoring sessions", async () => {
    const workspace = buildWorkspaceSeed("canonical-demo");
    const modelSession = await loadWorkspaceModelDocument(workspace);
    const configSession = await loadWorkspaceConfigDocument(workspace);

    const evolvedModel = cloneModel(modelSession.document);
    const evolvedConfig = cloneConfig(configSession.document);
    evolvedModel.version = "1.1";

    const targetEntity = evolvedModel.concepts[0];
    const targetFlow =
      evolvedModel.flows.find((flow) => flow.input?.concept === targetEntity?.name) ?? evolvedModel.flows[0];

    expect(targetEntity).toBeTruthy();
    expect(targetFlow).toBeTruthy();

    targetEntity!.fields.push({
      name: "operatorNote",
      type: "string",
      ui: {
        label: "Operator note",
        shortLabel: "Note",
        description: "Optional note used to verify model-driven editor reopen behavior",
        helpText: "A safe additive field used to verify editor reopen behavior",
        group: "Operations",
        section: "Follow-up",
        order: 55,
        listColumn: true,
        listColumnOrder: 55,
        width: "md"
      }
    });

    targetEntity!.ui = {
      ...targetEntity!.ui,
      helpText: "Evolved model-driven baseline used to verify safe iteration."
    };

    const statusField = targetEntity!.fields.find((field) => field.name === "status");
    const evolvedOption = (statusField?.enumValues as AuthoringEnumOption[] | undefined)?.find(
      (option) => typeof option !== "string"
    ) as AuthoringEnumOption | undefined;
    expect(evolvedOption).toBeTruthy();
    const evolvedOptionValue = evolvedOption!.value;
    evolvedOption!.label = "Evolved status option";
    evolvedOption!.iconHint = "status-evolved";

    targetFlow!.action = {
      ...targetFlow!.action,
      label: "Create evolved record",
      confirmationText: "Create this evolved record and queue related follow-up actions?"
    };

    const imported = buildImportedBundleSessions(
      {
        model: evolvedModel,
        config: evolvedConfig
      },
      "evolved-canonical-demo"
    );

    const diagnostics = validateModelDocument(imported.modelSession.document);
    expect(diagnostics.filter((entry) => entry.severity === "error")).toEqual([]);

    const serialized = serializeModelDocument(imported.modelSession.document);
    expect(serialized).toContain('"operatorNote"');
    expect(serialized).toContain('"Evolved status option"');
    expect(serialized).toContain('"Create evolved record"');

    const preview = buildPreviewManifest(imported.modelSession.document, targetEntity!.name);
    expect(preview?.tableColumns.map((column) => column.fieldName)).toContain("operatorNote");

    const statusPreview = preview?.tabs
      .flatMap((tab) => tab.fields)
      .find((field) => field.name === "status");
    expect(statusPreview?.enumOptions?.find((option) => option.value === evolvedOptionValue)).toMatchObject({
      label: "Evolved status option",
      iconHint: "status-evolved"
    });

    const flowAction = preview?.actions.find((action) => action.title === targetFlow!.name);
    expect(flowAction).toMatchObject({
      kind: "flow",
      label: "Create evolved record",
      confirmationText: "Create this evolved record and queue related follow-up actions?"
    });
  });
});
