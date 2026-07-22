import {
  buildOfficialSampleWorkspaceSeed,
  buildStarterTemplateWorkspaceSeed,
  buildWorkspaceSeed,
  listAuthoringCategoryCards,
  listAuthoringStartModes,
  listOfficialSampleCards,
  listStarterTemplateCards,
  type AuthoringCategoryCard,
  type AuthoringStartMode,
  type AuthoringStartModeId,
  type AuthoringWorkspaceSeed,
  type OfficialSampleCard,
  type StarterTemplateCard,
  type StarterTemplateId
} from "../services/modelLoader";

export type WorkspaceStoreState = {
  launchModes: AuthoringStartMode[];
  categoryCards: AuthoringCategoryCard[];
  officialSamples: OfficialSampleCard[];
  starterTemplates: StarterTemplateCard[];
  workspace: AuthoringWorkspaceSeed;
};

export type WorkspaceStoreAction =
  | { type: "select-start-mode"; modeId: AuthoringStartModeId }
  | { type: "select-official-sample"; sampleId: string }
  | { type: "select-starter-template"; templateId: StarterTemplateId };

export const initialWorkspaceStoreState: WorkspaceStoreState = {
  launchModes: listAuthoringStartModes(),
  categoryCards: listAuthoringCategoryCards(),
  officialSamples: listOfficialSampleCards(),
  starterTemplates: listStarterTemplateCards(),
  workspace: buildWorkspaceSeed()
};

export function workspaceStoreReducer(state: WorkspaceStoreState, action: WorkspaceStoreAction): WorkspaceStoreState {
  switch (action.type) {
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
    default:
      return state;
  }
}
