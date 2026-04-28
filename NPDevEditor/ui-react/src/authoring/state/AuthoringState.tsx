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
  buildWorkspaceSeed,
  buildOfficialSampleWorkspaceSeed,
  buildStarterTemplateWorkspaceSeed,
  listAuthoringCategoryCards,
  listAuthoringStartModes,
  listOfficialSampleCards,
  listStarterTemplateCards,
  type AuthoringCategoryCard,
  type OfficialSampleCard,
  type AuthoringStartMode,
  type AuthoringStartModeId,
  type AuthoringWorkspaceSeed,
  type StarterTemplateCard,
  type StarterTemplateId
} from "../services/modelLoader";
import { loadWorkspaceModelDocument } from "../services/modelDocumentService";
import { AUTHORING_DEFAULT_ROUTE_ID, type AuthoringRouteId } from "../routes/authoringRoutes";

type AuthoringState = {
  activeRouteId: AuthoringRouteId;
  launchModes: AuthoringStartMode[];
  categoryCards: AuthoringCategoryCard[];
  officialSamples: OfficialSampleCard[];
  starterTemplates: StarterTemplateCard[];
  workspace: AuthoringWorkspaceSeed;
  serviceStatuses: AuthoringServiceStatus[];
  bootstrapped: boolean;
  lastBootstrapLabel: string;
  documentSession: AuthoringDocumentSession | null;
  configSession: AuthoringConfigSession | null;
  selectedConceptName: string | null;
};

type AuthoringAction =
  | { type: "set-route"; routeId: AuthoringRouteId }
  | { type: "select-start-mode"; modeId: AuthoringStartModeId }
  | { type: "select-official-sample"; sampleId: string }
  | { type: "select-starter-template"; templateId: StarterTemplateId }
  | { type: "bootstrap-complete"; statuses: AuthoringServiceStatus[]; timestampLabel: string }
  | { type: "document-loaded"; session: AuthoringDocumentSession }
  | { type: "document-updated"; document: AuthoringModelDocument }
  | { type: "document-session-replaced"; session: AuthoringDocumentSession }
  | { type: "config-loaded"; session: AuthoringConfigSession }
  | { type: "config-updated"; document: AuthoringConfigDocument }
  | { type: "config-session-replaced"; session: AuthoringConfigSession }
  | { type: "select-concept"; conceptName: string | null };

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

const initialState: AuthoringState = {
  activeRouteId: AUTHORING_DEFAULT_ROUTE_ID,
  launchModes: listAuthoringStartModes(),
  categoryCards: listAuthoringCategoryCards(),
  officialSamples: listOfficialSampleCards(),
  starterTemplates: listStarterTemplateCards(),
  workspace: buildWorkspaceSeed(),
  serviceStatuses: [],
  bootstrapped: false,
  lastBootstrapLabel: "Not checked yet",
  documentSession: null,
  configSession: null,
  selectedConceptName: null
};

function reducer(state: AuthoringState, action: AuthoringAction): AuthoringState {
  switch (action.type) {
    case "set-route":
      return {
        ...state,
        activeRouteId: action.routeId
      };
    case "select-start-mode":
      return {
        ...state,
        workspace: buildWorkspaceSeed(action.modeId)
      };
    case "select-official-sample":
      return {
        ...state,
        workspace: buildOfficialSampleWorkspaceSeed(action.sampleId)
      };
    case "select-starter-template":
      return {
        ...state,
        workspace: buildStarterTemplateWorkspaceSeed(action.templateId)
      };
    case "bootstrap-complete":
      return {
        ...state,
        bootstrapped: true,
        serviceStatuses: action.statuses,
        lastBootstrapLabel: action.timestampLabel
      };
    case "document-loaded":
      return {
        ...state,
        documentSession: action.session,
        selectedConceptName: action.session.document.concepts[0]?.name ?? null
      };
    case "document-updated":
      return state.documentSession
        ? {
            ...state,
            documentSession: {
              ...state.documentSession,
              document: action.document,
              dirty: true
            },
            selectedConceptName:
              action.document.concepts.some((entity) => entity.name === state.selectedConceptName)
                ? state.selectedConceptName
                : action.document.concepts[0]?.name ?? null
          }
        : state;
    case "document-session-replaced":
      return {
        ...state,
        documentSession: action.session,
        selectedConceptName: action.session.document.concepts[0]?.name ?? null
      };
    case "config-loaded":
      return {
        ...state,
        configSession: action.session
      };
    case "config-updated":
      return state.configSession
        ? {
            ...state,
            configSession: {
              ...state.configSession,
              document: action.document,
              dirty: true
            }
          }
        : state;
    case "config-session-replaced":
      return {
        ...state,
        configSession: action.session
      };
    case "select-concept":
      return {
        ...state,
        selectedConceptName: action.conceptName
      };
    default:
      return state;
  }
}

const AuthoringStateContext = createContext<AuthoringContextValue | null>(null);

export function AuthoringStateProvider({ children }: { children: React.ReactNode }): JSX.Element {
  const [state, dispatch] = useReducer(reducer, initialState);

  const setRoute = useCallback((routeId: AuthoringRouteId) => {
    dispatch({ type: "set-route", routeId });
  }, []);

  const selectStartMode = useCallback((modeId: AuthoringStartModeId) => {
    dispatch({ type: "select-start-mode", modeId });
  }, []);

  const selectOfficialSample = useCallback((sampleId: string) => {
    dispatch({ type: "select-official-sample", sampleId });
  }, []);

  const selectStarterTemplate = useCallback((templateId: StarterTemplateId) => {
    dispatch({ type: "select-starter-template", templateId });
  }, []);

  const bootstrap = useCallback(async () => {
    const statuses = await fetchAuthoringServiceStatuses();
    dispatch({
      type: "bootstrap-complete",
      statuses,
      timestampLabel: new Date().toLocaleString()
    });
  }, []);

  const loadWorkspaceDocument = useCallback(async (workspace: AuthoringWorkspaceSeed) => {
    const session = await loadWorkspaceModelDocument(workspace);
    dispatch({
      type: "document-loaded",
      session
    });
  }, []);

  const updateDocument = useCallback((document: AuthoringModelDocument) => {
    dispatch({
      type: "document-updated",
      document
    });
  }, []);

  const replaceDocumentSession = useCallback((session: AuthoringDocumentSession) => {
    dispatch({
      type: "document-session-replaced",
      session
    });
  }, []);

  const loadWorkspaceConfig = useCallback(async (workspace: AuthoringWorkspaceSeed) => {
    const session = await loadWorkspaceConfigDocument(workspace);
    dispatch({
      type: "config-loaded",
      session
    });
  }, []);

  const updateConfig = useCallback((document: AuthoringConfigDocument) => {
    dispatch({
      type: "config-updated",
      document
    });
  }, []);

  const replaceConfigSession = useCallback((session: AuthoringConfigSession) => {
    dispatch({
      type: "config-session-replaced",
      session
    });
  }, []);

  const selectConcept = useCallback((conceptName: string | null) => {
    dispatch({
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
