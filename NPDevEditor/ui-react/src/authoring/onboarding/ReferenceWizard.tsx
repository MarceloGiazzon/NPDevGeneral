import React, { useMemo, useState } from "react";
import type { AuthoringEntity, AuthoringField } from "../editors/modelDocumentTypes";

type ReferenceWizardProps = {
  entities: AuthoringEntity[];
  selectedConceptName: string | null;
  onCreateReference: (sourceConceptName: string, field: AuthoringField) => void;
};

function defaultFieldName(targetName: string): string {
  return targetName ? targetName.charAt(0).toLowerCase() + targetName.slice(1) : "referenceField";
}

export default function ReferenceWizard({
  entities,
  selectedConceptName,
  onCreateReference
}: ReferenceWizardProps): JSX.Element | null {
  const sourceConceptName = selectedConceptName ?? entities[0]?.name ?? "";
  const availableTargets = entities.filter((entity) => entity.name !== sourceConceptName);
  const [targetConceptName, setTargetConceptName] = useState<string>(availableTargets[0]?.name ?? "");
  const [fieldName, setFieldName] = useState<string>(defaultFieldName(availableTargets[0]?.name ?? ""));

  const sourceConcept = useMemo(
    () => entities.find((entity) => entity.name === sourceConceptName) ?? null,
    [entities, sourceConceptName]
  );

  if (!sourceConcept || availableTargets.length === 0) {
    return null;
  }

  return (
    <article className="authoring-wizard-card">
      <div className="authoring-editor-section__miniheader">
        <strong>Relation / reference wizard</strong>
        <span>Create the first relationship without remembering all reference metadata fields.</span>
      </div>

      <div className="authoring-form-grid">
        <label>
          Source concept
          <input value={sourceConcept.name} readOnly />
        </label>

        <label>
          Target concept
          <select
            value={targetConceptName}
            onChange={(event) => {
              setTargetConceptName(event.target.value);
              setFieldName(defaultFieldName(event.target.value));
            }}
          >
            {availableTargets.map((entity) => (
              <option key={entity.name} value={entity.name}>
                {entity.name}
              </option>
            ))}
          </select>
        </label>

        <label>
          Field name
          <input value={fieldName} onChange={(event) => setFieldName(event.target.value)} />
        </label>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={() =>
            onCreateReference(sourceConcept.name, {
              name: fieldName || defaultFieldName(targetConceptName),
              type: "reference",
              reference: {
                target: targetConceptName,
                displayField: "id",
                searchFields: ["id"],
                pickerColumns: ["id"],
                inlineCreate: "deny"
              },
              ui: {
                label: targetConceptName
              }
            })
          }
        >
          Add reference field
        </button>
      </div>
    </article>
  );
}
