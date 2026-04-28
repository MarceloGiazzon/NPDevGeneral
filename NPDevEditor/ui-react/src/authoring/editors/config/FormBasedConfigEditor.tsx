import React, { useMemo } from "react";
import type { AuthoringWorkspaceSeed } from "../../services/modelLoader";
import type { AuthoringConfigDocument, AuthoringConfigSession } from "../../config/configDocumentTypes";
import { downloadConfigDocument } from "../../config/configDocumentService";
import { validateConfigDocument } from "../../config/configValidation";
import type { JsonValidationIssue } from "../../json/jsonEditorTypes";
import { useSynchronizedJsonEditor } from "../../json/useSynchronizedJsonEditor";
import RawJsonEditorPanel from "../../json/RawJsonEditorPanel";
import ScenarioConfigSection from "./ScenarioConfigSection";
import ProjectionConfigSection from "./ProjectionConfigSection";
import DatabaseRuntimeConfigSection from "./DatabaseRuntimeConfigSection";
import MetadataConfigSection from "./MetadataConfigSection";
import ConfigValidationPanel from "./ConfigValidationPanel";
import InlineValidationSummary from "../../validation/InlineValidationSummary";
import { buildConfigValidationDiagnostics } from "../../validation/authoringValidation";

type FormBasedConfigEditorProps = {
  workspace: AuthoringWorkspaceSeed;
  configSession: AuthoringConfigSession | null;
  onUpdateConfig: (document: AuthoringConfigDocument) => void;
};

export default function FormBasedConfigEditor({
  workspace,
  configSession,
  onUpdateConfig
}: FormBasedConfigEditorProps): JSX.Element {
  if (!configSession) {
    return (
      <div className="authoring-route-card">
        <div className="authoring-route-card__header">
          <div>
            <h3>Loading config editor</h3>
            <p>The authoring workspace is preparing a guided config session for the selected source.</p>
          </div>
          <div className="authoring-badge">Preparing</div>
        </div>
      </div>
    );
  }

  const document = configSession.document;
  const issues = useMemo<JsonValidationIssue[]>(() => validateConfigDocument(document), [document]);
  const inlineDiagnostics = useMemo(() => buildConfigValidationDiagnostics(document), [document]);
  const jsonEditor = useSynchronizedJsonEditor<AuthoringConfigDocument>({
    document,
    onApplyDocument: onUpdateConfig,
    validateDocument: validateConfigDocument
  });

  return (
    <div className="authoring-editor">
      <section className="authoring-editor-hero">
        <div>
          <div className="authoring-breadcrumb">Guided config workspace</div>
          <h3>Form-based config editor</h3>
          <p>
            Runtime, generator, projection, and environment settings are now editable through structured panels instead
            of raw config JSON.
          </p>
        </div>

        <div className="authoring-editor-hero__meta">
          <strong>{configSession.sourceLabel}</strong>
          <small>Workspace mode: {workspace.modelSource}</small>
          <small>Loaded: {configSession.lastLoadedLabel}</small>
          <small>{configSession.dirty ? "Unsaved config changes" : "Freshly loaded config session"}</small>
        </div>
      </section>

      <section className="authoring-editor-summary">
        <article className="authoring-summary-card">
          <strong>Scenario</strong>
          <span>{document.scenario.name}</span>
          <small>Scenario identity and output roots stay explicit and exportable.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Runtime</strong>
          <span>{document.runtime.serverPort}</span>
          <small>Server port, profile, and boot task are visible without scanning JSON.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Validation issues</strong>
          <span>{issues.length}</span>
          <small>Guided validation keeps the schema-backed config healthy during editing.</small>
        </article>
      </section>

      <InlineValidationSummary title="Config validation in context" diagnostics={inlineDiagnostics} />

      <div className="authoring-inline-actions">
        <button type="button" onClick={() => downloadConfigDocument(document)}>
          Export current config.json
        </button>
        <button
          type="button"
          className={`authoring-secondary-inline ${jsonEditor.mode === "form" ? "is-selected" : ""}`}
          onClick={() => jsonEditor.setMode("form")}
        >
          Guided form mode
        </button>
        <button
          type="button"
          className={`authoring-secondary-inline ${jsonEditor.mode === "json" ? "is-selected" : ""}`}
          onClick={() => jsonEditor.setMode("json")}
        >
          Raw JSON mode
        </button>
      </div>

      {jsonEditor.mode === "json" ? (
        <RawJsonEditorPanel
          title="Raw config.json mode"
          description="Advanced users can edit the canonical config artifact directly. Valid JSON applies back into the structured config editor automatically, while invalid JSON stays safely in the raw draft until fixed."
          draftText={jsonEditor.draftText}
          issues={jsonEditor.issues}
          hasPendingRawChanges={jsonEditor.hasPendingRawChanges}
          hasExternalConflict={jsonEditor.hasExternalConflict}
          onDraftChange={jsonEditor.onDraftChange}
          onReloadFromForms={jsonEditor.reloadFromForms}
          onReturnToForms={() => jsonEditor.setMode("form")}
        />
      ) : (
        <>
          <ScenarioConfigSection document={document} onChange={onUpdateConfig} />
          <ProjectionConfigSection document={document} onChange={onUpdateConfig} />
          <DatabaseRuntimeConfigSection document={document} onChange={onUpdateConfig} />
          <MetadataConfigSection document={document} onChange={onUpdateConfig} />
          <ConfigValidationPanel issues={issues} />

          <section id="authoring-config-json-preview" className="authoring-editor-section">
            <div className="authoring-editor-section__header">
              <div>
                <h3>Config JSON preview</h3>
                <p>The guided editor stays primary, but the synchronized export payload remains visible for verification.</p>
              </div>
            </div>
            <pre className="json-pane">{JSON.stringify(document, null, 2)}</pre>
          </section>
        </>
      )}
    </div>
  );
}
