import React, { useEffect, useState } from "react";

export default function ImportConflictAnalysisPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [analysisText, setAnalysisText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/import-conflicts", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load import conflict summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/import-conflicts/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load import conflict history: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setHistoryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });
  }, []);

  async function runSampleAnalysis() {
    setErrorText("");
    setAnalysisText("");

    const body = {
      templateId: "expense-request",
      analysisName: "Expense duplicate and reference analysis",
      uniqueField: "expenseId",
      referenceField: "employeeCode",
      knownReferenceValues: ["EMP-001", "EMP-002"],
      rows: [
        {
          expenseId: "EXP-001",
          employeeCode: "EMP-001",
          amount: "120.50"
        },
        {
          expenseId: "EXP-001",
          employeeCode: "EMP-404",
          amount: "95.00"
        },
        {
          expenseId: "EXP-003",
          employeeCode: "EMP-002",
          amount: "80.00"
        }
      ]
    };

    try {
      const response = await fetch("/api/admin/import-conflicts/analyze", {
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

      setAnalysisText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Reference Resolution and Duplicate Detection</h2>
      <p>
        Analyze import rows for duplicate identifiers and unresolved references.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={runSampleAnalysis}>Run sample conflict analysis</button>
      </div>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {summaryText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Summary</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{summaryText}</pre>
        </section>
      ) : null}

      {historyText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>History</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{historyText}</pre>
        </section>
      ) : null}

      {analysisText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Analysis response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{analysisText}</pre>
        </section>
      ) : null}
    </div>
  );
}