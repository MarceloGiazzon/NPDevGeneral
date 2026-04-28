import { useEffect, useState } from "react";

type PluginPackageSummary = {
  packageId?: string;
  displayName?: string;
  version?: string;
  trust?: { level?: string; source?: string; mode?: string };
  signature?: { algorithm?: string; digest?: string; status?: string; verifiedBy?: string };
  provenance?: { sourceType?: string; sourceLocation?: string; publishedAt?: string; attestation?: string };
};

type PluginPackagesResponse = {
  packages?: PluginPackageSummary[];
  rejectedPackages?: PluginPackageSummary[];
};

async function loadPluginPackages(): Promise<PluginPackagesResponse> {
  const response = await fetch("/api/admin/runtime/plugin-packages", { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`plugin packages request failed: ${response.status}`);
  }
  return response.json();
}

export default function PluginProvenancePanel(): JSX.Element {
  const [data, setData] = useState<PluginPackagesResponse | null>(null);
  const [error, setError] = useState<string>("");

  useEffect(() => {
    let active = true;
    loadPluginPackages()
      .then((payload) => {
        if (active) {
          setData(payload);
        }
      })
      .catch((cause) => {
        if (active) {
          setError(cause instanceof Error ? cause.message : String(cause));
        }
      });
    return () => {
      active = false;
    };
  }, []);

  const packages = data?.packages ?? [];

  return (
    <section>
      <h2>Plugin Provenance</h2>
      <p className="hint">Signature Verification and Package Provenance for sealed plugin packages.</p>
      {error ? <div className="status-box error">{error}</div> : null}
      <div className="metadata-summary">
        <div className="metadata-summary-card">
          <strong>Signature Verification</strong>
          <span>Show digest, algorithm and verification status.</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Package Provenance</strong>
          <span>Show sourceType, sourceLocation and attestation.</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Trust Chain</strong>
          <span>Summarize trust source, level and package origin.</span>
        </div>
      </div>
      <div className="trace-cards">
        {packages.map((pkg) => (
          <article key={pkg.packageId ?? pkg.displayName} className="trace-card">
            <div className="trace-card-header">{pkg.displayName ?? pkg.packageId}</div>
            <div className="trace-card-meta">{pkg.version ?? "unknown version"}</div>
            <div className="trace-card-meta">{pkg.signature?.algorithm ?? "no algorithm"} / {pkg.signature?.status ?? "unknown status"}</div>
            <div className="trace-card-meta">{pkg.provenance?.sourceType ?? "unknown source"} / {pkg.provenance?.sourceLocation ?? "unknown location"}</div>
          </article>
        ))}
      </div>
    </section>
  );
}
