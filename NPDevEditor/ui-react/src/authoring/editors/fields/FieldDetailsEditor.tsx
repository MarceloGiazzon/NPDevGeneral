import type { AuthoringEntity, AuthoringField } from "../modelDocumentTypes";
import {
  ensureArrayItemProperties,
  ensureObjectProperties
} from "../editorUtils";
import VisibilityConditionBuilder from "../../designers/VisibilityConditionBuilder";
import FieldPropertyEditor from "./FieldPropertyEditor";
import FieldFileConstraintsEditor from "./FieldFileConstraintsEditor";

type FieldDetailsEditorProps = {
  entity: AuthoringEntity;
  selectedField: AuthoringField;
  selectedIndex: number;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
  onRemoveField: (fieldName: string) => void;
  onMoveField: (fieldName: string, direction: -1 | 1) => void;
};

export default function FieldDetailsEditor({
  entity,
  selectedField,
  selectedIndex,
  onUpdateField,
  onRemoveField,
  onMoveField
}: FieldDetailsEditorProps): JSX.Element {
  return (
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
            {["string", "int", "integer", "long", "boolean", "uuid", "date", "datetime", "enum", "reference", "object", "array", "file"].map(
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
        <label title={selectedField.type === "file" ? "A file field can't be unique (LIFT-UPLOAD)" : undefined}>
          <input
            type="checkbox"
            disabled={selectedField.type === "file"}
            checked={Boolean(selectedField.unique)}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => {
                const unique = event.target.checked;
                return {
                  ...field,
                  unique,
                  connectable: unique ? field.connectable : undefined
                };
              })
            }
          />
          Unique
        </label>
        <label title={selectedField.type === "file" ? "A file field can't be an anchor (LIFT-UPLOAD)" : undefined}>
          <input
            type="checkbox"
            disabled={selectedField.type === "file"}
            checked={selectedField.connectable === "anchor"}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                unique: event.target.checked && !field.id ? true : field.unique,
                connectable: event.target.checked ? "anchor" : undefined
              }))
            }
          />
          Anchor
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

      {selectedField.type === "object" ? (
        <FieldPropertyEditor
          title="Nested object properties"
          properties={ensureObjectProperties(selectedField)}
          onChange={(properties) =>
            onUpdateField(selectedField.name, (field) => ({
              ...field,
              properties
            }))
          }
        />
      ) : null}

      {selectedField.type === "array" ? (
        <FieldPropertyEditor
          title="Repeated item properties"
          properties={ensureArrayItemProperties(selectedField)}
          onChange={(properties) =>
            onUpdateField(selectedField.name, (field) => ({
              ...field,
              items: {
                ...(field.items ?? { type: "object" }),
                type: "object",
                properties
              }
            }))
          }
        />
      ) : null}

      {selectedField.type === "file" ? (
        <FieldFileConstraintsEditor selectedField={selectedField} onUpdateField={onUpdateField} />
      ) : null}

      <VisibilityConditionBuilder
        entity={entity}
        field={selectedField}
        onUpdateField={(updater) => onUpdateField(selectedField.name, updater)}
      />
    </div>
  );
}
