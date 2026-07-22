import type { AuthoringConfigDocument, AuthoringConfigSession } from "../config/configDocumentTypes";

export type ConfigStoreState = {
  configSession: AuthoringConfigSession | null;
};

export type ConfigStoreAction =
  | { type: "config-loaded"; session: AuthoringConfigSession }
  | { type: "config-updated"; document: AuthoringConfigDocument }
  | { type: "config-session-replaced"; session: AuthoringConfigSession };

export const initialConfigStoreState: ConfigStoreState = {
  configSession: null
};

export function configStoreReducer(state: ConfigStoreState, action: ConfigStoreAction): ConfigStoreState {
  switch (action.type) {
    case "config-loaded":
    case "config-session-replaced":
      return {
        configSession: action.session
      };
    case "config-updated":
      return state.configSession
        ? {
            configSession: {
              ...state.configSession,
              document: action.document,
              dirty: true
            }
          }
        : state;
    default:
      return state;
  }
}
