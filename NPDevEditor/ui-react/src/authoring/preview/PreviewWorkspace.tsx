import React, { useEffect, useMemo, useState } from "react";
import type { AuthoringDocumentSession } from "../editors/modelDocumentTypes";
import {
  buildInitialPreviewContext,
  buildPreviewManifest,
  type PreviewManifest
} from "./previewManifest";
import FormPreviewSurface from "./forms/FormPreviewSurface";
import TablePreviewSurface from "./tables/TablePreviewSurface";
import PickerPreviewSurface from "./pickers/PickerPreviewSurface";
import ActionPreviewSurface from "./actions/ActionPreviewSurface";
import LayoutPreviewSurface from "./layout/LayoutPreviewSurface";

type PreviewWorkspaceProps = {
  documentSession: AuthoringDocumentSession | null;
  selectedConceptName: string | null;
  onSelectConcept: (conceptName: string) => void;
};

function previewableControlFields(manifest: PreviewManifest): PreviewManifest["entity"]["fields"] {
  return manifest.entity.fields.filter((field) => field.type === "enum" || field.type === "boolean");
}

export default function PreviewWorkspace({
  documentSession,
  selectedConceptName,
  onSelectConcept
}: PreviewWorkspaceProps): JSX.Element {
  const manifest = useMemo(
    () => (documentSession ? buildPreviewManifest(documentSession.document, selectedConceptName) : null),
    [documentSession, selectedConceptName]
  );

  const [interactionContext, setInteractionContext] = useState<Record<string, unknown>>({});

  useEffect(() => {
    if (manifest) {
      setInteractionContext(buildInitialPreviewContext(manifest.entity));
    }
  }, [manifest]);

  if (!documentSession || !manifest) {
    return (
      <div className="authoring-route-card">
        <div className="authoring-route-card__header">
          <div>
            <h3>Loading preview workspace</h3>
            <p>The preview surface is waiting for a model document session to become available.</p>
          </div>
          <div className="authoring-badge">Preparing</div>
        </div>
      </div>
    );
  }

  const driverFields = previewableControlFields(manifest);

  return (
    <div className="authoring-editor">
      <section className="authoring-editor-hero">
        <div>
          <div className="authoring-breadcrumb">Preview workspace</div>
          <h3>Metadata-driven preview surfaces</h3>
          <p>
            Preview forms, tables, pickers, actions, and layout structure before generation. These surfaces are derived
            from the authoring metadata rather than hard-coded sample UI.
          </p>
        </div>
        <div className="authoring-editor-hero__meta">
          <strong>{documentSession.sourceLabel}</strong>
          <small>Previewing concept: {manifest.entity.name}</small>
          <small>{manifest.tabs.length} tabs</small>
          <small>{manifest.actions.length} actions</small>
        </div>
      </section>

      <section className="authoring-editor-summary">
        <article className="authoring-summary-card">
          <strong>Tabs</strong>
          <span>{manifest.tabs.length}</span>
          <small>Layout is previewed from field tab and section metadata.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Pickers</strong>
          <span>{manifest.pickers.length}</span>
          <small>Reference metadata becomes visible through picker previews.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>List columns</strong>
          <span>{manifest.tableColumns.length}</span>
          <small>Table/list surfaces reflect column metadata and ordering.</small>
        </article>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Preview controls</h3>
            <p>Switch concepts and interaction-state drivers to see how metadata choices change the likely UI.</p>
          </div>
        </div>

        <div className="authoring-inline-actions">
          {documentSession.document.concepts.map((entity) => (
            <button
              key={entity.name}
              type="button"
              className={`authoring-secondary-inline ${entity.name === manifest.entity.name ? "is-selected" : ""}`}
              onClick={() => onSelectConcept(entity.name)}
            >
              {entity.ui?.label ?? entity.name}
            </button>
          ))}
        </div>

        {driverFields.length > 0 ? (
          <div className="authoring-form-grid">
            {driverFields.map((field) => (
              <label key={field.name}>
                {field.ui?.label ?? field.name}
                {field.type === "enum" ? (
                  <select
                    value={String(interactionContext[field.name] ?? "")}
                    onChange={(event) =>
                      setInteractionContext((current) => ({
                        ...current,
                        [field.name]: event.target.value
                      }))
                    }
                  >
                    {(field.enumValues ?? []).map((option) => {
                      const value = typeof option === "string" ? option : option.value;
                      const label = typeof option === "string" ? option : option.label ?? option.value;
                      return (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      );
                    })}
                  </select>
                ) : (
                  <select
                    value={interactionContext[field.name] ? "true" : "false"}
                    onChange={(event) =>
                      setInteractionContext((current) => ({
                        ...current,
                        [field.name]: event.target.value === "true"
                      }))
                    }
                  >
                    <option value="true">true</option>
                    <option value="false">false</option>
                  </select>
                )}
              </label>
            ))}
          </div>
        ) : null}
      </section>

      <FormPreviewSurface manifest={manifest} interactionContext={interactionContext} />
      <LayoutPreviewSurface manifest={manifest} />
      <TablePreviewSurface manifest={manifest} />
      <PickerPreviewSurface manifest={manifest} />
      <ActionPreviewSurface manifest={manifest} />
    </div>
  );
}
