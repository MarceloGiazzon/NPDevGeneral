import React, { useEffect, useState } from "react";
import type { AuthoringEntity, AuthoringField } from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";
import ReferencePickerDesigner from "../../designers/ReferencePickerDesigner";

type ReferencesEditorSectionProps = {
  entity: AuthoringEntity | null;
  allEntities: AuthoringEntity[];
  requestedFieldName?: string | null;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
};

export default function ReferencesEditorSection({
  entity,
  allEntities,
  requestedFieldName,
  onUpdateField
}: ReferencesEditorSectionProps): JSX.Element | null {
  const referenceFields = (entity?.fields ?? []).filter((field) => field.type === "reference");
  const [selectedFieldName, setSelectedFieldName] = useState<string | null>(referenceFields[0]?.name ?? null);

  useEffect(() => {
    setSelectedFieldName(referenceFields[0]?.name ?? null);
  }, [entity?.name]);

  useEffect(() => {
    if (requestedFieldName && referenceFields.some((field) => field.name === requestedFieldName)) {
      setSelectedFieldName(requestedFieldName);
    }
  }, [referenceFields, requestedFieldName]);

  if (!entity || referenceFields.length === 0) {
    return null;
  }

  const selectedField = referenceFields.find((field) => field.name === selectedFieldName) ?? referenceFields[0];
  const semantics = selectedField.reference ?? { target: "" };

  return (
    <section id="authoring-section-references" className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Reference editor</h3>
          <p>Guide picker, preview, display-template, and inline-create behavior for relationship fields.</p>
        </div>
      </div>

      <div className="authoring-editor-inline-actions">
        {referenceFields.map((field) => (
          <button
            key={field.name}
            type="button"
            className={`authoring-secondary-inline ${field.name === selectedField.name ? "is-selected" : ""}`}
            onClick={() => setSelectedFieldName(field.name)}
          >
            {field.name}
          </button>
        ))}
      </div>

      <ReferencePickerDesigner
        field={selectedField}
        entities={allEntities}
        onChange={(updater) => onUpdateField(selectedField.name, updater)}
      />

      <div className="authoring-form-grid">
        <label>
          Target concept
          <input
            value={semantics.target}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                reference: {
                  ...field.reference,
                  target: event.target.value
                }
              }))
            }
          />
        </label>

        <label>
          Display field
          <input
            value={semantics.displayField ?? ""}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                reference: {
                  ...field.reference,
                  target: field.reference?.target ?? "",
                  displayField: event.target.value
                }
              }))
            }
          />
        </label>

        <label>
          Default filter
          <input
            value={semantics.defaultFilter ?? ""}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                reference: {
                  ...field.reference,
                  target: field.reference?.target ?? "",
                  defaultFilter: event.target.value
                }
              }))
            }
          />
        </label>

        <label>
          Inline create
          <select
            value={semantics.inlineCreate ?? "deny"}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                reference: {
                  ...field.reference,
                  target: field.reference?.target ?? "",
                  inlineCreate: event.target.value as "allow" | "deny"
                }
              }))
            }
          >
            <option value="allow">allow</option>
            <option value="deny">deny</option>
          </select>
        </label>
      </div>

      <label className="authoring-form-grid__full">
        Display template
        <input
          value={semantics.displayTemplate ?? ""}
          onChange={(event) =>
            onUpdateField(selectedField.name, (field) => ({
              ...field,
              reference: {
                ...field.reference,
                target: field.reference?.target ?? "",
                displayTemplate: event.target.value
              }
            }))
          }
        />
      </label>

      <label className="authoring-form-grid__full">
        Preview card template
        <input
          value={semantics.previewCardTemplate ?? ""}
          onChange={(event) =>
            onUpdateField(selectedField.name, (field) => ({
              ...field,
              reference: {
                ...field.reference,
                target: field.reference?.target ?? "",
                previewCardTemplate: event.target.value
              }
            }))
          }
        />
      </label>

      <div className="authoring-form-grid">
        <label>
          Search fields
          <input
            value={joinTextList(semantics.searchFields)}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                reference: {
                  ...field.reference,
                  target: field.reference?.target ?? "",
                  searchFields: parseTextList(event.target.value)
                }
              }))
            }
          />
        </label>

        <label>
          Picker columns
          <input
            value={joinTextList(semantics.pickerColumns)}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                reference: {
                  ...field.reference,
                  target: field.reference?.target ?? "",
                  pickerColumns: parseTextList(event.target.value)
                }
              }))
            }
          />
        </label>

        <label>
          Preview fields
          <input
            value={joinTextList(semantics.previewFields)}
            onChange={(event) =>
              onUpdateField(selectedField.name, (field) => ({
                ...field,
                reference: {
                  ...field.reference,
                  target: field.reference?.target ?? "",
                  previewFields: parseTextList(event.target.value)
                }
              }))
            }
          />
        </label>
      </div>
    </section>
  );
}
