import React from "react";
import type { AuthoringWorkspaceSeed } from "../services/modelLoader";
import type {
  AuthoringDocumentSession,
  AuthoringEntity,
  AuthoringField,
  AuthoringFlow,
  AuthoringLifecycleTransition,
  AuthoringModelDocument,
  AuthoringOrchestrationRule
} from "./modelDocumentTypes";
import { cloneDocument, moveItem, prettyDocumentJson, updateEntity, updateField } from "./editorUtils";
import { downloadModelDocument, serializeModelDocument } from "../services/modelDocumentService";
import { useSynchronizedJsonEditor } from "../json/useSynchronizedJsonEditor";
import RawJsonEditorPanel from "../json/RawJsonEditorPanel";
import ConceptsEditorSection from "./concepts/ConceptsEditorSection";
import DomainTypesEditorSection from "./domainTypes/DomainTypesEditorSection";
import FieldsEditorSection from "./fields/FieldsEditorSection";
import EnumsEditorSection from "./enums/EnumsEditorSection";
import ReferencesEditorSection from "./references/ReferencesEditorSection";
import InvariantsEditorSection from "./invariants/InvariantsEditorSection";
import FlowsEditorSection from "./flows/FlowsEditorSection";
import QueriesEditorSection from "./queries/QueriesEditorSection";
import RuleProfilesEditorSection from "./ruleProfiles/RuleProfilesEditorSection";
import ProceduresEditorSection from "./procedures/ProceduresEditorSection";
import PanelsEditorSection from "./panels/PanelsEditorSection";
import StateMachinesEditorSection from "./stateMachines/StateMachinesEditorSection";
import ActionsEditorSection from "./actions/ActionsEditorSection";
import MetadataEditorSection from "./metadata/MetadataEditorSection";
import { validateModelDocument } from "./modelValidation";
import InlineValidationSummary from "../validation/InlineValidationSummary";
import { buildModelValidationDiagnostics } from "../validation/authoringValidation";
import { mergeValidationDiagnostics, useServerValidation } from "../validation/useServerValidation";
import ContextualHelpPanel from "../onboarding/ContextualHelpPanel";
import ConceptCreationWizard from "../onboarding/ConceptCreationWizard";
import ReferenceWizard from "../onboarding/ReferenceWizard";
import FlowCreationWizard from "../onboarding/FlowCreationWizard";
import { buildActionReasonEntries, buildFlowExplanationEntries, buildInvariantMeaningEntries } from "../explainability/modelExplainability";
import ExplainabilityTooltip from "../help/ExplainabilityTooltip";
import SemanticGraphPanel from "../graph/SemanticGraphPanel";
import { buildSemanticGraph } from "../graph/semanticGraph";

type FormBasedModelEditorProps = {
  workspace: AuthoringWorkspaceSeed;
  documentSession: AuthoringDocumentSession | null;
  selectedConceptName: string | null;
  focusedFieldName?: string | null;
  focusedSection?: string | null;
  onSelectConcept: (conceptName: string) => void;
  onUpdateDocument: (document: AuthoringModelDocument) => void;
};

export default function FormBasedModelEditor({
  workspace,
  documentSession,
  selectedConceptName,
  focusedFieldName,
  focusedSection,
  onSelectConcept,
  onUpdateDocument
}: FormBasedModelEditorProps): JSX.Element {
  if (!documentSession) {
    return (
      <div className="authoring-route-card">
        <div className="authoring-route-card__header">
          <div>
            <h3>Loading model editor</h3>
            <p>The authoring workspace is preparing a guided document session for the selected model source.</p>
          </div>
          <div className="authoring-badge">Preparing</div>
        </div>
      </div>
    );
  }

  return (
    <LoadedFormBasedModelEditor
      workspace={workspace}
      documentSession={documentSession}
      selectedConceptName={selectedConceptName}
      focusedFieldName={focusedFieldName}
      focusedSection={focusedSection}
      onSelectConcept={onSelectConcept}
      onUpdateDocument={onUpdateDocument}
    />
  );
}

function LoadedFormBasedModelEditor({
  workspace,
  documentSession,
  selectedConceptName,
  focusedFieldName,
  focusedSection,
  onSelectConcept,
  onUpdateDocument
}: FormBasedModelEditorProps): JSX.Element {
  const document = documentSession.document;
  const concepts = document.concepts ?? [];
  const flows = document.flows ?? [];
  const queries = document.queries ?? [];
  const ruleProfiles = document.ruleProfiles ?? [];
  const procedures = document.procedures ?? [];
  const panels = document.panels ?? [];
  const orchestrationRules = document.orchestrationRules ?? [];
  const conceptNames = concepts.map((entity) => entity.name);
  const selectedEntity = concepts.find((entity) => entity.name === selectedConceptName) ?? concepts[0] ?? null;
  const modelJson = serializeModelDocument(document);

  const replaceDocument = (nextDocument: AuthoringModelDocument): void => {
    onUpdateDocument(cloneDocument(nextDocument));
  };

  const updateSelectedEntity = (updater: (entity: AuthoringEntity) => AuthoringEntity): void => {
    if (!selectedEntity) {
      return;
    }
    replaceDocument(updateEntity(document, selectedEntity.name, updater));
  };

  const updateSelectedField = (fieldName: string, updater: (field: AuthoringField) => AuthoringField): void => {
    if (!selectedEntity) {
      return;
    }
    replaceDocument(updateField(document, selectedEntity.name, fieldName, updater));
  };

  const updateFlows = (flows: AuthoringFlow[]): void => {
    replaceDocument({
      ...document,
      flows
    });
  };

  const addConceptFromWizard = (entity: AuthoringEntity): void => {
    replaceDocument({
      ...document,
      concepts: [...concepts, entity]
    });
    onSelectConcept(entity.name);
  };

  const addReferenceFromWizard = (sourceConceptName: string, field: AuthoringField): void => {
    replaceDocument(
      updateEntity(document, sourceConceptName, (entity) => ({
        ...entity,
        fields: [...entity.fields, field]
      }))
    );
    onSelectConcept(sourceConceptName);
  };

  const addFlowFromWizard = (flow: AuthoringFlow): void => {
    replaceDocument({
      ...document,
      flows: [...flows, flow]
    });
  };

  const updateQueries = (nextQueries: typeof queries): void => {
    replaceDocument({
      ...document,
      queries: nextQueries
    });
  };

  const updateRuleProfiles = (nextRuleProfiles: typeof ruleProfiles): void => {
    replaceDocument({
      ...document,
      ruleProfiles: nextRuleProfiles
    });
  };

  const updateProcedures = (nextProcedures: typeof procedures): void => {
    replaceDocument({
      ...document,
      procedures: nextProcedures
    });
  };

  const updatePanels = (nextPanels: typeof panels): void => {
    replaceDocument({
      ...document,
      panels: nextPanels
    });
  };

  const updateOrchestrationRules = (rules: AuthoringOrchestrationRule[]): void => {
    replaceDocument({
      ...document,
      orchestrationRules: rules
    });
  };

  const updateEntityTransitions = (transitions: AuthoringLifecycleTransition[]): void => {
    updateSelectedEntity((entity) => ({
      ...entity,
      lifecycle: {
        ...entity.lifecycle,
        transitions
      }
    }));
  };

  const jsonEditor = useSynchronizedJsonEditor<AuthoringModelDocument>({
    document,
    onApplyDocument: replaceDocument,
    validateDocument: validateModelDocument
  });
  const localDiagnostics = buildModelValidationDiagnostics(document).filter(
    (entry) => !selectedEntity || entry.concept == null || entry.concept === selectedEntity.name
  );
  const serverValidation = useServerValidation(modelJson);
  const inlineDiagnostics = mergeValidationDiagnostics(localDiagnostics, serverValidation.result?.diagnostics ?? []);
  const semanticGraph = buildSemanticGraph(document);
  const invariantInsights = buildInvariantMeaningEntries(selectedEntity);
  const flowInsights = buildFlowExplanationEntries(document);
  const actionInsights = buildActionReasonEntries(selectedEntity, flows, orchestrationRules);

  React.useEffect(() => {
    const sectionId =
      focusedSection === "references"
        ? "authoring-section-references"
        : focusedSection === "fields"
          ? "authoring-section-fields"
          : focusedSection === "concepts"
            ? "authoring-section-concepts"
            : null;
    if (!sectionId) {
      return;
    }
    window.document.getElementById(sectionId)?.scrollIntoView({
      block: "start",
      behavior: "smooth"
    });
  }, [focusedFieldName, focusedSection, selectedEntity?.name]);

  return (
    <div className="authoring-editor">
      <section className="authoring-editor-hero">
        <div>
          <div className="authoring-breadcrumb">Guided authoring workspace</div>
          <h3>Form-based model editor</h3>
          <p>
            The canonical document shape is now editable through structured panels for concepts, queries, rule
            profiles, procedures, panels, fields, enums, references, domain types, invariants, flows, state
            machines, actions, and metadata.
          </p>
        </div>

        <div className="authoring-editor-hero__meta">
          <strong>{documentSession.sourceLabel}</strong>
          <small>Workspace mode: {workspace.modelSource}</small>
          <small>Loaded: {documentSession.lastLoadedLabel}</small>
          <small>{documentSession.dirty ? "Unsaved editor changes" : "Freshly loaded session"}</small>
        </div>
      </section>

      <section className="authoring-editor-summary">
        <article className="authoring-summary-card">
          <strong>Concepts</strong>
          <span>{concepts.length}</span>
          <small>Core business concepts with guided metadata and field editing.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Queries</strong>
          <span>{queries.length}</span>
          <small>Named read surfaces stay visible beside the concepts they depend on.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Procedures</strong>
          <span>{procedures.length}</span>
          <small>Reusable governed steps stay editable without dropping into raw JSON mode.</small>
        </article>
        <article className="authoring-summary-card">
          <strong>Panels</strong>
          <span>{panels.length}</span>
          <small>Supported UI surfaces are authored in the same contract bundle as flows and rules.</small>
        </article>
      </section>

      <InlineValidationSummary
        title={
          selectedEntity
            ? `${selectedEntity.name} validation in context${serverValidation.pending ? " (refreshing semantic checks...)" : ""}`
            : `Model validation in context${serverValidation.pending ? " (refreshing semantic checks...)" : ""}`
        }
        diagnostics={inlineDiagnostics}
      />

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Guided onboarding tools</h3>
            <p>
              Step 39 adds guided templates, lightweight creation wizards, and glossary-driven help so empty or
              early-stage drafts stop feeling directionless.
            </p>
          </div>
          <ExplainabilityTooltip
            title="Why guided tools stay in the editor"
            detail="NPDev tries to keep modeling decisions understandable at the moment they are made, not only after export or runtime."
          />
        </div>

        <div className="authoring-wizard-grid">
          <ConceptCreationWizard conceptCount={concepts.length} onCreateConcept={addConceptFromWizard} />
          <ReferenceWizard
            entities={concepts}
            selectedConceptName={selectedEntity?.name ?? null}
            onCreateReference={addReferenceFromWizard}
          />
          <FlowCreationWizard entities={concepts} onCreateFlow={addFlowFromWizard} />
        </div>

        <ContextualHelpPanel
          title="Model-editor glossary hooks"
          summary="Use these quick explanations when you understand the forms mechanically but still want help with NPDev vocabulary."
          tips={[
            "Create concepts first, then add references once at least two concepts exist.",
            "Queries, procedures, and panels are now first-class peers of flows, not side notes.",
            "Starter templates are meant to be edited freely after the first load."
          ]}
          glossaryHookIds={["concept", "reference", "flow", "invariant", "capability"]}
        />
      </section>

      <div className="authoring-inline-actions">
        <button type="button" onClick={() => downloadModelDocument(document)}>
          Export current model.json
        </button>
        <button
          type="button"
          className={`authoring-secondary-inline ${jsonEditor.mode === "form" ? "is-selected" : ""}`}
          onClick={() => jsonEditor.setMode("form")}
        >
          Guided form mode
        </button>
        <button
          type="button"
          className={`authoring-secondary-inline ${jsonEditor.mode === "json" ? "is-selected" : ""}`}
          onClick={() => jsonEditor.setMode("json")}
        >
          Raw JSON mode
        </button>
      </div>

      {jsonEditor.mode === "json" ? (
        <RawJsonEditorPanel
          title="Raw model.json mode"
          description="Advanced users can edit the canonical model artifact directly. Valid JSON applies back into the form editor automatically, while invalid JSON stays safely in the raw draft until fixed."
          draftText={jsonEditor.draftText}
          issues={jsonEditor.issues}
          hasPendingRawChanges={jsonEditor.hasPendingRawChanges}
          hasExternalConflict={jsonEditor.hasExternalConflict}
          onDraftChange={jsonEditor.onDraftChange}
          onReloadFromForms={jsonEditor.reloadFromForms}
          onReturnToForms={() => jsonEditor.setMode("form")}
        />
      ) : (
        <>
          <section className="authoring-editor-section">
            <div className="authoring-editor-section__header">
              <div>
                <h3>Explainability snapshot</h3>
                <p>Keep a live view of the model graph and why the current rules, flows, and actions matter.</p>
              </div>
              <ExplainabilityTooltip
                title="Explainability snapshot"
                detail="This section gives quick semantic context directly inside the editor so the model is not just a set of disconnected forms."
              />
            </div>

            <SemanticGraphPanel graph={semanticGraph} />

            <div className="authoring-template-grid">
              {[...invariantInsights, ...flowInsights.slice(0, 3), ...actionInsights.slice(0, 3)].map((entry) => (
                <article key={`${entry.kind}-${entry.title}`} className="authoring-explainability-card">
                  <div className="authoring-editor-section__miniheader">
                    <strong>{entry.title}</strong>
                    <span>{entry.kind}</span>
                  </div>
                  <p>{entry.summary}</p>
                  <ul className="authoring-template-card__notes">
                    {entry.details.map((detail) => (
                      <li key={detail}>{detail}</li>
                    ))}
                  </ul>
                </article>
              ))}
            </div>
          </section>

          <ConceptsEditorSection
            entities={concepts}
            selectedConceptName={selectedEntity?.name ?? null}
            onSelectConcept={onSelectConcept}
            onAddConcept={() => {
              const nextName = `Concept${concepts.length + 1}`;
              replaceDocument({
                ...document,
                concepts: [
                  ...concepts,
                  {
                    name: nextName,
                    ui: {
                      label: nextName
                    },
                    fields: [
                      {
                        name: "id",
                        type: "uuid",
                        id: true,
                        required: true
                      }
                    ],
                    invariants: []
                  }
                ]
              });
              onSelectConcept(nextName);
            }}
            onRemoveConcept={(conceptName) =>
              replaceDocument({
                ...document,
                concepts: concepts.filter((entity) => entity.name !== conceptName)
              })
            }
            onMoveConcept={(conceptName, direction) => {
              const conceptIndex = concepts.findIndex((entity) => entity.name === conceptName);
              if (conceptIndex >= 0) {
                replaceDocument({
                  ...document,
                  concepts: moveItem(concepts, conceptIndex, direction)
                });
              }
            }}
            onUpdateConcept={(conceptName, updater) => replaceDocument(updateEntity(document, conceptName, updater))}
          />

          <DomainTypesEditorSection
            domainTypes={document.domainTypes}
            onChange={(domainTypes) =>
              replaceDocument({
                ...document,
                domainTypes
              })
            }
          />

          <FieldsEditorSection
            entity={selectedEntity}
            requestedFieldName={focusedSection === "fields" ? focusedFieldName ?? null : null}
            onUpdateField={updateSelectedField}
            onAddField={() => {
              if (!selectedEntity) {
                return;
              }
              updateSelectedEntity((entity) => ({
                ...entity,
                fields: [
                  ...entity.fields,
                  {
                    name: `field${entity.fields.length + 1}`,
                    type: "string"
                  }
                ]
              }));
            }}
            onRemoveField={(fieldName) => {
              if (!selectedEntity) {
                return;
              }
              updateSelectedEntity((entity) => ({
                ...entity,
                fields: entity.fields.filter((field) => field.name !== fieldName)
              }));
            }}
            onMoveField={(fieldName, direction) => {
              if (!selectedEntity) {
                return;
              }
              const fieldIndex = selectedEntity.fields.findIndex((field) => field.name === fieldName);
              if (fieldIndex >= 0) {
                updateSelectedEntity((entity) => ({
                  ...entity,
                  fields: moveItem(entity.fields, fieldIndex, direction)
                }));
              }
            }}
          />

          <EnumsEditorSection entity={selectedEntity} onUpdateField={updateSelectedField} />
          <ReferencesEditorSection
            entity={selectedEntity}
            allEntities={concepts}
            requestedFieldName={focusedSection === "references" ? focusedFieldName ?? null : null}
            onUpdateField={updateSelectedField}
          />

          <InvariantsEditorSection
            entity={selectedEntity}
            onChange={(invariants) =>
              updateSelectedEntity((entity) => ({
                ...entity,
                invariants
              }))
            }
          />

          <QueriesEditorSection queries={queries} conceptNames={conceptNames} onChange={updateQueries} />

          <RuleProfilesEditorSection
            ruleProfiles={ruleProfiles}
            conceptNames={conceptNames}
            onChange={updateRuleProfiles}
          />

          <ProceduresEditorSection
            procedures={procedures}
            conceptNames={conceptNames}
            queryNames={queries.map((query) => query.name)}
            procedureNames={procedures.map((procedure) => procedure.name)}
            onChange={updateProcedures}
          />

          <PanelsEditorSection
            panels={panels}
            conceptNames={conceptNames}
            queryNames={queries.map((query) => query.name)}
            procedureNames={procedures.map((procedure) => procedure.name)}
            flowNames={flows.map((flow) => flow.name)}
            onChange={updatePanels}
          />

          <FlowsEditorSection flows={flows} onChange={updateFlows} />

          <StateMachinesEditorSection
            entity={selectedEntity}
            onChangeStates={(states) =>
              updateSelectedEntity((entity) => ({
                ...entity,
                lifecycle: {
                  ...entity.lifecycle,
                  states
                }
              }))
            }
            onChangeTransitions={updateEntityTransitions}
          />

          <ActionsEditorSection
            entity={selectedEntity}
            flows={flows}
            orchestrationRules={orchestrationRules}
            onChangeFlows={updateFlows}
            onChangeEntityTransitions={updateEntityTransitions}
            onChangeOrchestrationRules={updateOrchestrationRules}
          />

          <MetadataEditorSection
            document={document}
            entity={selectedEntity}
            onChangeDocument={replaceDocument}
            onUpdateField={updateSelectedField}
          />

          <section id="authoring-json-preview" className="authoring-editor-section">
            <div className="authoring-editor-section__header">
              <div>
                <h3>Canonical JSON preview</h3>
                <p>The editor stays form-first, but the synchronized export shape remains visible for verification.</p>
              </div>
            </div>
            <pre className="json-pane">{prettyDocumentJson(document)}</pre>
          </section>
        </>
      )}
    </div>
  );
}
