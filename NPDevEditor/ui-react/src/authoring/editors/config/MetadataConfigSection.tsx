import React from "react";
import type { AuthoringCapabilityBinding, AuthoringConfigDocument } from "../../config/configDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";

type MetadataConfigSectionProps = {
  document: AuthoringConfigDocument;
  onChange: (document: AuthoringConfigDocument) => void;
};

function ensureCapabilityBindings(document: AuthoringConfigDocument): AuthoringCapabilityBinding[] {
  return document.metadata?.capabilityBindings ?? [];
}

export default function MetadataConfigSection({
  document,
  onChange
}: MetadataConfigSectionProps): JSX.Element {
  const capabilityBindings = ensureCapabilityBindings(document);

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Metadata, bindings, and presets</h3>
          <p>Keep guided projection preferences, default permissions, and capability-binding notes in a structured lane.</p>
        </div>
      </div>

      <div className="authoring-form-grid">
        <label>
          Projection preset
          <input
            value={document.metadata?.projectionPreset ?? ""}
            onChange={(event) =>
              onChange({
                ...document,
                metadata: {
                  ...document.metadata,
                  projectionPreset: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          Sample preset label
          <input
            value={document.metadata?.samplePresetLabel ?? ""}
            onChange={(event) =>
              onChange({
                ...document,
                metadata: {
                  ...document.metadata,
                  samplePresetLabel: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          Default role
          <input
            value={document.metadata?.permissionDefaults?.defaultRole ?? ""}
            onChange={(event) =>
              onChange({
                ...document,
                metadata: {
                  ...document.metadata,
                  permissionDefaults: {
                    ...document.metadata?.permissionDefaults,
                    defaultRole: event.target.value
                  }
                }
              })
            }
          />
        </label>
      </div>

      <div className="authoring-form-grid">
        <label>
          Readonly roles
          <input
            value={joinTextList(document.metadata?.permissionDefaults?.readonlyRoles)}
            onChange={(event) =>
              onChange({
                ...document,
                metadata: {
                  ...document.metadata,
                  permissionDefaults: {
                    ...document.metadata?.permissionDefaults,
                    readonlyRoles: parseTextList(event.target.value)
                  }
                }
              })
            }
          />
        </label>

        <label>
          Hidden actions
          <input
            value={joinTextList(document.metadata?.permissionDefaults?.hiddenActions)}
            onChange={(event) =>
              onChange({
                ...document,
                metadata: {
                  ...document.metadata,
                  permissionDefaults: {
                    ...document.metadata?.permissionDefaults,
                    hiddenActions: parseTextList(event.target.value)
                  }
                }
              })
            }
          />
        </label>
      </div>

      <label className="authoring-form-grid__full">
        Notes
        <textarea
          rows={3}
          value={document.metadata?.notes ?? ""}
          onChange={(event) =>
            onChange({
              ...document,
              metadata: {
                ...document.metadata,
                notes: event.target.value
              }
            })
          }
        />
      </label>

      <div className="authoring-subcard">
        <div className="authoring-editor-section__miniheader">
          <strong>Capability bindings</strong>
          <button
            type="button"
            className="authoring-secondary-inline"
            onClick={() =>
              onChange({
                ...document,
                metadata: {
                  ...document.metadata,
                  capabilityBindings: [
                    ...capabilityBindings,
                    {
                      capability: "",
                      target: "",
                      mode: "default"
                    }
                  ]
                }
              })
            }
          >
            Add binding
          </button>
        </div>

        <table className="grid-table compact">
          <thead>
            <tr>
              <th>Capability</th>
              <th>Target</th>
              <th>Mode</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {capabilityBindings.map((binding, index) => (
              <tr key={`${binding.capability}-${binding.target}-${index}`}>
                <td>
                  <input
                    value={binding.capability}
                    onChange={(event) =>
                      onChange({
                        ...document,
                        metadata: {
                          ...document.metadata,
                          capabilityBindings: capabilityBindings.map((entry, entryIndex) =>
                            entryIndex === index
                              ? {
                                  ...entry,
                                  capability: event.target.value
                                }
                              : entry
                          )
                        }
                      })
                    }
                  />
                </td>
                <td>
                  <input
                    value={binding.target}
                    onChange={(event) =>
                      onChange({
                        ...document,
                        metadata: {
                          ...document.metadata,
                          capabilityBindings: capabilityBindings.map((entry, entryIndex) =>
                            entryIndex === index
                              ? {
                                  ...entry,
                                  target: event.target.value
                                }
                              : entry
                          )
                        }
                      })
                    }
                  />
                </td>
                <td>
                  <input
                    value={binding.mode}
                    onChange={(event) =>
                      onChange({
                        ...document,
                        metadata: {
                          ...document.metadata,
                          capabilityBindings: capabilityBindings.map((entry, entryIndex) =>
                            entryIndex === index
                              ? {
                                  ...entry,
                                  mode: event.target.value
                                }
                              : entry
                          )
                        }
                      })
                    }
                  />
                </td>
                <td>
                  <button
                    type="button"
                    className="authoring-ghost-button"
                    onClick={() =>
                      onChange({
                        ...document,
                        metadata: {
                          ...document.metadata,
                          capabilityBindings: capabilityBindings.filter((_, entryIndex) => entryIndex !== index)
                        }
                      })
                    }
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
