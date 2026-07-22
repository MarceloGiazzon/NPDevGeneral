import type {
  RuleEditorDraft,
  RuleEditorEntityRulesDraft,
  RuleEditorInvariantDraft,
  RuleEditorOrchestrationRuleDraft,
  RuleEditorTransitionRuleDraft
} from "./types";

export function emptyRuleEditorDraft(): RuleEditorDraft {
  return {
    namespace: "com.npdev.visual.rules",
    version: "rule-editor-draft",
    entities: []
  };
}

export function emptyEntityRules(name: string): RuleEditorEntityRulesDraft {
  return {
    entityName: name,
    invariantPalette: [],
    stateTransitionRules: [],
    orchestrationTriggerRules: []
  };
}

export function emptyInvariant(): RuleEditorInvariantDraft {
  return {
    name: "NewInvariant",
    expression: "true",
    message: "Rule passes"
  };
}

export function emptyTransitionRule(): RuleEditorTransitionRuleDraft {
  return {
    from: "FromState",
    to: "ToState",
    requires: [],
    message: "Describe the transition requirement"
  };
}

export function emptyOrchestrationRule(): RuleEditorOrchestrationRuleDraft {
  return {
    name: "NewTriggerRule",
    event: "BusinessEvent",
    condition: "true",
    action: "BusinessAction"
  };
}

export function normalizeRuleValue(value: unknown): string {
  return value == null ? "" : String(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asStringArray(value: unknown): string[] {
  return asArray(value).map((item) => normalizeRuleValue(item)).filter(Boolean);
}

function normalizeInvariant(value: unknown): RuleEditorInvariantDraft {
  const source = isRecord(value) ? value : {};
  const expression = source.expression ?? source.expr ?? source.condition;
  return {
    name: normalizeRuleValue(source.name) || "Invariant",
    expression: normalizeRuleValue(expression) || "true",
    message: normalizeRuleValue(source.message) || normalizeRuleValue(source.name) || "Rule passes"
  };
}

function normalizeTransition(value: unknown): RuleEditorTransitionRuleDraft {
  const source = isRecord(value) ? value : {};
  const action = isRecord(source.action) ? (source.action as RuleEditorTransitionRuleDraft["action"]) : undefined;
  const metadata = isRecord(source.metadata) ? (source.metadata as Record<string, string>) : undefined;
  const from = normalizeRuleValue(source.from) || "From";
  const to = normalizeRuleValue(source.to) || "To";
  return {
    from,
    to,
    requires: asStringArray(source.requires ?? source.requiredPayload),
    guard: normalizeRuleValue(source.guard) || undefined,
    event: normalizeRuleValue(source.event) || undefined,
    actionLabel: normalizeRuleValue(source.actionLabel) || undefined,
    action,
    metadata,
    message: normalizeRuleValue(source.message) || normalizeRuleValue(source.actionLabel) || `${from} to ${to}`
  };
}

function normalizeOrchestration(value: unknown): RuleEditorOrchestrationRuleDraft {
  const source = isRecord(value) ? value : {};
  const trigger = isRecord(source.trigger) ? source.trigger : {};
  const actions = asArray(source.actions);
  const firstAction = isRecord(actions[0]) ? actions[0] : {};
  return {
    name: normalizeRuleValue(source.name) || "TriggerRule",
    event: normalizeRuleValue(source.event ?? trigger.event ?? trigger.eventName) || "Event",
    condition: normalizeRuleValue(source.condition) || "true",
    action: normalizeRuleValue(source.action ?? firstAction.type ?? firstAction.capability ?? firstAction.operation) || "Action"
  };
}

function normalizeEntityRules(value: unknown, fallbackName: string): RuleEditorEntityRulesDraft {
  const source = isRecord(value) ? value : {};
  return {
    entityName: normalizeRuleValue(source.entityName ?? source.name) || fallbackName,
    invariantPalette: asArray(source.invariantPalette ?? source.invariants).map(normalizeInvariant),
    stateTransitionRules: asArray(source.stateTransitionRules ?? (isRecord(source.lifecycle) ? source.lifecycle.transitions : undefined)).map(normalizeTransition),
    orchestrationTriggerRules: asArray(source.orchestrationTriggerRules).map(normalizeOrchestration)
  };
}

export function normalizeRuleEditorDraft(value: unknown): RuleEditorDraft {
  if (!isRecord(value)) {
    return emptyRuleEditorDraft();
  }

  const entities = asArray(value.entities).map((entity, index) => normalizeEntityRules(entity, `Entity${index + 1}`));
  const orchestrationTriggerRules = asArray(value.orchestrationRules).map(normalizeOrchestration);
  if (orchestrationTriggerRules.length > 0) {
    const target = entities[0] ?? emptyEntityRules("Orchestration");
    target.orchestrationTriggerRules = [...target.orchestrationTriggerRules, ...orchestrationTriggerRules];
    if (entities.length === 0) {
      entities.push(target);
    }
  }

  return {
    namespace: normalizeRuleValue(value.namespace) || "com.npdev.visual.rules",
    version: normalizeRuleValue(value.version) || "rule-editor-draft",
    entities
  };
}
