import type { PromptHistoryRecord } from "./promptHistoryData";
import { formatPromptTimestamp, promptResultStatus } from "./promptHistoryData";

type PromptHistorySummaryProps = {
  records: PromptHistoryRecord[];
};

export function PromptHistorySummary({ records }: PromptHistorySummaryProps): JSX.Element {
  const resultCount = records.filter((record) => record.result.canonicalizationPlan || record.result.execution).length;
  const latestRecord = records[0] ?? null;

  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 12, marginBottom: 16 }}>
      <SummaryCard label="Total prompts" value={records.length} />
      <SummaryCard label="Results" value={resultCount} />
      <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
        <strong>Latest</strong>
        <div style={{ fontWeight: 700, marginTop: 6 }}>{latestRecord?.requestType ?? "-"}</div>
        <small style={{ color: "#666" }}>{latestRecord ? formatPromptTimestamp(latestRecord.submittedAt) : "No prompt yet"}</small>
      </article>
    </div>
  );
}

function SummaryCard({ label, value }: { label: string; value: number }): JSX.Element {
  return (
    <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
      <strong>{label}</strong>
      <div style={{ fontSize: 28, fontWeight: 700 }}>{value}</div>
    </article>
  );
}

export function PromptHistoryWarnings({ warnings }: { warnings: string[] }): JSX.Element | null {
  if (warnings.length === 0) {
    return null;
  }

  return (
    <div style={{ marginBottom: 16, padding: 12, border: "1px solid #d29922", borderRadius: 8, background: "#fff8c5", color: "#6f4e00" }}>
      <strong>Partial history loaded.</strong>
      <ul style={{ marginBottom: 0 }}>
        {warnings.map((warning, index) => (
          <li key={`${warning}-${index}`}>{warning}</li>
        ))}
      </ul>
    </div>
  );
}

export function PromptHistoryRecords({
  loading,
  records
}: {
  loading: boolean;
  records: PromptHistoryRecord[];
}): JSX.Element {
  if (loading) {
    return <p>Loading previous prompts...</p>;
  }
  if (records.length === 0) {
    return <p style={{ color: "#666" }}>No previous prompts recorded.</p>;
  }

  return (
    <div style={{ display: "grid", gap: 12 }}>
      {records.map((record, index) => (
        <PromptHistoryRecordCard key={`${record.sourceId}-${record.requestId}-${index}`} record={record} />
      ))}
    </div>
  );
}

function PromptHistoryRecordCard({ record }: { record: PromptHistoryRecord }): JSX.Element {
  return (
    <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: 8, marginBottom: 10 }}>
        <PromptRecordMetric label={record.requestType} value={record.sourceLabel} />
        <PromptRecordMetric label="Status" value={record.status} />
        <PromptRecordMetric label="Submitted" value={formatPromptTimestamp(record.submittedAt)} />
        <PromptRecordMetric label="Tenant" value={record.tenantId} />
      </div>

      <div style={{ color: "#57606a", fontSize: 12, marginBottom: 8 }}>Request ID: {record.requestId}</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: 12 }}>
        <PromptJsonBlock title="Prompt" value={record.payload ?? record.raw} />
        <PromptJsonBlock
          title={`Result: ${promptResultStatus(record)}`}
          value={
            record.result.canonicalizationPlan || record.result.execution
              ? record.result
              : "No canonicalization or execution result recorded yet."
          }
        />
      </div>
    </article>
  );
}

function PromptRecordMetric({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div>
      <strong>{label}</strong>
      <div style={{ color: "#57606a", fontSize: 13 }}>{value}</div>
    </div>
  );
}

function PromptJsonBlock({ title, value }: { title: string; value: unknown }): JSX.Element {
  const text = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  return (
    <div>
      <strong>{title}</strong>
      <pre
        style={{
          margin: "8px 0 0",
          border: "1px solid #e5e7eb",
          borderRadius: 8,
          padding: 10,
          background: "#f6f8fa",
          whiteSpace: "pre-wrap",
          overflowX: "auto"
        }}
      >
        {text}
      </pre>
    </div>
  );
}
