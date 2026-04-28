import React from "react";
import type { AuthoringEntity, AuthoringField, AuthoringModelDocument } from "../modelDocumentTypes";
import LayoutDesigner from "../../designers/LayoutDesigner";

type MetadataEditorSectionProps = {
  document: AuthoringModelDocument;
  entity: AuthoringEntity | null;
  onChangeDocument: (document: AuthoringModelDocument) => void;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
};

export default function MetadataEditorSection({
  document,
  entity,
  onChangeDocument,
  onUpdateField
}: MetadataEditorSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Metadata editor</h3>
          <p>Edit top-level model identity plus concept and field presentation metadata in structured forms.</p>
        </div>
      </div>

      <div className="authoring-form-grid">
        <label>
          Namespace
          <input
            value={document.namespace}
            onChange={(event) =>
              onChangeDocument({
                ...document,
                namespace: event.target.value
              })
            }
          />
        </label>

        <label>
          DSL version
          <input
            value={document.dslVersion ?? ""}
            onChange={(event) =>
              onChangeDocument({
                ...document,
                dslVersion: event.target.value
              })
            }
          />
        </label>

        <label>
          Model version
          <input
            value={document.version}
            onChange={(event) =>
              onChangeDocument({
                ...document,
                version: event.target.value
              })
            }
          />
        </label>
      </div>

      {entity ? (
        <div className="authoring-subcard">
          <div className="authoring-editor-section__miniheader">
            <strong>{entity.name} presentation metadata</strong>
          </div>

          <div className="authoring-form-grid">
            <label>
              Concept label
              <input
                value={entity.ui?.label ?? ""}
                onChange={(event) =>
                  onChangeDocument({
                    ...document,
                    concepts: document.concepts.map((entry) =>
                      entry.name === entity.name
                        ? {
                            ...entry,
                            ui: {
                              ...entry.ui,
                              label: event.target.value
                            }
                          }
                        : entry
                    )
                  })
                }
              />
            </label>

            <label>
              Form columns
              <input
                type="number"
                value={entity.ui?.formColumns ?? 1}
                onChange={(event) =>
                  onChangeDocument({
                    ...document,
                    concepts: document.concepts.map((entry) =>
                      entry.name === entity.name
                        ? {
                            ...entry,
                            ui: {
                              ...entry.ui,
                              formColumns: Number(event.target.value)
                            }
                          }
                        : entry
                    )
                  })
                }
              />
            </label>

            <label>
              Default sort
              <input
                value={entity.ui?.defaultSort ?? ""}
                onChange={(event) =>
                  onChangeDocument({
                    ...document,
                    concepts: document.concepts.map((entry) =>
                      entry.name === entity.name
                        ? {
                            ...entry,
                            ui: {
                              ...entry.ui,
                              defaultSort: event.target.value
                            }
                          }
                        : entry
                    )
                  })
                }
              />
            </label>
          </div>

          <div className="authoring-table-card">
            <table className="grid-table compact">
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Label</th>
                  <th>Tab</th>
                  <th>Section</th>
                  <th>Order</th>
                </tr>
              </thead>
              <tbody>
                {entity.fields.map((field) => (
                  <tr key={field.name}>
                    <td>{field.name}</td>
                    <td>
                      <input
                        value={field.ui?.label ?? ""}
                        onChange={(event) =>
                          onUpdateField(field.name, (fieldDraft) => ({
                            ...fieldDraft,
                            ui: {
                              ...fieldDraft.ui,
                              label: event.target.value
                            }
                          }))
                        }
                      />
                    </td>
                    <td>
                      <input
                        value={field.ui?.tab ?? ""}
                        onChange={(event) =>
                          onUpdateField(field.name, (fieldDraft) => ({
                            ...fieldDraft,
                            ui: {
                              ...fieldDraft.ui,
                              tab: event.target.value
                            }
                          }))
                        }
                      />
                    </td>
                    <td>
                      <input
                        value={field.ui?.section ?? ""}
                        onChange={(event) =>
                          onUpdateField(field.name, (fieldDraft) => ({
                            ...fieldDraft,
                            ui: {
                              ...fieldDraft.ui,
                              section: event.target.value
                            }
                          }))
                        }
                      />
                    </td>
                    <td>
                      <input
                        type="number"
                        value={field.ui?.order ?? 0}
                        onChange={(event) =>
                          onUpdateField(field.name, (fieldDraft) => ({
                            ...fieldDraft,
                            ui: {
                              ...fieldDraft.ui,
                              order: Number(event.target.value)
                            }
                          }))
                        }
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <LayoutDesigner
            document={document}
            entity={entity}
            onChangeDocument={onChangeDocument}
            onUpdateField={onUpdateField}
          />
        </div>
      ) : null}
    </section>
  );
}
