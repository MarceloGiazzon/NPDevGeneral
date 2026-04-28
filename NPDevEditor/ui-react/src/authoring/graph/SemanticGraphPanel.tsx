import React from "react";
import type { SemanticGraphModel } from "./semanticGraph";

type SemanticGraphPanelProps = {
  graph: SemanticGraphModel;
};

export default function SemanticGraphPanel({
  graph
}: SemanticGraphPanelProps): JSX.Element {
  return (
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
                <p>
                  {edge.from} {"->"} {edge.to}
                </p>
              </article>
            ))
          )}
        </div>
      </article>
    </div>
  );
}
