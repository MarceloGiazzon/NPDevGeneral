import React, { useEffect, useState } from "react";
import AuthoringShell from "../layout/AuthoringShell";
import AuthoringHomeScreen from "../home/AuthoringHomeScreen";
import ModelChooserScreen from "../models/chooser/ModelChooserScreen";
import FormBasedModelEditor from "../editors/FormBasedModelEditor";
import FormBasedConfigEditor from "../editors/config/FormBasedConfigEditor";
import ValidationWorkspace from "../validation/ValidationWorkspace";
import PreviewWorkspace from "../preview/PreviewWorkspace";
import ImportExportWorkspace from "../io/ImportExportWorkspace";
import {
  authoringHashFor,
  findAuthoringRoute,
  parseAuthoringHash,
  type AuthoringRouteDef,
  type AuthoringRouteId
} from "../routes/authoringRoutes";
import { AuthoringStateProvider, useAuthoringState } from "../state/AuthoringState";
import { serializeModelDocument } from "../services/modelDocumentService";
import AuthoringPlaceholder from "./AuthoringPlaceholder";

type AuthoringAppProps = {
  onReturnToWorkbench: () => void;
};

function resolveAuthoringRoute(hashValue: string): AuthoringRouteDef {
  return parseAuthoringHash(hashValue).route;
}

function AuthoringSurface({ onReturnToWorkbench }: AuthoringAppProps): JSX.Element {
  const [activeLocation, setActiveLocation] = useState(() => parseAuthoringHash(window.location.hash));
  const {
    state,
    setRoute,
    selectStartMode,
    selectOfficialSample,
    selectStarterTemplate,
    bootstrap,
    loadWorkspaceDocument,
    loadWorkspaceConfig,
    updateDocument,
    updateConfig,
    replaceDocumentSession,
    replaceConfigSession,
    selectConcept
  } = useAuthoringState();

  useEffect(() => {
    const handleHashChange = (): void => {
      setActiveLocation(parseAuthoringHash(window.location.hash));
    };

    window.addEventListener("hashchange", handleHashChange);
    return () => {
      window.removeEventListener("hashchange", handleHashChange);
    };
  }, []);

  useEffect(() => {
    setRoute(activeLocation.route.id);
  }, [activeLocation.route.id, setRoute]);

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);

  useEffect(() => {
    void loadWorkspaceDocument(state.workspace);
  }, [loadWorkspaceDocument, state.workspace]);

  useEffect(() => {
    void loadWorkspaceConfig(state.workspace);
  }, [loadWorkspaceConfig, state.workspace]);

  useEffect(() => {
    const conceptName = activeLocation.params.concept;
    if (
      conceptName &&
      state.documentSession?.document.concepts.some((entity) => entity.name === conceptName) &&
      state.selectedConceptName !== conceptName
    ) {
      selectConcept(conceptName);
    }
  }, [activeLocation.params.concept, selectConcept, state.documentSession, state.selectedConceptName]);

  const navigate = (routeId: AuthoringRouteId): void => {
    window.location.hash = authoringHashFor(routeId);
  };

  const chooseCategory = (categoryId: Parameters<typeof selectStartMode>[0]): void => {
    selectStartMode(categoryId);
  };

  const chooseSample = (sampleId: string): void => {
    selectOfficialSample(sampleId);
    navigate("model-editor");
  };

  const modelJson = state.documentSession ? serializeModelDocument(state.documentSession.document) : null;

  return (
    <AuthoringShell
      activeRoute={findAuthoringRoute(state.activeRouteId)}
      workspace={state.workspace}
      modelJson={modelJson}
      serviceStatuses={state.serviceStatuses}
      bootstrapped={state.bootstrapped}
      lastBootstrapLabel={state.lastBootstrapLabel}
      launchModes={state.launchModes}
      categoryCards={state.categoryCards}
      officialSamples={state.officialSamples}
      onNavigate={navigate}
      onSelectStartMode={selectStartMode}
      onSelectOfficialSample={selectOfficialSample}
      onReturnToWorkbench={onReturnToWorkbench}
    >
      <AuthoringRouteOutlet
        route={findAuthoringRoute(state.activeRouteId)}
        categoryCards={state.categoryCards}
        officialSamples={state.officialSamples}
        starterTemplates={state.starterTemplates}
        activeCategoryId={state.workspace.entryCategory}
        selectedSampleId={state.workspace.sampleId}
        selectedTemplateId={state.workspace.templateId}
        workspace={state.workspace}
        documentSession={state.documentSession}
        configSession={state.configSession}
        selectedConceptName={state.selectedConceptName}
        focusedFieldName={activeLocation.params.field}
        focusedSection={activeLocation.params.section}
        onChooseCategory={chooseCategory}
        onChooseSample={chooseSample}
        onChooseTemplate={selectStarterTemplate}
        onOpenChooser={() => navigate("model-selector")}
        onContinueToEditor={() => navigate("model-editor")}
        onSelectConcept={selectConcept}
        onUpdateDocument={updateDocument}
        onUpdateConfig={updateConfig}
        onReplaceDocumentSession={replaceDocumentSession}
        onReplaceConfigSession={replaceConfigSession}
      />
    </AuthoringShell>
  );
}

type AuthoringRouteOutletProps = {
  route: AuthoringRouteDef;
  categoryCards: ReturnType<typeof useAuthoringState>["state"]["categoryCards"];
  officialSamples: ReturnType<typeof useAuthoringState>["state"]["officialSamples"];
  starterTemplates: ReturnType<typeof useAuthoringState>["state"]["starterTemplates"];
  activeCategoryId: string;
  selectedSampleId?: string;
  selectedTemplateId?: ReturnType<typeof useAuthoringState>["state"]["workspace"]["templateId"];
  workspace: ReturnType<typeof useAuthoringState>["state"]["workspace"];
  documentSession: ReturnType<typeof useAuthoringState>["state"]["documentSession"];
  configSession: ReturnType<typeof useAuthoringState>["state"]["configSession"];
  selectedConceptName: ReturnType<typeof useAuthoringState>["state"]["selectedConceptName"];
  focusedFieldName: string | null | undefined;
  focusedSection: string | null | undefined;
  onChooseCategory: (categoryId: "canonical-demo" | "official-samples" | "arbitrary-model" | "import-existing" | "new-model") => void;
  onChooseSample: (sampleId: string) => void;
  onChooseTemplate: ReturnType<typeof useAuthoringState>["selectStarterTemplate"];
  onOpenChooser: () => void;
  onContinueToEditor: () => void;
  onSelectConcept: (conceptName: string) => void;
  onUpdateDocument: ReturnType<typeof useAuthoringState>["updateDocument"];
  onUpdateConfig: ReturnType<typeof useAuthoringState>["updateConfig"];
  onReplaceDocumentSession: ReturnType<typeof useAuthoringState>["replaceDocumentSession"];
  onReplaceConfigSession: ReturnType<typeof useAuthoringState>["replaceConfigSession"];
};

function AuthoringRouteOutlet({
  route,
  categoryCards,
  officialSamples,
  starterTemplates,
  activeCategoryId,
  selectedSampleId,
  selectedTemplateId,
  workspace,
  documentSession,
  configSession,
  selectedConceptName,
  focusedFieldName,
  focusedSection,
  onChooseCategory,
  onChooseSample,
  onChooseTemplate,
  onOpenChooser,
  onContinueToEditor,
  onSelectConcept,
  onUpdateDocument,
  onUpdateConfig,
  onReplaceDocumentSession,
  onReplaceConfigSession
}: AuthoringRouteOutletProps): JSX.Element {
  switch (route.id) {
    case "home":
      return (
        <AuthoringHomeScreen
          categories={categoryCards}
          activeCategoryId={activeCategoryId}
          officialSamples={officialSamples}
          starterTemplates={starterTemplates}
          selectedSampleId={selectedSampleId}
          selectedTemplateId={selectedTemplateId}
          onChooseCategory={onChooseCategory}
          onChooseSample={onChooseSample}
          onChooseTemplate={onChooseTemplate}
          onOpenChooser={onOpenChooser}
          onContinueToEditor={onContinueToEditor}
        />
      );
    case "model-selector":
      return (
        <ModelChooserScreen
          categories={categoryCards}
          selectedCategoryId={activeCategoryId}
          officialSamples={officialSamples}
          selectedSampleId={selectedSampleId}
          starterTemplates={starterTemplates}
          selectedTemplateId={selectedTemplateId}
          onChooseCategory={onChooseCategory}
          onChooseSample={onChooseSample}
          onChooseTemplate={onChooseTemplate}
          onContinueToEditor={onContinueToEditor}
        />
      );
    case "model-editor":
      return (
        <FormBasedModelEditor
          workspace={workspace}
          documentSession={documentSession}
          selectedConceptName={selectedConceptName}
          focusedFieldName={focusedFieldName ?? null}
          focusedSection={focusedSection ?? null}
          onSelectConcept={onSelectConcept}
          onUpdateDocument={onUpdateDocument}
        />
      );
    case "config-editor":
      return (
        <FormBasedConfigEditor
          workspace={workspace}
          configSession={configSession}
          onUpdateConfig={onUpdateConfig}
        />
      );
    case "preview":
      return (
        <PreviewWorkspace
          documentSession={documentSession}
          selectedConceptName={selectedConceptName}
          onSelectConcept={onSelectConcept}
        />
      );
    case "validation":
      return (
        <ValidationWorkspace
          documentSession={documentSession}
          configSession={configSession}
          selectedConceptName={selectedConceptName}
        />
      );
    case "import-export":
      return (
        <ImportExportWorkspace
          documentSession={documentSession}
          configSession={configSession}
          onReplaceDocumentSession={onReplaceDocumentSession}
          onReplaceConfigSession={onReplaceConfigSession}
        />
      );
    default:
      return <AuthoringPlaceholder />;
  }
}

export default function AuthoringApp(props: AuthoringAppProps): JSX.Element {
  return (
    <AuthoringStateProvider>
      <AuthoringSurface {...props} />
    </AuthoringStateProvider>
  );
}
