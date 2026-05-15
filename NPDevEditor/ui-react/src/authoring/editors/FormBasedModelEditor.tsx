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
import { cloneDocument, prettyDocumentJson, updateEntity, updateField } from "./editorUtils";
import { downloadModelDocument, serializeModelDocument } from "../services/modelDocumentService";
import { useSynchronizedJsonEditor } from "../json/useSynchronizedJsonEditor";
import RawJsonEditorPanel from "../json/RawJsonEditorPanel";
import { validateModelDocument } from "./modelValidation";
import InlineValidationSummary from "../validation/InlineValidationSummary";
import { buildModelValidationDiagnostics } from "../validation/authoringValidation";
import { mergeValidationDiagnostics, useServerValidation } from "../validation/useServerValidation";
import { buildActionReasonEntries, buildFlowExplanationEntries, buildInvariantMeaningEntries } from "../explainability/modelExplainability";
import { buildSemanticGraph } from "../graph/semanticGraph";
import ModelEditorHero from "./ModelEditorHero";
import GuidedOnboardingTools from "./GuidedOnboardingTools";
import ExplainabilitySnapshotSection from "./ExplainabilitySnapshotSection";
import ModelEditorFormSections from "./ModelEditorFormSections";
import ModelEditorDocumentActions from "./ModelEditorDocumentActions";
import ModelEditorLoadingState from "./ModelEditorLoadingState";

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
    return <ModelEditorLoadingState />;
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
      <ModelEditorHero workspace={workspace} documentSession={documentSession} document={document} />

      <InlineValidationSummary
        title={
          selectedEntity
            ? `${selectedEntity.name} validation in context${serverValidation.pending ? " (refreshing semantic checks...)" : ""}`
            : `Model validation in context${serverValidation.pending ? " (refreshing semantic checks...)" : ""}`
        }
        diagnostics={inlineDiagnostics}
      />

      <GuidedOnboardingTools
        concepts={concepts}
        selectedConceptName={selectedEntity?.name ?? null}
        onCreateConcept={addConceptFromWizard}
        onCreateReference={addReferenceFromWizard}
        onCreateFlow={addFlowFromWizard}
      />

      <ModelEditorDocumentActions
        mode={jsonEditor.mode}
        onExport={() => downloadModelDocument(document)}
        onSetMode={jsonEditor.setMode}
      />

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
          <ExplainabilitySnapshotSection
            semanticGraph={semanticGraph}
            invariantInsights={invariantInsights}
            flowInsights={flowInsights}
            actionInsights={actionInsights}
          />

          <ModelEditorFormSections
            document={document}
            concepts={concepts}
            conceptNames={conceptNames}
            selectedEntity={selectedEntity}
            flows={flows}
            queries={queries}
            ruleProfiles={ruleProfiles}
            procedures={procedures}
            panels={panels}
            orchestrationRules={orchestrationRules}
            focusedFieldName={focusedFieldName}
            focusedSection={focusedSection}
            onSelectConcept={onSelectConcept}
            replaceDocument={replaceDocument}
            updateSelectedEntity={updateSelectedEntity}
            updateSelectedField={updateSelectedField}
            updateFlows={updateFlows}
            updateQueries={updateQueries}
            updateRuleProfiles={updateRuleProfiles}
            updateProcedures={updateProcedures}
            updatePanels={updatePanels}
            updateOrchestrationRules={updateOrchestrationRules}
            updateEntityTransitions={updateEntityTransitions}
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
