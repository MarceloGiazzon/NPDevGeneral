import React from "react";
import type { AuthoringEntity, AuthoringField } from "../editors/modelDocumentTypes";
import SimpleConditionBuilder from "../widgets/SimpleConditionBuilder";

type VisibilityConditionBuilderProps = {
  entity: AuthoringEntity;
  field: AuthoringField;
  onUpdateField: (updater: (field: AuthoringField) => AuthoringField) => void;
};

export default function VisibilityConditionBuilder({
  entity,
  field,
  onUpdateField
}: VisibilityConditionBuilderProps): JSX.Element {
  const fieldOptions = entity.fields.filter((entry) => entry.name !== field.name).map((entry) => entry.name);

  const applyUiCondition = (
    key: "visibleWhen" | "enabledWhen" | "readonlyWhen" | "requiredWhen",
    value?: string
  ): void => {
    onUpdateField((fieldDraft) => ({
      ...fieldDraft,
      ui: {
        ...fieldDraft.ui,
        [key]: value
      }
    }));
  };

  return (
    <div className="authoring-designer-stack">
      <div className="authoring-editor-section__miniheader">
        <strong>Visibility and interaction designer</strong>
        <span>Build common field conditions without writing every expression from scratch.</span>
      </div>

      <div className="authoring-template-grid">
        <SimpleConditionBuilder
          title="Visible when"
          value={field.ui?.visibleWhen}
          fieldOptions={fieldOptions}
          onChange={(value) => applyUiCondition("visibleWhen", value)}
        />
        <SimpleConditionBuilder
          title="Enabled when"
          value={field.ui?.enabledWhen}
          fieldOptions={fieldOptions}
          onChange={(value) => applyUiCondition("enabledWhen", value)}
        />
        <SimpleConditionBuilder
          title="Readonly when"
          value={field.ui?.readonlyWhen}
          fieldOptions={fieldOptions}
          onChange={(value) => applyUiCondition("readonlyWhen", value)}
        />
        <SimpleConditionBuilder
          title="Required when"
          value={field.ui?.requiredWhen}
          fieldOptions={fieldOptions}
          onChange={(value) => applyUiCondition("requiredWhen", value)}
        />
      </div>
    </div>
  );
}
