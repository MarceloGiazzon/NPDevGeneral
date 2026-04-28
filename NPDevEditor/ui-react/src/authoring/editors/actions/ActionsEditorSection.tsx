import React from "react";
import type {
  AuthoringEntity,
  AuthoringFlow,
  AuthoringLifecycleTransition,
  AuthoringOrchestrationRule
} from "../modelDocumentTypes";
import ActionMetadataBuilder from "../../designers/ActionMetadataBuilder";
import ExplainabilityTooltip from "../../help/ExplainabilityTooltip";

type ActionsEditorSectionProps = {
  entity: AuthoringEntity | null;
  flows: AuthoringFlow[];
  orchestrationRules: AuthoringOrchestrationRule[];
  onChangeFlows: (flows: AuthoringFlow[]) => void;
  onChangeEntityTransitions: (transitions: AuthoringLifecycleTransition[]) => void;
  onChangeOrchestrationRules: (rules: AuthoringOrchestrationRule[]) => void;
};

export default function ActionsEditorSection({
  entity,
  flows,
  orchestrationRules,
  onChangeFlows,
  onChangeEntityTransitions,
  onChangeOrchestrationRules
}: ActionsEditorSectionProps): JSX.Element {
  const transitions = entity?.lifecycle?.transitions ?? [];
  const conditionFieldOptions = entity?.fields.map((field) => field.name) ?? [];

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Action metadata</h3>
          <p>Guide confirmations, success/failure hints, permission hints, and operator-facing labels.</p>
        </div>
        <ExplainabilityTooltip
          title="Why this action exists"
          detail="Action metadata exists so the platform can explain business operations to operators instead of exposing opaque buttons or transitions."
        />
      </div>

      <div className="authoring-editor-stack">
        <article className="authoring-subcard">
          <div className="authoring-editor-section__miniheader">
            <strong>Flow actions</strong>
          </div>
          {flows.map((flow, flowIndex) => (
            <ActionMetadataBuilder
              key={`${flow.name}-${flowIndex}`}
              title={`Flow action: ${flow.name}`}
              metadata={flow.action}
              conditionFieldOptions={conditionFieldOptions}
              onChange={(metadata) =>
                onChangeFlows(
                  flows.map((entry, entryIndex) =>
                    entryIndex === flowIndex
                      ? {
                          ...entry,
                          action: metadata
                        }
                      : entry
                  )
                )
              }
            />
          ))}
        </article>

        {entity ? (
          <article className="authoring-subcard">
            <div className="authoring-editor-section__miniheader">
              <strong>Transition actions for {entity.name}</strong>
            </div>
            {transitions.map((transition, transitionIndex) => (
              <ActionMetadataBuilder
                key={`${transition.from}-${transition.to}-${transitionIndex}`}
                title={`Transition action: ${transition.from} -> ${transition.to}`}
                metadata={{
                  ...transition.action,
                  label: transition.action?.label ?? transition.actionLabel
                }}
                conditionFieldOptions={conditionFieldOptions}
                onChange={(metadata) =>
                  onChangeEntityTransitions(
                    transitions.map((entry, entryIndex) =>
                      entryIndex === transitionIndex
                        ? {
                            ...entry,
                            actionLabel: metadata.label,
                            action: metadata
                          }
                        : entry
                    )
                  )
                }
              />
            ))}
          </article>
        ) : null}

        <article className="authoring-subcard">
          <div className="authoring-editor-section__miniheader">
            <strong>Orchestration actions</strong>
          </div>
          {orchestrationRules.map((rule, ruleIndex) =>
            (rule.actions ?? []).map((action, actionIndex) => (
              <ActionMetadataBuilder
                key={`${rule.name}-${actionIndex}`}
                title={`Orchestration action: ${rule.name} / ${action.type}`}
                metadata={action.action}
                conditionFieldOptions={conditionFieldOptions}
                onChange={(metadata) =>
                  onChangeOrchestrationRules(
                    orchestrationRules.map((entry, entryIndex) =>
                      entryIndex === ruleIndex
                        ? {
                            ...entry,
                            actions: (entry.actions ?? []).map((actionEntry, currentActionIndex) =>
                              currentActionIndex === actionIndex
                                ? {
                                    ...actionEntry,
                                    action: metadata
                                  }
                                : actionEntry
                            )
                          }
                        : entry
                    )
                  )
                }
              />
            ))
          )}
        </article>
      </div>
    </section>
  );
}
