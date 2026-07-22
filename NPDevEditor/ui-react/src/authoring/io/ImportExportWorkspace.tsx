import React, { useEffect, useMemo, useState } from "react";
import type { AuthoringConfigSession } from "../config/configDocumentTypes";
import type { AuthoringDocumentSession } from "../editors/modelDocumentTypes";
import { buildSemanticDiff } from "../diff/semanticDiff";
import { getBaselineBundle, listBaselineOptions } from "./baselineRegistry";
import { buildConfigValidationDiagnostics, buildModelValidationDiagnostics } from "../validation/authoringValidation";
import {
  buildRecommendedHandoffDir,
  summarizePipelinePreflight
} from "../pipeline/pipelineHandoff";
import {
  buildAuthoringBundle,
  buildImportedBundleSessions,
  downloadBundle,
  findSnapshotById,
  importBundleFromFiles,
  listSavedBundleSnapshots,
  saveBundleSnapshot,
  saveBundleToChosenDirectory
} from "./bundleIoService";
import SemanticDiffPanel from "./SemanticDiffPanel";
import PipelineHandoffSection from "./PipelineHandoffSection";

type ImportExportWorkspaceProps = {
  documentSession: AuthoringDocumentSession | null;
  configSession: AuthoringConfigSession | null;
  onReplaceDocumentSession: (session: AuthoringDocumentSession) => void;
  onReplaceConfigSession: (session: AuthoringConfigSession) => void;
};

export default function ImportExportWorkspace({
  documentSession,
  configSession,
  onReplaceDocumentSession,
  onReplaceConfigSession
}: ImportExportWorkspaceProps): JSX.Element {
  const [modelFile, setModelFile] = useState<File | null>(null);
  const [configFile, setConfigFile] = useState<File | null>(null);
  const [statusMessage, setStatusMessage] = useState<string>("Choose import, export, save, or compare actions.");
  const [selectedSnapshotId, setSelectedSnapshotId] = useState<string>("");
  const [selectedBaselineId, setSelectedBaselineId] = useState<string>("canonical-demo");
  const [refreshToken, setRefreshToken] = useState<number>(0);
  const [handoffDirHint, setHandoffDirHint] = useState<string>("NPDevSamples\\authoring-handoff");

  const currentBundle = useMemo(() => {
    if (!documentSession || !configSession) {
      return null;
    }
    return buildAuthoringBundle(documentSession, configSession);
  }, [configSession, documentSession]);

  const savedSnapshots = useMemo(() => listSavedBundleSnapshots(), [refreshToken]);
  const selectedSnapshot = selectedSnapshotId ? findSnapshotById(selectedSnapshotId) : null;
  const selectedBaseline = selectedBaselineId ? getBaselineBundle(selectedBaselineId) : null;
  const snapshotDiff = currentBundle && selectedSnapshot ? buildSemanticDiff(selectedSnapshot.bundle, currentBundle) : [];
  const baselineDiff = currentBundle && selectedBaseline ? buildSemanticDiff(selectedBaseline, currentBundle) : [];
  const modelDiagnostics = useMemo(
    () => (documentSession ? buildModelValidationDiagnostics(documentSession.document) : []),
    [documentSession]
  );
  const configDiagnostics = useMemo(
    () => (configSession ? buildConfigValidationDiagnostics(configSession.document) : []),
    [configSession]
  );
  const pipelineDiagnostics = [...modelDiagnostics, ...configDiagnostics];
  const pipelinePreflight = summarizePipelinePreflight(pipelineDiagnostics);
  useEffect(() => {
    if (currentBundle) {
      setHandoffDirHint(buildRecommendedHandoffDir(currentBundle));
    }
  }, [currentBundle]);

  if (!documentSession || !configSession || !currentBundle) {
    return (
      <div className="authoring-route-card">
        <div className="authoring-route-card__header">
          <div>
            <h3>Loading import/export workspace</h3>
            <p>The workspace is waiting for the model and config sessions before import/export can be used.</p>
          </div>
          <div className="authoring-badge">Preparing</div>
        </div>
      </div>
    );
  }

  const bundleLabel = documentSession.document.namespace.replace(/[^a-z0-9.-]+/gi, "-") || "npdev-bundle";

  return (
    <div className="authoring-editor">
      <section className="authoring-editor-hero">
        <div>
          <div className="authoring-breadcrumb">Import, export, and version-safe save</div>
          <h3>Bundle workspace</h3>
          <p>
            Import an existing `model.json` and `config.json`, export canonical files, save versioned snapshots, and
            compare current work against saved history or platform baselines.
          </p>
        </div>
        <div className="authoring-editor-hero__meta">
          <strong>{bundleLabel}</strong>
          <small>Model source: {documentSession.sourceLabel}</small>
          <small>Config source: {configSession.sourceLabel}</small>
          <small>{savedSnapshots.length} saved snapshots</small>
        </div>
      </section>

      <section className="authoring-editor-summary">
        <article className="authoring-summary-card">
          <strong>Model concepts</strong>
          <span>{documentSession.document.concepts.length}</span>
          <small>Current bundle content remains canonical NP input.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Saved versions</strong>
          <span>{savedSnapshots.length}</span>
          <small>Snapshots are stored locally for compare and restore workflows.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Status</strong>
          <span>{statusMessage}</span>
          <small>Import/export operations report their last outcome here.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Pipeline preflight</strong>
          <span>{pipelinePreflight.ready ? "Ready" : "Blocked"}</span>
          <small>
            {pipelinePreflight.errorCount} errors, {pipelinePreflight.warningCount} warnings
          </small>
        </article>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Import existing bundle</h3>
            <p>Bring an existing `model.json` and `config.json` pair into the current authoring session.</p>
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
          <label>
            config.json
            <input
              type="file"
              accept=".json,application/json"
              onChange={(event) => setConfigFile(event.target.files?.[0] ?? null)}
            />
          </label>
        </div>
        <div className="authoring-inline-actions">
          <button
            type="button"
            onClick={async () => {
              const imported = await importBundleFromFiles(modelFile, configFile);
              if (!imported.ok) {
                setStatusMessage(imported.message);
                return;
              }
              const sessions = buildImportedBundleSessions(imported.bundle, `${imported.modelFileName} + ${imported.configFileName}`);
              onReplaceDocumentSession(sessions.modelSession);
              onReplaceConfigSession(sessions.configSession);
              setStatusMessage(`Imported ${imported.modelFileName} and ${imported.configFileName}.`);
            }}
          >
            Import bundle
          </button>
        </div>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Export and save</h3>
            <p>Export canonical files, save a versioned snapshot, or write the pair into a chosen local folder when supported.</p>
          </div>
        </div>
        <div className="authoring-inline-actions">
          <button
            type="button"
            onClick={() => {
              downloadBundle(currentBundle, bundleLabel);
              setStatusMessage("Downloaded canonical model/config files and bundle manifest.");
            }}
          >
            Export canonical JSON
          </button>
          <button
            type="button"
            className="authoring-secondary-inline"
            onClick={() => {
              const snapshot = saveBundleSnapshot(currentBundle, bundleLabel, documentSession.sourceKey);
              setRefreshToken((value) => value + 1);
              setSelectedSnapshotId(snapshot.id);
              setStatusMessage(`Saved version snapshot ${snapshot.savedAt}.`);
            }}
          >
            Save versioned snapshot
          </button>
          <button
            type="button"
            className="authoring-ghost-button"
            onClick={async () => {
              const result = await saveBundleToChosenDirectory(currentBundle, bundleLabel);
              setStatusMessage(
                result === "saved"
                  ? "Saved model/config pair to the chosen local directory."
                  : "Chosen-directory save is not supported here. Use export or snapshot save instead."
              );
            }}
          >
            Save to chosen folder
          </button>
        </div>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Compare against previous saved version</h3>
            <p>Compare the current bundle against a locally saved snapshot to review semantic changes before export.</p>
          </div>
        </div>
        <label>
          Saved snapshot
          <select value={selectedSnapshotId} onChange={(event) => setSelectedSnapshotId(event.target.value)}>
            <option value="">Choose a saved snapshot</option>
            {savedSnapshots.map((snapshot) => (
              <option key={snapshot.id} value={snapshot.id}>
                {snapshot.label} @ {snapshot.savedAt}
              </option>
            ))}
          </select>
        </label>
        <SemanticDiffPanel title="Saved version diff" summaries={snapshotDiff} emptyMessage="Choose a saved snapshot to compare current work against its previous version." />
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Compare against baseline</h3>
            <p>Review semantic differences against the canonical demo or an official sample baseline.</p>
          </div>
        </div>
        <label>
          Baseline
          <select value={selectedBaselineId} onChange={(event) => setSelectedBaselineId(event.target.value)}>
            {listBaselineOptions().map((option) => (
              <option key={option.id} value={option.id}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <SemanticDiffPanel title="Baseline diff" summaries={baselineDiff} emptyMessage="Choose a baseline to compare semantic changes." />
      </section>

      <PipelineHandoffSection
        currentBundle={currentBundle}
        bundleLabel={bundleLabel}
        handoffDirHint={handoffDirHint}
        pipelineDiagnostics={pipelineDiagnostics}
        pipelinePreflight={pipelinePreflight}
        onChangeHandoffDirHint={setHandoffDirHint}
        onStatusMessage={setStatusMessage}
      />
    </div>
  );
}
