import { describe, expect, it } from "vitest";

import type { AuthoringEntity, AuthoringField } from "../editors/modelDocumentTypes";
import {
  buildInitialPreviewContext,
  buildPreviewManifest,
  evaluatePreviewExpression,
  resolveFieldInteractionState,
  type PreviewField
} from "./previewManifest";

/**
 * docs/RECORD_SURFACES_PLAN.md P5: evaluatePreviewExpression is the one piece of real logic in this
 * module -- a hand-rolled boolean expression evaluator (&&/||/==/!=, three-valued for unknown
 * clauses) that decides whether a preview field is visible/enabled/readonly/required. It feeds
 * what the user sees in Preview mode and editorRoundTripAndUx.test.ts never exercises it (its two
 * previewManifest cases both use fields with no *When clause at all). Untested logic here fails
 * silently: a broken clause just shows/hides the wrong field, nothing throws.
 */

function field(overrides: Partial<AuthoringField> = {}): AuthoringField {
  return { name: "status", type: "string", ...overrides };
}

function entity(fields: AuthoringField[]): AuthoringEntity {
  return { name: "Order", fields };
}

describe("evaluatePreviewExpression", () => {
  it("returns null for an undefined expression (no clause means 'always allow')", () => {
    expect(evaluatePreviewExpression(undefined, {})).toBeNull();
  });

  it.each([
    ["status == 'approved'", { status: "approved" }, true],
    ["status == 'approved'", { status: "draft" }, false],
    ["status != 'approved'", { status: "draft" }, true],
    ["amount == 100", { amount: 100 }, true],
    ["amount == 100", { amount: "100" }, false], // strict equality: numeric literal, string context value
    ["flagged == true", { flagged: true }, true],
    ["flagged == false", { flagged: true }, false],
    ["owner == null", { owner: null }, true],
    ["owner == null", { owner: "someone" }, false]
  ])("evaluates clause %s against %j -> %s", (expression, context, expected) => {
    expect(evaluatePreviewExpression(expression, context)).toBe(expected);
  });

  it("evaluates a field missing from context as a definite false via strict inequality (undefined !== literal), not as unknown", () => {
    expect(evaluatePreviewExpression("status == 'approved'", {})).toBe(false);
    expect(evaluatePreviewExpression("status != 'approved'", {})).toBe(true);
  });

  it("returns null for an unparseable clause -- the only source of a genuine 'unknown'", () => {
    expect(evaluatePreviewExpression("status >< 'approved'", { status: "approved" })).toBeNull();
  });

  it("combines OR clauses: true if any known clause is true, even if another is unparseable", () => {
    expect(
      evaluatePreviewExpression("status == 'approved' || bogus >< clause", { status: "approved" })
    ).toBe(true);
  });

  it("combines OR clauses: false once every clause resolves to a known false", () => {
    expect(evaluatePreviewExpression("status == 'approved' || status == 'rejected'", { status: "draft" })).toBe(
      false
    );
  });

  it("combines OR clauses: null only when every clause is unparseable (missing-context clauses are known-false, not unknown)", () => {
    expect(evaluatePreviewExpression("aaa >< 1 || bbb >< 2", {})).toBeNull();
    expect(evaluatePreviewExpression("missingA == 'x' || missingB == 'y'", {})).toBe(false);
  });

  it("combines AND clauses: false if any known clause is false, even if another is unparseable", () => {
    expect(
      evaluatePreviewExpression("status == 'approved' && bogus >< clause", { status: "draft" })
    ).toBe(false);
  });

  it("combines AND clauses: true only once every clause resolves to a known true", () => {
    expect(
      evaluatePreviewExpression("status == 'approved' && flagged == true", { status: "approved", flagged: true })
    ).toBe(true);
  });

  it("combines AND clauses: null only when every clause is unparseable (missing-context clauses are known-true for !=, not unknown)", () => {
    expect(evaluatePreviewExpression("aaa >< 1 && bbb >< 2", {})).toBeNull();
    expect(evaluatePreviewExpression("missingA != 'x' && missingB != 'y'", {})).toBe(true);
  });

  it("splits OR before AND (OR is the outermost grouping)", () => {
    // "a==1 && b==2 || c==3" reads as "(a==1 && b==2) || c==3" here since OR is split first.
    expect(evaluatePreviewExpression("a == 1 && b == 2 || c == 3", { a: 1, b: 9, c: 3 })).toBe(true);
    expect(evaluatePreviewExpression("a == 1 && b == 2 || c == 3", { a: 1, b: 9, c: 9 })).toBe(false);
  });
});

describe("buildInitialPreviewContext", () => {
  it("seeds a boolean field to false when no default is set", () => {
    expect(buildInitialPreviewContext(entity([field({ name: "flagged", type: "boolean" })]))).toEqual({
      flagged: false
    });
  });

  it("seeds a non-boolean, non-enum field with no default to null", () => {
    expect(buildInitialPreviewContext(entity([field({ name: "notes", type: "string" })]))).toEqual({
      notes: null
    });
  });

  it("prefers an explicit field default over type-based inference", () => {
    expect(
      buildInitialPreviewContext(entity([field({ name: "flagged", type: "boolean", default: true })]))
    ).toEqual({ flagged: true });
  });

  it("seeds an enum field from its option marked default: true", () => {
    expect(
      buildInitialPreviewContext(
        entity([
          field({
            name: "status",
            type: "enum",
            enumValues: [
              { value: "draft" },
              { value: "approved", default: true },
              { value: "rejected" }
            ]
          })
        ])
      )
    ).toEqual({ status: "approved" });
  });

  it("falls back to the first enum option when none is marked default", () => {
    expect(
      buildInitialPreviewContext(
        entity([field({ name: "status", type: "enum", enumValues: ["draft", "approved"] })])
      )
    ).toEqual({ status: "draft" });
  });
});

describe("resolveFieldInteractionState", () => {
  const baseField: PreviewField = {
    name: "status",
    label: "Status",
    type: "string",
    tab: "Details",
    section: "General",
    column: 1,
    columnSpan: 1,
    width: "md",
    order: 1,
    interaction: {}
  };

  it("defaults visible/enabled to true and readonly/required to false when no clause is set", () => {
    expect(resolveFieldInteractionState(baseField, {})).toEqual({
      visible: true,
      enabled: true,
      readonly: false,
      required: false
    });
  });

  it("evaluates each interaction clause independently against the same context", () => {
    const conditionalField: PreviewField = {
      ...baseField,
      interaction: {
        visibleWhen: "kind == 'detailed'",
        enabledWhen: "locked == false",
        readonlyWhen: "locked == true",
        requiredWhen: "kind == 'detailed'"
      }
    };
    expect(resolveFieldInteractionState(conditionalField, { kind: "detailed", locked: false })).toEqual({
      visible: true,
      enabled: true,
      readonly: false,
      required: true
    });
    expect(resolveFieldInteractionState(conditionalField, { kind: "summary", locked: true })).toEqual({
      visible: false,
      enabled: false,
      readonly: true,
      required: false
    });
  });
});

describe("buildPreviewManifest", () => {
  it("returns null when the document has no concepts", () => {
    expect(
      buildPreviewManifest(
        { namespace: "t", dslVersion: "1.0.0", version: "1.0", concepts: [], enums: [], flows: [], queries: [], ruleProfiles: [], procedures: [], panels: [], metadata: {} } as never,
        null
      )
    ).toBeNull();
  });

  it("falls back to the first concept when conceptName does not match any concept", () => {
    const document = {
      namespace: "t",
      dslVersion: "1.0.0",
      version: "1.0",
      concepts: [entity([field({ name: "id", type: "uuid" })])],
      enums: [],
      flows: [],
      queries: [],
      ruleProfiles: [],
      procedures: [],
      panels: [],
      metadata: {}
    } as never;
    expect(buildPreviewManifest(document, "NoSuchConcept")?.entity.name).toBe("Order");
  });

  it("groups fields into tabs and sorts both tabs' fields and table columns by declared order", () => {
    const document = {
      namespace: "t",
      dslVersion: "1.0.0",
      version: "1.0",
      concepts: [
        entity([
          field({ name: "second", ui: { tab: "Details", order: 2, listColumn: true, listColumnOrder: 2 } }),
          field({ name: "first", ui: { tab: "Details", order: 1, listColumn: true, listColumnOrder: 1 } }),
          field({ name: "other", ui: { tab: "Advanced", order: 1 } })
        ])
      ],
      enums: [],
      flows: [],
      queries: [],
      ruleProfiles: [],
      procedures: [],
      panels: [],
      metadata: {}
    } as never;

    const manifest = buildPreviewManifest(document, "Order");
    expect(manifest?.tabs.map((tab) => tab.name)).toEqual(["Details", "Advanced"]);
    expect(manifest?.tabs[0].fields.map((f) => f.name)).toEqual(["first", "second"]);
    expect(manifest?.tableColumns.map((c) => c.fieldName)).toEqual(["first", "second"]);
  });

  it("only produces a picker for reference fields that declare a target", () => {
    const document = {
      namespace: "t",
      dslVersion: "1.0.0",
      version: "1.0",
      concepts: [
        entity([
          field({ name: "owner", type: "reference", reference: { target: "User" } }),
          field({ name: "note", type: "reference" }) // reference type but no target -> not a picker
        ])
      ],
      enums: [],
      flows: [],
      queries: [],
      ruleProfiles: [],
      procedures: [],
      panels: [],
      metadata: {}
    } as never;

    const manifest = buildPreviewManifest(document, "Order");
    expect(manifest?.pickers.map((p) => p.fieldName)).toEqual(["owner"]);
    expect(manifest?.pickers[0].target).toBe("User");
  });

  it("combines flow, lifecycle-transition, and orchestration-rule actions into one preview action list", () => {
    const document = {
      namespace: "t",
      dslVersion: "1.0.0",
      version: "1.0",
      concepts: [
        {
          ...entity([field({ name: "id", type: "uuid" })]),
          lifecycle: {
            transitions: [{ from: "draft", to: "approved", event: "approve" }]
          }
        }
      ],
      enums: [],
      flows: [{ name: "Submit", input: { concept: "Order", mode: "update" }, steps: [] }],
      queries: [],
      ruleProfiles: [],
      procedures: [],
      panels: [],
      orchestrationRules: [
        {
          name: "NotifyOnApprove",
          trigger: { event: "approve" },
          actions: [{ type: "notify" }]
        }
      ],
      metadata: {}
    } as never;

    const manifest = buildPreviewManifest(document, "Order");
    expect(manifest?.actions.map((a) => a.kind)).toEqual(["flow", "transition", "orchestration"]);
    expect(manifest?.actions[1].title).toBe("draft -> approved");
    expect(manifest?.actions[2].title).toBe("NotifyOnApprove / notify");
  });
});
