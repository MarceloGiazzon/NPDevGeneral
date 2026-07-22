import React from "react";
import type { AuthoringProcedure, AuthoringProcedureStep } from "../modelDocumentTypes";
import ProcedureStepEditor from "./ProcedureStepEditor";

type ProceduresEditorSectionProps = {
  procedures: AuthoringProcedure[];
  conceptNames: string[];
  queryNames: string[];
  procedureNames: string[];
  onChange: (procedures: AuthoringProcedure[]) => void;
};

const STEP_TYPES = [
  "assign",
  "readConcept",
  "saveConcept",
  "runQuery",
  "callCapability",
  "callProcedure",
  "publishEvent",
  "return"
];

function buildDefaultStep(): AuthoringProcedureStep {
  return {
    name: "step-1",
    type: "return",
    value: "$input"
  };
}

/**
 * LIFT-QUERY-P4: the "query -> capability" preset from ADR discussion -- a runQuery step whose
 * output feeds directly into a callCapability step's single arg. Mirrors the pattern proven in
 * DefaultProcedureExecutorQueryToCapabilityTest: capabilities receive exactly the filtered rows,
 * with no data handle of their own (sandbox intact).
 */
function buildQueryToCapabilityPreset(stepCount: number): AuthoringProcedureStep[] {
  const queryStepName = `query-${stepCount + 1}`;
  const rowsTarget = `rows${stepCount + 1}`;
  return [
    {
      name: queryStepName,
      type: "runQuery",
      target: rowsTarget
    },
    {
      name: `call-capability-${stepCount + 2}`,
      type: "callCapability",
      args: { rows: rowsTarget },
      target: `result${stepCount + 2}`
    }
  ];
}

export default function ProceduresEditorSection({
  procedures,
  conceptNames,
  queryNames,
  procedureNames,
  onChange
}: ProceduresEditorSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Procedures</h3>
          <p>Author governed steps directly in the Editor so procedural intent survives into compilation and runtime.</p>
        </div>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={() =>
            onChange([
              ...procedures,
              {
                name: `Procedure${procedures.length + 1}`,
                steps: [buildDefaultStep()]
              }
            ])
          }
        >
          Add procedure
        </button>
      </div>

      <div className="authoring-editor-stack">
        {procedures.length === 0 ? (
          <article className="authoring-subcard">
            <p>No procedures yet. Add them when a flow needs a reusable governed step sequence.</p>
          </article>
        ) : (
          procedures.map((procedure, procedureIndex) => (
            <article key={`${procedure.name}-${procedureIndex}`} className="authoring-subcard">
              <div className="authoring-preview-card__header">
                <strong>{procedure.name || `Procedure ${procedureIndex + 1}`}</strong>
                <button
                  type="button"
                  className="authoring-ghost-button"
                  onClick={() => onChange(procedures.filter((_, index) => index !== procedureIndex))}
                >
                  Remove
                </button>
              </div>

              <div className="authoring-form-grid">
                <label>
                  Name
                  <input
                    value={procedure.name}
                    onChange={(event) =>
                      onChange(
                        procedures.map((entry, index) =>
                          index === procedureIndex
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
                  Description
                  <input
                    value={procedure.description ?? ""}
                    onChange={(event) =>
                      onChange(
                        procedures.map((entry, index) =>
                          index === procedureIndex
                            ? {
                                ...entry,
                                description: event.target.value || undefined
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>
              </div>

              <div className="authoring-editor-stack">
                {(procedure.steps ?? []).map((step, stepIndex) => (
                  <ProcedureStepEditor
                    key={`${step.name ?? "step"}-${stepIndex}`}
                    procedures={procedures}
                    procedure={procedure}
                    procedureIndex={procedureIndex}
                    step={step}
                    stepIndex={stepIndex}
                    conceptNames={conceptNames}
                    queryNames={queryNames}
                    procedureNames={procedureNames}
                    stepTypes={STEP_TYPES}
                    onChange={onChange}
                  />
                ))}
              </div>

              <div className="authoring-inline-actions">
                <button
                  type="button"
                  className="authoring-secondary-inline"
                  onClick={() =>
                    onChange(
                      procedures.map((entry, index) =>
                        index === procedureIndex
                          ? {
                              ...entry,
                              steps: [
                                ...(entry.steps ?? []),
                                {
                                  ...buildDefaultStep(),
                                  name: `step-${(entry.steps ?? []).length + 1}`
                                }
                              ]
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
                  className="authoring-secondary-inline"
                  onClick={() =>
                    onChange(
                      procedures.map((entry, index) =>
                        index === procedureIndex
                          ? {
                              ...entry,
                              steps: [...(entry.steps ?? []), ...buildQueryToCapabilityPreset((entry.steps ?? []).length)]
                            }
                          : entry
                      )
                    )
                  }
                  title="Adds a runQuery step whose output feeds a callCapability step's arg"
                >
                  Add query → capability
                </button>
              </div>
            </article>
          ))
        )}
      </div>
    </section>
  );
}
