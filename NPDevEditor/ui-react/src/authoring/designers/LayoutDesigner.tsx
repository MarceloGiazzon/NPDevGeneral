import React from "react";
import type { AuthoringEntity, AuthoringField, AuthoringModelDocument } from "../editors/modelDocumentTypes";

type LayoutDesignerProps = {
  document: AuthoringModelDocument;
  entity: AuthoringEntity;
  onChangeDocument: (document: AuthoringModelDocument) => void;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
};

const WIDTH_OPTIONS = ["compact", "normal", "wide", "full"];
const DISPLAY_MODES = ["form", "wizard", "split"];

export default function LayoutDesigner({
  document,
  entity,
  onChangeDocument,
  onUpdateField
}: LayoutDesignerProps): JSX.Element {
  const knownTabs = Array.from(new Set(entity.fields.map((field) => field.ui?.tab).filter(Boolean))) as string[];
  const knownSections = Array.from(new Set(entity.fields.map((field) => field.ui?.section).filter(Boolean))) as string[];

  return (
    <div className="authoring-designer-stack">
      <div className="authoring-editor-section__miniheader">
        <strong>Layout designer</strong>
        <span>Refine tabs, sections, columns, width, and list behavior through guided layout controls.</span>
      </div>

      <div className="authoring-form-grid">
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
          Display mode
          <select
            value={entity.ui?.displayMode ?? "form"}
            onChange={(event) =>
              onChangeDocument({
                ...document,
                concepts: document.concepts.map((entry) =>
                  entry.name === entity.name
                    ? {
                        ...entry,
                        ui: {
                          ...entry.ui,
                          displayMode: event.target.value
                        }
                      }
                    : entry
                )
              })
            }
          >
            {DISPLAY_MODES.map((mode) => (
              <option key={mode} value={mode}>
                {mode}
              </option>
            ))}
          </select>
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
                          defaultSort: event.target.value || undefined
                        }
                      }
                    : entry
                )
              })
            }
          />
        </label>

        <label>
          Default group
          <input
            value={entity.ui?.defaultGroup ?? ""}
            onChange={(event) =>
              onChangeDocument({
                ...document,
                concepts: document.concepts.map((entry) =>
                  entry.name === entity.name
                    ? {
                        ...entry,
                        ui: {
                          ...entry.ui,
                          defaultGroup: event.target.value || undefined
                        }
                      }
                    : entry
                )
              })
            }
          />
        </label>
      </div>

      <div className="authoring-designer-stack">
        {entity.fields.map((field) => (
          <article key={field.name} className="authoring-designer-card">
            <div className="authoring-preview-card__header">
              <strong>{field.ui?.label ?? field.name}</strong>
              <span>{field.name}</span>
            </div>

            <div className="authoring-form-grid">
              <label>
                Label
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
              </label>

              <label>
                Tab
                <input
                  list={`${field.name}-tabs`}
                  value={field.ui?.tab ?? ""}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        tab: event.target.value || undefined
                      }
                    }))
                  }
                />
                <datalist id={`${field.name}-tabs`}>
                  {knownTabs.map((tab) => (
                    <option key={tab} value={tab} />
                  ))}
                </datalist>
              </label>

              <label>
                Section
                <input
                  list={`${field.name}-sections`}
                  value={field.ui?.section ?? ""}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        section: event.target.value || undefined
                      }
                    }))
                  }
                />
                <datalist id={`${field.name}-sections`}>
                  {knownSections.map((section) => (
                    <option key={section} value={section} />
                  ))}
                </datalist>
              </label>

              <label>
                Column
                <input
                  type="number"
                  value={field.ui?.column ?? 1}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        column: Number(event.target.value)
                      }
                    }))
                  }
                />
              </label>

              <label>
                Column span
                <input
                  type="number"
                  value={field.ui?.columnSpan ?? 1}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        columnSpan: Number(event.target.value)
                      }
                    }))
                  }
                />
              </label>

              <label>
                Width
                <select
                  value={field.ui?.width ?? "normal"}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        width: event.target.value
                      }
                    }))
                  }
                >
                  {WIDTH_OPTIONS.map((width) => (
                    <option key={width} value={width}>
                      {width}
                    </option>
                  ))}
                </select>
              </label>

              <label>
                Form order
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
              </label>

              <label>
                List order
                <input
                  type="number"
                  value={field.ui?.listColumnOrder ?? 0}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        listColumnOrder: Number(event.target.value)
                      }
                    }))
                  }
                />
              </label>
            </div>

            <div className="authoring-toggle-row">
              <label>
                <input
                  type="checkbox"
                  checked={Boolean(field.ui?.summaryCard)}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        summaryCard: event.target.checked
                      }
                    }))
                  }
                />
                Summary card
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={Boolean(field.ui?.listColumn)}
                  onChange={(event) =>
                    onUpdateField(field.name, (fieldDraft) => ({
                      ...fieldDraft,
                      ui: {
                        ...fieldDraft.ui,
                        listColumn: event.target.checked
                      }
                    }))
                  }
                />
                Show in list/table
              </label>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
