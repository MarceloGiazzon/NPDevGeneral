import React, { useState } from "react";
import type { AuthoringEntity } from "../editors/modelDocumentTypes";

type ConceptCreationWizardProps = {
  conceptCount: number;
  onCreateConcept: (entity: AuthoringEntity) => void;
};

function normalizeName(value: string, fallback: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    return fallback;
  }
  const compact = trimmed.replace(/[^a-zA-Z0-9]+/g, " ").trim();
  const parts = compact.split(/\s+/).filter(Boolean);
  const camel = parts.map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join("");
  return camel || fallback;
}

export default function ConceptCreationWizard({
  conceptCount,
  onCreateConcept
}: ConceptCreationWizardProps): JSX.Element {
  const [conceptLabel, setConceptLabel] = useState<string>("New business object");
  const [includeStatus, setIncludeStatus] = useState<boolean>(true);
  const [includeDescription, setIncludeDescription] = useState<boolean>(true);

  return (
    <article className="authoring-wizard-card">
      <div className="authoring-editor-section__miniheader">
        <strong>Concept creation wizard</strong>
        <span>Use a safer guided start instead of inventing the first concept from nothing.</span>
      </div>

      <div className="authoring-form-grid">
        <label>
          Concept label
          <input value={conceptLabel} onChange={(event) => setConceptLabel(event.target.value)} />
        </label>

        <label className="authoring-toggle-row">
          <input type="checkbox" checked={includeStatus} onChange={(event) => setIncludeStatus(event.target.checked)} />
          Add starter status field
        </label>

        <label className="authoring-toggle-row">
          <input
            type="checkbox"
            checked={includeDescription}
            onChange={(event) => setIncludeDescription(event.target.checked)}
          />
          Add description field
        </label>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={() => {
            const conceptName = normalizeName(conceptLabel, `Concept${conceptCount + 1}`);
            const fields: AuthoringEntity["fields"] = [
              {
                name: "id",
                type: "uuid",
                id: true,
                required: true
              },
              {
                name: "name",
                type: "string",
                required: true,
                ui: {
                  label: `${conceptLabel || conceptName} name`,
                  order: 1
                }
              }
            ];

            if (includeDescription) {
              fields.push({
                name: "description",
                type: "string",
                ui: {
                  label: "Description",
                  order: 2
                }
              });
            }

            if (includeStatus) {
              fields.push({
                name: "status",
                type: "string",
                default: "Draft",
                enumValues: [
                  { value: "Draft", label: "Draft", default: true, order: 1, badge: "neutral" },
                  { value: "Active", label: "Active", order: 2, badge: "success" }
                ],
                ui: {
                  label: "Status",
                  order: includeDescription ? 3 : 2
                }
              });
            }

            onCreateConcept({
              name: conceptName,
              ui: {
                label: conceptLabel || conceptName,
                description: "Created from the Step 39 concept wizard."
              },
              fields,
              invariants: []
            });
          }}
        >
          Create concept from wizard
        </button>
      </div>
    </article>
  );
}
