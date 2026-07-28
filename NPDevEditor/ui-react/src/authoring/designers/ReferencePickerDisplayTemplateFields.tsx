import React from "react";
import type { AuthoringField } from "../editors/modelDocumentTypes";

const DISPLAY_PRESETS = [
  { id: "id-only", label: "ID only", buildTemplate: (displayField: string) => `{${displayField}}` },
  { id: "label-id", label: "Label + ID", buildTemplate: (displayField: string) => `{${displayField}} ({id})` },
  { id: "label-secondary", label: "Primary + secondary", buildTemplate: (displayField: string) => `{${displayField}} - {status}` }
];

type ReferencePickerDisplayTemplateFieldsProps = {
  semantics: NonNullable<AuthoringField["reference"]>;
  applyReference: (updater: NonNullable<AuthoringField["reference"]>) => void;
};

export default function ReferencePickerDisplayTemplateFields({
  semantics,
  applyReference
}: ReferencePickerDisplayTemplateFieldsProps): JSX.Element {
  return (
    <>
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
    </>
  );
}
