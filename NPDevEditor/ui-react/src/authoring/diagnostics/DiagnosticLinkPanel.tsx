import React from "react";
import type { DiagnosticLinkItem } from "./diagnosticLinking";

type DiagnosticLinkPanelProps = {
  items: DiagnosticLinkItem[];
};

export default function DiagnosticLinkPanel({
  items
}: DiagnosticLinkPanelProps): JSX.Element {
  return (
    <div className="authoring-designer-stack">
      {items.length === 0 ? (
        <article className="authoring-designer-card">
          <strong>No diagnostic links yet</strong>
          <p>Add flows, rules, or validation issues to grow trace and diagnostic expectations.</p>
        </article>
      ) : (
        items.map((item) => (
          <article key={`${item.title}-${item.source}`} className="authoring-designer-card">
            <div className="authoring-preview-card__header">
              <strong>{item.title}</strong>
              <span>{item.source}</span>
            </div>
            <p>{item.expectation}</p>
            <small>{item.evidenceHint}</small>
          </article>
        ))
      )}
    </div>
  );
}
