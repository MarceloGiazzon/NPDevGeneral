import { describe, expect, it } from "vitest";
import { normalizeRuleEditorDraft } from "./RuleEditorPanel";

describe("RuleEditorPanel draft normalization", () => {
  it("derives editor-safe rules from a canonical model payload", () => {
    const draft = normalizeRuleEditorDraft({
      namespace: "sample.workflow",
      version: "1.0",
      entities: [
        {
          name: "WorkItem",
          invariants: [
            { name: "PriorityNonNegative", expr: "priority >= 0" }
          ],
          lifecycle: {
            transitions: [
              {
                from: "Draft",
                to: "Completed",
                requiredPayload: ["completedAt"],
                guard: "completedAt != null",
                event: "WorkItemCompleted"
              }
            ]
          }
        }
      ],
      orchestrationRules: [
        {
          name: "NotifyWhenCompleted",
          trigger: { event: "WorkItemCompleted" },
          condition: "$event.status == \"Completed\"",
          actions: [{ type: "callCapability", capability: "notification" }]
        }
      ]
    });

    expect(draft.namespace).toBe("sample.workflow");
    expect(draft.entities[0].entityName).toBe("WorkItem");
    expect(draft.entities[0].invariantPalette).toHaveLength(1);
    expect(draft.entities[0].invariantPalette[0].expression).toBe("priority >= 0");
    expect(draft.entities[0].stateTransitionRules[0].requires).toEqual(["completedAt"]);
    expect(draft.entities[0].orchestrationTriggerRules[0].event).toBe("WorkItemCompleted");
  });

  it("fills missing arrays in an existing rule-editor draft", () => {
    const draft = normalizeRuleEditorDraft({
      namespace: "sample.rules",
      version: "draft",
      entities: [
        { entityName: "Tenant" }
      ]
    });

    expect(draft.entities[0].invariantPalette).toEqual([]);
    expect(draft.entities[0].stateTransitionRules).toEqual([]);
    expect(draft.entities[0].orchestrationTriggerRules).toEqual([]);
  });
});
