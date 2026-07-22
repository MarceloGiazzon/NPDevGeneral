import React, { createContext, useCallback, useContext, useMemo, useReducer } from "react";
import type {
  AuthoringConfigDocument,
  AuthoringConfigSession
} from "../config/configDocumentTypes";
import { loadWorkspaceConfigDocument } from "../config/configDocumentService";
import { fetchAuthoringServiceStatuses, type AuthoringServiceStatus } from "../services/authoringApi";
import type {
  AuthoringDocumentSession,
  AuthoringModelDocument
} from "../editors/modelDocumentTypes";
import {
  type AuthoringCategoryCard,
  type OfficialSampleCard,
  type AuthoringStartMode,
  type AuthoringStartModeId,
  type AuthoringWorkspaceSeed,
  type StarterTemplateCard,
  type StarterTemplateId
} from "../services/modelLoader";
import { loadWorkspaceModelDocument } from "../services/modelDocumentService";
import type { AuthoringRouteId } from "../routes/authoringRoutes";
import {
  initialModelStoreState,
  modelStoreReducer,
  type ModelStoreState
} from "../stores/modelStore";
import {
  configStoreReducer,
  initialConfigStoreState,
  type ConfigStoreState
} from "../stores/configStore";
import {
  initialPreviewValidationStoreState,
  previewValidationStoreReducer,
  type PreviewValidationStoreState
} from "../stores/previewValidationStore";
import {
  initialWorkspaceStoreState,
  workspaceStoreReducer,
  type WorkspaceStoreState
} from "../stores/workspaceStore";

type AuthoringState = WorkspaceStoreState & PreviewValidationStoreState & ModelStoreState & ConfigStoreState;

type AuthoringContextValue = {
  state: AuthoringState;
  setRoute: (routeId: AuthoringRouteId) => void;
  selectStartMode: (modeId: AuthoringStartModeId) => void;
  selectOfficialSample: (sampleId: string) => void;
  selectStarterTemplate: (templateId: StarterTemplateId) => void;
  bootstrap: () => Promise<void>;
  loadWorkspaceDocument: (workspace: AuthoringWorkspaceSeed) => Promise<void>;
  updateDocument: (document: AuthoringModelDocument) => void;
  replaceDocumentSession: (session: AuthoringDocumentSession) => void;
  loadWorkspaceConfig: (workspace: AuthoringWorkspaceSeed) => Promise<void>;
  updateConfig: (document: AuthoringConfigDocument) => void;
  replaceConfigSession: (session: AuthoringConfigSession) => void;
  selectConcept: (conceptName: string | null) => void;
};

const AuthoringStateContext = createContext<AuthoringContextValue | null>(null);

export function AuthoringStateProvider({ children }: { children: React.ReactNode }): JSX.Element {
  const [workspaceState, dispatchWorkspace] = useReducer(workspaceStoreReducer, initialWorkspaceStoreState);
  const [previewValidationState, dispatchPreviewValidation] = useReducer(
    previewValidationStoreReducer,
    initialPreviewValidationStoreState
  );
  const [modelState, dispatchModel] = useReducer(modelStoreReducer, initialModelStoreState);
  const [configState, dispatchConfig] = useReducer(configStoreReducer, initialConfigStoreState);
  const state = useMemo<AuthoringState>(
    () => ({
      ...workspaceState,
      ...previewValidationState,
      ...modelState,
      ...configState
    }),
    [configState, modelState, previewValidationState, workspaceState]
  );

  const setRoute = useCallback((routeId: AuthoringRouteId) => {
    dispatchPreviewValidation({ type: "set-route", routeId });
  }, []);

  const selectStartMode = useCallback((modeId: AuthoringStartModeId) => {
    dispatchWorkspace({ type: "select-start-mode", modeId });
  }, []);

  const selectOfficialSample = useCallback((sampleId: string) => {
    dispatchWorkspace({ type: "select-official-sample", sampleId });
  }, []);

  const selectStarterTemplate = useCallback((templateId: StarterTemplateId) => {
    dispatchWorkspace({ type: "select-starter-template", templateId });
  }, []);

  const bootstrap = useCallback(async () => {
    const statuses = await fetchAuthoringServiceStatuses();
    dispatchPreviewValidation({
      type: "bootstrap-complete",
      statuses,
      timestampLabel: new Date().toLocaleString()
    });
  }, []);

  const loadWorkspaceDocument = useCallback(async (workspace: AuthoringWorkspaceSeed) => {
    const session = await loadWorkspaceModelDocument(workspace);
    dispatchModel({
      type: "document-loaded",
      session
    });
  }, []);

  const updateDocument = useCallback((document: AuthoringModelDocument) => {
    dispatchModel({
      type: "document-updated",
      document
    });
  }, []);

  const replaceDocumentSession = useCallback((session: AuthoringDocumentSession) => {
    dispatchModel({
      type: "document-session-replaced",
      session
    });
  }, []);

  const loadWorkspaceConfig = useCallback(async (workspace: AuthoringWorkspaceSeed) => {
    const session = await loadWorkspaceConfigDocument(workspace);
    dispatchConfig({
      type: "config-loaded",
      session
    });
  }, []);

  const updateConfig = useCallback((document: AuthoringConfigDocument) => {
    dispatchConfig({
      type: "config-updated",
      document
    });
  }, []);

  const replaceConfigSession = useCallback((session: AuthoringConfigSession) => {
    dispatchConfig({
      type: "config-session-replaced",
      session
    });
  }, []);

  const selectConcept = useCallback((conceptName: string | null) => {
    dispatchModel({
      type: "select-concept",
      conceptName
    });
  }, []);

  const value = useMemo<AuthoringContextValue>(() => {
    return {
      state,
      setRoute,
      selectStartMode,
      selectOfficialSample,
      selectStarterTemplate,
      bootstrap,
      loadWorkspaceDocument,
      loadWorkspaceConfig,
      updateDocument,
      replaceDocumentSession,
      updateConfig,
      replaceConfigSession,
      selectConcept
    };
  }, [
    bootstrap,
    loadWorkspaceConfig,
    loadWorkspaceDocument,
    replaceConfigSession,
    replaceDocumentSession,
    selectConcept,
    selectOfficialSample,
    selectStarterTemplate,
    selectStartMode,
    setRoute,
    state,
    updateConfig,
    updateDocument
  ]);

  return <AuthoringStateContext.Provider value={value}>{children}</AuthoringStateContext.Provider>;
}

export function useAuthoringState(): AuthoringContextValue {
  const context = useContext(AuthoringStateContext);
  if (!context) {
    throw new Error("useAuthoringState must be used inside AuthoringStateProvider.");
  }
  return context;
}
