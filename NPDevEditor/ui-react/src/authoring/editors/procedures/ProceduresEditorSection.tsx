import React from "react";
import type { AuthoringProcedure, AuthoringProcedureStep } from "../modelDocumentTypes";

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
                  <article key={`${step.name ?? "step"}-${stepIndex}`} className="authoring-subcard">
                    <div className="authoring-preview-card__header">
                      <strong>{step.name ?? `Step ${stepIndex + 1}`}</strong>
                      <button
                        type="button"
                        className="authoring-ghost-button"
                        onClick={() =>
                          onChange(
                            procedures.map((entry, index) =>
                              index === procedureIndex
                                ? {
                                    ...entry,
                                    steps: (entry.steps ?? []).filter((_, nestedIndex) => nestedIndex !== stepIndex)
                                  }
                                : entry
                            )
                          )
                        }
                      >
                        Remove step
                      </button>
                    </div>

                    <div className="authoring-form-grid">
                      <label>
                        Step name
                        <input
                          value={step.name ?? ""}
                          onChange={(event) =>
                            onChange(
                              procedures.map((entry, index) =>
                                index === procedureIndex
                                  ? {
                                      ...entry,
                                      steps: (entry.steps ?? []).map((stepEntry, nestedIndex) =>
                                        nestedIndex === stepIndex
                                          ? {
                                              ...stepEntry,
                                              name: event.target.value || undefined
                                            }
                                          : stepEntry
                                      )
                                    }
                                  : entry
                              )
                            )
                          }
                        />
                      </label>

                      <label>
                        Type
                        <select
                          value={step.type}
                          onChange={(event) =>
                            onChange(
                              procedures.map((entry, index) =>
                                index === procedureIndex
                                  ? {
                                      ...entry,
                                      steps: (entry.steps ?? []).map((stepEntry, nestedIndex) =>
                                        nestedIndex === stepIndex
                                          ? {
                                              ...stepEntry,
                                              type: event.target.value
                                            }
                                          : stepEntry
                                      )
                                    }
                                  : entry
                              )
                            )
                          }
                        >
                          {STEP_TYPES.map((stepType) => (
                            <option key={stepType} value={stepType}>
                              {stepType}
                            </option>
                          ))}
                        </select>
                      </label>

                      <label>
                        Concept
                        <select
                          value={step.concept ?? ""}
                          onChange={(event) =>
                            onChange(
                              procedures.map((entry, index) =>
                                index === procedureIndex
                                  ? {
                                      ...entry,
                                      steps: (entry.steps ?? []).map((stepEntry, nestedIndex) =>
                                        nestedIndex === stepIndex
                                          ? {
                                              ...stepEntry,
                                              concept: event.target.value || undefined
                                            }
                                          : stepEntry
                                      )
                                    }
                                  : entry
                              )
                            )
                          }
                        >
                          <option value="">None</option>
                          {conceptNames.map((conceptName) => (
                            <option key={conceptName} value={conceptName}>
                              {conceptName}
                            </option>
                          ))}
                        </select>
                      </label>

                      <label>
                        Query
                        <select
                          value={step.query ?? ""}
                          onChange={(event) =>
                            onChange(
                              procedures.map((entry, index) =>
                                index === procedureIndex
                                  ? {
                                      ...entry,
                                      steps: (entry.steps ?? []).map((stepEntry, nestedIndex) =>
                                        nestedIndex === stepIndex
                                          ? {
                                              ...stepEntry,
                                              query: event.target.value || undefined
                                            }
                                          : stepEntry
                                      )
                                    }
                                  : entry
                              )
                            )
                          }
                        >
                          <option value="">None</option>
                          {queryNames.map((queryName) => (
                            <option key={queryName} value={queryName}>
                              {queryName}
                            </option>
                          ))}
                        </select>
                      </label>

                      <label>
                        Procedure
                        <select
                          value={step.procedure ?? ""}
                          onChange={(event) =>
                            onChange(
                              procedures.map((entry, index) =>
                                index === procedureIndex
                                  ? {
                                      ...entry,
                                      steps: (entry.steps ?? []).map((stepEntry, nestedIndex) =>
                                        nestedIndex === stepIndex
                                          ? {
                                              ...stepEntry,
                                              procedure: event.target.value || undefined
                                            }
                                          : stepEntry
                                      )
                                    }
                                  : entry
                              )
                            )
                          }
                        >
                          <option value="">None</option>
                          {procedureNames
                            .filter((procedureName) => procedureName !== procedure.name)
                            .map((procedureName) => (
                              <option key={procedureName} value={procedureName}>
                                {procedureName}
                              </option>
                            ))}
                        </select>
                      </label>
                    </div>
                  </article>
                ))}
              </div>

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
            </article>
          ))
        )}
      </div>
    </section>
  );
}
