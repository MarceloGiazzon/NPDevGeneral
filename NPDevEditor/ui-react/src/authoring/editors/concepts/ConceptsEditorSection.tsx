import React from "react";
import type { AuthoringEntity } from "../modelDocumentTypes";

type ConceptsEditorSectionProps = {
  entities: AuthoringEntity[];
  selectedConceptName: string | null;
  onSelectConcept: (conceptName: string) => void;
  onAddConcept: () => void;
  onRemoveConcept: (conceptName: string) => void;
  onMoveConcept: (conceptName: string, direction: -1 | 1) => void;
  onUpdateConcept: (conceptName: string, updater: (entity: AuthoringEntity) => AuthoringEntity) => void;
};

export default function ConceptsEditorSection({
  entities,
  selectedConceptName,
  onSelectConcept,
  onAddConcept,
  onRemoveConcept,
  onMoveConcept,
  onUpdateConcept
}: ConceptsEditorSectionProps): JSX.Element {
  const selectedConcept = entities.find((entity) => entity.name === selectedConceptName) ?? entities[0];
  const selectedIndex = entities.findIndex((entity) => entity.name === selectedConcept?.name);

  return (
    <section id="authoring-section-concepts" className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Concept editor</h3>
          <p>Manage the main business concepts and their top-level presentation metadata.</p>
        </div>
        <button type="button" onClick={onAddConcept}>
          Add concept
        </button>
      </div>

      <div className="authoring-editor-grid">
        <div className="authoring-editor-list">
          {entities.map((entity, index) => (
            <button
              key={entity.name}
              type="button"
              className={`authoring-editor-list__item ${entity.name === selectedConcept?.name ? "is-selected" : ""}`}
              onClick={() => onSelectConcept(entity.name)}
            >
              <strong>{entity.ui?.label ?? entity.name}</strong>
              <small>{entity.fields.length} fields</small>
              <span>
                {index + 1}. {entity.name}
              </span>
            </button>
          ))}
        </div>

        {selectedConcept ? (
          <div className="authoring-editor-card">
            <div className="authoring-editor-inline-actions">
              <button
                type="button"
                className="authoring-secondary-inline"
                disabled={selectedIndex <= 0}
                onClick={() => onMoveConcept(selectedConcept.name, -1)}
              >
                Move up
              </button>
              <button
                type="button"
                className="authoring-secondary-inline"
                disabled={selectedIndex >= entities.length - 1}
                onClick={() => onMoveConcept(selectedConcept.name, 1)}
              >
                Move down
              </button>
              <button
                type="button"
                className="authoring-ghost-button"
                disabled={entities.length <= 1}
                onClick={() => onRemoveConcept(selectedConcept.name)}
              >
                Remove concept
              </button>
            </div>

            <div className="authoring-form-grid">
              <label>
                Concept name
                <input
                  value={selectedConcept.name}
                  onChange={(event) => {
                    const nextName = event.target.value;
                    onUpdateConcept(selectedConcept.name, (entity) => ({
                      ...entity,
                      name: nextName
                    }));
                    onSelectConcept(nextName);
                  }}
                />
              </label>

              <label>
                Label
                <input
                  value={selectedConcept.ui?.label ?? ""}
                  onChange={(event) =>
                    onUpdateConcept(selectedConcept.name, (entity) => ({
                      ...entity,
                      ui: {
                        ...entity.ui,
                        label: event.target.value
                      }
                    }))
                  }
                />
              </label>

              <label>
                Group
                <input
                  value={selectedConcept.ui?.group ?? ""}
                  onChange={(event) =>
                    onUpdateConcept(selectedConcept.name, (entity) => ({
                      ...entity,
                      ui: {
                        ...entity.ui,
                        group: event.target.value
                      }
                    }))
                  }
                />
              </label>

              <label>
                Section
                <input
                  value={selectedConcept.ui?.section ?? ""}
                  onChange={(event) =>
                    onUpdateConcept(selectedConcept.name, (entity) => ({
                      ...entity,
                      ui: {
                        ...entity.ui,
                        section: event.target.value
                      }
                    }))
                  }
                />
              </label>
            </div>

            <label className="authoring-form-grid__full">
              Description
              <textarea
                rows={3}
                value={selectedConcept.ui?.description ?? ""}
                onChange={(event) =>
                  onUpdateConcept(selectedConcept.name, (entity) => ({
                    ...entity,
                    ui: {
                      ...entity.ui,
                      description: event.target.value
                    }
                  }))
                }
              />
            </label>

            <label className="authoring-form-grid__full">
              Help text
              <textarea
                rows={2}
                value={selectedConcept.ui?.helpText ?? ""}
                onChange={(event) =>
                  onUpdateConcept(selectedConcept.name, (entity) => ({
                    ...entity,
                    ui: {
                      ...entity.ui,
                      helpText: event.target.value
                    }
                  }))
                }
              />
            </label>
          </div>
        ) : null}
      </div>
    </section>
  );
}
