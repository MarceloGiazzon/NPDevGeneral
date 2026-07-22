import React from "react";
import type { AuthoringFlow, AuthoringFlowStep } from "../modelDocumentTypes";

type FlowStepsTableProps = {
  flows: AuthoringFlow[];
  flowIndex: number;
  flow: AuthoringFlow;
  onChange: (flows: AuthoringFlow[]) => void;
};

const KNOWN_FLOW_STEP_TYPES = [
  "invariant",
  "capabilityCall",
  "event",
  "scheduleEvent",
  "branch",
  "map",
  "await",
  "return",
  "forEach"
];

function buildNestedStep(stepNumber: number): AuthoringFlowStep {
  return {
    name: `loop-step-${stepNumber}`,
    type: "return",
    value: "$last"
  };
}

function isForEachType(type: string): boolean {
  const normalized = type.trim().toLowerCase();
  return normalized === "foreach" || normalized === "loop";
}

/** Renders one level of a flow's step list, recursing into `steps` (the forEach loop body) so a
 * `forEach` step's nested body is editable inline instead of requiring a separate screen. */
function FlowStepList({
  steps,
  onChange,
  datalistId
}: {
  steps: AuthoringFlowStep[];
  onChange: (steps: AuthoringFlowStep[]) => void;
  datalistId: string;
}): JSX.Element {
  const updateStep = (stepIndex: number, updater: (step: AuthoringFlowStep) => AuthoringFlowStep): void => {
    onChange(steps.map((entry, currentStepIndex) => (currentStepIndex === stepIndex ? updater(entry) : entry)));
  };

  const removeStep = (stepIndex: number): void => {
    onChange(steps.filter((_, currentStepIndex) => currentStepIndex !== stepIndex));
  };

  return (
    <table className="grid-table compact">
      <thead>
        <tr>
          <th>Name</th>
          <th>Type</th>
          <th>Scope</th>
          <th>Signal</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {steps.map((step, stepIndex) => {
          const isForEach = isForEachType(step.type);
          return (
            <React.Fragment key={`${step.name}-${stepIndex}`}>
              <tr>
                <td>
                  <input value={step.name} onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, name: event.target.value }))} />
                </td>
                <td>
                  <input
                    list={datalistId}
                    value={step.type}
                    onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, type: event.target.value }))}
                  />
                </td>
                <td>
                  <input value={step.scope ?? ""} onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, scope: event.target.value }))} />
                </td>
                <td>
                  <input
                    value={step.value ?? step.event ?? step.condition ?? ""}
                    onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, value: event.target.value }))}
                  />
                </td>
                <td>
                  <button type="button" className="authoring-ghost-button" onClick={() => removeStep(stepIndex)}>
                    Remove
                  </button>
                </td>
              </tr>
              {isForEach && (
                <tr>
                  <td colSpan={5}>
                    <div className="authoring-subcard authoring-foreach-editor">
                      <div className="authoring-form-grid">
                        <label>
                          Collection ref
                          <input
                            value={step.collection ?? ""}
                            placeholder="input.orders"
                            onChange={(event) =>
                              updateStep(stepIndex, (entry) => ({ ...entry, collection: event.target.value }))
                            }
                          />
                        </label>
                        <label>
                          Item key
                          <input
                            value={step.itemKey ?? ""}
                            placeholder="order"
                            onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, itemKey: event.target.value }))}
                          />
                        </label>
                        <label>
                          Max loop iterations
                          <input
                            type="number"
                            min={1}
                            value={step.maxLoopIterations ?? ""}
                            onChange={(event) =>
                              updateStep(stepIndex, (entry) => ({
                                ...entry,
                                maxLoopIterations: event.target.value === "" ? undefined : Number(event.target.value)
                              }))
                            }
                          />
                        </label>
                      </div>
                      <div className="authoring-editor-section__miniheader">
                        <strong>Loop body</strong>
                        <button
                          type="button"
                          className="authoring-secondary-inline"
                          onClick={() =>
                            updateStep(stepIndex, (entry) => ({
                              ...entry,
                              steps: [...(entry.steps ?? []), buildNestedStep((entry.steps?.length ?? 0) + 1)]
                            }))
                          }
                        >
                          Add loop step
                        </button>
                      </div>
                      <FlowStepList
                        steps={step.steps ?? []}
                        datalistId={datalistId}
                        onChange={(nextLoopSteps) => updateStep(stepIndex, (entry) => ({ ...entry, steps: nextLoopSteps }))}
                      />
                    </div>
                  </td>
                </tr>
              )}
            </React.Fragment>
          );
        })}
      </tbody>
    </table>
  );
}

export default function FlowStepsTable({ flows, flowIndex, flow, onChange }: FlowStepsTableProps): JSX.Element {
  const datalistId = `flow-step-types-${flowIndex}`;
  return (
    <>
      <datalist id={datalistId}>
        {KNOWN_FLOW_STEP_TYPES.map((type) => (
          <option key={type} value={type} />
        ))}
      </datalist>
      <FlowStepList
        steps={flow.steps ?? []}
        datalistId={datalistId}
        onChange={(nextSteps) =>
          onChange(
            flows.map((entry, entryIndex) => (entryIndex === flowIndex ? { ...entry, steps: nextSteps } : entry))
          )
        }
      />
    </>
  );
}
