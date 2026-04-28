import React, { useEffect, useState } from "react";

type GovernanceHistoryResponse = {
  count: number;
  items: Record<string, unknown>[];
};

type GovernanceResponse = {
  governanceId: string;
  sourceType: string;
  sourceRequestId: string;
  status: string;
  updatedAt: string;
  message: string;
};

export default function SemanticGovernancePanel() {
  const [historyText, setHistoryText] = useState<string>("");
  const [responseText, setResponseText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/model/governance/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load governance history: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload: GovernanceHistoryResponse) => {
        setHistoryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });
  }, []);

  async function runSampleLifecycle() {
    setErrorText("");
    setResponseText("");

    try {
      const candidatesResponse = await fetch("/api/admin/model/rollback/candidates", {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const candidatesText = await candidatesResponse.text();
      if (!candidatesResponse.ok) {
        throw new Error(candidatesText || ("HTTP " + candidatesResponse.status));
      }

      const parsedCandidates = JSON.parse(candidatesText);
      if (!parsedCandidates.items || !parsedCandidates.items.length) {
        throw new Error("No semantic request candidate exists yet for governance draft.");
      }

      const candidate = parsedCandidates.items[0];

      const draftBody = {
        requestType: "governedChange",
        sourceType: candidate.sourceType,
        sourceRequestId: candidate.requestId,
        title: "Sample governed publication",
        comment: "Draft created from Step 59 panel.",
        metadata: {
          createdBy: "panel-sample"
        }
      };

      const draftResponse = await fetch("/api/admin/model/governance/draft", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-API-Key": "dev-key"
        },
        body: JSON.stringify(draftBody)
      });

      const draftText = await draftResponse.text();
      if (!draftResponse.ok) {
        throw new Error(draftText || ("HTTP " + draftResponse.status));
      }

      const draftParsed: GovernanceResponse = JSON.parse(draftText);

      const steps = [
        { url: "/api/admin/model/governance/review", comment: "Move draft to review." },
        { url: "/api/admin/model/governance/approve", comment: "Approve reviewed change." },
        { url: "/api/admin/model/governance/publish", comment: "Publish approved change." }
      ];

      let latest: GovernanceResponse = draftParsed;

      for (const step of steps) {
        const response = await fetch(step.url, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-API-Key": "dev-key"
          },
          body: JSON.stringify({
            governanceId: latest.governanceId,
            comment: step.comment
          })
        });

        const text = await response.text();
        if (!response.ok) {
          throw new Error(text || ("HTTP " + response.status));
        }

        latest = JSON.parse(text);
      }

      setResponseText(JSON.stringify(latest, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Draft, Review, Approve, Publish</h2>
      <p>
        Create and move governed semantic changes through publication lifecycle.
      </p>

      <button type="button" onClick={runSampleLifecycle}>
        Run sample governance lifecycle
      </button>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {responseText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Latest response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{responseText}</pre>
        </section>
      ) : null}

      {historyText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Governance history</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{historyText}</pre>
        </section>
      ) : null}
    </div>
  );
}