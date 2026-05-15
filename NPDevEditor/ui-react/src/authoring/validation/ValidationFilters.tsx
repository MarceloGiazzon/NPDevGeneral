import type { ValidationSeverity } from "../../types";

export type SeverityFilter = "all" | ValidationSeverity;
export type ScopeFilter = "all" | "model" | "config";

type ValidationFiltersProps = {
  severityFilter: SeverityFilter;
  scopeFilter: ScopeFilter;
  onSetSeverityFilter: (filter: SeverityFilter) => void;
  onSetScopeFilter: (filter: ScopeFilter) => void;
};

export default function ValidationFilters({
  severityFilter,
  scopeFilter,
  onSetSeverityFilter,
  onSetScopeFilter
}: ValidationFiltersProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Filters</h3>
          <p>Switch between severity and scope views to find where a problem lives and what kind it is.</p>
        </div>
      </div>
      <div className="authoring-inline-actions">
        {(["all", "error", "warning", "info"] as const).map((value) => (
          <button
            key={value}
            type="button"
            className={`authoring-secondary-inline ${severityFilter === value ? "is-selected" : ""}`}
            onClick={() => onSetSeverityFilter(value)}
          >
            {value === "all" ? "All severities" : value}
          </button>
        ))}
      </div>
      <div className="authoring-inline-actions">
        {(["all", "model", "config"] as const).map((value) => (
          <button
            key={value}
            type="button"
            className={`authoring-secondary-inline ${scopeFilter === value ? "is-selected" : ""}`}
            onClick={() => onSetScopeFilter(value)}
          >
            {value === "all" ? "All scopes" : value}
          </button>
        ))}
      </div>
    </section>
  );
}
