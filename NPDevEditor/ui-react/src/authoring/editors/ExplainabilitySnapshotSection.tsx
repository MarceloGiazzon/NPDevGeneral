import type { ExplainabilityInsight } from "../explainability/modelExplainability";
import type { SemanticGraphModel } from "../graph/semanticGraph";
import ExplainabilityTooltip from "../help/ExplainabilityTooltip";
import SemanticGraphPanel from "../graph/SemanticGraphPanel";

type ExplainabilitySnapshotSectionProps = {
  semanticGraph: SemanticGraphModel;
  invariantInsights: ExplainabilityInsight[];
  flowInsights: ExplainabilityInsight[];
  actionInsights: ExplainabilityInsight[];
};

export default function ExplainabilitySnapshotSection({
  semanticGraph,
  invariantInsights,
  flowInsights,
  actionInsights
}: ExplainabilitySnapshotSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Explainability snapshot</h3>
          <p>Keep a live view of the model graph and why the current rules, flows, and actions matter.</p>
        </div>
        <ExplainabilityTooltip
          title="Explainability snapshot"
          detail="This section gives quick semantic context directly inside the editor so the model is not just a set of disconnected forms."
        />
      </div>

      <SemanticGraphPanel graph={semanticGraph} />

      <div className="authoring-template-grid">
        {[...invariantInsights, ...flowInsights.slice(0, 3), ...actionInsights.slice(0, 3)].map((entry) => (
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
  );
}
