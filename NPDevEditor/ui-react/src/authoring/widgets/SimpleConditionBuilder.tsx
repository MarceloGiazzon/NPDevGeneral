import React, { useState } from "react";

type ConditionOperator = "equals" | "not-equals" | "is-set" | "is-not-set";

type SimpleConditionBuilderProps = {
  title: string;
  value?: string;
  fieldOptions: string[];
  onChange: (value?: string) => void;
};

function formatConditionValue(rawValue: string): string {
  const trimmed = rawValue.trim();
  if (trimmed === "") {
    return '""';
  }
  if (/^(true|false|null)$/i.test(trimmed) || /^-?\d+(\.\d+)?$/.test(trimmed)) {
    return trimmed;
  }
  return `"${trimmed.replace(/"/g, '\\"')}"`;
}

function buildConditionExpression(fieldName: string, operator: ConditionOperator, compareValue: string): string {
  switch (operator) {
    case "equals":
      return `${fieldName} == ${formatConditionValue(compareValue)}`;
    case "not-equals":
      return `${fieldName} != ${formatConditionValue(compareValue)}`;
    case "is-set":
      return `${fieldName} != null`;
    case "is-not-set":
      return `${fieldName} == null`;
    default:
      return "";
  }
}

export default function SimpleConditionBuilder({
  title,
  value,
  fieldOptions,
  onChange
}: SimpleConditionBuilderProps): JSX.Element {
  const [fieldName, setFieldName] = useState<string>(fieldOptions[0] ?? "");
  const [operator, setOperator] = useState<ConditionOperator>("equals");
  const [compareValue, setCompareValue] = useState<string>("");

  return (
    <div className="authoring-designer-card">
      <div className="authoring-editor-section__miniheader">
        <strong>{title}</strong>
        <span>Condition builder</span>
      </div>

      <div className="authoring-inline-grid">
        <select value={fieldName} onChange={(event) => setFieldName(event.target.value)}>
          {fieldOptions.length === 0 ? <option value="">No fields available</option> : null}
          {fieldOptions.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>

        <select value={operator} onChange={(event) => setOperator(event.target.value as ConditionOperator)}>
          <option value="equals">equals</option>
          <option value="not-equals">not equals</option>
          <option value="is-set">is set</option>
          <option value="is-not-set">is not set</option>
        </select>

        <input
          value={compareValue}
          placeholder={operator === "is-set" || operator === "is-not-set" ? "No value needed" : "Comparison value"}
          disabled={operator === "is-set" || operator === "is-not-set"}
          onChange={(event) => setCompareValue(event.target.value)}
        />
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          className="authoring-secondary-inline"
          disabled={!fieldName}
          onClick={() => onChange(buildConditionExpression(fieldName, operator, compareValue))}
        >
          Apply builder expression
        </button>
        <button type="button" className="authoring-ghost-button" onClick={() => onChange(undefined)}>
          Clear condition
        </button>
      </div>

      <label className="authoring-form-grid__full">
        Canonical expression
        <input
          value={value ?? ""}
          placeholder={'field == "value"'}
          onChange={(event) => onChange(event.target.value || undefined)}
        />
      </label>
    </div>
  );
}
