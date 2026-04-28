import React, { useEffect, useState } from "react";

export default function ImportCorrectionWorkspacePanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [decisionText, setDecisionText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/import-corrections", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load correction summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/import-corrections/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load correction history: HTTP " + response.status);
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

  async function submitSampleDecision() {
    setErrorText("");
    setDecisionText("");

    try {
      const analysisHistoryResponse = await fetch("/api/admin/import-conflicts/history", {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const analysisHistoryText = await analysisHistoryResponse.text();
      if (!analysisHistoryResponse.ok) {
        throw new Error(analysisHistoryText || ("HTTP " + analysisHistoryResponse.status));
      }

      const parsedHistory = JSON.parse(analysisHistoryText);
      if (!parsedHistory.items || !parsedHistory.items.length) {
        throw new Error("No conflict analysis found. Run Step 67 analysis first.");
      }

      const latest = parsedHistory.items[0];

      const body = {
        analysisId: latest.analysisId,
        rowNumber: 2,
        action: "SEND_FOR_REVIEW",
        note: "Duplicate plus unresolved reference. Needs human review."
      };

      const response = await fetch("/api/admin/import-corrections/decide", {
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

      setDecisionText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Import Correction Workspace</h2>
      <p>
        Record guided decisions for conflicted import rows.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={submitSampleDecision}>Submit sample correction decision</button>
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

      {decisionText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Decision response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{decisionText}</pre>
        </section>
      ) : null}
    </div>
  );
}