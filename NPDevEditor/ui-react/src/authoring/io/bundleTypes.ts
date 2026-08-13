import type { AuthoringConfigDocument, AuthoringConfigSession } from "../config/configDocumentTypes";
import type { AuthoringDocumentSession, AuthoringModelDocument } from "../editors/modelDocumentTypes";

export type AuthoringBundle = {
  model: AuthoringModelDocument;
  config: AuthoringConfigDocument;
};

export type AuthoringBundleSession = {
  modelSession: AuthoringDocumentSession;
  configSession: AuthoringConfigSession;
};

export type SavedBundleSnapshot = {
  id: string;
  label: string;
  savedAt: string;
  sourceKey: string;
  bundle: AuthoringBundle;
};

export type BundleImportResult =
  | {
      ok: true;
      bundle: AuthoringBundle;
      modelFileName: string;
      configFileName: string;
    }
  | {
      ok: false;
      message: string;
    };

// R6 (MASTER-ROADMAP.md): a bare model.json (e.g. an AppGen app definition) has no sibling
// config.json to pair it with -- ModelOnlyImportSection opens one directly instead of forcing a
// bundle import that would fail on the missing config file.
export type ModelImportResult =
  | {
      ok: true;
      document: AuthoringModelDocument;
      modelFileName: string;
    }
  | {
      ok: false;
      message: string;
    };

export type SemanticDiffChange = {
  kind: "added" | "removed" | "changed";
  path: string;
  before?: string;
  after?: string;
};

export type SemanticDiffSummary = {
  title: string;
  changes: SemanticDiffChange[];
};
