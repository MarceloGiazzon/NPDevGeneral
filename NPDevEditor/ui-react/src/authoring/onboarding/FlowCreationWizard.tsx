import React, { useState } from "react";
import type { AuthoringEntity, AuthoringFlow } from "../editors/modelDocumentTypes";

type FlowCreationWizardProps = {
  entities: AuthoringEntity[];
  onCreateFlow: (flow: AuthoringFlow) => void;
};

type FlowPreset = "submit-and-return" | "approval-lite";

export default function FlowCreationWizard({
  entities,
  onCreateFlow
}: FlowCreationWizardProps): JSX.Element | null {
  const [conceptName, setConceptName] = useState<string>(entities[0]?.name ?? "");
  const [flowName, setFlowName] = useState<string>("CreateFlow");
  const [preset, setPreset] = useState<FlowPreset>("submit-and-return");

  if (entities.length === 0) {
    return null;
  }

  return (
    <article className="authoring-wizard-card">
      <div className="authoring-editor-section__miniheader">
        <strong>Flow creation wizard</strong>
        <span>Start from a known business-flow shape instead of an empty step list.</span>
      </div>

      <div className="authoring-form-grid">
        <label>
          Concept
          <select value={conceptName} onChange={(event) => setConceptName(event.target.value)}>
            {entities.map((entity) => (
              <option key={entity.name} value={entity.name}>
                {entity.name}
              </option>
            ))}
          </select>
        </label>

        <label>
          Flow name
          <input value={flowName} onChange={(event) => setFlowName(event.target.value)} />
        </label>

        <label>
          Flow preset
          <select value={preset} onChange={(event) => setPreset(event.target.value as FlowPreset)}>
            <option value="submit-and-return">Submit and return</option>
            <option value="approval-lite">Approval-lite starter</option>
          </select>
        </label>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={() =>
            onCreateFlow({
              name: flowName || `Create${conceptName}`,
              input: {
                concept: conceptName,
                mode: "create"
              },
              action: {
                label: flowName || `Create ${conceptName}`
              },
              steps:
                preset === "approval-lite"
                  ? [
                      {
                        name: "validate-input",
                        type: "validate",
                        value: "$input"
                      },
                      {
                        name: "persist-request",
                        type: "callCapability",
                        scope: "persistence",
                        value: "$input"
                      },
                      {
                        name: "return-request",
                        type: "return",
                        value: "$input"
                      }
                    ]
                  : [
                      {
                        name: "validate-input",
                        type: "validate",
                        value: "$input"
                      },
                      {
                        name: "return-input",
                        type: "return",
                        value: "$input"
                      }
                    ]
            })
          }
        >
          Create flow from wizard
        </button>
      </div>
    </article>
  );
}
