import type { AuthoringField } from "../editors/modelDocumentTypes";

const WIDTH_OPTIONS = ["compact", "normal", "wide", "full"];

type LayoutFieldCardProps = {
  field: AuthoringField;
  knownTabs: string[];
  knownSections: string[];
  onUpdateField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
};

export default function LayoutFieldCard({
  field,
  knownTabs,
  knownSections,
  onUpdateField
}: LayoutFieldCardProps): JSX.Element {
  const updateUi = (patch: NonNullable<AuthoringField["ui"]>): void => {
    onUpdateField(field.name, (fieldDraft) => ({
      ...fieldDraft,
      ui: {
        ...fieldDraft.ui,
        ...patch
      }
    }));
  };

  return (
    <article className="authoring-designer-card">
      <div className="authoring-preview-card__header">
        <strong>{field.ui?.label ?? field.name}</strong>
        <span>{field.name}</span>
      </div>

      <div className="authoring-form-grid">
        <label>
          Label
          <input value={field.ui?.label ?? ""} onChange={(event) => updateUi({ label: event.target.value })} />
        </label>
        <label>
          Tab
          <input
            list={`${field.name}-tabs`}
            value={field.ui?.tab ?? ""}
            onChange={(event) => updateUi({ tab: event.target.value || undefined })}
          />
          <datalist id={`${field.name}-tabs`}>
            {knownTabs.map((tab) => (
              <option key={tab} value={tab} />
            ))}
          </datalist>
        </label>
        <label>
          Section
          <input
            list={`${field.name}-sections`}
            value={field.ui?.section ?? ""}
            onChange={(event) => updateUi({ section: event.target.value || undefined })}
          />
          <datalist id={`${field.name}-sections`}>
            {knownSections.map((section) => (
              <option key={section} value={section} />
            ))}
          </datalist>
        </label>
        <label>
          Column
          <input type="number" value={field.ui?.column ?? 1} onChange={(event) => updateUi({ column: Number(event.target.value) })} />
        </label>
        <label>
          Column span
          <input type="number" value={field.ui?.columnSpan ?? 1} onChange={(event) => updateUi({ columnSpan: Number(event.target.value) })} />
        </label>
        <label>
          Width
          <select value={field.ui?.width ?? "normal"} onChange={(event) => updateUi({ width: event.target.value })}>
            {WIDTH_OPTIONS.map((width) => (
              <option key={width} value={width}>
                {width}
              </option>
            ))}
          </select>
        </label>
        <label>
          Form order
          <input type="number" value={field.ui?.order ?? 0} onChange={(event) => updateUi({ order: Number(event.target.value) })} />
        </label>
        <label>
          List order
          <input type="number" value={field.ui?.listColumnOrder ?? 0} onChange={(event) => updateUi({ listColumnOrder: Number(event.target.value) })} />
        </label>
      </div>

      <div className="authoring-toggle-row">
        <label>
          <input type="checkbox" checked={Boolean(field.ui?.summaryCard)} onChange={(event) => updateUi({ summaryCard: event.target.checked })} />
          Summary card
        </label>
        <label>
          <input type="checkbox" checked={Boolean(field.ui?.listColumn)} onChange={(event) => updateUi({ listColumn: event.target.checked })} />
          Show in list/table
        </label>
      </div>
    </article>
  );
}
