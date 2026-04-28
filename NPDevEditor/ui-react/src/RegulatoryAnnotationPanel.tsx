import React, { useState } from "react";

type AnnotationResponse = {
  annotationId: string;
  targetType: string;
  targetId: string;
  regulationId: string;
  createdAt: string;
  message: string;
};

type ExportResponse = {
  exportId: string;
  fileName: string;
  generatedAt: string;
  title: string;
  requestedBy: string;
  scope: string;
  annotationCount: number;
  message: string;
};

export default function RegulatoryAnnotationPanel() {
  const [annotationText, setAnnotationText] = useState<string>("");
  const [exportText, setExportText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  async function createSampleAnnotation() {
    setErrorText("");
    setAnnotationText("");

    const body = {
      targetType: "governance",
      targetId: "sample-governance-record",
      regulationId: "REG-001",
      policyReference: "Internal Policy 4.2",
      rationale: "This governed publication must preserve review evidence.",
      evidenceNote: "Approval chain and rollback history should remain visible."
    };

    try {
      const response = await fetch("/api/admin/compliance/annotate", {
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

      const parsed: AnnotationResponse = JSON.parse(text);
      setAnnotationText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  async function generateSampleExport() {
    setErrorText("");
    setExportText("");

    const body = {
      title: "Compliance export",
      requestedBy: "step63-panel",
      scope: "regulatory-annotations"
    };

    try {
      const response = await fetch("/api/admin/compliance/export", {
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

      const parsed: ExportResponse = JSON.parse(text);
      setExportText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Regulatory Annotation and Compliance Export</h2>
      <p>
        Record regulatory annotations and generate a readable compliance export.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={createSampleAnnotation}>Create sample annotation</button>{" "}
        <button type="button" onClick={generateSampleExport}>Generate sample compliance export</button>
      </div>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {annotationText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Annotation response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{annotationText}</pre>
        </section>
      ) : null}

      {exportText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Compliance export response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{exportText}</pre>
        </section>
      ) : null}
    </div>
  );
}