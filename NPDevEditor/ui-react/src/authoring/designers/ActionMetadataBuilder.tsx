import React from "react";
import type { UiActionMetadata } from "../../types";
import SimpleConditionBuilder from "../widgets/SimpleConditionBuilder";

type ActionMetadataBuilderProps = {
  title: string;
  metadata?: UiActionMetadata;
  conditionFieldOptions: string[];
  onChange: (metadata: UiActionMetadata) => void;
};

const DANGER_LEVELS: Array<NonNullable<UiActionMetadata["dangerLevel"]>> = ["low", "medium", "high", "critical"];

export default function ActionMetadataBuilder({
  title,
  metadata,
  conditionFieldOptions,
  onChange
}: ActionMetadataBuilderProps): JSX.Element {
  const action = metadata ?? {};

  return (
    <article className="authoring-designer-card">
      <div className="authoring-preview-card__header">
        <strong>{title}</strong>
        <span>Action builder</span>
      </div>

      <div className="authoring-form-grid">
        <label>
          Label
          <input value={action.label ?? ""} onChange={(event) => onChange({ ...action, label: event.target.value || undefined })} />
        </label>

        <label>
          Permission hint
          <input
            value={action.permissionHint ?? ""}
            onChange={(event) => onChange({ ...action, permissionHint: event.target.value || undefined })}
          />
        </label>

        <label>
          Danger level
          <select
            value={action.dangerLevel ?? "low"}
            onChange={(event) => onChange({ ...action, dangerLevel: event.target.value as UiActionMetadata["dangerLevel"] })}
          >
            {DANGER_LEVELS.map((level) => (
              <option key={level} value={level}>
                {level}
              </option>
            ))}
          </select>
        </label>

        <label>
          Input form hint
          <input
            value={action.inputFormHint ?? ""}
            onChange={(event) => onChange({ ...action, inputFormHint: event.target.value || undefined })}
          />
        </label>
      </div>

      <label className="authoring-form-grid__full">
        Confirmation text
        <input
          value={action.confirmationText ?? ""}
          onChange={(event) => onChange({ ...action, confirmationText: event.target.value || undefined })}
        />
      </label>

      <div className="authoring-form-grid">
        <label>
          Success message
          <input
            value={action.successMessage ?? ""}
            onChange={(event) => onChange({ ...action, successMessage: event.target.value || undefined })}
          />
        </label>

        <label>
          Failure hint
          <input
            value={action.failureHint ?? ""}
            onChange={(event) => onChange({ ...action, failureHint: event.target.value || undefined })}
          />
        </label>
      </div>

      <SimpleConditionBuilder
        title="Visible when"
        value={action.visibleWhen}
        fieldOptions={conditionFieldOptions}
        onChange={(value) => onChange({ ...action, visibleWhen: value })}
      />
    </article>
  );
}
