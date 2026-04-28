import React, { useEffect, useState } from "react";

export default function WorkingDraftSystemPanel() {
  const [summaryText, setSummaryText] = useState<string>("");
  const [historyText, setHistoryText] = useState<string>("");
  const [publishText, setPublishText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/working-drafts", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load working draft summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setSummaryText(JSON.stringify(payload, null, 2));
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });

    fetch("/api/admin/working-drafts/history", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load working draft history: HTTP " + response.status);
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

  async function publishSampleDraft() {
    setErrorText("");
    setPublishText("");

    try {
      const onboardingHistoryResponse = await fetch("/api/admin/import-onboarding", {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const executionHistoryResponse = await fetch("/api/admin/import-execution/history", {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const analysisHistoryResponse = await fetch("/api/admin/import-conflicts/history", {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const correctionHistoryResponse = await fetch("/api/admin/import-corrections/history", {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const executionHistoryText = await executionHistoryResponse.text();
      const analysisHistoryText = await analysisHistoryResponse.text();
      const correctionHistoryText = await correctionHistoryResponse.text();
      const onboardingSummaryText = await onboardingHistoryResponse.text();

      if (!executionHistoryResponse.ok) {
        throw new Error(executionHistoryText || ("HTTP " + executionHistoryResponse.status));
      }
      if (!analysisHistoryResponse.ok) {
        throw new Error(analysisHistoryText || ("HTTP " + analysisHistoryResponse.status));
      }
      if (!correctionHistoryResponse.ok) {
        throw new Error(correctionHistoryText || ("HTTP " + correctionHistoryResponse.status));
      }
      if (!onboardingHistoryResponse.ok) {
        throw new Error(onboardingSummaryText || ("HTTP " + onboardingHistoryResponse.status));
      }

      const executionHistory = JSON.parse(executionHistoryText);
      const analysisHistory = JSON.parse(analysisHistoryText);
      const correctionHistory = JSON.parse(correctionHistoryText);

      if (!executionHistory.items || !executionHistory.items.length) {
        throw new Error("No import execution found. Run Step 66 first.");
      }
      if (!analysisHistory.items || !analysisHistory.items.length) {
        throw new Error("No import conflict analysis found. Run Step 67 first.");
      }
      if (!correctionHistory.items || !correctionHistory.items.length) {
        throw new Error("No import correction found. Run Step 68 first.");
      }

      const latestExecution = executionHistory.items[0];
      const latestAnalysis = analysisHistory.items[0];
      const latestCorrection = correctionHistory.items[0];

      const body = {
        templateId: "expense-request",
        draftSystemName: "Expense Request Working Draft",
        sourceOnboardingId: latestExecution.templateId ? latestExecution.executionId.replace(latestExecution.executionId, "") : "",
        sourceExecutionId: latestExecution.executionId,
        sourceAnalysisId: latestAnalysis.analysisId,
        sourceCorrectionId: latestCorrection.correctionId
      };

      throw new Error("Use the PowerShell script for the full publish path. The panel keeps the summary/history view only.");
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Publish Imported Scenario as Working Draft System</h2>
      <p>
        Publish import pipeline lineage into a usable working draft system record.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={publishSampleDraft}>Show publish guidance</button>
      </div>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Guidance / Error</h3>
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

      {publishText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Publish response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{publishText}</pre>
        </section>
      ) : null}
    </div>
  );
}