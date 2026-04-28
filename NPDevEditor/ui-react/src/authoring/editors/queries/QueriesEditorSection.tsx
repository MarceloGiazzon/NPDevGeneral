import React from "react";
import type { AuthoringQuery } from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";

type QueriesEditorSectionProps = {
  queries: AuthoringQuery[];
  conceptNames: string[];
  onChange: (queries: AuthoringQuery[]) => void;
};

export default function QueriesEditorSection({
  queries,
  conceptNames,
  onChange
}: QueriesEditorSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Queries</h3>
          <p>Define reusable concept reads in the same contract bundle that goes to validation, generation, and runtime.</p>
        </div>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={() =>
            onChange([
              ...queries,
              {
                name: `Query${queries.length + 1}`,
                concept: conceptNames[0] ?? "",
                where: ""
              }
            ])
          }
        >
          Add query
        </button>
      </div>

      <div className="authoring-editor-stack">
        {queries.length === 0 ? (
          <article className="authoring-subcard">
            <p>No queries yet. Add one when a concept needs a named read surface.</p>
          </article>
        ) : (
          queries.map((query, queryIndex) => (
            <article key={`${query.name}-${queryIndex}`} className="authoring-subcard">
              <div className="authoring-preview-card__header">
                <strong>{query.name || `Query ${queryIndex + 1}`}</strong>
                <button
                  type="button"
                  className="authoring-ghost-button"
                  onClick={() => onChange(queries.filter((_, index) => index !== queryIndex))}
                >
                  Remove
                </button>
              </div>

              <div className="authoring-form-grid">
                <label>
                  Name
                  <input
                    value={query.name}
                    onChange={(event) =>
                      onChange(
                        queries.map((entry, index) =>
                          index === queryIndex
                            ? {
                                ...entry,
                                name: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>

                <label>
                  Concept
                  <select
                    value={query.concept}
                    onChange={(event) =>
                      onChange(
                        queries.map((entry, index) =>
                          index === queryIndex
                            ? {
                                ...entry,
                                concept: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  >
                    <option value="">Choose concept</option>
                    {conceptNames.map((conceptName) => (
                      <option key={conceptName} value={conceptName}>
                        {conceptName}
                      </option>
                    ))}
                  </select>
                </label>

                <label>
                  Filter / where
                  <input
                    value={query.where ?? query.filter ?? ""}
                    onChange={(event) =>
                      onChange(
                        queries.map((entry, index) =>
                          index === queryIndex
                            ? {
                                ...entry,
                                where: event.target.value || undefined,
                                filter: undefined
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>

                <label>
                  Order by
                  <input
                    value={joinTextList(query.orderBy)}
                    onChange={(event) =>
                      onChange(
                        queries.map((entry, index) =>
                          index === queryIndex
                            ? {
                                ...entry,
                                orderBy: parseTextList(event.target.value)
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>
              </div>
            </article>
          ))
        )}
      </div>
    </section>
  );
}
