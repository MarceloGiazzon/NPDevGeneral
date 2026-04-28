import type { UiActionMetadata } from "../../types";
import type {
  AuthoringEntity,
  AuthoringFlow,
  AuthoringLifecycleTransition,
  AuthoringModelDocument,
  AuthoringOrchestrationRule
} from "../editors/modelDocumentTypes";

export type ExplainabilityInsight = {
  title: string;
  kind: "invariant" | "flow" | "action";
  summary: string;
  details: string[];
};

function explainFlowStepType(stepType: string): string {
  switch (stepType) {
    case "validate":
      return "Validates business input before the flow continues.";
    case "callCapability":
      return "Calls a declared capability so runtime behavior stays explicit.";
    case "return":
      return "Returns a result to the caller or next stage.";
    case "waitForEvent":
      return "Pauses execution until a later business event resumes the flow.";
    default:
      return "Performs a declared flow step in the canonical runtime sequence.";
  }
}

function actionReasonSummary(title: string, metadata: UiActionMetadata | undefined): ExplainabilityInsight {
  return {
    title,
    kind: "action",
    summary: metadata?.label
      ? `This action exists because the model declares an operator-facing action named "${metadata.label}".`
      : "This action exists because the model exposes a business operation that needs an operator-facing surface.",
    details: [
      metadata?.permissionHint ? `Permission hint: ${metadata.permissionHint}` : "Permission hint is not set.",
      metadata?.confirmationText ? `Confirmation: ${metadata.confirmationText}` : "No confirmation text is set.",
      metadata?.successMessage ? `Success message: ${metadata.successMessage}` : "No success message is set.",
      metadata?.failureHint ? `Failure hint: ${metadata.failureHint}` : "No failure hint is set."
    ]
  };
}

export function buildInvariantMeaningEntries(entity: AuthoringEntity | null): ExplainabilityInsight[] {
  if (!entity) {
    return [];
  }

  return (entity.invariants ?? []).map((invariant) => ({
    title: `${entity.name}.${invariant.name}`,
    kind: "invariant",
    summary:
      invariant.type === "unique"
        ? `This invariant prevents duplicate combinations for ${invariant.fields?.join(", ") || "the selected fields"}.`
        : `This invariant evaluates the rule expression so ${entity.name} records stay valid.`,
    details: [
      invariant.fields?.length ? `Scoped fields: ${invariant.fields.join(", ")}` : "No specific fields are listed.",
      invariant.expression || invariant.expr ? `Expression: ${invariant.expression ?? invariant.expr}` : "No expression is set yet."
    ]
  }));
}

export function buildFlowExplanationEntries(document: AuthoringModelDocument): ExplainabilityInsight[] {
  return (document.flows ?? []).map((flow) => ({
    title: flow.name,
    kind: "flow",
    summary: `This flow exists to perform ${flow.input?.mode ?? "business"} work for ${flow.input?.concept ?? "the selected concept"}.`,
    details: (flow.steps ?? []).map((step) => `${step.name}: ${explainFlowStepType(step.type)}`)
  }));
}

export function buildActionReasonEntries(
  entity: AuthoringEntity | null,
  flows: AuthoringFlow[],
  orchestrationRules: AuthoringOrchestrationRule[] | undefined
): ExplainabilityInsight[] {
  const insights: ExplainabilityInsight[] = [];

  for (const flow of flows ?? []) {
    insights.push(actionReasonSummary(`Flow action: ${flow.name}`, flow.action));
  }

  for (const transition of entity?.lifecycle?.transitions ?? []) {
    insights.push(
      actionReasonSummary(
        `Transition action: ${transition.from} -> ${transition.to}`,
        {
          ...transition.action,
          label: transition.action?.label ?? transition.actionLabel
        }
      )
    );
  }

  for (const rule of orchestrationRules ?? []) {
    for (const action of rule.actions ?? []) {
      insights.push(actionReasonSummary(`Orchestration action: ${rule.name} / ${action.type}`, action.action));
    }
  }

  return insights;
}
