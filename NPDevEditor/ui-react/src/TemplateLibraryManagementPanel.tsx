import React, { useEffect, useState } from "react";

export default function TemplateLibraryManagementPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [registerText, setRegisterText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/template-library", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load template library summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/template-library/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load template library history: HTTP " + response.status);
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

  async function registerSample() {
    setErrorText("");
    setRegisterText("");

    const body = {
      templateId: "procurement-review",
      title: "Procurement Review",
      versionTag: "v1",
      owner: "operations-team",
      lifecycleStage: "beta",
      recommendationLevel: "recommended",
      complianceNote: "Useful for procurement approval and review processes.",
      supportedOnboardingStyle: "guided"
    };

    try {
      const response = await fetch("/api/admin/template-library/register", {
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

      setRegisterText(JSON.stringify(JSON.parse(text), null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Template Library Management</h2>
      <p>
        Manage scenario templates as visible library assets with version and lifecycle metadata.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={registerSample}>Register sample template asset</button>
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

      {registerText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Registration response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{registerText}</pre>
        </section>
      ) : null}
    </div>
  );
}
