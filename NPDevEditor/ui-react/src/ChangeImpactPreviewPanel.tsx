import React, { useEffect, useState } from "react";

type PreviewResponse = {
  requestType: string;
  payload: Record<string, unknown>;
  impactedSurface: string[];
  risk: string;
  compatibility: string;
  recommendation: string;
  previewGeneratedAt: string;
};

type HistoryItem = {
  sourceType: string;
  requestId: string;
  requestType: string;
  status: string;
  submittedAt: string;
  payload: Record<string, unknown>;
};

type HistoryResponse = {
  count: number;
  items: HistoryItem[];
};

export default function ChangeImpactPreviewPanel() {
  const [previewText, setPreviewText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/model/change-preview/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load version history: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload: HistoryResponse) => {
        setHistoryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });
  }, []);

  async function runPreview() {
    setErrorText("");
    setPreviewText("");

    const body = {
      requestType: "addAwaitEventStep",
      payload: {
        requestType: "addAwaitEventStep",
        flowName: "SubmitExpense",
        stepName: "await-finance-approval",
        eventName: "FinanceApproved",
        correlationField: "expenseId",
        notes: "Wait for finance approval before continuing."
      }
    };

    try {
      const response = await fetch("/api/admin/model/change-preview", {
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

  return (
    <div style={{ padding: "16px" }}>
      <h2>Change Impact Preview and Version History</h2>
      <p>
        Preview likely impact before publication and inspect recent semantic request history.
      </p>

      <button type="button" onClick={runPreview}>
        Run sample preview
      </button>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {previewText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Preview response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{previewText}</pre>
        </section>
      ) : null}

      {historyText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Version history</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{historyText}</pre>
        </section>
      ) : null}
    </div>
  );
}