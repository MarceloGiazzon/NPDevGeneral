import React from "react";
import type { AuthoringConfigValidationIssue } from "../../config/configDocumentTypes";

type ConfigValidationPanelProps = {
  issues: AuthoringConfigValidationIssue[];
};

export default function ConfigValidationPanel({
  issues
}: ConfigValidationPanelProps): JSX.Element {
  const errorCount = issues.filter((issue) => issue.severity === "error").length;
  const warningCount = issues.filter((issue) => issue.severity === "warning").length;

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Config validation</h3>
          <p>Lightweight client-side checks keep the config export canonical and catch common mistakes early.</p>
        </div>
        <div className="authoring-badge">{errorCount} errors / {warningCount} warnings</div>
      </div>

      {issues.length === 0 ? (
        <div className="authoring-subcard">
          <strong>No validation issues</strong>
          <p className="hint">This config is structurally healthy according to the guided editor checks.</p>
        </div>
      ) : (
        <div className="authoring-editor-stack">
          {issues.map((issue, index) => (
            <article
              key={`${issue.path}-${index}`}
              className={`authoring-validation-card ${issue.severity === "error" ? "is-error" : "is-warning"}`}
            >
              <strong>{issue.path}</strong>
              <span>{issue.severity.toUpperCase()}</span>
              <p>{issue.message}</p>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
