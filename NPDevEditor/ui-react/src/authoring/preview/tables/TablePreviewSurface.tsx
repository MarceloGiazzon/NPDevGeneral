import React from "react";
import type { PreviewManifest } from "../previewManifest";

type TablePreviewSurfaceProps = {
  manifest: PreviewManifest;
};

export default function TablePreviewSurface({
  manifest
}: TablePreviewSurfaceProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Table and list preview</h3>
          <p>List columns come from list metadata rather than being guessed from the raw field order.</p>
        </div>
      </div>

      {manifest.tableColumns.length === 0 ? (
        <article className="authoring-validation-card">
          <strong>{manifest.entity.name}</strong>
          <span>INFO</span>
          <p>No list columns are configured for this concept yet.</p>
        </article>
      ) : (
        <div className="authoring-preview-card">
          <table className="grid-table compact">
            <thead>
              <tr>
                {manifest.tableColumns.map((column) => (
                  <th key={column.fieldName}>{column.label}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                {manifest.tableColumns.map((column) => (
                  <td key={column.fieldName}>
                    <div className="authoring-preview-cell">
                      <strong>{column.fieldName}</strong>
                      <small>{column.width}</small>
                    </div>
                  </td>
                ))}
              </tr>
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
