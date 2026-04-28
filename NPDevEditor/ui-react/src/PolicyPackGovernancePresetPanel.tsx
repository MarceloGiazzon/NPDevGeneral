import React, { useEffect, useState } from "react";

export default function PolicyPackGovernancePresetPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [applyText, setApplyText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/policy-packs", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load policy pack summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/policy-packs/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load policy pack history: HTTP " + response.status);
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

  async function applyPolicyPack() {
    setErrorText("");
    setApplyText("");

    const body = {
      policyPackId: "regulated-enterprise-governance",
      targetContextName: "Expense Request Composed System",
      appliedBy: "step79-panel",
      applicationReason: "The target system needs strong review, rollback, and audit evidence."
    };

    try {
      const response = await fetch("/api/admin/policy-packs/apply", {
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

      setApplyText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Policy Packs and Governance Presets</h2>
      <p>
        Apply a named governance preset instead of assembling every governance choice manually.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={applyPolicyPack}>Apply sample policy pack</button>
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

      {applyText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Apply response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{applyText}</pre>
        </section>
      ) : null}
    </div>
  );
}