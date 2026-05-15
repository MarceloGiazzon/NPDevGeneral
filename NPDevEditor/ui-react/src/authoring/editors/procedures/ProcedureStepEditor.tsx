import type { AuthoringProcedure, AuthoringProcedureStep } from "../modelDocumentTypes";

type ProcedureStepEditorProps = {
  procedures: AuthoringProcedure[];
  procedure: AuthoringProcedure;
  procedureIndex: number;
  step: AuthoringProcedureStep;
  stepIndex: number;
  conceptNames: string[];
  queryNames: string[];
  procedureNames: string[];
  stepTypes: string[];
  onChange: (procedures: AuthoringProcedure[]) => void;
};

export default function ProcedureStepEditor({
  procedures,
  procedure,
  procedureIndex,
  step,
  stepIndex,
  conceptNames,
  queryNames,
  procedureNames,
  stepTypes,
  onChange
}: ProcedureStepEditorProps): JSX.Element {
  const updateStep = (updater: (step: AuthoringProcedureStep) => AuthoringProcedureStep): void => {
    onChange(
      procedures.map((entry, index) =>
        index === procedureIndex
          ? {
              ...entry,
              steps: (entry.steps ?? []).map((stepEntry, nestedIndex) =>
                nestedIndex === stepIndex ? updater(stepEntry) : stepEntry
              )
            }
          : entry
      )
    );
  };

  return (
    <article className="authoring-subcard">
      <div className="authoring-preview-card__header">
        <strong>{step.name ?? `Step ${stepIndex + 1}`}</strong>
        <button
          type="button"
          className="authoring-ghost-button"
          onClick={() =>
            onChange(
              procedures.map((entry, index) =>
                index === procedureIndex
                  ? {
                      ...entry,
                      steps: (entry.steps ?? []).filter((_, nestedIndex) => nestedIndex !== stepIndex)
                    }
                  : entry
              )
            )
          }
        >
          Remove step
        </button>
      </div>

      <div className="authoring-form-grid">
        <label>
          Step name
          <input value={step.name ?? ""} onChange={(event) => updateStep((entry) => ({ ...entry, name: event.target.value || undefined }))} />
        </label>
        <label>
          Type
          <select value={step.type} onChange={(event) => updateStep((entry) => ({ ...entry, type: event.target.value }))}>
            {stepTypes.map((stepType) => (
              <option key={stepType} value={stepType}>
                {stepType}
              </option>
            ))}
          </select>
        </label>
        <StepOptionSelect label="Concept" value={step.concept ?? ""} options={conceptNames} onChange={(value) => updateStep((entry) => ({ ...entry, concept: value || undefined }))} />
        <StepOptionSelect label="Query" value={step.query ?? ""} options={queryNames} onChange={(value) => updateStep((entry) => ({ ...entry, query: value || undefined }))} />
        <StepOptionSelect
          label="Procedure"
          value={step.procedure ?? ""}
          options={procedureNames.filter((procedureName) => procedureName !== procedure.name)}
          onChange={(value) => updateStep((entry) => ({ ...entry, procedure: value || undefined }))}
        />
      </div>
    </article>
  );
}

function StepOptionSelect({
  label,
  value,
  options,
  onChange
}: {
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
}): JSX.Element {
  return (
    <label>
      {label}
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">None</option>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </label>
  );
}
