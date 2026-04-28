import React from "react";
import { AUTHORING_ROUTES, type AuthoringRouteDef, type AuthoringRouteId } from "../routes/authoringRoutes";
import type { AuthoringServiceStatus } from "../services/authoringApi";
import { ModelSyncStatusBanner } from "../sync/ModelSyncStatusBanner";
import { buildPipelineSteps } from "../navigation/authoringStep48Ux";
import type {
  AuthoringCategoryCard,
  AuthoringStartMode,
  AuthoringWorkspaceSeed,
  OfficialSampleCard
} from "../services/modelLoader";

type AuthoringShellProps = {
  activeRoute: AuthoringRouteDef;
  workspace: AuthoringWorkspaceSeed;
  modelJson: string | null;
  serviceStatuses: AuthoringServiceStatus[];
  bootstrapped: boolean;
  lastBootstrapLabel: string;
  launchModes: AuthoringStartMode[];
  categoryCards: AuthoringCategoryCard[];
  officialSamples: OfficialSampleCard[];
  onNavigate: (routeId: AuthoringRouteId) => void;
  onSelectStartMode: (modeId: AuthoringStartMode["id"]) => void;
  onSelectOfficialSample: (sampleId: string) => void;
  onReturnToWorkbench: () => void;
  children: React.ReactNode;
};

function statusTone(status: AuthoringServiceStatus["status"]): string {
  switch (status) {
    case "ready":
      return "is-ready";
    case "degraded":
      return "is-degraded";
    default:
      return "is-unavailable";
  }
}

export default function AuthoringShell({
  activeRoute,
  workspace,
  modelJson,
  serviceStatuses,
  bootstrapped,
  lastBootstrapLabel,
  launchModes,
  categoryCards,
  officialSamples,
  onNavigate,
  onSelectStartMode,
  onSelectOfficialSample,
  onReturnToWorkbench,
  children
}: AuthoringShellProps): JSX.Element {
  const pipelineSteps = buildPipelineSteps(activeRoute.id);

  return (
    <div className="authoring-app-shell">
      <aside className="authoring-sidebar">
        <div className="authoring-sidebar__brand">
          <div className="authoring-badge">NPDev Authoring</div>
          <h1>Authoring Studio Shell</h1>
          <p>
            Stable scaffolding for model selection, editing, config, preview, validation, and import/export.
          </p>
        </div>

        <nav className="authoring-nav" aria-label="Authoring routes">
          {AUTHORING_ROUTES.map((route) => {
            const active = route.id === activeRoute.id;
            return (
              <button
                key={route.id}
                type="button"
                className={`authoring-nav__item ${active ? "is-active" : ""}`}
                onClick={() => onNavigate(route.id)}
              >
                <span>{route.label}</span>
                <small>{route.summary}</small>
              </button>
            );
          })}
        </nav>

        <div className="authoring-sidebar__actions">
          <button type="button" className="authoring-secondary-button" onClick={onReturnToWorkbench}>
            Return To Workbench
          </button>
        </div>
      </aside>

      <div className="authoring-main">
        <header className="authoring-topbar">
          <div>
            <div className="authoring-breadcrumb">Distinct surface: `#/authoring/*`</div>
            <h2>{activeRoute.label}</h2>
            <p>{activeRoute.summary}</p>
          </div>

          <div className="authoring-topbar__card">
            <strong>Workspace seed</strong>
            <span>{workspace.title}</span>
            <small>{workspace.description}</small>
            <small>Audience: {workspace.recommendedAudience}</small>
            {workspace.sampleId ? <small>Selected sample: {workspace.sampleId}</small> : null}
            {workspace.templateTitle ? <small>Starter template: {workspace.templateTitle}</small> : null}
          </div>
        </header>

        <ModelSyncStatusBanner modelJson={modelJson} />

        <nav className="authoring-pipeline-nav" aria-label="Authoring pipeline">
          {pipelineSteps.map((step) =>
            step.routeId ? (
              <button
                key={step.id}
                type="button"
                className={`authoring-pipeline-step ${step.active ? "is-active" : ""}`}
                onClick={() => onNavigate(step.routeId!)}
                aria-current={step.active ? "page" : undefined}
              >
                <strong>{step.label}</strong>
                <small>{step.detail}</small>
              </button>
            ) : (
              <div key={step.id} className="authoring-pipeline-step is-downstream">
                <strong>{step.label}</strong>
                <small>{step.detail}</small>
              </div>
            )
          )}
        </nav>

        <section className="authoring-status-strip">
          <div className="authoring-status-card">
            <strong>Bootstrap status</strong>
            <span>{bootstrapped ? "Ready" : "Bootstrapping"}</span>
            <small>Last refresh: {lastBootstrapLabel}</small>
          </div>

          <div className="authoring-status-card">
            <strong>Shell purpose</strong>
            <span>Guided authoring with stable structure</span>
            <small>Routing and workspace scaffolding are now supporting the real form-based model editor.</small>
          </div>
        </section>

        <section className="authoring-panel">
          <div className="authoring-panel__header">
            <div>
              <h3>Start Modes</h3>
              <p>Base model-entry abstraction for canonical demo, samples, arbitrary files, and blank starts.</p>
            </div>
          </div>

          <div className="authoring-mode-grid">
            {launchModes.map((mode) => (
              <button
                key={mode.id}
                type="button"
                className={`authoring-mode-card ${workspace.modelSource === mode.id ? "is-selected" : ""}`}
                onClick={() => onSelectStartMode(mode.id)}
              >
                <div className="authoring-mode-card__title">
                  <span>{mode.title}</span>
                  {mode.recommended ? <em>Recommended</em> : null}
                </div>
                <small>{mode.description}</small>
              </button>
            ))}
          </div>
        </section>

        <section className="authoring-panel">
          <div className="authoring-panel__header">
            <div>
              <h3>Category Summary</h3>
              <p>Platform-owned model lanes stay explicit so onboarding and experimentation remain understandable.</p>
            </div>
          </div>

          <div className="authoring-summary-grid">
            <article className="authoring-summary-card">
              <strong>Category cards</strong>
              <span>{categoryCards.length}</span>
              <small>Canonical demo, curated official samples, and open-ended user paths are separated.</small>
            </article>
            <article className="authoring-summary-card">
              <strong>Official samples</strong>
              <span>{officialSamples.length}</span>
              <small>Curated sample catalog is ready for chooser-driven loading.</small>
            </article>
            <article className="authoring-summary-card">
              <strong>Primary action</strong>
              <span>{workspace.primaryActionLabel}</span>
              <small>Workspace state updates now drive the next editor move instead of ad hoc tab switches.</small>
            </article>
          </div>

          {officialSamples.length > 0 ? (
            <div className="authoring-inline-actions">
              {officialSamples.slice(0, 3).map((sample) => (
                <button key={sample.id} type="button" className="authoring-ghost-button" onClick={() => onSelectOfficialSample(sample.id)}>
                  Load {sample.title}
                </button>
              ))}
            </div>
          ) : null}
        </section>

        <section className="authoring-panel">
          <div className="authoring-panel__header">
            <div>
              <h3>Service Foundation</h3>
              <p>Authoring services are already separated from screens and can be expanded without routing rewrites.</p>
            </div>
          </div>

          <div className="authoring-service-grid">
            {serviceStatuses.map((status) => (
              <article key={status.id} className={`authoring-service-card ${statusTone(status.status)}`}>
                <header>
                  <strong>{status.label}</strong>
                  <span>{status.status}</span>
                </header>
                <p>{status.detail}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="authoring-panel">{children}</section>
      </div>
    </div>
  );
}
