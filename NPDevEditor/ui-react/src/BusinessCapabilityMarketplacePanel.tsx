import React, { useEffect, useState } from "react";

export default function BusinessCapabilityMarketplacePanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [selectionText, setSelectionText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/capability-marketplace", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load capability marketplace summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/capability-marketplace/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load capability marketplace history: HTTP " + response.status);
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

  async function selectCapability() {
    setErrorText("");
    setSelectionText("");

    const body = {
      capabilityId: "approval",
      targetContextName: "Expense Request Working Draft",
      selectedBy: "step75-panel",
      selectionReason: "Approval is needed before publishing expense decisions."
    };

    try {
      const response = await fetch("/api/admin/capability-marketplace/select", {
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

      setSelectionText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Business Capability Marketplace Surface</h2>
      <p>
        Browse reusable business capabilities and record a capability selection for a target context.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={selectCapability}>Select sample capability</button>
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

      {selectionText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Selection response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{selectionText}</pre>
        </section>
      ) : null}
    </div>
  );
}