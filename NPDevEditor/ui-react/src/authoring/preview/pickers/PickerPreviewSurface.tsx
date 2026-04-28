import React from "react";
import type { PreviewManifest } from "../previewManifest";

type PickerPreviewSurfaceProps = {
  manifest: PreviewManifest;
};

export default function PickerPreviewSurface({
  manifest
}: PickerPreviewSurfaceProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Reference picker preview</h3>
          <p>Preview templates, search fields, picker columns, and inline-create behavior for references.</p>
        </div>
      </div>

      <div className="authoring-preview-grid">
        {manifest.pickers.length === 0 ? (
          <article className="authoring-validation-card">
            <strong>{manifest.entity.name}</strong>
            <span>INFO</span>
            <p>No reference picker metadata is configured for this concept.</p>
          </article>
        ) : (
          manifest.pickers.map((picker) => (
            <article key={picker.fieldName} className="authoring-preview-card">
              <div className="authoring-preview-card__header">
                <strong>{picker.label}</strong>
                <span>{picker.target}</span>
              </div>
              {picker.displayTemplate ? <p>Display template: {picker.displayTemplate}</p> : null}
              {picker.previewCardTemplate ? <p>Card template: {picker.previewCardTemplate}</p> : null}
              <p>Picker columns: {picker.pickerColumns.join(", ") || "none"}</p>
              <p>Preview fields: {picker.previewFields.join(", ") || "none"}</p>
              <p>Default filter: {picker.defaultFilter ?? "none"}</p>
              <p>Inline create: {picker.inlineCreate ?? "unknown"}</p>
            </article>
          ))
        )}
      </div>
    </section>
  );
}
