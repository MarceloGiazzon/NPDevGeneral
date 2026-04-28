import React, { useEffect, useState } from "react";

export default function ImportExecutionPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [executionText, setExecutionText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/import-execution", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load import execution summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/import-execution/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load import execution history: HTTP " + response.status);
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

  async function runSampleExecution() {
    setErrorText("");
    setExecutionText("");

    const body = {
      templateId: "expense-request",
      executionName: "Expense import validation run",
      requiredFields: ["expenseId", "employee", "amount"],
      rows: [
        {
          expenseId: "EXP-001",
          employee: "Ana",
          amount: "120.50",
          category: "Travel"
        },
        {
          expenseId: "EXP-002",
          employee: "",
          amount: "80.00",
          category: "Meals"
        }
      ]
    };

    try {
      const response = await fetch("/api/admin/import-execution/run", {
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

      setExecutionText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Import Execution and Row-Level Validation</h2>
      <p>
        Execute a sample mapped import and inspect row-level validation results.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={runSampleExecution}>Run sample import execution</button>
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

      {executionText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Execution response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{executionText}</pre>
        </section>
      ) : null}
    </div>
  );
}