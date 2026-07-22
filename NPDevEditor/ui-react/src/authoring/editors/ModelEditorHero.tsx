import type { AuthoringWorkspaceSeed } from "../services/modelLoader";
import type { AuthoringDocumentSession, AuthoringModelDocument } from "./modelDocumentTypes";

type ModelEditorHeroProps = {
  workspace: AuthoringWorkspaceSeed;
  documentSession: AuthoringDocumentSession;
  document: AuthoringModelDocument;
};

export default function ModelEditorHero({
  workspace,
  documentSession,
  document
}: ModelEditorHeroProps): JSX.Element {
  const concepts = document.concepts ?? [];
  const queries = document.queries ?? [];
  const procedures = document.procedures ?? [];
  const panels = document.panels ?? [];

  return (
    <>
      <section className="authoring-editor-hero">
        <div>
          <div className="authoring-breadcrumb">Guided authoring workspace</div>
          <h3>Form-based model editor</h3>
          <p>
            The canonical document shape is now editable through structured panels for concepts, queries, rule
            profiles, procedures, panels, fields, enums, references, domain types, invariants, flows, state
            machines, actions, and metadata.
          </p>
        </div>

        <div className="authoring-editor-hero__meta">
          <strong>{documentSession.sourceLabel}</strong>
          <small>Workspace mode: {workspace.modelSource}</small>
          <small>Loaded: {documentSession.lastLoadedLabel}</small>
          <small>{documentSession.dirty ? "Unsaved editor changes" : "Freshly loaded session"}</small>
        </div>
      </section>

      <section className="authoring-editor-summary">
        <article className="authoring-summary-card">
          <strong>Concepts</strong>
          <span>{concepts.length}</span>
          <small>Core business concepts with guided metadata and field editing.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Queries</strong>
          <span>{queries.length}</span>
          <small>Named read surfaces stay visible beside the concepts they depend on.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Procedures</strong>
          <span>{procedures.length}</span>
          <small>Reusable governed steps stay editable without dropping into raw JSON mode.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Panels</strong>
          <span>{panels.length}</span>
          <small>Supported UI surfaces are authored in the same contract bundle as flows and rules.</small>
        </article>
      </section>
    </>
  );
}
