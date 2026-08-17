import React from "react";
import type { SemanticGraphModel } from "./semanticGraph";
import ErDiagramView from "./ErDiagramView";

type SemanticGraphPanelProps = {
  graph: SemanticGraphModel;
};

type ViewMode = "diagram" | "list";

export default function SemanticGraphPanel({
  graph
}: SemanticGraphPanelProps): JSX.Element {
  const [viewMode, setViewMode] = React.useState<ViewMode>(graph.erTables.length > 0 ? "diagram" : "list");

  return (
    <div className="authoring-semantic-graph">
      <div className="authoring-view-toggle" role="tablist" aria-label="Semantic graph view">
        <button
          type="button"
          role="tab"
          aria-selected={viewMode === "diagram"}
          className={viewMode === "diagram" ? "authoring-view-toggle__tab authoring-view-toggle__tab--active" : "authoring-view-toggle__tab"}
          onClick={() => setViewMode("diagram")}
        >
          ER diagram
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={viewMode === "list"}
          className={viewMode === "list" ? "authoring-view-toggle__tab authoring-view-toggle__tab--active" : "authoring-view-toggle__tab"}
          onClick={() => setViewMode("list")}
        >
          List
        </button>
      </div>

      {viewMode === "diagram" ? (
        <article className="authoring-preview-card">
          <div className="authoring-preview-card__header">
            <strong>Concept relationships</strong>
            <span>{graph.erTables.length} concepts · {graph.erRelationships.length} references</span>
          </div>
          <ErDiagramView tables={graph.erTables} relationships={graph.erRelationships} />
        </article>
      ) : (
        <div className="authoring-preview-grid">
          <article className="authoring-preview-card">
            <div className="authoring-preview-card__header">
              <strong>Semantic graph nodes</strong>
              <span>{graph.nodes.length} nodes</span>
            </div>
            <div className="authoring-designer-stack">
              {graph.nodes.map((node) => (
                <article key={node.id} className="authoring-designer-card">
                  <div className="authoring-preview-card__header">
                    <strong>{node.label}</strong>
                    <span>{node.kind}</span>
                  </div>
                  <p>{node.summary}</p>
                </article>
              ))}
            </div>
          </article>

          <article className="authoring-preview-card">
            <div className="authoring-preview-card__header">
              <strong>Relationship map</strong>
              <span>{graph.edges.length} links</span>
            </div>
            <div className="authoring-designer-stack">
              {graph.edges.length === 0 ? (
                <p>No semantic links are available yet for this selection.</p>
              ) : (
                graph.edges.map((edge, index) => (
                  <article key={`${edge.from}-${edge.to}-${index}`} className="authoring-designer-card">
                    <strong>{edge.label}</strong>
                    {edge.warning ? <span className="authoring-status-pill authoring-status-pill--warning">truth edge</span> : null}
                    <p>
                      {edge.from} {"->"} {edge.to}
                    </p>
                  </article>
                ))
              )}
            </div>
          </article>
        </div>
      )}
    </div>
  );
}
