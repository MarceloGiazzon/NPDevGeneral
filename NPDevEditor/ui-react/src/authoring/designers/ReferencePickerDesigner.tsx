import React from "react";
import type { AuthoringEntity, AuthoringField } from "../editors/modelDocumentTypes";
import { joinTextList, parseTextList } from "../editors/editorUtils";

type ReferencePickerDesignerProps = {
  field: AuthoringField;
  entities: AuthoringEntity[];
  onChange: (updater: (field: AuthoringField) => AuthoringField) => void;
};

const PICKER_TYPES = ["dialog", "inline-search", "dropdown"];
const DISPLAY_PRESETS = [
  { id: "id-only", label: "ID only", buildTemplate: (displayField: string) => `{${displayField}}` },
  { id: "label-id", label: "Label + ID", buildTemplate: (displayField: string) => `{${displayField}} ({id})` },
  { id: "label-secondary", label: "Primary + secondary", buildTemplate: (displayField: string) => `{${displayField}} - {status}` }
];

export default function ReferencePickerDesigner({
  field,
  entities,
  onChange
}: ReferencePickerDesignerProps): JSX.Element {
  const semantics = field.reference ?? { target: "" };
  const targetEntity = entities.find((entity) => entity.name === semantics.target) ?? null;
  const targetFields = targetEntity?.fields.map((entry) => entry.name) ?? [];
  const targetAnchors =
    targetEntity?.fields.filter((entry) => entry.id || (entry.unique && entry.connectable === "anchor")) ?? [];

  const applyReference = (updater: NonNullable<AuthoringField["reference"]>) => {
    onChange((fieldDraft) => ({
      ...fieldDraft,
      reference: updater
    }));
  };

  return (
    <div className="authoring-designer-stack">
      <div className="authoring-editor-section__miniheader">
        <strong>Reference picker designer</strong>
        <span>Design target selection, picker columns, and card/template output with guided controls.</span>
      </div>

      <div className="authoring-form-grid">
        <label>
          Target concept
          <select
            value={semantics.target}
            onChange={(event) =>
              applyReference({
                ...semantics,
                target: event.target.value,
                displayField: "id",
                searchFields: ["id"],
                pickerColumns: ["id"]
              })
            }
          >
            <option value="">Choose target</option>
            {entities.map((entity) => (
              <option key={entity.name} value={entity.name}>
                {entity.name}
              </option>
            ))}
          </select>
        </label>

        <label>
          Display field
          <select
            value={semantics.displayField ?? ""}
            onChange={(event) =>
              applyReference({
                ...semantics,
                displayField: event.target.value || undefined
              })
            }
          >
            <option value="">Choose field</option>
            {targetFields.map((fieldName) => (
              <option key={fieldName} value={fieldName}>
                {fieldName}
              </option>
            ))}
          </select>
        </label>

        <label>
          Via anchor
          <select
            value={semantics.via ?? ""}
            disabled={!targetEntity}
            onChange={(event) =>
              applyReference({
                ...semantics,
                via: event.target.value || undefined
              })
            }
          >
            <option value="">id</option>
            {targetAnchors
              .filter((anchorField) => anchorField.name !== "id")
              .map((anchorField) => (
                <option key={anchorField.name} value={anchorField.name}>
                  {anchorField.name} ({anchorField.type ?? "string"})
                </option>
              ))}
          </select>
        </label>

        <label>
          Delete policy
          <select
            value={semantics.onDelete ?? "restrict"}
            onChange={(event) =>
              applyReference({
                ...semantics,
                onDelete: event.target.value as NonNullable<typeof semantics.onDelete>
              })
            }
          >
            <option value="restrict">restrict</option>
            <option value="cascade">cascade</option>
            <option value="nullify">nullify</option>
          </select>
        </label>

        <label>
          Picker type
          <select
            value={field.ui?.pickerType ?? "dialog"}
            onChange={(event) =>
              onChange((fieldDraft) => ({
                ...fieldDraft,
                ui: {
                  ...fieldDraft.ui,
                  pickerType: event.target.value
                }
              }))
            }
          >
            {PICKER_TYPES.map((pickerType) => (
              <option key={pickerType} value={pickerType}>
                {pickerType}
              </option>
            ))}
          </select>
        </label>

        <label className="authoring-toggle-row">
          <input
            type="checkbox"
            checked={field.ui?.allowInlineCreate ?? semantics.inlineCreate === "allow"}
            onChange={(event) => {
              const checked = event.target.checked;
              onChange((fieldDraft) => ({
                ...fieldDraft,
                ui: {
                  ...fieldDraft.ui,
                  allowInlineCreate: checked
                },
                reference: {
                  ...(fieldDraft.reference ?? semantics),
                  target: (fieldDraft.reference ?? semantics).target,
                  inlineCreate: checked ? "allow" : "deny"
                }
              }));
            }}
          />
          Allow inline create
        </label>

        <label className="authoring-toggle-row">
          <input
            type="checkbox"
            checked={semantics.multiple ?? false}
            onChange={(event) =>
              applyReference({
                ...semantics,
                multiple: event.target.checked || undefined
              })
            }
          />
          Multiple
        </label>
      </div>

      <div className="authoring-inline-actions">
        {DISPLAY_PRESETS.map((preset) => (
          <button
            key={preset.id}
            type="button"
            className="authoring-secondary-inline"
            disabled={!semantics.displayField}
            onClick={() =>
              applyReference({
                ...semantics,
                displayTemplate: preset.buildTemplate(semantics.displayField ?? "id")
              })
            }
          >
            {preset.label}
          </button>
        ))}
      </div>

      <label className="authoring-form-grid__full">
        Display template
        <input
          value={semantics.displayTemplate ?? ""}
          onChange={(event) =>
            applyReference({
              ...semantics,
              displayTemplate: event.target.value || undefined
            })
          }
        />
      </label>

      <label className="authoring-form-grid__full">
        Preview card template
        <input
          value={semantics.previewCardTemplate ?? ""}
          onChange={(event) =>
            applyReference({
              ...semantics,
              previewCardTemplate: event.target.value || undefined
            })
          }
        />
      </label>

      <div className="authoring-form-grid">
        <label>
          Search fields
          <input
            value={joinTextList(semantics.searchFields)}
            onChange={(event) =>
              applyReference({
                ...semantics,
                searchFields: parseTextList(event.target.value)
              })
            }
          />
        </label>

        <label>
          Picker columns
          <input
            value={joinTextList(semantics.pickerColumns)}
            onChange={(event) =>
              applyReference({
                ...semantics,
                pickerColumns: parseTextList(event.target.value)
              })
            }
          />
        </label>

        <label>
          Preview fields
          <input
            value={joinTextList(semantics.previewFields)}
            onChange={(event) =>
              applyReference({
                ...semantics,
                previewFields: parseTextList(event.target.value)
              })
            }
          />
        </label>

        <label>
          Default filter
          <input
            value={semantics.defaultFilter ?? ""}
            onChange={(event) =>
              applyReference({
                ...semantics,
                defaultFilter: event.target.value || undefined
              })
            }
          />
        </label>
      </div>

      {targetFields.length > 0 ? (
        <div className="authoring-chip-row">
          {targetFields.map((fieldName) => (
            <button
              key={fieldName}
              type="button"
              className="authoring-help-term"
              onClick={() =>
                applyReference({
                  ...semantics,
                  pickerColumns: Array.from(new Set([...(semantics.pickerColumns ?? []), fieldName]))
                })
              }
            >
              Add {fieldName} column
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
