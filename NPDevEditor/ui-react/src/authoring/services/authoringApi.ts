import type { UiModelResponse, ValidationDiagnostic } from "../../types";
import { withApiKeyHeaders } from "../../api/apiKey";

export type ServiceStatusKind = "ready" | "degraded" | "unavailable";

export type AuthoringServiceStatus = {
  id: string;
  label: string;
  status: ServiceStatusKind;
  detail: string;
};

export type ModelSyncStatus = {
  inSync: boolean;
  authoringHash: string | null;
  deployHash: string | null;
  lastExportedAt: string | null;
  status: string;
};

export type RuntimeMetadataValidationResponse = {
  contract?: string;
  valid?: boolean;
  errorCount?: number;
  warningCount?: number;
  diagnostics?: ValidationDiagnostic[];
};

type RuntimeMetadataIndex = {
  metadataManifestVersion?: string;
  catalogCount?: number;
};

type RuntimeValidationSupport = {
  filteredCount?: number;
};

type RuntimePermissionAwarePreview = {
  permissionAware?: boolean;
  permissionSummary?: Record<string, unknown>;
};

async function fetchFirstRuntimeConceptName(): Promise<string | null> {
  try {
    const model = await readJson<UiModelResponse>("/api/admin/ui-model");
    return model.concepts?.find((concept) => concept.name && concept.name.trim())?.name ?? null;
  } catch {
    return null;
  }
}

async function readJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    method: "GET",
    headers: withApiKeyHeaders({
      "Content-Type": "application/json"
    })
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${response.statusText}`);
  }

  return (await response.json()) as T;
}

async function postJson<T>(path: string, body: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: withApiKeyHeaders({
      "Content-Type": "application/json"
    }),
    body,
    signal
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${response.statusText}`);
  }

  return (await response.json()) as T;
}

function unavailableStatus(id: string, label: string, detail: string): AuthoringServiceStatus {
  return { id, label, status: "unavailable", detail };
}

export async function fetchMetadataServiceStatus(): Promise<AuthoringServiceStatus> {
  try {
    const index = await readJson<RuntimeMetadataIndex>("/api/admin/runtime/metadata/index");
    return {
      id: "metadata",
      label: "Metadata API",
      status: "ready",
      detail: `${index.catalogCount ?? 0} catalogs discovered in runtime metadata index ${index.metadataManifestVersion ?? ""}`.trim()
    };
  } catch (error) {
    return unavailableStatus("metadata", "Metadata API", error instanceof Error ? error.message : "Metadata index unavailable.");
  }
}

export async function fetchValidationServiceStatus(): Promise<AuthoringServiceStatus> {
  try {
    const conceptName = await fetchFirstRuntimeConceptName();
    const suffix = conceptName ? `?concept=${encodeURIComponent(conceptName)}` : "";
    const response = await readJson<RuntimeValidationSupport>(`/api/admin/runtime/metadata/validation-support${suffix}`);
    return {
      id: "validation",
      label: "Validation Support",
      status: "ready",
      detail: `${response.filteredCount ?? 0} validation hints available${conceptName ? ` for ${conceptName}` : ""}.`
    };
  } catch (error) {
    return unavailableStatus("validation", "Validation Support", error instanceof Error ? error.message : "Validation support unavailable.");
  }
}

export async function fetchPermissionAwarePreviewStatus(): Promise<AuthoringServiceStatus> {
  try {
    const conceptName = await fetchFirstRuntimeConceptName();
    if (!conceptName) {
      return unavailableStatus("permission-aware-ui", "Permission-Aware UI Metadata", "No runtime concept available for preview.");
    }
    const response = await readJson<RuntimePermissionAwarePreview>(`/api/runtime/metadata/ui/preview/${encodeURIComponent(conceptName)}`);
    const permissionAware = Boolean(response.permissionAware);
    const summary = response.permissionSummary ?? {};
    const readonlyFields = Number(summary.readonlyFieldCount ?? 0);
    const hiddenFields = Number(summary.hiddenFieldCount ?? 0);
    return {
      id: "permission-aware-ui",
      label: "Permission-Aware UI Metadata",
      status: permissionAware ? "ready" : "degraded",
      detail: permissionAware
        ? `${readonlyFields} readonly and ${hiddenFields} hidden field states available for preview shaping.`
        : "Preview endpoint responded without permission-aware shaping."
    };
  } catch (error) {
    return unavailableStatus(
      "permission-aware-ui",
      "Permission-Aware UI Metadata",
      error instanceof Error ? error.message : "Permission-aware preview unavailable."
    );
  }
}

export async function fetchModelSyncStatus(modelJson: string, signal?: AbortSignal): Promise<ModelSyncStatus> {
  return postJson<ModelSyncStatus>("/api/admin/model/sync-status", modelJson, signal);
}

export async function fetchRuntimeMetadataValidation(
  modelJson: string,
  signal?: AbortSignal
): Promise<RuntimeMetadataValidationResponse> {
  return postJson<RuntimeMetadataValidationResponse>("/api/runtime/metadata/validate", modelJson, signal);
}

export async function fetchAuthoringServiceStatuses(): Promise<AuthoringServiceStatus[]> {
  const results = await Promise.all([
    fetchMetadataServiceStatus(),
    fetchValidationServiceStatus(),
    fetchPermissionAwarePreviewStatus()
  ]);

  return results;
}
