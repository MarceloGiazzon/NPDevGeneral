import { useMemo, useState } from "react";

type OrchestrationTriggerRule = {
  eventName: string;
  concept: string;
  condition: string;
};

type OrchestrationActionRule = {
  actionType: string;
  capability: string;
  operation: string;
};

type OrchestrationScheduleRule = {
  mode: string;
  delay: string;
  wakeEvent: string;
};

type OrchestrationEditorDraft = {
  name: string;
  triggerRules: OrchestrationTriggerRule[];
  actionRules: OrchestrationActionRule[];
  scheduleRules: OrchestrationScheduleRule[];
};

type Props = {
  initialDraft?: Partial<OrchestrationEditorDraft> | null;
  onSaveDraft?: (draft: OrchestrationEditorDraft) => Promise<void> | void;
  onResetDraft?: () => Promise<void> | void;
};

const DEFAULT_DRAFT: OrchestrationEditorDraft = {
  name: "NewBusinessFlow",
  triggerRules: [
    {
      eventName: "BusinessEvent",
      concept: "BusinessConcept",
      condition: "true"
    }
  ],
  actionRules: [
    {
      actionType: "capability",
      capability: "notification",
      operation: "send"
    }
  ],
  scheduleRules: [
    {
      mode: "delay",
      delay: "PT24H",
      wakeEvent: "FollowUpDue"
    }
  ]
};

function normalizeDraft(input?: Partial<OrchestrationEditorDraft> | null): OrchestrationEditorDraft {
  const source = input ?? {};
  return {
    name: typeof source.name === "string" && source.name.trim() ? source.name.trim() : DEFAULT_DRAFT.name,
    triggerRules: Array.isArray(source.triggerRules) && source.triggerRules.length > 0
      ? source.triggerRules.map((item) => ({
          eventName: String(item?.eventName ?? "BusinessEvent"),
          concept: String(item?.concept ?? "BusinessConcept"),
          condition: String(item?.condition ?? "")
        }))
      : DEFAULT_DRAFT.triggerRules,
    actionRules: Array.isArray(source.actionRules) && source.actionRules.length > 0
      ? source.actionRules.map((item) => ({
          actionType: String(item?.actionType ?? "capability"),
          capability: String(item?.capability ?? "notification"),
          operation: String(item?.operation ?? "send")
        }))
      : DEFAULT_DRAFT.actionRules,
    scheduleRules: Array.isArray(source.scheduleRules) && source.scheduleRules.length > 0
      ? source.scheduleRules.map((item) => ({
          mode: String(item?.mode ?? "delay"),
          delay: String(item?.delay ?? "PT24H"),
          wakeEvent: String(item?.wakeEvent ?? "FollowUpDue")
        }))
      : DEFAULT_DRAFT.scheduleRules
  };
}

export default function OrchestrationEditorPanel({ initialDraft, onSaveDraft, onResetDraft }: Props) {
  const [draft, setDraft] = useState<OrchestrationEditorDraft>(() => normalizeDraft(initialDraft));
  const [status, setStatus] = useState<string>("");

  const summary = useMemo(() => {
    return {
      triggerCount: draft.triggerRules.length,
      actionCount: draft.actionRules.length,
      scheduleCount: draft.scheduleRules.length
    };
  }, [draft]);

  async function handleSave() {
    setStatus("Saving orchestration draft...");
    await onSaveDraft?.(draft);
    setStatus("Orchestration draft saved.");
  }

  async function handleReset() {
    await onResetDraft?.();
    setDraft(normalizeDraft(null));
    setStatus("Orchestration draft reset.");
  }

  return (
    <section className="panel">
      <div className="section-header">
        <div>
          <h2>Visual Orchestration Editor</h2>
          <div className="hint">Edit trigger, action, and schedule rules as an orchestration draft.</div>
        </div>
        <div className="button-row">
          <button type="button" onClick={handleSave}>Save orchestration draft</button>
          <button type="button" className="secondary-button" onClick={handleReset}>Reset orchestration draft</button>
        </div>
      </div>

      {status ? <div className="status-box">{status}</div> : null}

      <div className="metadata-summary">
        <div className="metadata-summary-card">
          <strong>Flow name</strong>
          <span>{draft.name}</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Trigger rules</strong>
          <span>{summary.triggerCount}</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Action rules</strong>
          <span>{summary.actionCount}</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Schedule rules</strong>
          <span>{summary.scheduleCount}</span>
        </div>
      </div>

      <div className="task-preview-grid">
        <div className="task-preview-card">
          <div className="task-preview-head">
            <strong>Trigger Palette</strong>
            <span className="metadata-badge">event</span>
          </div>
          {draft.triggerRules.map((rule, index) => (
            <div key={`trigger-${index}`} className="relation-hint-block">
              <div><strong>Event:</strong> {rule.eventName}</div>
              <div><strong>Concept:</strong> {rule.concept}</div>
              <div><strong>Condition:</strong> {rule.condition || "(none)"}</div>
            </div>
          ))}
        </div>

        <div className="task-preview-card">
          <div className="task-preview-head">
            <strong>Action Palette</strong>
            <span className="metadata-badge">effect</span>
          </div>
          {draft.actionRules.map((rule, index) => (
            <div key={`action-${index}`} className="relation-hint-block">
              <div><strong>Type:</strong> {rule.actionType}</div>
              <div><strong>Capability:</strong> {rule.capability}</div>
              <div><strong>Operation:</strong> {rule.operation}</div>
            </div>
          ))}
        </div>

        <div className="task-preview-card">
          <div className="task-preview-head">
            <strong>Schedule Palette</strong>
            <span className="metadata-badge">time</span>
          </div>
          {draft.scheduleRules.map((rule, index) => (
            <div key={`schedule-${index}`} className="relation-hint-block">
              <div><strong>Mode:</strong> {rule.mode}</div>
              <div><strong>Delay / timer:</strong> {rule.delay}</div>
              <div><strong>Wake event:</strong> {rule.wakeEvent}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
