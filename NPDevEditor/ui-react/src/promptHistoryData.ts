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

export function stringifyPromptValue(value: unknown, fallback = "-"): string {
  if (value === null || value === undefined) {
    return fallback;
  }
  const text = String(value).trim();
  return text ? text : fallback;
}

function idValue(value: unknown): string | null {
  const text = stringifyPromptValue(value, "").trim();
  return text ? text : null;
}

function timestampFrom(value: unknown): number {
  const parsed = Date.parse(stringifyPromptValue(value, ""));
  return Number.isNaN(parsed) ? 0 : parsed;
}

function timestampValue(record: PromptHistoryRecord): number {
  return Math.max(
    timestampFrom(record.submittedAt),
    timestampFrom(record.result.canonicalizationPlan?.plannedAt),
    timestampFrom(record.result.execution?.executedAt)
  );
}

export function formatPromptTimestamp(value: string): string {
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) {
    return value || "-";
  }
  return new Date(parsed).toLocaleString();
}

export function promptResultStatus(record: PromptHistoryRecord): string {
  if (record.result.execution) {
    return stringifyPromptValue(record.result.execution.status);
  }
  if (record.result.canonicalizationPlan) {
    return stringifyPromptValue(record.result.canonicalizationPlan.outcome ?? record.result.canonicalizationPlan.status);
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
    requestId: stringifyPromptValue(item.requestId),
    requestType: stringifyPromptValue(item.requestType),
    status: stringifyPromptValue(item.status),
    submittedAt: stringifyPromptValue(item.submittedAt, ""),
    tenantId: stringifyPromptValue(item.tenantId),
    payload: item.payload,
    result,
    raw: item
  };
}

function putByRequestId(target: Map<string, Record<string, unknown>>, item: Record<string, unknown>): void {
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
  plans.forEach((plan) => putByRequestId(plansByRequestId, plan));
  executions.forEach((execution) => putByRequestId(executionsByRequestId, execution));

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
      records.push(normalizePromptRecord(source, plan, { canonicalizationPlan: plan }));
    }
  }
  for (const [requestId, execution] of executionsByRequestId) {
    if (!seenRequestIds.has(requestId)) {
      seenRequestIds.add(requestId);
      records.push(normalizePromptRecord(source, execution, { execution }));
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
    { key: "requests", label: `${source.label} request history`, endpoint: source.requestEndpoint },
    { key: "plans", label: `${source.label} canonicalization result history`, endpoint: source.planEndpoint },
    { key: "executions", label: `${source.label} execution result history`, endpoint: source.executionEndpoint }
  ] as const;

  const settled = await Promise.allSettled(endpoints.map((entry) => fetchHistoryItems(entry.endpoint, entry.label)));
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
