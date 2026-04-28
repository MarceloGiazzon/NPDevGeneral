import { useEffect, useMemo, useState } from "react";
import { npdevClient } from "./api/npdevClient";
import type {
  RuleEditorDraft,
  RuleEditorEntityRulesDraft,
  RuleEditorInvariantDraft,
  RuleEditorTransitionRuleDraft,
  RuleEditorOrchestrationRuleDraft
} from "./types";

function emptyDraft(): RuleEditorDraft {
  return {
    namespace: "com.npdev.visual.rules",
    version: "rule-editor-draft",
    entities: []
  };
}

function emptyEntityRules(name: string): RuleEditorEntityRulesDraft {
  return {
    entityName: name,
    invariantPalette: [],
    stateTransitionRules: [],
    orchestrationTriggerRules: []
  };
}

function emptyInvariant(): RuleEditorInvariantDraft {
  return {
    name: "NewInvariant",
    expression: "true",
    message: "Rule passes"
  };
}

function emptyTransitionRule(): RuleEditorTransitionRuleDraft {
  return {
    from: "FromState",
    to: "ToState",
    requires: [],
    message: "Describe the transition requirement"
  };
}

function emptyOrchestrationRule(): RuleEditorOrchestrationRuleDraft {
  return {
    name: "NewTriggerRule",
    event: "BusinessEvent",
    condition: "true",
    action: "BusinessAction"
  };
}

function normalize(value: unknown): string {
  return value == null ? "" : String(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asStringArray(value: unknown): string[] {
  return asArray(value).map((item) => normalize(item)).filter(Boolean);
}

function normalizeInvariant(value: unknown): RuleEditorInvariantDraft {
  const source = isRecord(value) ? value : {};
  const expression = source.expression ?? source.expr ?? source.condition;
  return {
    name: normalize(source.name) || "Invariant",
    expression: normalize(expression) || "true",
    message: normalize(source.message) || normalize(source.name) || "Rule passes"
  };
}

function normalizeTransition(value: unknown): RuleEditorTransitionRuleDraft {
  const source = isRecord(value) ? value : {};
  const action = isRecord(source.action) ? source.action as RuleEditorTransitionRuleDraft["action"] : undefined;
  const metadata = isRecord(source.metadata) ? source.metadata as Record<string, string> : undefined;
  const from = normalize(source.from) || "From";
  const to = normalize(source.to) || "To";
  return {
    from,
    to,
    requires: asStringArray(source.requires ?? source.requiredPayload),
    guard: normalize(source.guard) || undefined,
    event: normalize(source.event) || undefined,
    actionLabel: normalize(source.actionLabel) || undefined,
    action,
    metadata,
    message: normalize(source.message) || normalize(source.actionLabel) || `${from} to ${to}`
  };
}

function normalizeOrchestration(value: unknown): RuleEditorOrchestrationRuleDraft {
  const source = isRecord(value) ? value : {};
  const trigger = isRecord(source.trigger) ? source.trigger : {};
  const actions = asArray(source.actions);
  const firstAction = isRecord(actions[0]) ? actions[0] : {};
  return {
    name: normalize(source.name) || "TriggerRule",
    event: normalize(source.event ?? trigger.event ?? trigger.eventName) || "Event",
    condition: normalize(source.condition) || "true",
    action: normalize(source.action ?? firstAction.type ?? firstAction.capability ?? firstAction.operation) || "Action"
  };
}

function normalizeEntityRules(value: unknown, fallbackName: string): RuleEditorEntityRulesDraft {
  const source = isRecord(value) ? value : {};
  return {
    entityName: normalize(source.entityName ?? source.name) || fallbackName,
    invariantPalette: asArray(source.invariantPalette ?? source.invariants).map(normalizeInvariant),
    stateTransitionRules: asArray(source.stateTransitionRules ?? (isRecord(source.lifecycle) ? source.lifecycle.transitions : undefined)).map(normalizeTransition),
    orchestrationTriggerRules: asArray(source.orchestrationTriggerRules).map(normalizeOrchestration)
  };
}

export function normalizeRuleEditorDraft(value: unknown): RuleEditorDraft {
  if (!isRecord(value)) {
    return emptyDraft();
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
    namespace: normalize(value.namespace) || "com.npdev.visual.rules",
    version: normalize(value.version) || "rule-editor-draft",
    entities
  };
}

export default function RuleEditorPanel() {
  const [draft, setDraft] = useState<RuleEditorDraft>(emptyDraft());
  const [selectedEntity, setSelectedEntity] = useState<string>("");
  const [statusMessage, setStatusMessage] = useState<string>("");
  const [statusIsError, setStatusIsError] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(false);

  useEffect(() => {
    void loadDraft();
  }, []);

  const entityRules = useMemo(() => {
    return draft.entities.find((item) => item.entityName === selectedEntity) ?? draft.entities[0] ?? null;
  }, [draft, selectedEntity]);

  useEffect(() => {
    if (!selectedEntity && draft.entities.length > 0) {
      setSelectedEntity(draft.entities[0].entityName);
      return;
    }
    if (selectedEntity && !draft.entities.some((item) => item.entityName === selectedEntity)) {
      setSelectedEntity(draft.entities[0]?.entityName ?? "");
    }
  }, [draft, selectedEntity]);

  async function loadDraft() {
    setLoading(true);
    try {
      const nextDraft = await npdevClient.getRuleEditorDraft();
      setDraft(normalizeRuleEditorDraft(nextDraft));
      setStatusMessage("Visual Rule Editor draft loaded.");
      setStatusIsError(false);
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : "Failed to load rule editor draft.");
      setStatusIsError(true);
    } finally {
      setLoading(false);
    }
  }

  function updateEntity(mutator: (current: RuleEditorEntityRulesDraft) => RuleEditorEntityRulesDraft) {
    if (!entityRules) {
      return;
    }
    setDraft((current) => ({
      ...current,
      entities: current.entities.map((item) => item.entityName === entityRules.entityName ? mutator(item) : item)
    }));
  }

  function addEntity() {
    const nextName = `Entity${draft.entities.length + 1}`;
    const next = emptyEntityRules(nextName);
    setDraft((current) => ({ ...current, entities: [...current.entities, next] }));
    setSelectedEntity(nextName);
  }

  function addInvariant() {
    updateEntity((current) => ({ ...current, invariantPalette: [...current.invariantPalette, emptyInvariant()] }));
  }

  function addTransitionRule() {
    updateEntity((current) => ({ ...current, stateTransitionRules: [...current.stateTransitionRules, emptyTransitionRule()] }));
  }

  function addOrchestrationRule() {
    updateEntity((current) => ({ ...current, orchestrationTriggerRules: [...current.orchestrationTriggerRules, emptyOrchestrationRule()] }));
  }

  function updateInvariant(index: number, patch: Partial<RuleEditorInvariantDraft>) {
    updateEntity((current) => ({
      ...current,
      invariantPalette: current.invariantPalette.map((item, i) => i === index ? { ...item, ...patch } : item)
    }));
  }

  function updateTransition(index: number, patch: Partial<RuleEditorTransitionRuleDraft>) {
    updateEntity((current) => ({
      ...current,
      stateTransitionRules: current.stateTransitionRules.map((item, i) => i === index ? { ...item, ...patch } : item)
    }));
  }

  function updateOrchestration(index: number, patch: Partial<RuleEditorOrchestrationRuleDraft>) {
    updateEntity((current) => ({
      ...current,
      orchestrationTriggerRules: current.orchestrationTriggerRules.map((item, i) => i === index ? { ...item, ...patch } : item)
    }));
  }

  async function saveDraft() {
    setLoading(true);
    try {
      const normalizedDraft = normalizeRuleEditorDraft(draft);
      const saved = await npdevClient.saveRuleEditorDraft(normalizedDraft);
      setDraft(normalizeRuleEditorDraft(saved));
      setStatusMessage("Visual Rule Editor draft saved.");
      setStatusIsError(false);
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : "Failed to save rule editor draft.");
      setStatusIsError(true);
    } finally {
      setLoading(false);
    }
  }

  async function resetDraft() {
    setLoading(true);
    try {
      await npdevClient.resetRuleEditorDraft();
      await loadDraft();
      setStatusMessage("Visual Rule Editor draft reset.");
      setStatusIsError(false);
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : "Failed to reset rule editor draft.");
      setStatusIsError(true);
    } finally {
      setLoading(false);
    }
  }

  const invariantPalette = entityRules?.invariantPalette ?? [];
  const stateTransitionRules = entityRules?.stateTransitionRules ?? [];
  const orchestrationTriggerRules = entityRules?.orchestrationTriggerRules ?? [];

  return (
    <section className="panel">
      <div className="section-header">
        <div>
          <h2>Visual Rule Editor</h2>
          <div className="hint">Invariant Palette, State Transition Rules, and Orchestration Trigger Rules backed by the runtime draft endpoint.</div>
        </div>
        <div className="button-row">
          <button type="button" onClick={() => void loadDraft()} disabled={loading}>Reload Draft</button>
          <button type="button" onClick={() => void saveDraft()} disabled={loading}>Save Draft</button>
          <button type="button" className="secondary-button" onClick={() => void resetDraft()} disabled={loading}>Reset Draft</button>
        </div>
      </div>

      {statusMessage ? <div className={statusIsError ? "status-box error" : "status-box"}>{statusMessage}</div> : null}

      <div className="metadata-summary">
        <div className="metadata-summary-card"><strong>Invariant Palette</strong><span>{invariantPalette.length} rules</span></div>
        <div className="metadata-summary-card"><strong>State Transition Rules</strong><span>{stateTransitionRules.length} rules</span></div>
        <div className="metadata-summary-card"><strong>Orchestration Trigger Rules</strong><span>{orchestrationTriggerRules.length} rules</span></div>
      </div>

      <div className="button-row">
        <button type="button" onClick={addEntity}>Add Concept Rules</button>
        <button type="button" onClick={addInvariant} disabled={!entityRules}>Add Invariant</button>
        <button type="button" onClick={addTransitionRule} disabled={!entityRules}>Add Transition Rule</button>
        <button type="button" onClick={addOrchestrationRule} disabled={!entityRules}>Add Trigger Rule</button>
      </div>

      <div className="form-grid two-col">
        <label htmlFor="rule-editor-entity">Selected concept</label>
        <select id="rule-editor-entity" value={entityRules?.entityName ?? ""} onChange={(event) => setSelectedEntity(event.target.value)}>
          <option value="">Select concept rules</option>
          {draft.entities.map((entity) => (
            <option key={entity.entityName} value={entity.entityName}>{entity.entityName}</option>
          ))}
        </select>
      </div>

      {entityRules ? (
        <>
          <div className="subpanel"><h3>Invariant Palette</h3></div>
          <table className="grid-table compact">
            <thead><tr><th>Name</th><th>Expression</th><th>Message</th></tr></thead>
            <tbody>
              {invariantPalette.map((rule, index) => (
                <tr key={`${rule.name}-${index}`}>
                  <td><input value={normalize(rule.name)} onChange={(event) => updateInvariant(index, { name: event.target.value })} /></td>
                  <td><input value={normalize(rule.expression)} onChange={(event) => updateInvariant(index, { expression: event.target.value })} /></td>
                  <td><input value={normalize(rule.message)} onChange={(event) => updateInvariant(index, { message: event.target.value })} /></td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="subpanel"><h3>State Transition Rules</h3></div>
          <table className="grid-table compact">
            <thead><tr><th>From</th><th>To</th><th>Requires</th><th>Message</th></tr></thead>
            <tbody>
              {stateTransitionRules.map((rule, index) => (
                <tr key={`${rule.from}-${rule.to}-${index}`}>
                  <td><input value={normalize(rule.from)} onChange={(event) => updateTransition(index, { from: event.target.value })} /></td>
                  <td><input value={normalize(rule.to)} onChange={(event) => updateTransition(index, { to: event.target.value })} /></td>
                  <td><input value={rule.requires.join(", ")} onChange={(event) => updateTransition(index, { requires: event.target.value.split(",").map((item) => item.trim()).filter(Boolean) })} /></td>
                  <td><input value={normalize(rule.message)} onChange={(event) => updateTransition(index, { message: event.target.value })} /></td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="subpanel"><h3>Orchestration Trigger Rules</h3></div>
          <table className="grid-table compact">
            <thead><tr><th>Name</th><th>Event</th><th>Condition</th><th>Action</th></tr></thead>
            <tbody>
              {orchestrationTriggerRules.map((rule, index) => (
                <tr key={`${rule.name}-${index}`}>
                  <td><input value={normalize(rule.name)} onChange={(event) => updateOrchestration(index, { name: event.target.value })} /></td>
                  <td><input value={normalize(rule.event)} onChange={(event) => updateOrchestration(index, { event: event.target.value })} /></td>
                  <td><input value={normalize(rule.condition)} onChange={(event) => updateOrchestration(index, { condition: event.target.value })} /></td>
                  <td><input value={normalize(rule.action)} onChange={(event) => updateOrchestration(index, { action: event.target.value })} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : <div className="hint">Add or select concept rules to begin editing.</div>}
    </section>
  );
}
