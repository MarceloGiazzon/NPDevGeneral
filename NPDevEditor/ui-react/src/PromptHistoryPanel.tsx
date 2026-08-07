import { useEffect, useState } from "react";
import { fetchPromptHistory, type PromptHistoryRecord } from "./promptHistoryData";
import { PromptHistoryRecords, PromptHistorySummary, PromptHistoryWarnings } from "./PromptHistoryViews";

export { fetchPromptHistory } from "./promptHistoryData";
export type { PromptHistoryLoadResult, PromptHistoryRecord, PromptHistorySource } from "./promptHistoryData";

export default function PromptHistoryPanel(): JSX.Element {
  const [records, setRecords] = useState<PromptHistoryRecord[]>([]);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>("");

  async function loadHistory(): Promise<void> {
    setLoading(true);
    setError("");

    try {
      const result = await fetchPromptHistory();
      setRecords(result.records);
      setWarnings(result.warnings);
    } catch (err) {
      setRecords([]);
      setWarnings([]);
      setError(err instanceof Error ? err.message : "Failed to load prompt history.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadHistory();
  }, []);

  return (
    <section style={{ padding: 16 }}>
      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 12,
          alignItems: "center",
          justifyContent: "space-between",
          marginBottom: 16
        }}
      >
        <div>
          <h2 style={{ marginTop: 0, marginBottom: 8 }}>Prompt History</h2>
          <p style={{ margin: 0, color: "#555" }}>
            Previous semantic behavior prompts submitted through the runtime write-back API.
          </p>
        </div>

        <button
          type="button"
          onClick={() => {
            void loadHistory();
          }}
          disabled={loading}
          style={{
            padding: "10px 14px",
            borderRadius: 8,
            border: "1px solid #1f6feb",
            background: "#1f6feb",
            color: "#fff",
            cursor: loading ? "not-allowed" : "pointer"
          }}
        >
          {loading ? "Loading..." : "Reload history"}
        </button>
      </div>

      {error ? (
        <div
          style={{
            marginBottom: 16,
            padding: 12,
            border: "1px solid #d9534f",
            borderRadius: 8,
            background: "#fff4f4",
            color: "#8a1f17"
          }}
        >
          {error}
        </div>
      ) : null}

      {!error ? <PromptHistoryWarnings warnings={warnings} /> : null}
      <PromptHistorySummary records={records} />
      <PromptHistoryRecords loading={loading} records={records} />
    </section>
  );
}
