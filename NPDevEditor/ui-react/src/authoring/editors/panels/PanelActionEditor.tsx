import type { AuthoringPanel, AuthoringPanelAction } from "../modelDocumentTypes";

type PanelActionEditorProps = {
  panel: AuthoringPanel;
  panels: AuthoringPanel[];
  panelIndex: number;
  action: AuthoringPanelAction;
  actionIndex: number;
  procedureNames: string[];
  flowNames: string[];
  actionBindings: string[];
  onChange: (panels: AuthoringPanel[]) => void;
};

export default function PanelActionEditor({
  panel,
  panels,
  panelIndex,
  action,
  actionIndex,
  procedureNames,
  flowNames,
  actionBindings,
  onChange
}: PanelActionEditorProps): JSX.Element {
  const updateAction = (updater: (entry: AuthoringPanelAction) => AuthoringPanelAction): void => {
    onChange(
      panels.map((entry, index) =>
        index === panelIndex
          ? {
              ...entry,
              actions: (entry.actions ?? []).map((actionEntry, nestedIndex) =>
                nestedIndex === actionIndex ? updater(actionEntry) : actionEntry
              )
            }
          : entry
      )
    );
  };

  return (
    <article className="authoring-subcard">
      <div className="authoring-preview-card__header">
        <strong>{action.name || `Action ${actionIndex + 1}`}</strong>
        <button
          type="button"
          className="authoring-ghost-button"
          onClick={() =>
            onChange(
              panels.map((entry, index) =>
                index === panelIndex
                  ? {
                      ...entry,
                      actions: (entry.actions ?? []).filter((_, nestedIndex) => nestedIndex !== actionIndex)
                    }
                  : entry
              )
            )
          }
        >
          Remove action
        </button>
      </div>

      <div className="authoring-form-grid">
        <label>
          Name
          <input value={action.name} onChange={(event) => updateAction((entry) => ({ ...entry, name: event.target.value }))} />
        </label>
        <label>
          Binding
          <select value={action.binding ?? "procedure"} onChange={(event) => updateAction((entry) => ({ ...entry, binding: event.target.value }))}>
            {actionBindings.map((binding) => (
              <option key={binding} value={binding}>
                {binding}
              </option>
            ))}
          </select>
        </label>
        <label>
          Label
          <input value={action.label ?? ""} onChange={(event) => updateAction((entry) => ({ ...entry, label: event.target.value || undefined }))} />
        </label>
        <label>
          Procedure
          <select value={action.procedure ?? ""} onChange={(event) => updateAction((entry) => ({ ...entry, procedure: event.target.value || undefined }))}>
            <option value="">None</option>
            {procedureNames.map((procedureName) => (
              <option key={procedureName} value={procedureName}>
                {procedureName}
              </option>
            ))}
          </select>
        </label>
        <label>
          Flow
          <select value={action.flow ?? ""} onChange={(event) => updateAction((entry) => ({ ...entry, flow: event.target.value || undefined }))}>
            <option value="">None</option>
            {flowNames.map((flowName) => (
              <option key={flowName} value={flowName}>
                {flowName}
              </option>
            ))}
          </select>
        </label>
      </div>
      <span hidden>{panel.name}</span>
    </article>
  );
}
