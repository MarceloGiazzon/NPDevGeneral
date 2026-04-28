import React, { useEffect, useMemo, useState } from "react";
import { withApiKeyHeaders } from "./api/apiKey";

export type PromptHistorySource = {
  id: string;
  label: string;
  requestEndpoint: string;
  planEndpoint: string;
  executionEndpoint: string;
};

type PromptHistoryResponse = {
  count?: number;
  items?: Record<string, unknown>[];
};

export type PromptHistoryResult = {
  canonicalizationPlan?: Record<string, unknown>;
  execution?: Record<string, unknown>;
};

export type PromptHistoryRecord = {
  sourceId: string;
  sourceLabel: string;
  requestId: string;
  requestType: string;
  status: string;
  submittedAt: string;
  tenantId: string;
  payload: unknown;
  result: PromptHistoryResult;
  raw: Record<string, unknown>;
};

export type PromptHistoryLoadResult = {
  records: PromptHistoryRecord[];
  warnings: string[];
};

const HISTORY_SOURCES: PromptHistorySource[] = [
  {
    id: "structural",
    label: "Structural",
    requestEndpoint: "/api/admin/model/structural-writeback/history",
    planEndpoint: "/api/admin/model/structural-writeback/canonicalization/history",
    executionEndpoint: "/api/admin/model/structural-writeback/execution/history"
  },
  {
    id: "semantic-behavior",
    label: "Semantic behavior",
    requestEndpoint: "/api/admin/model/semantic-behavior-writeback/history",
    planEndpoint: "/api/admin/model/semantic-behavior-writeback/canonicalization/history",
    executionEndpoint: "/api/admin/model/semantic-behavior-writeback/execution/history"
  }
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringify(value: unknown, fallback = "—"): string {
  if (value === null || value === undefined) {
    return fallback;
  }
  const text = String(value).trim();
  return text ? text : fallback;
}

function idValue(value: unknown): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  const text = String(value).trim();
  return text ? text : null;
}

function timestampFrom(value: unknown): number {
  const parsed = Date.parse(stringify(value, ""));
  return Number.isNaN(parsed) ? 0 : parsed;
}

function timestampValue(record: PromptHistoryRecord): number {
  return Math.max(
    timestampFrom(record.submittedAt),
    timestampFrom(record.result.canonicalizationPlan?.plannedAt),
    timestampFrom(record.result.execution?.executedAt)
  );
}

function formatTimestamp(value: string): string {
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) {
    return value || "—";
  }
  return new Date(parsed).toLocaleString();
}

function resultStatus(record: PromptHistoryRecord): string {
  if (record.result.execution) {
    return stringify(record.result.execution.status);
  }
  if (record.result.canonicalizationPlan) {
    return stringify(
      record.result.canonicalizationPlan.outcome ?? record.result.canonicalizationPlan.status
    );
  }
  return "Pending";
}

function normalizePromptRecord(
  source: PromptHistorySource,
  item: Record<string, unknown>,
  result: PromptHistoryResult = {}
): PromptHistoryRecord {
  return {
    sourceId: source.id,
    sourceLabel: source.label,
    requestId: stringify(item.requestId),
    requestType: stringify(item.requestType),
    status: stringify(item.status),
    submittedAt: stringify(item.submittedAt, ""),
    tenantId: stringify(item.tenantId),
    payload: item.payload,
    result,
    raw: item
  };
}

function putByRequestId(
  target: Map<string, Record<string, unknown>>,
  item: Record<string, unknown>
): void {
  const requestId = idValue(item.requestId);
  if (requestId && !target.has(requestId)) {
    target.set(requestId, item);
  }
}

function mergePromptHistoryRecords(
  source: PromptHistorySource,
  requests: Record<string, unknown>[],
  plans: Record<string, unknown>[],
  executions: Record<string, unknown>[]
): PromptHistoryRecord[] {
  const plansByRequestId = new Map<string, Record<string, unknown>>();
  const executionsByRequestId = new Map<string, Record<string, unknown>>();

  for (const plan of plans) {
    putByRequestId(plansByRequestId, plan);
  }
  for (const execution of executions) {
    putByRequestId(executionsByRequestId, execution);
  }

  const seenRequestIds = new Set<string>();
  const records = requests.map((request) => {
    const requestId = idValue(request.requestId);
    if (requestId) {
      seenRequestIds.add(requestId);
    }
    return normalizePromptRecord(source, request, {
      canonicalizationPlan: requestId ? plansByRequestId.get(requestId) : undefined,
      execution: requestId ? executionsByRequestId.get(requestId) : undefined
    });
  });

  for (const [requestId, plan] of plansByRequestId) {
    if (!seenRequestIds.has(requestId)) {
      seenRequestIds.add(requestId);
      records.push(normalizePromptRecord(source, plan, {
        canonicalizationPlan: plan
      }));
    }
  }

  for (const [requestId, execution] of executionsByRequestId) {
    if (!seenRequestIds.has(requestId)) {
      seenRequestIds.add(requestId);
      records.push(normalizePromptRecord(source, execution, {
        execution
      }));
    }
  }

  return records;
}

async function fetchHistoryItems(endpoint: string, label: string): Promise<Record<string, unknown>[]> {
  const response = await fetch(endpoint, {
    headers: withApiKeyHeaders({
      Accept: "application/json"
    })
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${label} failed: ${text || "HTTP " + response.status}`);
  }

  const payload = (await response.json()) as PromptHistoryResponse;
  return Array.isArray(payload.items) ? payload.items.filter(isRecord) : [];
}

async function fetchPromptHistorySource(source: PromptHistorySource): Promise<PromptHistoryLoadResult> {
  const endpoints = [
    {
      key: "requests",
      label: `${source.label} request history`,
      endpoint: source.requestEndpoint
    },
    {
      key: "plans",
      label: `${source.label} canonicalization result history`,
      endpoint: source.planEndpoint
    },
    {
      key: "executions",
      label: `${source.label} execution result history`,
      endpoint: source.executionEndpoint
    }
  ] as const;

  const settled = await Promise.allSettled(
    endpoints.map((entry) => fetchHistoryItems(entry.endpoint, entry.label))
  );
  const warnings: string[] = [];
  const buckets: Record<(typeof endpoints)[number]["key"], Record<string, unknown>[]> = {
    requests: [],
    plans: [],
    executions: []
  };

  settled.forEach((result, index) => {
    const endpoint = endpoints[index];
    if (result.status === "fulfilled") {
      buckets[endpoint.key] = result.value;
    } else {
      warnings.push(result.reason instanceof Error ? result.reason.message : String(result.reason));
    }
  });

  return {
    records: mergePromptHistoryRecords(source, buckets.requests, buckets.plans, buckets.executions),
    warnings
  };
}

export async function fetchPromptHistory(
  sources: PromptHistorySource[] = HISTORY_SOURCES
): Promise<PromptHistoryLoadResult> {
  const sourceResults = await Promise.all(sources.map(fetchPromptHistorySource));
  return {
    records: sourceResults
      .flatMap((result) => result.records)
      .sort((left, right) => timestampValue(right) - timestampValue(left)),
    warnings: sourceResults.flatMap((result) => result.warnings)
  };
}

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

  const structuralCount = useMemo(
    () => records.filter((record) => record.sourceId === "structural").length,
    [records]
  );
  const semanticBehaviorCount = records.length - structuralCount;
  const resultCount = records.filter(
    (record) => record.result.canonicalizationPlan || record.result.execution
  ).length;
  const latestRecord = records[0] ?? null;

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
            Previous structural and semantic behavior prompts submitted through runtime write-back APIs.
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

      {!error && warnings.length > 0 ? (
        <div
          style={{
            marginBottom: 16,
            padding: 12,
            border: "1px solid #d29922",
            borderRadius: 8,
            background: "#fff8c5",
            color: "#6f4e00"
          }}
        >
          <strong>Partial history loaded.</strong>
          <ul style={{ marginBottom: 0 }}>
            {warnings.map((warning, index) => (
              <li key={`${warning}-${index}`}>{warning}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
          gap: 12,
          marginBottom: 16
        }}
      >
        <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
          <strong>Total prompts</strong>
          <div style={{ fontSize: 28, fontWeight: 700 }}>{records.length}</div>
        </article>
        <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
          <strong>Results</strong>
          <div style={{ fontSize: 28, fontWeight: 700 }}>{resultCount}</div>
        </article>
        <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
          <strong>Structural</strong>
          <div style={{ fontSize: 28, fontWeight: 700 }}>{structuralCount}</div>
        </article>
        <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
          <strong>Semantic behavior</strong>
          <div style={{ fontSize: 28, fontWeight: 700 }}>{semanticBehaviorCount}</div>
        </article>
        <article style={{ border: "1px solid #d0d7de", borderRadius: 8, padding: 12, background: "#fff" }}>
          <strong>Latest</strong>
          <div style={{ fontWeight: 700, marginTop: 6 }}>{latestRecord?.requestType ?? "—"}</div>
          <small style={{ color: "#666" }}>{latestRecord ? formatTimestamp(latestRecord.submittedAt) : "No prompt yet"}</small>
        </article>
      </div>

      {loading ? (
        <p>Loading previous prompts...</p>
      ) : records.length === 0 ? (
        <p style={{ color: "#666" }}>No previous prompts recorded.</p>
      ) : (
        <div style={{ display: "grid", gap: 12 }}>
          {records.map((record, index) => (
            <article
              key={`${record.sourceId}-${record.requestId}-${index}`}
              style={{
                border: "1px solid #d0d7de",
                borderRadius: 8,
                padding: 12,
                background: "#fff"
              }}
            >
              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))",
                  gap: 8,
                  marginBottom: 10
                }}
              >
                <div>
                  <strong>{record.requestType}</strong>
                  <div style={{ color: "#57606a", fontSize: 13 }}>{record.sourceLabel}</div>
                </div>
                <div>
                  <strong>Status</strong>
                  <div style={{ color: "#57606a", fontSize: 13 }}>{record.status}</div>
                </div>
                <div>
                  <strong>Submitted</strong>
                  <div style={{ color: "#57606a", fontSize: 13 }}>{formatTimestamp(record.submittedAt)}</div>
                </div>
                <div>
                  <strong>Tenant</strong>
                  <div style={{ color: "#57606a", fontSize: 13 }}>{record.tenantId}</div>
                </div>
              </div>

              <div style={{ color: "#57606a", fontSize: 12, marginBottom: 8 }}>Request ID: {record.requestId}</div>
              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))",
                  gap: 12
                }}
              >
                <div>
                  <strong>Prompt</strong>
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
                    {JSON.stringify(record.payload ?? record.raw, null, 2)}
                  </pre>
                </div>
                <div>
                  <strong>Result: {resultStatus(record)}</strong>
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
                    {record.result.canonicalizationPlan || record.result.execution
                      ? JSON.stringify(record.result, null, 2)
                      : "No canonicalization or execution result recorded yet."}
                  </pre>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
