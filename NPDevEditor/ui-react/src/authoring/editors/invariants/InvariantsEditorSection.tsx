import React from "react";
import type { AuthoringEntity, AuthoringInvariant } from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";
import ExplainabilityTooltip from "../../help/ExplainabilityTooltip";
import {
  ExpressionSyntaxError,
  isBooleanShaped,
  parseExpression,
  referencedFields
} from "./computedExpressionTs";

/**
 * Live, client-side-only feedback for an invariant `expression`. Mirrors (a strict subset of)
 * the server's `ComputedExpression`/`SemanticValidator` checks so authors see typos and
 * non-boolean expressions immediately; `validateModel` on save remains authoritative. A parse
 * failure here doesn't necessarily mean the expression is wrong — it may be using CEL-specific
 * syntax (`.matches()`, `scope.exists()`, etc.) this lightweight client parser doesn't cover, so
 * that case is reported as "extended syntax" rather than an error.
 */
function describeExpression(
  expression: string,
  knownFields: Set<string>
): { kind: "empty" | "ok" | "extended" | "error"; message?: string } {
  const trimmed = expression.trim();
  if (!trimmed) {
    return { kind: "empty" };
  }
  let node;
  try {
    node = parseExpression(trimmed);
  } catch (err) {
    if (err instanceof ExpressionSyntaxError) {
      return { kind: "extended", message: "Extended syntax (e.g. scope.exists/.matches/.uniqueBy) — validated on save." };
    }
    throw err;
  }
  if (!isBooleanShaped(node)) {
    return { kind: "error", message: "Expression must evaluate to a boolean (use ==, !=, <, >, &&, ||, or !)." };
  }
  const unknown = [...referencedFields(node)]
    .map((name) => (name.includes(".") ? name.slice(0, name.indexOf(".")) : name))
    .filter((root) => !knownFields.has(root.toLowerCase()));
  if (unknown.length > 0) {
    return { kind: "error", message: `Unknown field${unknown.length > 1 ? "s" : ""}: ${unknown.join(", ")}` };
  }
  return { kind: "ok" };
}

type InvariantsEditorSectionProps = {
  entity: AuthoringEntity | null;
  onChange: (invariants: AuthoringInvariant[]) => void;
};

export default function InvariantsEditorSection({
  entity,
  onChange
}: InvariantsEditorSectionProps): JSX.Element | null {
  if (!entity) {
    return null;
  }

  const invariants = entity.invariants ?? [];
  const fieldNames = entity.fields.map((field) => field.name);
  const knownFields = new Set(fieldNames.map((name) => name.toLowerCase()));
  const fieldDatalistId = `invariant-fields-${entity.name}`;

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Invariant editor</h3>
          <p>Edit unique constraints and expression-based rules against the selected concept.</p>
        </div>
        <ExplainabilityTooltip
          title="What invariants mean"
          detail="Invariants explain why a concept is valid. They are part of the model’s meaning, not just extra validation noise."
        />
        <button
          type="button"
          onClick={() =>
            onChange([
              ...invariants,
              {
                name: `Invariant${invariants.length + 1}`,
                type: "expression",
                expression: "true"
              }
            ])
          }
        >
          Add invariant
        </button>
      </div>

      <datalist id={fieldDatalistId}>
        {fieldNames.map((name) => (
          <option key={name} value={name} />
        ))}
      </datalist>

      <div className="authoring-table-card">
        <table className="grid-table compact">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Fields</th>
              <th>Expression</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {invariants.map((invariant, index) => (
              <tr key={`${invariant.name}-${index}`}>
                <td>
                  <input
                    value={invariant.name}
                    onChange={(event) =>
                      onChange(
                        invariants.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                name: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  <select
                    value={invariant.type ?? "expression"}
                    onChange={(event) =>
                      onChange(
                        invariants.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                type: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  >
                    <option value="expression">expression</option>
                    <option value="unique">unique</option>
                  </select>
                </td>
                <td>
                  <input
                    value={joinTextList(invariant.fields)}
                    onChange={(event) =>
                      onChange(
                        invariants.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                fields: parseTextList(event.target.value)
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  {(() => {
                    const expressionValue = invariant.expression ?? invariant.expr ?? "";
                    const status =
                      invariant.type === "expression" || !invariant.type
                        ? describeExpression(expressionValue, knownFields)
                        : { kind: "empty" as const };
                    return (
                      <>
                        <input
                          list={fieldDatalistId}
                          value={expressionValue}
                          aria-invalid={status.kind === "error"}
                          title={status.message}
                          onChange={(event) =>
                            onChange(
                              invariants.map((entry, entryIndex) =>
                                entryIndex === index
                                  ? {
                                      ...entry,
                                      expression: event.target.value
                                    }
                                  : entry
                              )
                            )
                          }
                        />
                        {status.message ? (
                          <p
                            className={
                              status.kind === "error"
                                ? "authoring-inline-error"
                                : "authoring-inline-hint"
                            }
                          >
                            {status.message}
                          </p>
                        ) : null}
                      </>
                    );
                  })()}
                </td>
                <td>
                  <button
                    type="button"
                    className="authoring-ghost-button"
                    onClick={() => onChange(invariants.filter((_, entryIndex) => entryIndex !== index))}
                  >
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
