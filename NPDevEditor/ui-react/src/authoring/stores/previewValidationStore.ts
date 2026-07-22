import type { AuthoringServiceStatus } from "../services/authoringApi";
import { AUTHORING_DEFAULT_ROUTE_ID, type AuthoringRouteId } from "../routes/authoringRoutes";

export type PreviewValidationStoreState = {
  activeRouteId: AuthoringRouteId;
  serviceStatuses: AuthoringServiceStatus[];
  bootstrapped: boolean;
  lastBootstrapLabel: string;
};

export type PreviewValidationStoreAction =
  | { type: "set-route"; routeId: AuthoringRouteId }
  | { type: "bootstrap-complete"; statuses: AuthoringServiceStatus[]; timestampLabel: string };

export const initialPreviewValidationStoreState: PreviewValidationStoreState = {
  activeRouteId: AUTHORING_DEFAULT_ROUTE_ID,
  serviceStatuses: [],
  bootstrapped: false,
  lastBootstrapLabel: "Not checked yet"
};

export function previewValidationStoreReducer(
  state: PreviewValidationStoreState,
  action: PreviewValidationStoreAction
): PreviewValidationStoreState {
  switch (action.type) {
    case "set-route":
      return {
        ...state,
        activeRouteId: action.routeId
      };
    case "bootstrap-complete":
      return {
        ...state,
        bootstrapped: true,
        serviceStatuses: action.statuses,
        lastBootstrapLabel: action.timestampLabel
      };
    default:
      return state;
  }
}
