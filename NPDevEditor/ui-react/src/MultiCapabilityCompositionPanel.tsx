import React, { useEffect, useState } from "react";

export default function MultiCapabilityCompositionPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [compositionText, setCompositionText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/capability-compositions", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load capability composition summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/capability-compositions/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load capability composition history: HTTP " + response.status);
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

  async function composeSystem() {
    setErrorText("");
    setCompositionText("");

    const body = {
      compositionName: "Expense workflow with notifications",
      targetSystemName: "Expense Request Composed System",
      selectedCapabilityIds: ["intake", "approval", "notifications"],
      compositionReason: "The business flow needs request intake, approval, and state-change communication.",
      composedBy: "step76-panel"
    };

    try {
      const response = await fetch("/api/admin/capability-compositions/compose", {
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

      setCompositionText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Guided Composition of Multi-Capability Systems</h2>
      <p>
        Combine several business capabilities into one planned business system.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={composeSystem}>Compose sample multi-capability system</button>
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

      {compositionText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Composition response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{compositionText}</pre>
        </section>
      ) : null}
    </div>
  );
}