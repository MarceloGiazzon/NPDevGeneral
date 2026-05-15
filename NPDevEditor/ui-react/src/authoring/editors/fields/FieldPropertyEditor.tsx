import type { AuthoringSchemaProperty } from "../modelDocumentTypes";
import { buildEmptyProperty } from "../editorUtils";

type FieldPropertyEditorProps = {
  title: string;
  properties: Record<string, AuthoringSchemaProperty>;
  onChange: (properties: Record<string, AuthoringSchemaProperty>) => void;
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

export default function FieldPropertyEditor({
  title,
  properties,
  onChange
}: FieldPropertyEditorProps): JSX.Element {
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
            onClick={() => onChange(Object.fromEntries(Object.entries(properties).filter(([name]) => name !== propertyName)))}
          >
            Remove
          </button>
        </div>
      ))}
    </div>
  );
}
