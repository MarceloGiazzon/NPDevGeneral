import type { AuthoringDocumentSession, AuthoringModelDocument } from "../editors/modelDocumentTypes";

export type ModelStoreState = {
  documentSession: AuthoringDocumentSession | null;
  selectedConceptName: string | null;
};

export type ModelStoreAction =
  | { type: "document-loaded"; session: AuthoringDocumentSession }
  | { type: "document-updated"; document: AuthoringModelDocument }
  | { type: "document-session-replaced"; session: AuthoringDocumentSession }
  | { type: "select-concept"; conceptName: string | null };

export const initialModelStoreState: ModelStoreState = {
  documentSession: null,
  selectedConceptName: null
};

export function modelStoreReducer(state: ModelStoreState, action: ModelStoreAction): ModelStoreState {
  switch (action.type) {
    case "document-loaded":
    case "document-session-replaced":
      return {
        documentSession: action.session,
        selectedConceptName: action.session.document.concepts[0]?.name ?? null
      };
    case "document-updated":
      return state.documentSession
        ? {
            documentSession: {
              ...state.documentSession,
              document: action.document,
              dirty: true
            },
            selectedConceptName: action.document.concepts.some((entity) => entity.name === state.selectedConceptName)
              ? state.selectedConceptName
              : action.document.concepts[0]?.name ?? null
          }
        : state;
    case "select-concept":
      return {
        ...state,
        selectedConceptName: action.conceptName
      };
    default:
      return state;
  }
}
