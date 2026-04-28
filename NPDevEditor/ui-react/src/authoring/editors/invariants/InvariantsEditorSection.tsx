import React from "react";
import type { AuthoringEntity, AuthoringInvariant } from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";
import ExplainabilityTooltip from "../../help/ExplainabilityTooltip";

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
                  <input
                    value={invariant.expression ?? invariant.expr ?? ""}
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
