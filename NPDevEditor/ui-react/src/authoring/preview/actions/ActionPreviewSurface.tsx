import React from "react";
import type { PreviewManifest } from "../previewManifest";

type ActionPreviewSurfaceProps = {
  manifest: PreviewManifest;
};

export default function ActionPreviewSurface({
  manifest
}: ActionPreviewSurfaceProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Action preview</h3>
          <p>Preview flow, transition, and orchestration actions with their operator-facing metadata.</p>
        </div>
      </div>

      <div className="authoring-preview-grid">
        {manifest.actions.length === 0 ? (
          <article className="authoring-validation-card">
            <strong>{manifest.entity.name}</strong>
            <span>INFO</span>
            <p>No action metadata is available for this concept.</p>
          </article>
        ) : (
          manifest.actions.map((action) => (
            <article key={`${action.kind}-${action.title}`} className="authoring-preview-card">
              <div className="authoring-preview-card__header">
                <strong>{action.label}</strong>
                <span>{action.kind}</span>
              </div>
              <p>{action.description}</p>
              {action.confirmationText ? <p>Confirm: {action.confirmationText}</p> : null}
              {action.successMessage ? <p>Success: {action.successMessage}</p> : null}
              {action.permissionHint ? <p>Permission: {action.permissionHint}</p> : null}
              {action.eventName ? <p>Event: {action.eventName}</p> : null}
              {action.dangerLevel ? <p>Danger: {action.dangerLevel}</p> : null}
            </article>
          ))
        )}
      </div>
    </section>
  );
}
