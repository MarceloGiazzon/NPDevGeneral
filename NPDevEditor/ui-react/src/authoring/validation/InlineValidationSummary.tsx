import React from "react";
import type { ValidationDiagnostic } from "../../types";

type InlineValidationSummaryProps = {
  title: string;
  diagnostics: ValidationDiagnostic[];
};

export default function InlineValidationSummary({
  title,
  diagnostics
}: InlineValidationSummaryProps): JSX.Element | null {
  if (diagnostics.length === 0) {
    return null;
  }

  const errorCount = diagnostics.filter((entry) => entry.severity === "error").length;
  const warningCount = diagnostics.filter((entry) => entry.severity === "warning").length;

  return (
    <section className="authoring-inline-validation">
      <div className="authoring-inline-validation__header">
        <strong>{title}</strong>
        <span>
          {errorCount} errors / {warningCount} warnings
        </span>
      </div>
      <div className="authoring-inline-validation__list">
        {diagnostics.slice(0, 4).map((diagnostic) => (
          <article
            key={`${diagnostic.code}-${diagnostic.path}`}
            className={`authoring-inline-validation__item is-${diagnostic.severity}`}
          >
            <strong>{diagnostic.path ?? diagnostic.code}</strong>
            <p>{diagnostic.message}</p>
          </article>
        ))}
      </div>
    </section>
  );
}
