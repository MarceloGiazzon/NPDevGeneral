import React, { useEffect, useState } from "react";

type PreviewResponse = {
  templateId: string;
  spreadsheetColumnCount: number;
  mappingCount: number;
  starter: Record<string, unknown>;
  missingSuggestedColumns: string[];
  previewStatus: string;
};

type RecordResponse = {
  onboardingId: string;
  templateId: string;
  onboardingName: string;
  createdAt: string;
  status: string;
  message: string;
};

export default function SpreadsheetOnboardingPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [previewText, setPreviewText] = useState<string>("");
  const [recordText, setRecordText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/import-onboarding", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load import onboarding summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });
  }, []);

  async function previewSample() {
    setErrorText("");
    setPreviewText("");

    const body = {
      templateId: "expense-request",
      spreadsheetColumns: [
        "Expense ID",
        "Employee",
        "Amount",
        "Category",
        "Approval Status"
      ],
      mappings: {
        "Expense ID": "expenseId",
        "Employee": "employee",
        "Amount": "amount",
        "Category": "category",
        "Approval Status": "approvalStatus"
      }
    };

    try {
      const response = await fetch("/api/admin/import-onboarding/preview", {
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

      const parsed: PreviewResponse = JSON.parse(text);
      setPreviewText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  async function recordSample() {
    setErrorText("");
    setRecordText("");

    const body = {
      templateId: "expense-request",
      onboardingName: "Expense spreadsheet onboarding",
      mappings: {
        "Expense ID": "expenseId",
        "Employee": "employee",
        "Amount": "amount",
        "Category": "category",
        "Approval Status": "approvalStatus"
      }
    };

    try {
      const response = await fetch("/api/admin/import-onboarding/record", {
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

      const parsed: RecordResponse = JSON.parse(text);
      setRecordText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Import Mapping and Spreadsheet-to-Scenario Onboarding</h2>
      <p>
        Preview spreadsheet mappings and record onboarding requests.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={previewSample}>Preview sample mapping</button>{" "}
        <button type="button" onClick={recordSample}>Record sample onboarding</button>
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

      {previewText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Preview response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{previewText}</pre>
        </section>
      ) : null}

      {recordText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Onboarding response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{recordText}</pre>
        </section>
      ) : null}
    </div>
  );
}