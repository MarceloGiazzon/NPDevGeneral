import React from "react";
import type { AuthoringDocumentSession } from "../editors/modelDocumentTypes";
import { buildImportedModelSession, importModelFromDirectory } from "./bundleIoService";

type FileSystemWindow = Window & {
  showDirectoryPicker?: () => Promise<FileSystemDirectoryHandle>;
};

type ModelFolderImportSectionProps = {
  onImportModel: (session: AuthoringDocumentSession) => void;
  onStatusMessage: (message: string) => void;
};

/**
 * Companion to ModelOnlyImportSection: a real AppGen app definition often splits its model.json
 * across sibling `$ref`-linked files (concepts/<Name>.json, packs/<name>/pack.json, ...) --
 * WmsOffice is a live example. A plain <input type=file> only ever sees the one picked file, so
 * it cannot follow those refs; this opens the whole app-definition FOLDER instead (File System
 * Access API), letting importModelFromDirectory resolve every linked file the same way
 * `npdev inspect bonds` / the DSL parser already do server-side.
 */
export default function ModelFolderImportSection({
  onImportModel,
  onStatusMessage
}: ModelFolderImportSectionProps): JSX.Element {
  const fsWindow = window as FileSystemWindow;
  const supported = typeof fsWindow.showDirectoryPicker === "function";

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Open a model folder</h3>
          <p>
            Open the folder holding a model.json that splits concepts, flows, or packs across linked files -- for
            example a real AppGen app definition's `definition` folder.
          </p>
        </div>
      </div>
      <div className="authoring-inline-actions">
        <button
          type="button"
          disabled={!supported}
          onClick={async () => {
            if (!fsWindow.showDirectoryPicker) {
              return;
            }
            let directoryHandle: FileSystemDirectoryHandle;
            try {
              directoryHandle = await fsWindow.showDirectoryPicker();
            } catch {
              return; // user cancelled the picker
            }
            const imported = await importModelFromDirectory(directoryHandle);
            if (!imported.ok) {
              onStatusMessage(imported.message);
              return;
            }
            const session = buildImportedModelSession(imported.document, imported.modelFileName);
            onImportModel(session);
            onStatusMessage(`Opened ${imported.modelFileName} (resolved linked files; config session unchanged).`);
          }}
        >
          Open a model folder
        </button>
      </div>
      {!supported ? <p>This browser does not support opening a folder. Use "Open a model.json directly" above for a single, unsplit file.</p> : null}
    </section>
  );
}
