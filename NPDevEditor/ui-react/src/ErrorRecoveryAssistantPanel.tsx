import React, { useEffect, useState } from "react";

export default function ErrorRecoveryAssistantPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [adviceText, setAdviceText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/error-recovery", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load error recovery summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/error-recovery/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load error recovery history: HTTP " + response.status);
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

  async function requestAdvice() {
    setErrorText("");
    setAdviceText("");

    const body = {
      problemType: "unresolved_reference",
      sourceArea: "import-conflicts",
      contextNote: "A spreadsheet row points to an employee code that does not exist yet."
    };

    try {
      const response = await fetch("/api/admin/error-recovery/advise", {
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

      setAdviceText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>User-Facing Error Recovery Assistant</h2>
      <p>
        Request clear recovery guidance for known problem types.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={requestAdvice}>Request sample recovery advice</button>
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

      {adviceText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Advice response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{adviceText}</pre>
        </section>
      ) : null}
    </div>
  );
}