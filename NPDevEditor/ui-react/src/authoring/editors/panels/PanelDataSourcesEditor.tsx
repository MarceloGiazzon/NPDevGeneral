import type { AuthoringPanel, AuthoringPanelDataSource } from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";

type PanelDataSourcesEditorProps = {
  panel: AuthoringPanel;
  panels: AuthoringPanel[];
  panelIndex: number;
  conceptNames: string[];
  onChange: (panels: AuthoringPanel[]) => void;
};

const ROW_OPS = ["add", "delete"];

function buildDefaultDataSource(existing: AuthoringPanelDataSource[]): AuthoringPanelDataSource {
  return { name: `dataSource${existing.length + 1}` };
}

/**
 * LIFT-ROWOPS-P4: authors the declared Panel's dataSources[] array -- including the rowOps that
 * let a dataSource create/delete rows through the generic CRUD gateway (LIFT-ROWOPS-P1/P2/P3)
 * instead of only ever rendering read-only data. One level of parent/child nesting mirrors the
 * DSL's own limit (SemanticValidator rejects a second level).
 */
export default function PanelDataSourcesEditor({
  panel,
  panels,
  panelIndex,
  conceptNames,
  onChange
}: PanelDataSourcesEditorProps): JSX.Element {
  const dataSources = panel.dataSources ?? [];

  const updateDataSources = (updater: (entries: AuthoringPanelDataSource[]) => AuthoringPanelDataSource[]): void => {
    onChange(
      panels.map((entry, index) => (index === panelIndex ? { ...entry, dataSources: updater(entry.dataSources ?? []) } : entry))
    );
  };

  const updateDataSource = (
    dataSourceIndex: number,
    updater: (entry: AuthoringPanelDataSource) => AuthoringPanelDataSource
  ): void => {
    updateDataSources((entries) => entries.map((entry, index) => (index === dataSourceIndex ? updater(entry) : entry)));
  };

  const toggleRowOp = (dataSourceIndex: number, op: string): void => {
    updateDataSource(dataSourceIndex, (entry) => {
      const current = entry.rowOps ?? [];
      const next = current.includes(op) ? current.filter((value) => value !== op) : [...current, op];
      return { ...entry, rowOps: next.length ? next : undefined };
    });
  };

  return (
    <div className="authoring-editor-stack">
      <div className="authoring-editor-section__miniheader">
        <span>Data sources</span>
        <button
          type="button"
          className="authoring-secondary-inline"
          onClick={() => updateDataSources((entries) => [...entries, buildDefaultDataSource(entries)])}
        >
          Add data source
        </button>
      </div>

      {dataSources.length === 0 ? (
        <p>No data sources yet. A concept-bound data source can opt into row add/delete below.</p>
      ) : (
        <div className="authoring-table-card">
          <table className="grid-table compact">
            <thead>
              <tr>
                <th>Name</th>
                <th>Concept</th>
                <th>Parent data source</th>
                <th>Parent field</th>
                <th>Child field</th>
                <th>Row ops</th>
                <th>Add-form fields</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {dataSources.map((dataSource, dataSourceIndex) => (
                <tr key={`${dataSource.name}-${dataSourceIndex}`}>
                  <td>
                    <input
                      value={dataSource.name}
                      onChange={(event) =>
                        updateDataSource(dataSourceIndex, (entry) => ({ ...entry, name: event.target.value }))
                      }
                    />
                  </td>
                  <td>
                    <select
                      value={dataSource.concept ?? ""}
                      onChange={(event) =>
                        updateDataSource(dataSourceIndex, (entry) => ({
                          ...entry,
                          concept: event.target.value || undefined
                        }))
                      }
                    >
                      <option value="">None</option>
                      {conceptNames.map((conceptName) => (
                        <option key={conceptName} value={conceptName}>
                          {conceptName}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <select
                      value={dataSource.parentDataSource ?? ""}
                      onChange={(event) =>
                        updateDataSource(dataSourceIndex, (entry) => ({
                          ...entry,
                          parentDataSource: event.target.value || undefined
                        }))
                      }
                    >
                      <option value="">(root)</option>
                      {dataSources
                        .filter((_, otherIndex) => otherIndex !== dataSourceIndex)
                        .map((otherDataSource) => (
                          <option key={otherDataSource.name} value={otherDataSource.name}>
                            {otherDataSource.name}
                          </option>
                        ))}
                    </select>
                  </td>
                  <td>
                    <input
                      value={dataSource.parentField ?? ""}
                      onChange={(event) =>
                        updateDataSource(dataSourceIndex, (entry) => ({
                          ...entry,
                          parentField: event.target.value || undefined
                        }))
                      }
                    />
                  </td>
                  <td>
                    <input
                      value={dataSource.childField ?? ""}
                      onChange={(event) =>
                        updateDataSource(dataSourceIndex, (entry) => ({
                          ...entry,
                          childField: event.target.value || undefined
                        }))
                      }
                    />
                  </td>
                  <td>
                    {ROW_OPS.map((op) => (
                      <label key={op} style={{ marginRight: "8px" }}>
                        <input
                          type="checkbox"
                          checked={(dataSource.rowOps ?? []).includes(op)}
                          onChange={() => toggleRowOp(dataSourceIndex, op)}
                        />
                        {op}
                      </label>
                    ))}
                  </td>
                  <td>
                    <input
                      value={joinTextList(dataSource.addFormFields)}
                      placeholder="field1, field2"
                      onChange={(event) =>
                        updateDataSource(dataSourceIndex, (entry) => ({
                          ...entry,
                          addFormFields: parseTextList(event.target.value)
                        }))
                      }
                    />
                  </td>
                  <td>
                    <button
                      type="button"
                      className="authoring-ghost-button"
                      onClick={() => updateDataSources((entries) => entries.filter((_, index) => index !== dataSourceIndex))}
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
