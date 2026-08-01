import { describe, expect, it } from "vitest";

import type { AuthoringModelDocument } from "./modelDocumentTypes";
import { updateField } from "./editorUtils";

/**
 * Move 9 B2 (docs/ACCEPTED_BOUNDARIES.md B1): renaming a field in the editor must automatically
 * stamp `renamedFrom` into the saved model -- the DSL/migration side already treats a declared
 * `renamedFrom` as "preserve this column's data" instead of "drop the old column, add a new one"
 * (see `SchemaLifecycleExecutorInPlaceRenameTest`); the only gap was the editor never emitting it.
 * `updateField` (editorUtils.ts) is the single choke point every field-name edit passes through
 * (`FieldDetailsEditor.tsx`'s "Field name" input is its only caller that changes `name`), so these
 * tests exercise it directly rather than the React component tree.
 */
function emptyDocument(): AuthoringModelDocument {
  return {
    namespace: "test",
    version: "1.0",
    domainTypes: [],
    concepts: [
      {
        name: "Widget",
        fields: [{ name: "quantity", type: "integer" }]
      }
    ],
    capabilities: [],
    bindings: [],
    events: [],
    orchestrationRules: [],
    flows: [],
    queries: [],
    ruleProfiles: [],
    procedures: [],
    panels: []
  };
}

describe("Move 9 B2: updateField stamps renamedFrom on an actual rename", () => {
  it("stamps renamedFrom with the original name when a field is renamed", () => {
    const renamed = updateField(emptyDocument(), "Widget", "quantity", (field) => ({
      ...field,
      name: "auditQuantity"
    }));

    const field = renamed.concepts[0].fields[0];
    expect(field.name).toBe("auditQuantity");
    expect(field.renamedFrom).toBe("quantity");
  });

  it("does not stamp renamedFrom when the update leaves the name unchanged", () => {
    const updated = updateField(emptyDocument(), "Widget", "quantity", (field) => ({
      ...field,
      required: true
    }));

    const field = updated.concepts[0].fields[0];
    expect(field.name).toBe("quantity");
    expect(field.required).toBe(true);
    expect(field.renamedFrom).toBeUndefined();
  });

  it("preserves the ORIGINAL name across two renames in one session, not the intermediate name", () => {
    const first = updateField(emptyDocument(), "Widget", "quantity", (field) => ({
      ...field,
      name: "auditQuantity"
    }));
    const second = updateField(first, "Widget", "auditQuantity", (field) => ({
      ...field,
      name: "finalQuantity"
    }));

    const field = second.concepts[0].fields[0];
    expect(field.name).toBe("finalQuantity");
    expect(field.renamedFrom).toBe("quantity");
  });

  it("clears renamedFrom when the field is renamed back to its original name", () => {
    const renamed = updateField(emptyDocument(), "Widget", "quantity", (field) => ({
      ...field,
      name: "auditQuantity"
    }));
    const revertedBack = updateField(renamed, "Widget", "auditQuantity", (field) => ({
      ...field,
      name: "quantity"
    }));

    const field = revertedBack.concepts[0].fields[0];
    expect(field.name).toBe("quantity");
    expect(field.renamedFrom).toBeUndefined();
  });

  it("never stamps renamedFrom on a brand-new field (adding a field never calls updateField)", () => {
    const document = emptyDocument();
    document.concepts[0].fields.push({ name: "note", type: "string" });

    expect(document.concepts[0].fields[1].renamedFrom).toBeUndefined();
  });
});
