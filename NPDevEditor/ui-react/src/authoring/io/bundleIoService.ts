import type { AuthoringConfigDocument, AuthoringConfigSession } from "../config/configDocumentTypes";
import { parseJsonDocument, serializeJsonDocument } from "../services/serialization/jsonSerialization";
import type { AuthoringDocumentSession, AuthoringEntity, AuthoringModelDocument } from "../editors/modelDocumentTypes";
import type {
  AuthoringBundle,
  AuthoringBundleSession,
  BundleImportResult,
  SavedBundleSnapshot
} from "./bundleTypes";

const STORAGE_KEY = "npdev.authoring.bundleSnapshots.v1";

function cloneBundle(bundle: AuthoringBundle): AuthoringBundle {
  return JSON.parse(JSON.stringify(bundle)) as AuthoringBundle;
}

type CanonicalModelDocument = AuthoringModelDocument;

type LegacyCompatibleModelDocument = Omit<AuthoringModelDocument, "concepts"> & {
  concepts?: AuthoringEntity[];
  entities?: AuthoringEntity[];
  orchestrations?: AuthoringModelDocument["orchestrationRules"];
};

export function toCanonicalModelDocument(document: AuthoringModelDocument): CanonicalModelDocument {
  return {
    $schema: document.$schema,
    namespace: document.namespace,
    dslVersion: document.dslVersion,
    version: document.version,
    domainTypes: [...(document.domainTypes ?? [])],
    concepts: [...document.concepts],
    capabilities: [...(document.capabilities ?? [])],
    bindings: [...(document.bindings ?? [])],
    events: [...(document.events ?? [])],
    orchestrationRules: [...(document.orchestrationRules ?? [])],
    flows: [...(document.flows ?? [])],
    queries: [...(document.queries ?? [])],
    ruleProfiles: [...(document.ruleProfiles ?? [])],
    procedures: [...(document.procedures ?? [])],
    panels: [...(document.panels ?? [])],
    metadata: document.metadata
  };
}

export function toInternalModelDocument(document: LegacyCompatibleModelDocument): AuthoringModelDocument {
  const concepts = document.concepts ?? document.entities ?? [];
  const { concepts: _concepts, entities: _entities, ...rest } = document;
  return {
    $schema: rest.$schema,
    namespace: rest.namespace,
    dslVersion: rest.dslVersion,
    version: rest.version,
    domainTypes: [...(rest.domainTypes ?? [])],
    concepts,
    capabilities: [...(rest.capabilities ?? [])],
    bindings: [...(rest.bindings ?? [])],
    events: [...(rest.events ?? [])],
    orchestrationRules: [...(rest.orchestrationRules ?? rest.orchestrations ?? [])],
    flows: [...(rest.flows ?? [])],
    queries: [...(document.queries ?? [])],
    ruleProfiles: [...(document.ruleProfiles ?? [])],
    procedures: [...(document.procedures ?? [])],
    panels: [...(document.panels ?? [])],
    metadata: rest.metadata
  };
}

export function buildAuthoringBundle(
  modelSession: AuthoringDocumentSession,
  configSession: AuthoringConfigSession
): AuthoringBundle {
  return {
    model: modelSession.document,
    config: configSession.document
  };
}

export function buildImportedBundleSessions(
  bundle: AuthoringBundle,
  label: string
): AuthoringBundleSession {
  const timestamp = new Date().toLocaleString();
  return {
    modelSession: {
      sourceKey: `import:model:${label}`,
      sourceLabel: `Imported model: ${label}`,
      document: toInternalModelDocument(JSON.parse(JSON.stringify(bundle.model)) as LegacyCompatibleModelDocument),
      dirty: true,
      lastLoadedLabel: timestamp
    },
    configSession: {
      sourceKey: `import:config:${label}`,
      sourceLabel: `Imported config: ${label}`,
      document: JSON.parse(JSON.stringify(bundle.config)) as AuthoringConfigDocument,
      dirty: true,
      lastLoadedLabel: timestamp
    }
  };
}

export async function importBundleFromFiles(
  modelFile: File | null,
  configFile: File | null
): Promise<BundleImportResult> {
  if (!modelFile || !configFile) {
    return {
      ok: false,
      message: "Choose both model.json and config.json before importing."
    };
  }

  const [modelText, configText] = await Promise.all([modelFile.text(), configFile.text()]);
  const parsedModel = parseJsonDocument<AuthoringModelDocument>(modelText);
  if (!parsedModel.ok) {
    return {
      ok: false,
      message: `Model import failed: ${parsedModel.issue.message}`
    };
  }

  const parsedConfig = parseJsonDocument<AuthoringConfigDocument>(configText);
  if (!parsedConfig.ok) {
    return {
      ok: false,
      message: `Config import failed: ${parsedConfig.issue.message}`
    };
  }

  return {
    ok: true,
    bundle: {
      model: toInternalModelDocument(parsedModel.value as LegacyCompatibleModelDocument),
      config: parsedConfig.value
    },
    modelFileName: modelFile.name,
    configFileName: configFile.name
  };
}

export function downloadBundle(bundle: AuthoringBundle, bundleLabel: string): void {
  const manifest = {
    bundleLabel,
    exportedAt: new Date().toISOString(),
    files: {
      model: "model.json",
      config: "config.json"
    }
  };

  const entries = [
    { filename: `${bundleLabel}.model.json`, content: serializeJsonDocument(toCanonicalModelDocument(bundle.model)) },
    { filename: `${bundleLabel}.config.json`, content: serializeJsonDocument(bundle.config) },
    { filename: `${bundleLabel}.bundle-manifest.json`, content: JSON.stringify(manifest, null, 2) }
  ];

  for (const entry of entries) {
    const blob = new Blob([entry.content], { type: "application/json;charset=utf-8" });
    const objectUrl = window.URL.createObjectURL(blob);
    const anchor = window.document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = entry.filename;
    anchor.click();
    window.URL.revokeObjectURL(objectUrl);
  }
}

type FileSystemWindow = Window & {
  showDirectoryPicker?: () => Promise<{
    getFileHandle: (
      name: string,
      options: { create: boolean }
    ) => Promise<{
      createWritable: () => Promise<{
        write: (content: string) => Promise<void>;
        close: () => Promise<void>;
      }>;
    }>;
  }>;
};

export async function saveBundleToChosenDirectory(
  bundle: AuthoringBundle,
  bundleLabel: string
): Promise<"saved" | "unsupported"> {
  const fsWindow = window as FileSystemWindow;
  if (!fsWindow.showDirectoryPicker) {
    return "unsupported";
  }

  const directoryHandle = await fsWindow.showDirectoryPicker();
  const files = [
    { name: `${bundleLabel}.model.json`, content: serializeJsonDocument(toCanonicalModelDocument(bundle.model)) },
    { name: `${bundleLabel}.config.json`, content: serializeJsonDocument(bundle.config) }
  ];

  for (const file of files) {
    const handle = await directoryHandle.getFileHandle(file.name, { create: true });
    const writable = await handle.createWritable();
    await writable.write(file.content);
    await writable.close();
  }

  return "saved";
}

function readSnapshots(): SavedBundleSnapshot[] {
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return [];
  }
  try {
    return JSON.parse(raw) as SavedBundleSnapshot[];
  } catch {
    return [];
  }
}

function writeSnapshots(snapshots: SavedBundleSnapshot[]): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshots));
}

export function listSavedBundleSnapshots(): SavedBundleSnapshot[] {
  return readSnapshots().sort((left, right) => right.savedAt.localeCompare(left.savedAt));
}

export function saveBundleSnapshot(bundle: AuthoringBundle, label: string, sourceKey: string): SavedBundleSnapshot {
  const snapshot: SavedBundleSnapshot = {
    id: `${sourceKey}-${Date.now()}`,
    label,
    savedAt: new Date().toISOString(),
    sourceKey,
    bundle: cloneBundle(bundle)
  };

  const snapshots = [snapshot, ...readSnapshots()].slice(0, 30);
  writeSnapshots(snapshots);
  return snapshot;
}

export function findSnapshotById(snapshotId: string): SavedBundleSnapshot | null {
  return listSavedBundleSnapshots().find((snapshot) => snapshot.id === snapshotId) ?? null;
}
