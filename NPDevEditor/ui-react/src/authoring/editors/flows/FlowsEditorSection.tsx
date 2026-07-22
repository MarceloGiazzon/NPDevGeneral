import React from "react";
import type { AuthoringFlow, AuthoringFlowStep } from "../modelDocumentTypes";
import ExplainabilityTooltip from "../../help/ExplainabilityTooltip";
import FlowStepsTable from "./FlowStepsTable";

type FlowsEditorSectionProps = {
  flows: AuthoringFlow[];
  onChange: (flows: AuthoringFlow[]) => void;
};

function buildFlowStep(stepNumber: number): AuthoringFlowStep {
  return {
    name: `step-${stepNumber}`,
    type: "return",
    value: "$input"
  };
}

export default function FlowsEditorSection({
  flows,
  onChange
}: FlowsEditorSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Flow editor</h3>
          <p>Edit explicit flows, their primary action metadata, and the step sequence that drives runtime behavior.</p>
        </div>
        <ExplainabilityTooltip
          title="Why flow explanation matters"
          detail="Flows are how NPDev represents business procedures. Understanding each step makes runtime behavior less magical."
        />
        <button
          type="button"
          onClick={() =>
            onChange([
              ...flows,
              {
                name: `Flow${flows.length + 1}`,
                input: {
                  concept: "NewConcept",
                  mode: "create"
                },
                steps: [buildFlowStep(1)]
              }
            ])
          }
        >
          Add flow
        </button>
      </div>

      <div className="authoring-editor-stack">
        {flows.map((flow, flowIndex) => (
          <article key={`${flow.name}-${flowIndex}`} className="authoring-subcard">
            <div className="authoring-form-grid">
              <label>
                Flow name
                <input
                  value={flow.name}
                  onChange={(event) =>
                    onChange(
                      flows.map((entry, entryIndex) =>
                        entryIndex === flowIndex
                          ? {
                              ...entry,
                              name: event.target.value
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>

              <label>
                Input concept
                <input
                  value={flow.input?.concept ?? ""}
                  onChange={(event) =>
                    onChange(
                      flows.map((entry, entryIndex) =>
                        entryIndex === flowIndex
                          ? {
                              ...entry,
                              input: {
                                ...entry.input,
                                concept: event.target.value
                              }
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>

              <label>
                Mode
                <input
                  value={flow.input?.mode ?? ""}
                  onChange={(event) =>
                    onChange(
                      flows.map((entry, entryIndex) =>
                        entryIndex === flowIndex
                          ? {
                              ...entry,
                              input: {
                                ...entry.input,
                                mode: event.target.value
                              }
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>

              <label>
                Action label
                <input
                  value={flow.action?.label ?? ""}
                  onChange={(event) =>
                    onChange(
                      flows.map((entry, entryIndex) =>
                        entryIndex === flowIndex
                          ? {
                              ...entry,
                              action: {
                                ...entry.action,
                                label: event.target.value
                              }
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>
            </div>

            <div className="authoring-editor-section__miniheader">
              <strong>Flow steps</strong>
              <div className="authoring-row-actions">
                <button
                  type="button"
                  className="authoring-secondary-inline"
                  onClick={() =>
                    onChange(
                      flows.map((entry, entryIndex) =>
                        entryIndex === flowIndex
                          ? {
                              ...entry,
                              steps: [...(entry.steps ?? []), buildFlowStep((entry.steps?.length ?? 0) + 1)]
                            }
                          : entry
                      )
                    )
                  }
                >
                  Add step
                </button>
                <button
                  type="button"
                  className="authoring-ghost-button"
                  onClick={() => onChange(flows.filter((_, entryIndex) => entryIndex !== flowIndex))}
                >
                  Remove flow
                </button>
              </div>
            </div>

            <FlowStepsTable flows={flows} flowIndex={flowIndex} flow={flow} onChange={onChange} />
          </article>
        ))}
      </div>
    </section>
  );
}
