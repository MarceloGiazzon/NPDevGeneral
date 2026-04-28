import { useEffect, useMemo, useState } from "react";
import { npdevClient } from "./api/npdevClient";
import type { ModelEditorDraft, ModelEditorEntityDraft, ModelEditorFieldDraft } from "./types";

function normalize(value: unknown): string {
  return value == null ? "" : String(value).trim();
}

function humanize(value: string): string {
  const normalized = normalize(value);
  if (!normalized) {
    return "";
  }
  return normalized
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/^\w/, (char) => char.toUpperCase());
}

function emptyDraft(): ModelEditorDraft {
  return {
    namespace: "com.npdev.visual",
    dslVersion: "1.0",
    version: "editor-draft",
    entities: []
  };
}

function emptyFieldDraft(): ModelEditorFieldDraft {
  return {
    name: "newField",
    type: "string",
    ref: null,
    enumValues: [],
    ui: { label: "New Field", widget: "text", order: 100 }
  };
}

export default function ModelEditorPanel() {
  const [draft, setDraft] = useState<ModelEditorDraft>(emptyDraft());
  const [selectedConceptName, setSelectedConceptName] = useState<string>("");
  const [statusMessage, setStatusMessage] = useState<string>("");
  const [statusIsError, setStatusIsError] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(false);

  useEffect(() => {
    void loadDraft();
  }, []);

  const selectedConcept = useMemo<ModelEditorEntityDraft | null>(() => {
    return draft.entities.find((entity) => entity.name === selectedConceptName) ?? draft.entities[0] ?? null;
  }, [draft, selectedConceptName]);

  useEffect(() => {
    if (!selectedConceptName && draft.entities.length > 0) {
      setSelectedConceptName(draft.entities[0].name);
      return;
    }
    if (selectedConceptName && !draft.entities.some((entity) => entity.name === selectedConceptName)) {
      setSelectedConceptName(draft.entities[0]?.name ?? "");
    }
  }, [draft, selectedConceptName]);

  async function loadDraft() {
    setLoading(true);
    try {
      const nextDraft = await npdevClient.getModelEditorDraft();
      setDraft(nextDraft);
      setStatusMessage("Visual Model Editor draft loaded.");
      setStatusIsError(false);
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : "Failed to load model editor draft.");
      setStatusIsError(true);
    } finally {
      setLoading(false);
    }
  }

  function updateSelectedConcept(mutator: (concept: ModelEditorEntityDraft) => ModelEditorEntityDraft) {
    if (!selectedConcept) {
      return;
    }
    setDraft((current) => ({
      ...current,
      entities: current.entities.map((entity) => entity.name === selectedConcept.name ? mutator(entity) : entity)
    }));
  }

  function addConcept() {
    const nextName = `NewConcept${draft.entities.length + 1}`;
    const concept: ModelEditorEntityDraft = {
      name: nextName,
      fields: [
        {
          name: "id",
          type: "uuid",
          ui: { label: "Id", widget: "text", order: 0 }
        }
      ],
      lifecycle: { statusField: "status", transitions: [] }
    };
    setDraft((current) => ({ ...current, entities: [...current.entities, concept] }));
    setSelectedConceptName(nextName);
  }

  function addField() {
    updateSelectedConcept((concept) => ({ ...concept, fields: [...concept.fields, emptyFieldDraft()] }));
  }

  function updateField(index: number, patch: Partial<ModelEditorFieldDraft>) {
    updateSelectedConcept((concept) => ({
      ...concept,
      fields: concept.fields.map((field, fieldIndex) => fieldIndex === index ? { ...field, ...patch } : field)
    }));
  }

  async function saveDraft() {
    setLoading(true);
    try {
      const saved = await npdevClient.saveModelEditorDraft(draft);
      setDraft(saved);
      setStatusMessage("Visual Model Editor draft saved.");
      setStatusIsError(false);
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : "Failed to save model editor draft.");
      setStatusIsError(true);
    } finally {
      setLoading(false);
    }
  }

  async function resetDraft() {
    setLoading(true);
    try {
      await npdevClient.resetModelEditorDraft();
      await loadDraft();
      setStatusMessage("Visual Model Editor draft reset.");
      setStatusIsError(false);
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : "Failed to reset model editor draft.");
      setStatusIsError(true);
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="panel">
      <div className="section-header">
        <div>
          <h2>Visual Model Editor</h2>
          <div className="hint">Concept Palette, Field Palette, State Palette, and simple UI metadata editing backed by the runtime draft endpoint.</div>
        </div>
        <div className="button-row">
          <button type="button" onClick={() => void loadDraft()} disabled={loading}>Reload Draft</button>
          <button type="button" onClick={() => void saveDraft()} disabled={loading}>Save Draft</button>
          <button type="button" className="secondary-button" onClick={() => void resetDraft()} disabled={loading}>Reset Draft</button>
        </div>
      </div>

      {statusMessage ? <div className={statusIsError ? "status-box error" : "status-box"}>{statusMessage}</div> : null}

      <div className="metadata-summary">
        <div className="metadata-summary-card">
          <strong>Concept Palette</strong>
          <span>{draft.entities.length} concepts</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Field Palette</strong>
          <span>{selectedConcept?.fields.length ?? 0} fields in selected concept</span>
        </div>
        <div className="metadata-summary-card">
          <strong>State Palette</strong>
          <span>{selectedConcept?.lifecycle?.transitions?.length ?? 0} transitions</span>
        </div>
      </div>

      <div className="button-row">
        <button type="button" onClick={addConcept}>Add Concept</button>
        <button type="button" onClick={addField} disabled={!selectedConcept}>Add Field</button>
      </div>

      <div className="form-grid two-col">
        <label htmlFor="model-editor-concept">Selected concept</label>
        <select id="model-editor-concept" value={selectedConcept?.name ?? ""} onChange={(event) => setSelectedConceptName(event.target.value)}>
          <option value="">Select concept</option>
          {draft.entities.map((entity) => (
            <option key={entity.name} value={entity.name}>{humanize(entity.name)}</option>
          ))}
        </select>
      </div>

      {selectedConcept ? (
        <>
          <div className="subpanel">
            <h3>Simple UI Metadata</h3>
            <div className="hint">Edit labels, widgets, field order, refs, enums, and lifecycle scaffolding for the selected concept.</div>
          </div>

          <table className="grid-table compact">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Label</th>
                <th>Widget</th>
                <th>Ref</th>
                <th>Enum values</th>
                <th>Order</th>
              </tr>
            </thead>
            <tbody>
              {selectedConcept.fields.map((field, index) => (
                <tr key={`${field.name}-${index}`}>
                  <td><input value={field.name} onChange={(event) => updateField(index, { name: event.target.value })} /></td>
                  <td><input value={normalize(field.type)} onChange={(event) => updateField(index, { type: event.target.value })} /></td>
                  <td><input value={normalize(field.ui?.label)} onChange={(event) => updateField(index, { ui: { ...(field.ui ?? {}), label: event.target.value } })} /></td>
                  <td><input value={normalize(field.ui?.widget)} onChange={(event) => updateField(index, { ui: { ...(field.ui ?? {}), widget: event.target.value } })} /></td>
                  <td><input value={normalize(field.ref)} onChange={(event) => updateField(index, { ref: event.target.value || null })} placeholder="optional" /></td>
                  <td><input value={(field.enumValues ?? []).join(", ")} onChange={(event) => updateField(index, { enumValues: event.target.value.split(",").map((part) => part.trim()).filter(Boolean) })} placeholder="A, B, C" /></td>
                  <td><input value={normalize(field.ui?.order)} onChange={(event) => updateField(index, { ui: { ...(field.ui ?? {}), order: event.target.value ? Number(event.target.value) : undefined } })} /></td>
                </tr>
              ))}
            </tbody>
          </table>

          <pre className="json-pane small">{JSON.stringify(selectedConcept, null, 2)}</pre>
        </>
      ) : (
        <div className="hint">No concept selected yet.</div>
      )}
    </section>
  );
}
