import React, { useEffect, useState } from "react";

type RollbackCandidate = {
  sourceType: string;
  requestId: string;
  requestType: string;
  status: string;
  submittedAt: string;
  payload: Record<string, unknown>;
};

type RollbackCandidatesResponse = {
  count: number;
  items: RollbackCandidate[];
};

type RollbackHistoryResponse = {
  count: number;
  items: Record<string, unknown>[];
};

type RollbackResponse = {
  rollbackId: string;
  status: string;
  sourceType: string;
  targetRequestId: string;
  targetRequestType: string;
  executedAt: string;
  message: string;
};

export default function SemanticRollbackPanel() {
  const [candidatesText, setCandidatesText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [responseText, setResponseText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/model/rollback/candidates", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load rollback candidates: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload: RollbackCandidatesResponse) => {
        setCandidatesText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/model/rollback/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load rollback history: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload: RollbackHistoryResponse) => {
        setHistoryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });
  }, []);

  async function runSampleRollback() {
    setErrorText("");
    setResponseText("");

    try {
      const candidatesResponse = await fetch("/api/admin/model/rollback/candidates", {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const candidatesTextRaw = await candidatesResponse.text();
      if (!candidatesResponse.ok) {
        throw new Error(candidatesTextRaw || ("HTTP " + candidatesResponse.status));
      }

      const parsedCandidates: RollbackCandidatesResponse = JSON.parse(candidatesTextRaw);
      if (!parsedCandidates.items.length) {
        throw new Error("No rollback candidates available yet.");
      }

      const candidate = parsedCandidates.items[0];

      const body = {
        sourceType: candidate.sourceType,
        targetRequestId: candidate.requestId,
        reason: "Manual rollback test from Step 58 panel."
      };

      const response = await fetch("/api/admin/model/rollback", {
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

      const parsed: RollbackResponse = JSON.parse(text);
      setResponseText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Rollback and Safe Restore</h2>
      <p>
        Inspect rollback candidates, view rollback history, and record a rollback action.
      </p>

      <button type="button" onClick={runSampleRollback}>
        Execute sample rollback
      </button>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {responseText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Rollback response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{responseText}</pre>
        </section>
      ) : null}

      {candidatesText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Rollback candidates</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{candidatesText}</pre>
        </section>
      ) : null}

      {historyText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Rollback history</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{historyText}</pre>
        </section>
      ) : null}
    </div>
  );
}