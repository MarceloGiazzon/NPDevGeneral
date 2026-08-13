import React, { useState } from "react";
import type { AuthoringDocumentSession } from "../editors/modelDocumentTypes";
import { buildImportedModelSession, importModelFromFile } from "./bundleIoService";

type ModelOnlyImportSectionProps = {
  onImportModel: (session: AuthoringDocumentSession) => void;
  onStatusMessage: (message: string) => void;
};

/**
 * R6 (MASTER-ROADMAP.md): a bundle import requires a model.json AND a config.json pair.
 * A real app definition (e.g. an AppGen app under AppGen/apps) is often just model.json on its
 * own -- this opens one directly, leaving the current config session untouched, instead of
 * forcing a bundle import that fails for lack of a config file the model was never shipped with.
 */
export default function ModelOnlyImportSection({
  onImportModel,
  onStatusMessage
}: ModelOnlyImportSectionProps): JSX.Element {
  const [modelFile, setModelFile] = useState<File | null>(null);

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Open a model.json directly</h3>
          <p>Open a standalone model.json (no config.json required) -- for example a real AppGen app definition.</p>
        </div>
      </div>
      <div className="authoring-form-grid">
        <label>
          model.json
          <input
            type="file"
            accept=".json,application/json"
            onChange={(event) => setModelFile(event.target.files?.[0] ?? null)}
          />
        </label>
      </div>
      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={async () => {
            const imported = await importModelFromFile(modelFile);
            if (!imported.ok) {
              onStatusMessage(imported.message);
              return;
            }
            const session = buildImportedModelSession(imported.document, imported.modelFileName);
            onImportModel(session);
            onStatusMessage(`Opened ${imported.modelFileName} (model only -- config session unchanged).`);
          }}
        >
          Open model only
        </button>
      </div>
    </section>
  );
}
