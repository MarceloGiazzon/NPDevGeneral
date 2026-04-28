import React from "react";
import type { JsonValidationIssue } from "./jsonEditorTypes";

type RawJsonEditorPanelProps = {
  title: string;
  description: string;
  draftText: string;
  issues: JsonValidationIssue[];
  hasPendingRawChanges: boolean;
  hasExternalConflict: boolean;
  onDraftChange: (value: string) => void;
  onReloadFromForms: () => void;
  onReturnToForms: () => void;
};

export default function RawJsonEditorPanel({
  title,
  description,
  draftText,
  issues,
  hasPendingRawChanges,
  hasExternalConflict,
  onDraftChange,
  onReloadFromForms,
  onReturnToForms
}: RawJsonEditorPanelProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>{title}</h3>
          <p>{description}</p>
        </div>
        <div className="authoring-inline-actions">
          <button type="button" className="authoring-secondary-inline" onClick={onReturnToForms}>
            Return to forms
          </button>
          <button type="button" className="authoring-ghost-button" onClick={onReloadFromForms}>
            Reload from forms
          </button>
        </div>
      </div>

      <div className="authoring-editor-summary">
        <article className="authoring-summary-card">
          <strong>Raw mode</strong>
          <span>Power feature</span>
          <small>Advanced editing stays available without becoming the default authoring path.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Pending raw changes</strong>
          <span>{hasPendingRawChanges ? "Yes" : "No"}</span>
          <small>Valid JSON applies back into the form editor automatically.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Issues</strong>
          <span>{issues.length}</span>
          <small>Path-aware overlays show parse or validation problems on canonical JSON paths.</small>
        </article>
      </div>

      {hasExternalConflict ? (
        <div className="authoring-conflict-banner">
          <strong>Raw draft is stale.</strong>
          <p>
            The form-backed document changed while the raw draft was diverged. Reload from forms to resync, or finish
            fixing the raw JSON and let it apply back into the canonical document.
          </p>
        </div>
      ) : null}

      <label className="authoring-form-grid__full">
        Raw JSON text
        <textarea
          className="authoring-json-textarea"
          rows={28}
          value={draftText}
          onChange={(event) => onDraftChange(event.target.value)}
        />
      </label>

      <div className="authoring-editor-stack">
        {issues.length === 0 ? (
          <article className="authoring-validation-card">
            <strong>$</strong>
            <span>OK</span>
            <p>The raw JSON is valid and synchronized with the form-backed document.</p>
          </article>
        ) : (
          issues.map((issue, index) => (
            <article
              key={`${issue.path}-${index}`}
              className={`authoring-validation-card ${issue.severity === "error" ? "is-error" : "is-warning"}`}
            >
              <strong>{issue.path}</strong>
              <span>{issue.severity.toUpperCase()}</span>
              <p>{issue.message}</p>
            </article>
          ))
        )}
      </div>
    </section>
  );
}
