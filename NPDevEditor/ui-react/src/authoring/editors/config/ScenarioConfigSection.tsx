import React from "react";
import type { AuthoringConfigDocument } from "../../config/configDocumentTypes";

type ScenarioConfigSectionProps = {
  document: AuthoringConfigDocument;
  onChange: (document: AuthoringConfigDocument) => void;
};

export default function ScenarioConfigSection({
  document,
  onChange
}: ScenarioConfigSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Scenario settings</h3>
          <p>Control the scenario identity, description, and output root that anchor the config file.</p>
        </div>
      </div>

      <div className="authoring-form-grid">
        <label>
          Scenario name
          <input
            value={document.scenario.name}
            onChange={(event) =>
              onChange({
                ...document,
                scenario: {
                  ...document.scenario,
                  name: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          Output root
          <input
            value={document.scenario.outputRoot}
            onChange={(event) =>
              onChange({
                ...document,
                scenario: {
                  ...document.scenario,
                  outputRoot: event.target.value
                }
              })
            }
          />
        </label>
      </div>

      <label className="authoring-form-grid__full">
        Scenario description
        <textarea
          rows={3}
          value={document.scenario.description ?? ""}
          onChange={(event) =>
            onChange({
              ...document,
              scenario: {
                ...document.scenario,
                description: event.target.value
              }
            })
          }
        />
      </label>
    </section>
  );
}
