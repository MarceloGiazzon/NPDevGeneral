import React, { useMemo, useState } from "react";
import type { ValidationDiagnostic } from "../../types";
import type { AuthoringConfigSession } from "../config/configDocumentTypes";
import type { AuthoringDocumentSession } from "../editors/modelDocumentTypes";
import DiagnosticLinkPanel from "../diagnostics/DiagnosticLinkPanel";
import { buildDiagnosticLinkItems } from "../diagnostics/diagnosticLinking";
import { buildFieldExplainabilityEntries } from "../explainability/interactionExplainability";
import {
  buildActionReasonEntries,
  buildFlowExplanationEntries,
  buildInvariantMeaningEntries
} from "../explainability/modelExplainability";
import ExplainabilityTooltip from "../help/ExplainabilityTooltip";
import SemanticGraphPanel from "../graph/SemanticGraphPanel";
import { buildSemanticGraph } from "../graph/semanticGraph";
import { buildDiagnosticNavigationTarget } from "../navigation/authoringStep48Ux";
import { buildConfigValidationDiagnostics, buildModelValidationDiagnostics } from "./authoringValidation";
import ValidationFilters, { type ScopeFilter, type SeverityFilter } from "./ValidationFilters";

type ValidationWorkspaceProps = {
  documentSession: AuthoringDocumentSession | null;
  configSession: AuthoringConfigSession | null;
  selectedConceptName: string | null;
};

function diagnosticScope(entry: ValidationDiagnostic): "model" | "config" {
  return entry.sourceModule.includes("config") ? "config" : "model";
}

export default function ValidationWorkspace({
  documentSession,
  configSession,
  selectedConceptName
}: ValidationWorkspaceProps): JSX.Element {
  const [severityFilter, setSeverityFilter] = useState<SeverityFilter>("all");
  const [scopeFilter, setScopeFilter] = useState<ScopeFilter>("all");

  const modelDiagnostics = useMemo(
    () => (documentSession ? buildModelValidationDiagnostics(documentSession.document) : []),
    [documentSession]
  );
  const configDiagnostics = useMemo(
    () => (configSession ? buildConfigValidationDiagnostics(configSession.document) : []),
    [configSession]
  );

  const entity =
    documentSession?.document.concepts.find((entry) => entry.name === selectedConceptName) ??
    documentSession?.document.concepts[0] ??
    null;
  const explainabilityEntries = useMemo(() => buildFieldExplainabilityEntries(entity), [entity]);
  const invariantInsights = useMemo(() => buildInvariantMeaningEntries(entity), [entity]);
  const flowInsights = useMemo(
    () => (documentSession ? buildFlowExplanationEntries(documentSession.document) : []),
    [documentSession]
  );
  const actionInsights = useMemo(
    () =>
      documentSession
        ? buildActionReasonEntries(
            entity,
            documentSession.document.flows ?? [],
            documentSession.document.orchestrationRules ?? []
          )
        : [],
    [documentSession, entity]
  );

  const diagnostics = [...modelDiagnostics, ...configDiagnostics];
  const diagnosticLinks = useMemo(
    () =>
      documentSession
        ? buildDiagnosticLinkItems(documentSession.document, configSession?.document ?? null, diagnostics)
        : [],
    [configSession, diagnostics, documentSession]
  );
  const semanticGraph = useMemo(
    () => (documentSession ? buildSemanticGraph(documentSession.document, configSession?.document ?? null) : null),
    [configSession, documentSession]
  );
  const filteredDiagnostics = diagnostics.filter((entry) => {
    if (severityFilter !== "all" && entry.severity !== severityFilter) {
      return false;
    }
    if (scopeFilter !== "all" && diagnosticScope(entry) !== scopeFilter) {
      return false;
    }
    return true;
  });

  const groupedByLayer = filteredDiagnostics.reduce<Record<string, ValidationDiagnostic[]>>((acc, entry) => {
    const key = entry.layer;
    acc[key] = [...(acc[key] ?? []), entry];
    return acc;
  }, {});

  return (
    <div className="authoring-editor">
      <section className="authoring-editor-hero">
        <div>
          <div className="authoring-breadcrumb">Validation and explainability</div>
          <h3>Validation workspace</h3>
          <p>
            Validation is now a first-class authoring aid with grouped diagnostics, severity filters, suggested fixes,
            and explainability for interaction-driven field behavior.
          </p>
        </div>
        <div className="authoring-editor-hero__meta">
          <strong>{filteredDiagnostics.length} visible diagnostics</strong>
          <small>{modelDiagnostics.length} model diagnostics</small>
          <small>{configDiagnostics.length} config diagnostics</small>
          <small>{explainabilityEntries.length} explainability entries</small>
        </div>
      </section>

      <section className="authoring-editor-summary">
        <article className="authoring-summary-card">
          <strong>Errors</strong>
          <span>{diagnostics.filter((entry) => entry.severity === "error").length}</span>
          <small>Blocking problems that should be fixed before export.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Warnings</strong>
          <span>{diagnostics.filter((entry) => entry.severity === "warning").length}</span>
          <small>Guidance that improves the authoring outcome without blocking progress.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Explainability</strong>
          <span>{explainabilityEntries.length}</span>
          <small>Field visibility, enablement, readonly, and required-state explanations.</small>
        </article>
      </section>

      <ValidationFilters
        severityFilter={severityFilter}
        scopeFilter={scopeFilter}
        onSetSeverityFilter={setSeverityFilter}
        onSetScopeFilter={setScopeFilter}
      />

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Grouped diagnostics</h3>
            <p>Problems are grouped by validation layer so syntax, semantics, and UX metadata issues stay learnable.</p>
          </div>
          <ExplainabilityTooltip
            title="Why grouped diagnostics matter"
            detail="NPDev separates structural, semantic, and UX-metadata issues so users can tell whether a problem is about shape, meaning, or presentation."
          />
        </div>
        <div className="authoring-editor-stack">
          {Object.entries(groupedByLayer).length === 0 ? (
            <article className="authoring-validation-card">
              <strong>$</strong>
              <span>OK</span>
              <p>No diagnostics are visible for the current filters.</p>
            </article>
          ) : (
            Object.entries(groupedByLayer).map(([layer, entries]) => (
              <article key={layer} className="authoring-subcard">
                <div className="authoring-editor-section__miniheader">
                  <strong>{layer}</strong>
                  <span>{entries.length} items</span>
                </div>
                <div className="authoring-editor-stack">
                  {entries.map((entry) => {
                    const navigationTarget = buildDiagnosticNavigationTarget(entry);
                    return (
                    <button
                      key={`${entry.code}-${entry.path}`}
                      type="button"
                      className={`authoring-validation-card ${entry.severity === "error" ? "is-error" : "is-warning"}`}
                      onClick={() => {
                        window.location.hash = navigationTarget.hash;
                      }}
                      aria-label={`Go to ${navigationTarget.locationLabel}`}
                    >
                      <strong>{entry.path ?? entry.code}</strong>
                      <span>{entry.severity.toUpperCase()}</span>
                      <div>
                        <p>{entry.message}</p>
                        {entry.suggestedFix ? <p>Suggested fix: {entry.suggestedFix}</p> : null}
                        {entry.concept || entry.field ? (
                          <p>
                            Context: {entry.concept ?? "global"}
                            {entry.field ? ` / ${entry.field}` : ""}
                          </p>
                        ) : null}
                        <p className="authoring-diagnostic-link-hint">Go to {navigationTarget.locationLabel}</p>
                      </div>
                    </button>
                  );})}
                </div>
              </article>
            ))
          )}
        </div>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Explainability</h3>
            <p>Interaction metadata is explained in plain language so “why is this field visible or disabled?” is answerable.</p>
          </div>
          <ExplainabilityTooltip
            title="Why explainability is here"
            detail="Validation and explainability live together because authoring problems are easier to fix when the model’s meaning is visible beside the diagnostics."
          />
        </div>
        <div className="authoring-editor-stack">
          {explainabilityEntries.length === 0 ? (
            <article className="authoring-validation-card">
              <strong>{entity?.name ?? "No concept selected"}</strong>
              <span>INFO</span>
              <p>No visibility or enablement explanations are available for the current concept.</p>
            </article>
          ) : (
            explainabilityEntries.map((entry) => (
              <article key={`${entry.targetPath}-${entry.kind}`} className="authoring-explainability-card">
                <div className="authoring-editor-section__miniheader">
                  <strong>{entry.title}</strong>
                  <span>{entry.kind}</span>
                </div>
                <p>{entry.summary}</p>
                <pre className="json-pane small">{entry.expression}</pre>
              </article>
            ))
          )}
        </div>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Semantic graph</h3>
            <p>See how concepts, references, flows, capabilities, and events connect so the model stops feeling like isolated forms.</p>
          </div>
        </div>
        {semanticGraph ? <SemanticGraphPanel graph={semanticGraph} /> : null}
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Model meaning</h3>
            <p>These panels explain what invariants, flows, and actions mean in platform terms instead of leaving that understanding implicit.</p>
          </div>
        </div>
        <div className="authoring-template-grid">
          {[...invariantInsights, ...flowInsights, ...actionInsights].map((entry) => (
            <article key={`${entry.kind}-${entry.title}`} className="authoring-explainability-card">
              <div className="authoring-editor-section__miniheader">
                <strong>{entry.title}</strong>
                <span>{entry.kind}</span>
              </div>
              <p>{entry.summary}</p>
              <ul className="authoring-template-card__notes">
                {entry.details.map((detail) => (
                  <li key={detail}>{detail}</li>
                ))}
              </ul>
            </article>
          ))}
        </div>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Diagnostics and trace linkage</h3>
            <p>Link current authoring decisions to the kind of diagnostics, runtime evidence, or trace expectations they should produce later.</p>
          </div>
        </div>
        <DiagnosticLinkPanel items={diagnosticLinks} />
      </section>
    </div>
  );
}
