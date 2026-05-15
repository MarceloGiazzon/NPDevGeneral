import type { AuthoringFlow, AuthoringFlowStep } from "../modelDocumentTypes";

type FlowStepsTableProps = {
  flows: AuthoringFlow[];
  flowIndex: number;
  flow: AuthoringFlow;
  onChange: (flows: AuthoringFlow[]) => void;
};

export default function FlowStepsTable({
  flows,
  flowIndex,
  flow,
  onChange
}: FlowStepsTableProps): JSX.Element {
  const updateStep = (
    stepIndex: number,
    updater: (step: AuthoringFlowStep) => AuthoringFlowStep
  ): void => {
    onChange(
      flows.map((entry, entryIndex) =>
        entryIndex === flowIndex
          ? {
              ...entry,
              steps: (entry.steps ?? []).map((stepEntry, currentStepIndex) =>
                currentStepIndex === stepIndex ? updater(stepEntry) : stepEntry
              )
            }
          : entry
      )
    );
  };

  return (
    <table className="grid-table compact">
      <thead>
        <tr>
          <th>Name</th>
          <th>Type</th>
          <th>Scope</th>
          <th>Signal</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {(flow.steps ?? []).map((step, stepIndex) => (
          <tr key={`${step.name}-${stepIndex}`}>
            <td>
              <input value={step.name} onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, name: event.target.value }))} />
            </td>
            <td>
              <input value={step.type} onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, type: event.target.value }))} />
            </td>
            <td>
              <input value={step.scope ?? ""} onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, scope: event.target.value }))} />
            </td>
            <td>
              <input value={step.value ?? step.event ?? step.condition ?? ""} onChange={(event) => updateStep(stepIndex, (entry) => ({ ...entry, value: event.target.value }))} />
            </td>
            <td>
              <button
                type="button"
                className="authoring-ghost-button"
                onClick={() =>
                  onChange(
                    flows.map((entry, entryIndex) =>
                      entryIndex === flowIndex
                        ? {
                            ...entry,
                            steps: (entry.steps ?? []).filter((_, currentStepIndex) => currentStepIndex !== stepIndex)
                          }
                        : entry
                    )
                  )
                }
              >
                Remove
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
