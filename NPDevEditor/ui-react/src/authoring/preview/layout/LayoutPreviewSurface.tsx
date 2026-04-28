import React from "react";
import type { PreviewManifest } from "../previewManifest";

type LayoutPreviewSurfaceProps = {
  manifest: PreviewManifest;
};

export default function LayoutPreviewSurface({
  manifest
}: LayoutPreviewSurfaceProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Layout preview</h3>
          <p>Inspect tabs, sections, ordering, and grid hints to see the likely UI structure before generation.</p>
        </div>
      </div>

      <div className="authoring-preview-stack">
        {manifest.tabs.map((tab) => (
          <article key={tab.name} className="authoring-preview-card">
            <div className="authoring-preview-card__header">
              <strong>{tab.name}</strong>
              <span>{tab.fields.length} fields</span>
            </div>
            <div className="authoring-preview-layout-grid">
              {tab.fields.map((field) => (
                <div key={field.name} className="authoring-preview-layout-item">
                  <strong>{field.label}</strong>
                  <small>
                    {field.section} | col {field.column} | span {field.columnSpan} | {field.width}
                  </small>
                </div>
              ))}
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}
