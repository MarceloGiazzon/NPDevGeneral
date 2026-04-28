import React, { useState } from "react";

type AuditExportResponse = {
  exportId: string;
  fileName: string;
  generatedAt: string;
  title: string;
  requestedBy: string;
  scope: string;
  structuralCount: number;
  behaviorCount: number;
  rollbackCount: number;
  governanceCount: number;
  message: string;
};

export default function HumanReadableAuditExportPanel() {
  const [responseText, setResponseText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  async function runSampleExport() {
    setErrorText("");
    setResponseText("");

    const body = {
      title: "Operations audit export",
      requestedBy: "step62-panel",
      scope: "authoring-governance-surface"
    };

    try {
      const response = await fetch("/api/admin/audit/export", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-API-Key": "dev-key"
        },
        body: JSON.stringify(body)
      });

      const text = await response.text();
      if (!response.ok) {
        throw new Error(text || ("HTTP " + response.status));
      }

      const parsed: AuditExportResponse = JSON.parse(text);
      setResponseText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Human-Readable Audit Export</h2>
      <p>
        Generate a readable audit artifact from semantic request history.
      </p>

      <button type="button" onClick={runSampleExport}>
        Generate sample audit export
      </button>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {responseText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Export response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{responseText}</pre>
        </section>
      ) : null}
    </div>
  );
}