import React from "react";
import type { PreviewManifest } from "../previewManifest";
import { resolveFieldInteractionState } from "../previewManifest";

type FormPreviewSurfaceProps = {
  manifest: PreviewManifest;
  interactionContext: Record<string, unknown>;
};

function toneForState(value: boolean | null, positiveClass: string): string {
  if (value === null) {
    return "is-unknown";
  }
  return value ? positiveClass : "is-muted";
}

export default function FormPreviewSurface({
  manifest,
  interactionContext
}: FormPreviewSurfaceProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Form preview</h3>
          <p>Preview tabs, sections, widgets, and interaction metadata without generating the runtime UI.</p>
        </div>
      </div>

      <div className="authoring-preview-stack">
        {manifest.tabs.map((tab) => (
          <article key={tab.name} className="authoring-preview-card">
            <div className="authoring-preview-card__header">
              <strong>{tab.name}</strong>
              <span>{tab.fields.length} fields</span>
            </div>
            <div className="authoring-preview-form-grid">
              {tab.fields.map((field) => {
                const interaction = resolveFieldInteractionState(field, interactionContext);
                return (
                  <div key={field.name} className="authoring-preview-field">
                    <div className="authoring-preview-field__header">
                      <strong>{field.label}</strong>
                      <span>{field.widget ?? field.type}</span>
                    </div>
                    <small>{field.section}</small>
                    {field.placeholder ? <p>Placeholder: {field.placeholder}</p> : null}
                    {field.helpText ? <p>{field.helpText}</p> : null}
                    <div className="authoring-preview-state-row">
                      <span className={toneForState(interaction.visible, "is-positive")}>visible</span>
                      <span className={toneForState(interaction.enabled, "is-positive")}>enabled</span>
                      <span className={toneForState(interaction.readonly, "is-warning")}>readonly</span>
                      <span className={toneForState(interaction.required, "is-warning")}>required</span>
                    </div>
                  </div>
                );
              })}
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}
