import React, { useState } from "react";
import { listGlossaryEntries, type GlossaryTermId } from "./glossary";

type ContextualHelpPanelProps = {
  title: string;
  summary: string;
  tips: string[];
  glossaryHookIds: GlossaryTermId[];
};

export default function ContextualHelpPanel({
  title,
  summary,
  tips,
  glossaryHookIds
}: ContextualHelpPanelProps): JSX.Element {
  const glossaryEntries = listGlossaryEntries(glossaryHookIds);
  const [activeTermId, setActiveTermId] = useState<GlossaryTermId>(glossaryEntries[0]?.id ?? "concept");
  const activeEntry = glossaryEntries.find((entry) => entry.id === activeTermId) ?? glossaryEntries[0];

  return (
    <article className="authoring-help-panel">
      <header className="authoring-template-card__header">
        <div>
          <h4>{title}</h4>
          <p>{summary}</p>
        </div>
        <span>Help</span>
      </header>

      <ul className="authoring-template-card__notes">
        {tips.map((tip) => (
          <li key={tip}>{tip}</li>
        ))}
      </ul>

      <div className="authoring-help-term-row">
        {glossaryEntries.map((entry) => (
          <button
            key={entry.id}
            type="button"
            className={`authoring-help-term ${entry.id === activeEntry?.id ? "is-selected" : ""}`}
            onClick={() => setActiveTermId(entry.id)}
          >
            {entry.term}
          </button>
        ))}
      </div>

      {activeEntry ? (
        <div className="authoring-help-definition">
          <strong>{activeEntry.term}</strong>
          <p>{activeEntry.definition}</p>
          <small>{activeEntry.whyItMatters}</small>
        </div>
      ) : null}
    </article>
  );
}
