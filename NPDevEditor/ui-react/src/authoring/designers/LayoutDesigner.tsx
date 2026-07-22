import React from "react";
import type { AuthoringEntity, AuthoringField, AuthoringModelDocument } from "../editors/modelDocumentTypes";
import LayoutFieldCard from "./LayoutFieldCard";

type LayoutDesignerProps = {
  document: AuthoringModelDocument;
  entity: AuthoringEntity;
  onChangeDocument: (document: AuthoringModelDocument) => void;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
};

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
          <LayoutFieldCard
            key={field.name}
            field={field}
            knownTabs={knownTabs}
            knownSections={knownSections}
            onUpdateField={onUpdateField}
          />
        ))}
      </div>
    </div>
  );
}
