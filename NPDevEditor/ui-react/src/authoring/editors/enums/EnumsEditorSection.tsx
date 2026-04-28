import React, { useEffect, useState } from "react";
import type { AuthoringEntity, AuthoringEnumOption, AuthoringField } from "../modelDocumentTypes";
import { enumOptionFromValue } from "../editorUtils";
import EnumConfigurationDesigner from "../../designers/EnumConfigurationDesigner";

type EnumsEditorSectionProps = {
  entity: AuthoringEntity | null;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
};

export default function EnumsEditorSection({
  entity,
  onUpdateField
}: EnumsEditorSectionProps): JSX.Element | null {
  const enumFields = (entity?.fields ?? []).filter((field) => field.type === "enum");
  const [selectedFieldName, setSelectedFieldName] = useState<string | null>(enumFields[0]?.name ?? null);

  useEffect(() => {
    setSelectedFieldName(enumFields[0]?.name ?? null);
  }, [entity?.name]);

  if (!entity || enumFields.length === 0) {
    return null;
  }

  const selectedField = enumFields.find((field) => field.name === selectedFieldName) ?? enumFields[0];
  const options = (selectedField.enumValues ?? []).map(enumOptionFromValue);

  const updateOptions = (nextOptions: AuthoringEnumOption[]): void => {
    onUpdateField(selectedField.name, (field) => ({
      ...field,
      enumValues: nextOptions
    }));
  };

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Enum editor</h3>
          <p>Manage enriched enum options, ordering, badges, and icon hints.</p>
        </div>
      </div>

      <div className="authoring-editor-inline-actions">
        {enumFields.map((field) => (
          <button
            key={field.name}
            type="button"
            className={`authoring-secondary-inline ${field.name === selectedField.name ? "is-selected" : ""}`}
            onClick={() => setSelectedFieldName(field.name)}
          >
            {field.name}
          </button>
        ))}
        <button
          type="button"
          onClick={() =>
            updateOptions([
              ...options,
              {
                value: `Option${options.length + 1}`,
                label: `Option ${options.length + 1}`,
                order: (options.length + 1) * 10
              }
            ])
          }
        >
          Add option
        </button>
      </div>

      <EnumConfigurationDesigner options={options} onChange={updateOptions} />
    </section>
  );
}
