import React, { useEffect, useState } from "react";
import type { AuthoringEntity, AuthoringField } from "../modelDocumentTypes";
import FieldDetailsEditor from "./FieldDetailsEditor";

type FieldsEditorSectionProps = {
  entity: AuthoringEntity | null;
  requestedFieldName?: string | null;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
  onAddField: () => void;
  onRemoveField: (fieldName: string) => void;
  onMoveField: (fieldName: string, direction: -1 | 1) => void;
};

export default function FieldsEditorSection({
  entity,
  requestedFieldName,
  onUpdateField,
  onAddField,
  onRemoveField,
  onMoveField
}: FieldsEditorSectionProps): JSX.Element {
  const [selectedFieldName, setSelectedFieldName] = useState<string | null>(entity?.fields[0]?.name ?? null);

  useEffect(() => {
    setSelectedFieldName(entity?.fields[0]?.name ?? null);
  }, [entity?.name]);

  useEffect(() => {
    if (entity && !entity.fields.some((field) => field.name === selectedFieldName)) {
      setSelectedFieldName(entity.fields[0]?.name ?? null);
    }
  }, [entity, selectedFieldName]);

  useEffect(() => {
    if (entity && requestedFieldName && entity.fields.some((field) => field.name === requestedFieldName)) {
      setSelectedFieldName(requestedFieldName);
    }
  }, [entity, requestedFieldName]);

  if (!entity) {
    return null;
  }

  const selectedField = entity.fields.find((field) => field.name === selectedFieldName) ?? entity.fields[0];
  const selectedIndex = entity.fields.findIndex((field) => field.name === selectedField?.name);

  return (
    <section id="authoring-section-fields" className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Field editor</h3>
          <p>Work with required flags, defaults, derived values, nested objects, and repeated sections.</p>
        </div>
        <button type="button" onClick={onAddField}>
          Add field
        </button>
      </div>

      <div className="authoring-editor-grid">
        <div className="authoring-editor-list">
          {entity.fields.map((field, index) => (
            <button
              key={field.name}
              type="button"
              className={`authoring-editor-list__item ${field.name === selectedField?.name ? "is-selected" : ""}`}
              onClick={() => setSelectedFieldName(field.name)}
            >
              <strong>{field.ui?.label ?? field.name}</strong>
              <small>{field.type ?? "string"}</small>
              <span>
                {index + 1}. {field.name}
              </span>
            </button>
          ))}
        </div>

        {selectedField ? (
          <FieldDetailsEditor
            entity={entity}
            selectedField={selectedField}
            selectedIndex={selectedIndex}
            onUpdateField={onUpdateField}
            onRemoveField={onRemoveField}
            onMoveField={onMoveField}
          />
        ) : null}
      </div>
    </section>
  );
}
