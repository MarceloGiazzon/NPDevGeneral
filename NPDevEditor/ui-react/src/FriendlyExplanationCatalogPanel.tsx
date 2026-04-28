import React, { useEffect, useState } from "react";

type FriendlyExplanationEntry = {
  code: string;
  title: string;
  category: string;
  humanExplanation: string;
  userMessage: string;
  remediationHint: string;
  sampleScenario: string;
};

type FriendlyExplanationResponse = {
  catalogPath: string;
  version: number;
  entryCount: number;
  categories: string[];
  entries: FriendlyExplanationEntry[];
};

export default function FriendlyExplanationCatalogPanel() {
  const [data, setData] = useState<FriendlyExplanationResponse | null>(null);
  const [error, setError] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/runtime/friendly-explanations")
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load friendly explanations: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setData(payload);
        setError("");
      })
      .catch((err: Error) => {
        setError(err.message);
      });
  }, []);

  return (
    <div style={{ padding: "16px" }}>
      <h2>Friendly Explanations</h2>
      <p>
        Runtime-served catalog of business-readable explanations for validation,
        invariant, state, permission, and orchestration outcomes.
      </p>

      {error ? (
        <div style={{ color: "#b42318", marginBottom: "12px" }}>
          {error}
        </div>
      ) : null}

      {!data ? (
        <div>Loading catalog...</div>
      ) : (
        <>
          <div style={{ marginBottom: "16px" }}>
            <strong>Catalog path:</strong> {data.catalogPath}
            <br />
            <strong>Version:</strong> {data.version}
            <br />
            <strong>Entry count:</strong> {data.entryCount}
            <br />
            <strong>Categories:</strong> {data.categories.join(", ")}
          </div>

          {data.entries.map((entry) => (
            <section
              key={entry.code}
              style={{
                border: "1px solid #d0d7de",
                borderRadius: "8px",
                padding: "12px",
                marginBottom: "12px"
              }}
            >
              <h3 style={{ marginTop: 0 }}>{entry.title}</h3>
              <div><strong>Code:</strong> {entry.code}</div>
              <div><strong>Category:</strong> {entry.category}</div>
              <div style={{ marginTop: "8px" }}>
                <strong>Human explanation:</strong> {entry.humanExplanation}
              </div>
              <div style={{ marginTop: "8px" }}>
                <strong>User message:</strong> {entry.userMessage}
              </div>
              <div style={{ marginTop: "8px" }}>
                <strong>Remediation hint:</strong> {entry.remediationHint}
              </div>
              <div style={{ marginTop: "8px" }}>
                <strong>Sample scenario:</strong> {entry.sampleScenario}
              </div>
            </section>
          ))}
        </>
      )}
    </div>
  );
}
