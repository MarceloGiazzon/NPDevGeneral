import React from "react";
import type { AuthoringEntity, AuthoringLifecycleState, AuthoringLifecycleTransition } from "../editors/modelDocumentTypes";
import { joinTextList, parseTextList } from "../editors/editorUtils";
import SimpleConditionBuilder from "../widgets/SimpleConditionBuilder";

type StateTransitionDesignerProps = {
  entity: AuthoringEntity;
  states: AuthoringLifecycleState[];
  transitions: AuthoringLifecycleTransition[];
  onChangeTransitions: (transitions: AuthoringLifecycleTransition[]) => void;
};

export default function StateTransitionDesigner({
  entity,
  states,
  transitions,
  onChangeTransitions
}: StateTransitionDesignerProps): JSX.Element {
  const stateOptions = states.map((state) => state.value);
  const fieldOptions = entity.fields.map((field) => field.name);

  return (
    <div className="authoring-designer-stack">
      <div className="authoring-editor-section__miniheader">
        <strong>State transition designer</strong>
        <span>Use state-aware dropdowns and guided guard builders instead of editing transitions as raw rows only.</span>
      </div>

      {transitions.map((transition, index) => (
        <article key={`${transition.from}-${transition.to}-${index}`} className="authoring-designer-card">
          <div className="authoring-form-grid">
            <label>
              From
              <select
                value={transition.from}
                onChange={(event) =>
                  onChangeTransitions(
                    transitions.map((entry, entryIndex) =>
                      entryIndex === index
                        ? {
                            ...entry,
                            from: event.target.value
                          }
                        : entry
                    )
                  )
                }
              >
                {stateOptions.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>

            <label>
              To
              <select
                value={transition.to}
                onChange={(event) =>
                  onChangeTransitions(
                    transitions.map((entry, entryIndex) =>
                      entryIndex === index
                        ? {
                            ...entry,
                            to: event.target.value
                          }
                        : entry
                    )
                  )
                }
              >
                {stateOptions.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>

            <label>
              Event
              <input
                value={transition.event ?? ""}
                onChange={(event) =>
                  onChangeTransitions(
                    transitions.map((entry, entryIndex) =>
                      entryIndex === index
                        ? {
                            ...entry,
                            event: event.target.value || undefined
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
                value={transition.actionLabel ?? ""}
                onChange={(event) =>
                  onChangeTransitions(
                    transitions.map((entry, entryIndex) =>
                      entryIndex === index
                        ? {
                            ...entry,
                            actionLabel: event.target.value || undefined
                          }
                        : entry
                    )
                  )
                }
              />
            </label>
          </div>

          <label className="authoring-form-grid__full">
            Required payload
            <input
              value={joinTextList(transition.requiredPayload)}
              onChange={(event) =>
                onChangeTransitions(
                  transitions.map((entry, entryIndex) =>
                    entryIndex === index
                      ? {
                          ...entry,
                          requiredPayload: parseTextList(event.target.value)
                        }
                      : entry
                  )
                )
              }
            />
          </label>

          <SimpleConditionBuilder
            title={`Guard for ${transition.from} to ${transition.to}`}
            value={transition.guard}
            fieldOptions={fieldOptions}
            onChange={(value) =>
              onChangeTransitions(
                transitions.map((entry, entryIndex) =>
                  entryIndex === index
                    ? {
                        ...entry,
                        guard: value
                      }
                    : entry
                )
              )
            }
          />
        </article>
      ))}
    </div>
  );
}
