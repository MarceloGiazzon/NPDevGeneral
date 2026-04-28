import React from "react";
import type { AuthoringEnumOption } from "../editors/modelDocumentTypes";
import { moveItem } from "../editors/editorUtils";

type EnumConfigurationDesignerProps = {
  options: AuthoringEnumOption[];
  onChange: (options: AuthoringEnumOption[]) => void;
};

const BADGE_OPTIONS = ["neutral", "info", "success", "warning", "danger"];
const ICON_OPTIONS = ["circle", "clock", "check", "x", "flag", "calendar"];

export default function EnumConfigurationDesigner({
  options,
  onChange
}: EnumConfigurationDesignerProps): JSX.Element {
  return (
    <div className="authoring-designer-stack">
      <div className="authoring-editor-section__miniheader">
        <strong>Enum configuration designer</strong>
        <span>Configure visual hints, grouping, defaults, and ordering with guided controls.</span>
      </div>

      <div className="authoring-designer-stack">
        {options.map((option, index) => (
          <article key={`${option.value}-${index}`} className="authoring-designer-card">
            <div className="authoring-preview-card__header">
              <strong>{option.label ?? option.value}</strong>
              <span>{option.value}</span>
            </div>

            <div className="authoring-form-grid">
              <label>
                Value
                <input
                  value={option.value}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) =>
                        entryIndex === index
                          ? {
                              ...entry,
                              value: event.target.value
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>

              <label>
                Label
                <input
                  value={option.label ?? ""}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) =>
                        entryIndex === index
                          ? {
                              ...entry,
                              label: event.target.value
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>

              <label>
                Badge
                <select
                  value={option.badge ?? ""}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) =>
                        entryIndex === index
                          ? {
                              ...entry,
                              badge: event.target.value || undefined
                            }
                          : entry
                      )
                    )
                  }
                >
                  <option value="">None</option>
                  {BADGE_OPTIONS.map((badge) => (
                    <option key={badge} value={badge}>
                      {badge}
                    </option>
                  ))}
                </select>
              </label>

              <label>
                Icon hint
                <select
                  value={option.iconHint ?? ""}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) =>
                        entryIndex === index
                          ? {
                              ...entry,
                              iconHint: event.target.value || undefined
                            }
                          : entry
                      )
                    )
                  }
                >
                  <option value="">None</option>
                  {ICON_OPTIONS.map((icon) => (
                    <option key={icon} value={icon}>
                      {icon}
                    </option>
                  ))}
                </select>
              </label>

              <label>
                Group
                <input
                  value={option.group ?? ""}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) =>
                        entryIndex === index
                          ? {
                              ...entry,
                              group: event.target.value || undefined
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>

              <label>
                Order
                <input
                  type="number"
                  value={option.order ?? (index + 1) * 10}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) =>
                        entryIndex === index
                          ? {
                              ...entry,
                              order: Number(event.target.value)
                            }
                          : entry
                      )
                    )
                  }
                />
              </label>
            </div>

            <label className="authoring-form-grid__full">
              Description
              <input
                value={option.description ?? ""}
                onChange={(event) =>
                  onChange(
                    options.map((entry, entryIndex) =>
                      entryIndex === index
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

            <div className="authoring-toggle-row">
              <label>
                <input
                  type="checkbox"
                  checked={Boolean(option.default)}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) => ({
                        ...entry,
                        default: entryIndex === index ? event.target.checked : false
                      }))
                    )
                  }
                />
                Default option
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={Boolean(option.deprecated)}
                  onChange={(event) =>
                    onChange(
                      options.map((entry, entryIndex) =>
                        entryIndex === index
                          ? {
                              ...entry,
                              deprecated: event.target.checked
                            }
                          : entry
                      )
                    )
                  }
                />
                Deprecated
              </label>
            </div>

            <div className="authoring-inline-actions">
              <button type="button" className="authoring-secondary-inline" disabled={index === 0} onClick={() => onChange(moveItem(options, index, -1))}>
                Move up
              </button>
              <button
                type="button"
                className="authoring-secondary-inline"
                disabled={index === options.length - 1}
                onClick={() => onChange(moveItem(options, index, 1))}
              >
                Move down
              </button>
              <button
                type="button"
                className="authoring-ghost-button"
                onClick={() => onChange(options.filter((_, entryIndex) => entryIndex !== index))}
              >
                Remove option
              </button>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
