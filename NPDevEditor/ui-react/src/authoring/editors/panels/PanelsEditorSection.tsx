import React from "react";
import type { AuthoringPanel, AuthoringPanelAction } from "../modelDocumentTypes";
import PanelActionEditor from "./PanelActionEditor";
import PanelDataSourcesEditor from "./PanelDataSourcesEditor";

type PanelsEditorSectionProps = {
  panels: AuthoringPanel[];
  conceptNames: string[];
  procedureNames: string[];
  flowNames: string[];
  onChange: (panels: AuthoringPanel[]) => void;
};

const PANEL_LAYOUT_TYPES = ["form", "table", "detail", "dashboard", "stack", "grid"];
const PANEL_ACTION_BINDINGS = ["procedure", "flow", "conceptQuery", "conceptMutation"];

function buildDefaultPanelAction(): AuthoringPanelAction {
  return {
    name: "PanelAction1",
    binding: "procedure"
  };
}

export default function PanelsEditorSection({
  panels,
  conceptNames,
  procedureNames,
  flowNames,
  onChange
}: PanelsEditorSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Panels</h3>
          <p>Keep panel routes, data sources, and actions in the same canonical contract instead of side files or hidden workbench state.</p>
        </div>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={() =>
            onChange([
              ...panels,
              {
                name: `Panel${panels.length + 1}`,
                route: `/panel-${panels.length + 1}`,
                layout: { type: "form" },
                actions: []
              }
            ])
          }
        >
          Add panel
        </button>
      </div>

      <div className="authoring-editor-stack">
        {panels.length === 0 ? (
          <article className="authoring-subcard">
            <p>No panels yet. Add them when a concept needs an explicit supported UI surface.</p>
          </article>
        ) : (
          panels.map((panel, panelIndex) => (
            <article key={`${panel.name}-${panelIndex}`} className="authoring-subcard">
              <div className="authoring-preview-card__header">
                <strong>{panel.name || `Panel ${panelIndex + 1}`}</strong>
                <button
                  type="button"
                  className="authoring-ghost-button"
                  onClick={() => onChange(panels.filter((_, index) => index !== panelIndex))}
                >
                  Remove
                </button>
              </div>

              <div className="authoring-form-grid">
                <label>
                  Name
                  <input
                    value={panel.name}
                    onChange={(event) =>
                      onChange(
                        panels.map((entry, index) =>
                          index === panelIndex
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
                  Route
                  <input
                    value={panel.route ?? ""}
                    onChange={(event) =>
                      onChange(
                        panels.map((entry, index) =>
                          index === panelIndex
                            ? {
                                ...entry,
                                route: event.target.value || undefined
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>

                <label>
                  Layout
                  <select
                    value={panel.layout?.type ?? "form"}
                    onChange={(event) =>
                      onChange(
                        panels.map((entry, index) =>
                          index === panelIndex
                            ? {
                                ...entry,
                                layout: {
                                  ...(entry.layout ?? { type: "form" }),
                                  type: event.target.value
                                }
                              }
                            : entry
                        )
                      )
                    }
                  >
                    {PANEL_LAYOUT_TYPES.map((layoutType) => (
                      <option key={layoutType} value={layoutType}>
                        {layoutType}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <PanelDataSourcesEditor
                panel={panel}
                panels={panels}
                panelIndex={panelIndex}
                conceptNames={conceptNames}
                onChange={onChange}
              />

              <div className="authoring-editor-stack">
                {(panel.actions ?? []).map((action, actionIndex) => (
                  <PanelActionEditor
                    key={`${action.name}-${actionIndex}`}
                    panel={panel}
                    panels={panels}
                    panelIndex={panelIndex}
                    action={action}
                    actionIndex={actionIndex}
                    procedureNames={procedureNames}
                    flowNames={flowNames}
                    actionBindings={PANEL_ACTION_BINDINGS}
                    onChange={onChange}
                  />
                ))}
              </div>

              <button
                type="button"
                className="authoring-secondary-inline"
                onClick={() =>
                  onChange(
                    panels.map((entry, index) =>
                      index === panelIndex
                        ? {
                            ...entry,
                            actions: [
                              ...(entry.actions ?? []),
                              {
                                ...buildDefaultPanelAction(),
                                name: `PanelAction${(entry.actions ?? []).length + 1}`
                              }
                            ]
                          }
                        : entry
                    )
                  )
                }
              >
                Add panel action
              </button>
            </article>
          ))
        )}
      </div>
    </section>
  );
}
