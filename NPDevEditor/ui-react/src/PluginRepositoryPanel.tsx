import React, { useMemo } from "react";

export type PluginRepositorySummary = {
  repositoryId: string;
  displayName: string;
  repositoryType: string;
  endpoint: string;
  trustMode: string;
  signatureRequired: boolean;
  packageIds: string[];
};

export type PluginRepositoryPanelProps = {
  repositories?: PluginRepositorySummary[];
  selectedRepositoryId?: string | null;
  onSelectRepository?: (repositoryId: string) => void;
};

export default function PluginRepositoryPanel(props: PluginRepositoryPanelProps) {
  const repositories = props.repositories ?? [];
  const selectedRepository = useMemo(() => {
    if (!props.selectedRepositoryId) {
      return repositories[0] ?? null;
    }
    return repositories.find((item) => item.repositoryId === props.selectedRepositoryId) ?? repositories[0] ?? null;
  }, [props.selectedRepositoryId, repositories]);

  return (
    <section>
      <h2>Remote Repository</h2>
      <p className="hint">
        Repository Catalog metadata keeps package discovery and Sync Status outside the semantic model while surfacing Signature Policy clearly.
      </p>

      <div className="button-row">
        {repositories.map((repository) => (
          <button
            key={repository.repositoryId}
            type="button"
            className={selectedRepository?.repositoryId === repository.repositoryId ? "secondary-button active" : "secondary-button"}
            onClick={() => props.onSelectRepository?.(repository.repositoryId)}
          >
            {repository.displayName}
          </button>
        ))}
      </div>

      <div className="subpanel">
        <h3>Repository Catalog</h3>
        {selectedRepository ? (
          <dl className="status-block">
            <dt>Repository Id</dt>
            <dd>{selectedRepository.repositoryId}</dd>
            <dt>Endpoint</dt>
            <dd>{selectedRepository.endpoint}</dd>
            <dt>Repository Type</dt>
            <dd>{selectedRepository.repositoryType}</dd>
            <dt>Signature Policy</dt>
            <dd>{selectedRepository.signatureRequired ? "required" : "optional"}</dd>
            <dt>Trust Mode</dt>
            <dd>{selectedRepository.trustMode}</dd>
            <dt>Sync Status</dt>
            <dd>{selectedRepository.packageIds.length > 0 ? "catalog-loaded" : "empty"}</dd>
          </dl>
        ) : (
          <p className="hint">No repository metadata loaded.</p>
        )}
      </div>
    </section>
  );
}
