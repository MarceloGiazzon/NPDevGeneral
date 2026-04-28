import React, { useEffect, useState } from "react";
import type { AuthoringEntity, AuthoringField, AuthoringSchemaProperty } from "../modelDocumentTypes";
import {
  buildEmptyProperty,
  ensureArrayItemProperties,
  ensureObjectProperties
} from "../editorUtils";
import VisibilityConditionBuilder from "../../designers/VisibilityConditionBuilder";

type FieldsEditorSectionProps = {
  entity: AuthoringEntity | null;
  requestedFieldName?: string | null;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
  onAddField: () => void;
  onRemoveField: (fieldName: string) => void;
  onMoveField: (fieldName: string, direction: -1 | 1) => void;
};

function updatePropertyMap(
  properties: Record<string, AuthoringSchemaProperty>,
  propertyName: string,
  updater: (property: AuthoringSchemaProperty) => AuthoringSchemaProperty
): Record<string, AuthoringSchemaProperty> {
  return Object.fromEntries(
    Object.entries(properties).map(([name, value]) => [name, name === propertyName ? updater(value) : value])
  );
}

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

  const renderPropertyEditor = (
    title: string,
    properties: Record<string, AuthoringSchemaProperty>,
    onChange: (properties: Record<string, AuthoringSchemaProperty>) => void
  ): JSX.Element => {
    return (
      <div className="authoring-subcard">
        <div className="authoring-editor-section__miniheader">
          <strong>{title}</strong>
          <button
            type="button"
            className="authoring-secondary-inline"
            onClick={() => {
              const [propertyName, property] = buildEmptyProperty(`field${Object.keys(properties).length + 1}`);
              onChange({
                ...properties,
                [propertyName]: property
              });
            }}
          >
            Add property
          </button>
        </div>

        {Object.entries(properties).map(([propertyName, propertyValue]) => (
          <div key={propertyName} className="authoring-inline-grid">
            <input
              value={propertyName}
              onChange={(event) => {
                const nextEntries = Object.entries(properties).map(([name, value]) =>
                  name === propertyName ? [event.target.value, value] : [name, value]
                );
                onChange(Object.fromEntries(nextEntries));
              }}
            />
            <input
              value={propertyValue.type ?? ""}
              onChange={(event) =>
                onChange(
                  updatePropertyMap(properties, propertyName, (propertyDraft) => ({
                    ...propertyDraft,
                    type: event.target.value
                  }))
                )
              }
            />
            <button
              type="button"
              className="authoring-ghost-button"
              onClick={() =>
                onChange(
                  Object.fromEntries(Object.entries(properties).filter(([name]) => name !== propertyName))
                )
              }
            >
              Remove
            </button>
          </div>
        ))}
      </div>
    );
  };

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
          <div className="authoring-editor-card">
            <div className="authoring-editor-inline-actions">
              <button
                type="button"
                className="authoring-secondary-inline"
                disabled={selectedIndex <= 0}
                onClick={() => onMoveField(selectedField.name, -1)}
              >
                Move up
              </button>
              <button
                type="button"
                className="authoring-secondary-inline"
                disabled={selectedIndex >= entity.fields.length - 1}
                onClick={() => onMoveField(selectedField.name, 1)}
              >
                Move down
              </button>
              <button
                type="button"
                className="authoring-ghost-button"
                onClick={() => onRemoveField(selectedField.name)}
              >
                Remove field
              </button>
            </div>

            <div className="authoring-form-grid">
              <label>
                Field name
                <input
                  value={selectedField.name}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      name: event.target.value
                    }))
                  }
                />
              </label>

              <label>
                Type
                <select
                  value={selectedField.type ?? "string"}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      type: event.target.value
                    }))
                  }
                >
                  {["string", "integer", "number", "boolean", "uuid", "datetime", "enum", "reference", "object", "array"].map(
                    (type) => (
                      <option key={type} value={type}>
                        {type}
                      </option>
                    )
                  )}
                </select>
              </label>

              <label>
                Domain type
                <input
                  value={selectedField.domainType ?? ""}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      domainType: event.target.value || undefined
                    }))
                  }
                />
              </label>

              <label>
                Default
                <input
                  value={selectedField.default == null ? "" : String(selectedField.default)}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      default: event.target.value || undefined
                    }))
                  }
                />
              </label>
            </div>

            <div className="authoring-toggle-row">
              <label>
                <input
                  type="checkbox"
                  checked={Boolean(selectedField.required)}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      required: event.target.checked
                    }))
                  }
                />
                Required
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={Boolean(selectedField.id)}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      id: event.target.checked
                    }))
                  }
                />
                ID field
              </label>
            </div>

            <label className="authoring-form-grid__full">
              Description
              <textarea
                rows={2}
                value={selectedField.description ?? ""}
                onChange={(event) =>
                  onUpdateField(selectedField.name, (field) => ({
                    ...field,
                    description: event.target.value
                  }))
                }
              />
            </label>

            <div className="authoring-form-grid">
              <label>
                Default expression
                <input
                  value={selectedField.defaultExpression ?? ""}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      defaultExpression: event.target.value || undefined
                    }))
                  }
                />
              </label>

              <label>
                Derived expression
                <input
                  value={selectedField.derivedExpression ?? ""}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      derivedExpression: event.target.value || undefined
                    }))
                  }
                />
              </label>

              <label>
                Item identity field
                <input
                  value={selectedField.itemIdentityField ?? ""}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      itemIdentityField: event.target.value || undefined
                    }))
                  }
                />
              </label>

              <label>
                Duplication policy
                <select
                  value={selectedField.duplicationPolicy ?? "allow"}
                  onChange={(event) =>
                    onUpdateField(selectedField.name, (field) => ({
                      ...field,
                      duplicationPolicy: event.target.value as "allow" | "deny"
                    }))
                  }
                >
                  <option value="allow">allow</option>
                  <option value="deny">deny</option>
                </select>
              </label>
            </div>

            {selectedField.type === "object"
              ? renderPropertyEditor("Nested object properties", ensureObjectProperties(selectedField), (properties) =>
                  onUpdateField(selectedField.name, (field) => ({
                    ...field,
                    properties
                  }))
                )
              : null}

            {selectedField.type === "array"
              ? renderPropertyEditor("Repeated item properties", ensureArrayItemProperties(selectedField), (properties) =>
                  onUpdateField(selectedField.name, (field) => ({
                    ...field,
                    items: {
                      ...(field.items ?? { type: "object" }),
                      type: "object",
                      properties
                    }
                  }))
                )
              : null}

            <VisibilityConditionBuilder
              entity={entity}
              field={selectedField}
              onUpdateField={(updater) => onUpdateField(selectedField.name, updater)}
            />
          </div>
        ) : null}
      </div>
    </section>
  );
}
