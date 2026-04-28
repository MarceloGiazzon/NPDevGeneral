import React, { useEffect, useState } from "react";

type TemplateResponse = {
  catalogPath: string;
  template: Record<string, unknown>;
};

type SummaryResponse = {
  catalogPath: string;
  version: number;
  templates: Record<string, unknown>[];
};

type SpecializationResponse = {
  specializationId: string;
  templateId: string;
  specializationName: string;
  createdAt: string;
  status: string;
  message: string;
};

export default function ScenarioTemplatePanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [templateText, setTemplateText] = useState<string>("");
  const [specializationText, setSpecializationText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/templates/scenarios", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load scenario templates: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload: SummaryResponse) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });
  }, []);

  async function loadTemplate(templateId: string) {
    setErrorText("");
    setTemplateText("");

    try {
      const response = await fetch("/api/admin/templates/scenarios/" + templateId, {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const text = await response.text();
      if (!response.ok) {
        throw new Error(text || ("HTTP " + response.status));
      }

      const parsed: TemplateResponse = JSON.parse(text);
      setTemplateText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  async function specializeSample() {
    setErrorText("");
    setSpecializationText("");

    const body = {
      templateId: "expense-request",
      specializationName: "Expense Request for Field Teams",
      inputs: {
        businessName: "Field Operations",
        expenseLabel: "Travel Expense",
        approvalStages: 2,
        auditExportEnabled: true
      }
    };

    try {
      const response = await fetch("/api/admin/templates/scenarios/specialize", {
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

      const parsed: SpecializationResponse = JSON.parse(text);
      setSpecializationText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Scenario Templates and Guided Specialization</h2>
      <p>
        Inspect scenario templates and record guided specialization requests.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={() => loadTemplate("service-intake")}>Load service-intake</button>{" "}
        <button type="button" onClick={() => loadTemplate("approval-workflow")}>Load approval-workflow</button>{" "}
        <button type="button" onClick={() => loadTemplate("expense-request")}>Load expense-request</button>{" "}
        <button type="button" onClick={specializeSample}>Record sample specialization</button>
      </div>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {summaryText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Template summary</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{summaryText}</pre>
        </section>
      ) : null}

      {templateText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Template detail</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{templateText}</pre>
        </section>
      ) : null}

      {specializationText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Specialization response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{specializationText}</pre>
        </section>
      ) : null}
    </div>
  );
}