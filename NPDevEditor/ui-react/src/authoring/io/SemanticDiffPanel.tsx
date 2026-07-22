import type { buildSemanticDiff } from "../diff/semanticDiff";

type SemanticDiffPanelProps = {
  title: string;
  summaries: ReturnType<typeof buildSemanticDiff>;
  emptyMessage: string;
};

export default function SemanticDiffPanel({
  title,
  summaries,
  emptyMessage
}: SemanticDiffPanelProps): JSX.Element {
  const totalChanges = summaries.reduce((total, summary) => total + summary.changes.length, 0);

  return (
    <div className="authoring-preview-stack">
      <article className="authoring-preview-card">
        <div className="authoring-preview-card__header">
          <strong>{title}</strong>
          <span>{totalChanges} changes</span>
        </div>
        {totalChanges === 0 ? (
          <p>{emptyMessage}</p>
        ) : (
          summaries.map((summary) => (
            <div key={summary.title} className="authoring-diff-section">
              <strong>{summary.title}</strong>
              {summary.changes.length === 0 ? (
                <p>No changes.</p>
              ) : (
                <div className="authoring-editor-stack">
                  {summary.changes.map((change) => (
                    <article key={`${change.path}-${change.kind}`} className="authoring-diff-card">
                      <div className="authoring-preview-card__header">
                        <strong>{change.path}</strong>
                        <span>{change.kind}</span>
                      </div>
                      {change.before !== undefined ? <p>Before: {change.before}</p> : null}
                      {change.after !== undefined ? <p>After: {change.after}</p> : null}
                    </article>
                  ))}
                </div>
              )}
            </div>
          ))
        )}
      </article>
    </div>
  );
}
