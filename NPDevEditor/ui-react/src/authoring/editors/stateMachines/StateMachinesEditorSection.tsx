import React from "react";
import type {
  AuthoringEntity,
  AuthoringLifecycleState,
  AuthoringLifecycleTransition
} from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";
import StateTransitionDesigner from "../../designers/StateTransitionDesigner";

type StateMachinesEditorSectionProps = {
  entity: AuthoringEntity | null;
  onChangeStates: (states: AuthoringLifecycleState[]) => void;
  onChangeTransitions: (transitions: AuthoringLifecycleTransition[]) => void;
};

export default function StateMachinesEditorSection({
  entity,
  onChangeStates,
  onChangeTransitions
}: StateMachinesEditorSectionProps): JSX.Element | null {
  if (!entity) {
    return null;
  }

  const states = entity.lifecycle?.states ?? [];
  const transitions = entity.lifecycle?.transitions ?? [];

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>State machine editor</h3>
          <p>Control lifecycle states, terminal markers, guarded transitions, and transition payload requirements.</p>
        </div>
      </div>

      <div className="authoring-subcard">
        <div className="authoring-editor-section__miniheader">
          <strong>States</strong>
          <button
            type="button"
            className="authoring-secondary-inline"
            onClick={() =>
              onChangeStates([
                ...states,
                {
                  value: `State${states.length + 1}`,
                  label: `State ${states.length + 1}`
                }
              ])
            }
          >
            Add state
          </button>
        </div>

        <table className="grid-table compact">
          <thead>
            <tr>
              <th>Value</th>
              <th>Label</th>
              <th>Initial</th>
              <th>Terminal</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {states.map((state, index) => (
              <tr key={`${state.value}-${index}`}>
                <td>
                  <input
                    value={state.value}
                    onChange={(event) =>
                      onChangeStates(
                        states.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                value: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  <input
                    value={state.label ?? ""}
                    onChange={(event) =>
                      onChangeStates(
                        states.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                label: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    checked={Boolean(state.initial)}
                    onChange={(event) =>
                      onChangeStates(
                        states.map((entry, entryIndex) => ({
                          ...entry,
                          initial: entryIndex === index ? event.target.checked : false
                        }))
                      )
                    }
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    checked={Boolean(state.terminal)}
                    onChange={(event) =>
                      onChangeStates(
                        states.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                terminal: event.target.checked
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  <button
                    type="button"
                    className="authoring-ghost-button"
                    onClick={() => onChangeStates(states.filter((_, entryIndex) => entryIndex !== index))}
                  >
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="authoring-subcard">
        <div className="authoring-editor-section__miniheader">
          <strong>Transitions</strong>
          <button
            type="button"
            className="authoring-secondary-inline"
            onClick={() =>
              onChangeTransitions([
                ...transitions,
                {
                  from: states[0]?.value ?? "",
                  to: states[1]?.value ?? "",
                  actionLabel: "Transition"
                }
              ])
            }
          >
            Add transition
          </button>
        </div>

        <table className="grid-table compact">
          <thead>
            <tr>
              <th>From</th>
              <th>To</th>
              <th>Event</th>
              <th>Required payload</th>
              <th>Guard</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {transitions.map((transition, index) => (
              <tr key={`${transition.from}-${transition.to}-${index}`}>
                <td>
                  <input
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
                  />
                </td>
                <td>
                  <input
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
                  />
                </td>
                <td>
                  <input
                    value={transition.event ?? ""}
                    onChange={(event) =>
                      onChangeTransitions(
                        transitions.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                event: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
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
                </td>
                <td>
                  <input
                    value={transition.guard ?? ""}
                    onChange={(event) =>
                      onChangeTransitions(
                        transitions.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                guard: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  <button
                    type="button"
                    className="authoring-ghost-button"
                    onClick={() => onChangeTransitions(transitions.filter((_, entryIndex) => entryIndex !== index))}
                  >
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <StateTransitionDesigner
          entity={entity}
          states={states}
          transitions={transitions}
          onChangeTransitions={onChangeTransitions}
        />
      </div>
    </section>
  );
}
