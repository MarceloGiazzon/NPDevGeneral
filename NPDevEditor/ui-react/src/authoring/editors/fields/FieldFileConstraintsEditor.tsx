import type { AuthoringField } from "../modelDocumentTypes";

type FieldFileConstraintsEditorProps = {
  selectedField: AuthoringField;
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
};

export default function FieldFileConstraintsEditor({
  selectedField,
  onUpdateField
}: FieldFileConstraintsEditorProps): JSX.Element {
  return (
    <div className="authoring-form-grid">
      <label>
        Allowed content types (comma-separated)
        <input
          value={(selectedField.file?.contentTypes ?? []).join(", ")}
          placeholder="application/pdf, image/png"
          onChange={(event) =>
            onUpdateField(selectedField.name, (field) => ({
              ...field,
              file: {
                ...field.file,
                contentTypes: event.target.value
                  .split(",")
                  .map((value) => value.trim())
                  .filter(Boolean)
              }
            }))
          }
        />
      </label>
      <label>
        Max size (bytes)
        <input
          type="number"
          min={1}
          value={selectedField.file?.maxSizeBytes ?? ""}
          onChange={(event) =>
            onUpdateField(selectedField.name, (field) => ({
              ...field,
              file: {
                ...field.file,
                maxSizeBytes: event.target.value ? Number(event.target.value) : undefined
              }
            }))
          }
        />
      </label>
      <label>
        <input
          type="checkbox"
          checked={Boolean(selectedField.file?.multiple)}
          onChange={(event) =>
            onUpdateField(selectedField.name, (field) => ({
              ...field,
              file: { ...field.file, multiple: event.target.checked }
            }))
          }
        />
        Multiple files
      </label>
    </div>
  );
}
