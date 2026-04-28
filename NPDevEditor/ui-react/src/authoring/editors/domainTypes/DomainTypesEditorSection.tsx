import React from "react";
import type { AuthoringDomainType } from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";

type DomainTypesEditorSectionProps = {
  domainTypes: AuthoringDomainType[];
  onChange: (domainTypes: AuthoringDomainType[]) => void;
};

export default function DomainTypesEditorSection({
  domainTypes,
  onChange
}: DomainTypesEditorSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Domain types</h3>
          <p>Define reusable semantic types like external identifiers and normalized codes without dropping into raw JSON.</p>
        </div>
        <button
          type="button"
          onClick={() =>
            onChange([
              ...domainTypes,
              {
                name: `DomainType${domainTypes.length + 1}`,
                baseType: "string",
                normalization: [],
                examples: []
              }
            ])
          }
        >
          Add domain type
        </button>
      </div>

      <div className="authoring-table-card">
        <table className="grid-table compact">
          <thead>
            <tr>
              <th>Name</th>
              <th>Base type</th>
              <th>Format</th>
              <th>Examples</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {domainTypes.map((domainType, index) => (
              <tr key={`${domainType.name}-${index}`}>
                <td>
                  <input
                    value={domainType.name}
                    onChange={(event) =>
                      onChange(
                        domainTypes.map((entry, entryIndex) =>
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
                  <input
                    value={domainType.baseType}
                    onChange={(event) =>
                      onChange(
                        domainTypes.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                baseType: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  <input
                    value={domainType.format ?? ""}
                    onChange={(event) =>
                      onChange(
                        domainTypes.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                format: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </td>
                <td>
                  <input
                    value={joinTextList(domainType.examples)}
                    onChange={(event) =>
                      onChange(
                        domainTypes.map((entry, entryIndex) =>
                          entryIndex === index
                            ? {
                                ...entry,
                                examples: parseTextList(event.target.value)
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
                    onClick={() => onChange(domainTypes.filter((_, entryIndex) => entryIndex !== index))}
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
