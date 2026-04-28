import { useDeferredValue, useEffect, useState } from "react";
import { fetchModelSyncStatus, type ModelSyncStatus } from "../services/authoringApi";

type ModelSyncStatusBannerProps = {
  modelJson: string | null;
};

export function ModelSyncStatusBanner({ modelJson }: ModelSyncStatusBannerProps): JSX.Element | null {
  const deferredModelJson = useDeferredValue(modelJson);
  const [syncStatus, setSyncStatus] = useState<ModelSyncStatus | null>(null);

  useEffect(() => {
    if (!deferredModelJson) {
      setSyncStatus(null);
      return;
    }

    const controller = new AbortController();
    void fetchModelSyncStatus(deferredModelJson, controller.signal)
      .then(setSyncStatus)
      .catch((error) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        setSyncStatus({
          inSync: false,
          authoringHash: null,
          deployHash: null,
          lastExportedAt: null,
          status: "error"
        });
      });

    return () => controller.abort();
  }, [deferredModelJson]);

  if (!syncStatus || syncStatus.status === "unknown" || syncStatus.inSync) {
    return null;
  }

  const lastExportedLabel = syncStatus.lastExportedAt
    ? new Date(syncStatus.lastExportedAt).toLocaleString()
    : null;

  let message =
    "Your editing model differs from the deployed model. Export and re-project before deployment.";
  if (syncStatus.status === "deploy_not_found") {
    message = "Deploy model not found. Export your model before claiming it is deployed.";
  } else if (syncStatus.status === "error") {
    message = "Model sync check is currently unavailable. Verify Deploy artifacts before publishing.";
  }

  return (
    <div
      role="alert"
      className={`authoring-sync-banner ${syncStatus.status === "error" ? "is-error" : "is-warning"}`}
    >
      <span className="authoring-sync-banner__icon" aria-hidden="true">
        !
      </span>
      <div className="authoring-sync-banner__content">
        <strong>Model Sync Status</strong>
        <p>{message}</p>
      </div>
      {lastExportedLabel ? <small>Last deploy copy: {lastExportedLabel}</small> : null}
    </div>
  );
}
