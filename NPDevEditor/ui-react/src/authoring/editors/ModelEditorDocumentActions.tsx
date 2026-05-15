import type { JsonEditorMode } from "../json/jsonEditorTypes";

type ModelEditorDocumentActionsProps = {
  mode: JsonEditorMode;
  onExport: () => void;
  onSetMode: (mode: JsonEditorMode) => void;
};

export default function ModelEditorDocumentActions({
  mode,
  onExport,
  onSetMode
}: ModelEditorDocumentActionsProps): JSX.Element {
  return (
    <div className="authoring-inline-actions">
      <button type="button" onClick={onExport}>
        Export current model.json
      </button>
      <button
        type="button"
        className={`authoring-secondary-inline ${mode === "form" ? "is-selected" : ""}`}
        onClick={() => onSetMode("form")}
      >
        Guided form mode
      </button>
      <button
        type="button"
        className={`authoring-secondary-inline ${mode === "json" ? "is-selected" : ""}`}
        onClick={() => onSetMode("json")}
      >
        Raw JSON mode
      </button>
    </div>
  );
}
