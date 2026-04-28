import React from "react";
import type { AuthoringRuleProfile } from "../modelDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";

type RuleProfilesEditorSectionProps = {
  ruleProfiles: AuthoringRuleProfile[];
  conceptNames: string[];
  onChange: (ruleProfiles: AuthoringRuleProfile[]) => void;
};

export default function RuleProfilesEditorSection({
  ruleProfiles,
  conceptNames,
  onChange
}: RuleProfilesEditorSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Rule Profiles</h3>
          <p>Keep semantic rule groupings explicit instead of burying them in ad hoc editor or runtime defaults.</p>
        </div>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          onClick={() =>
            onChange([
              ...ruleProfiles,
              {
                name: "interactive",
                concept: conceptNames[0] ?? "",
                enabled: true,
                appliesTo: []
              }
            ])
          }
        >
          Add rule profile
        </button>
      </div>

      <div className="authoring-editor-stack">
        {ruleProfiles.length === 0 ? (
          <article className="authoring-subcard">
            <p>No rule profiles yet. Add them when different execution modes need explicit rule scope.</p>
          </article>
        ) : (
          ruleProfiles.map((profile, profileIndex) => (
            <article key={`${profile.name}-${profileIndex}`} className="authoring-subcard">
              <div className="authoring-preview-card__header">
                <strong>{profile.name || `Rule profile ${profileIndex + 1}`}</strong>
                <button
                  type="button"
                  className="authoring-ghost-button"
                  onClick={() => onChange(ruleProfiles.filter((_, index) => index !== profileIndex))}
                >
                  Remove
                </button>
              </div>

              <div className="authoring-form-grid">
                <label>
                  Name
                  <input
                    value={profile.name}
                    onChange={(event) =>
                      onChange(
                        ruleProfiles.map((entry, index) =>
                          index === profileIndex
                            ? {
                                ...entry,
                                name: event.target.value
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>

                <label>
                  Concept
                  <select
                    value={profile.concept ?? ""}
                    onChange={(event) =>
                      onChange(
                        ruleProfiles.map((entry, index) =>
                          index === profileIndex
                            ? {
                                ...entry,
                                concept: event.target.value || undefined
                              }
                            : entry
                        )
                      )
                    }
                  >
                    <option value="">Any concept</option>
                    {conceptNames.map((conceptName) => (
                      <option key={conceptName} value={conceptName}>
                        {conceptName}
                      </option>
                    ))}
                  </select>
                </label>

                <label>
                  Applies to
                  <input
                    value={joinTextList(profile.appliesTo)}
                    onChange={(event) =>
                      onChange(
                        ruleProfiles.map((entry, index) =>
                          index === profileIndex
                            ? {
                                ...entry,
                                appliesTo: parseTextList(event.target.value)
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>

                <label>
                  Permissions
                  <input
                    value={joinTextList(profile.permissionRequirements ?? profile.permissions)}
                    onChange={(event) =>
                      onChange(
                        ruleProfiles.map((entry, index) =>
                          index === profileIndex
                            ? {
                                ...entry,
                                permissionRequirements: parseTextList(event.target.value),
                                permissions: undefined
                              }
                            : entry
                        )
                      )
                    }
                  />
                </label>
              </div>

              <label>
                Description
                <textarea
                  value={profile.description ?? ""}
                  onChange={(event) =>
                    onChange(
                      ruleProfiles.map((entry, index) =>
                        index === profileIndex
                          ? {
                              ...entry,
                              description: event.target.value || undefined
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>

              <label>
                <input
                  type="checkbox"
                  checked={profile.enabled ?? true}
                  onChange={(event) =>
                    onChange(
                      ruleProfiles.map((entry, index) =>
                        index === profileIndex
                          ? {
                              ...entry,
                              enabled: event.target.checked
                            }
                          : entry
                      )
                    )
                  }
                />
                Enabled
              </label>
            </article>
          ))
        )}
      </div>
    </section>
  );
}
