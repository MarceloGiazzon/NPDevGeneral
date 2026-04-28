import type {
  AuditRecordItem,
  CorrelationTimelineItem,
  CorrelationTimelineResponse,
  ModelEditorDraft,
  RuleEditorDraft,
  RuntimeEventItem,
  UiModelResponse
} from "../types";
import { withApiKeyHeaders } from "./apiKey";

function jsonHeaders(): Headers {
  return withApiKeyHeaders({
    "Content-Type": "application/json"
  });
}

export type ApiError = {
  message: string;
  status?: number;
  statusText?: string;
};

class HttpError extends Error {
  readonly status: number;
  readonly statusText: string;

  constructor(message: string, status: number, statusText: string) {
    super(message);
    this.name = "HttpError";
    this.status = status;
    this.statusText = statusText;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

async function buildHttpError(response: Response): Promise<HttpError> {
  let detail = "";
  const contentType = response.headers.get("content-type") ?? "";

  try {
    if (contentType.includes("application/json")) {
      const payload = await response.json();
      if (isRecord(payload)) {
        detail = String(payload.message ?? payload.error ?? payload.statusText ?? "");
      }
    } else {
      detail = (await response.text()).trim();
    }
  } catch {
    detail = "";
  }

  const fallback = `HTTP ${response.status} ${response.statusText}`;
  return new HttpError(detail || fallback, response.status, response.statusText);
}

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await buildHttpError(response);
  }
  return (await response.json()) as T;
}

async function get<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    method: "GET",
    headers: jsonHeaders()
  });
  return readJson<T>(response);
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify(body)
  });
  return readJson<T>(response);
}

async function deleteRequest(path: string): Promise<void> {
  const response = await fetch(path, {
    method: "DELETE",
    headers: jsonHeaders()
  });

  if (!response.ok && response.status !== 204) {
    throw await buildHttpError(response);
  }
}

function buildCorrelationPath(correlationId: string, limit = 50, offset = 0): string {
  const params = new URLSearchParams({
    limit: String(limit),
    offset: String(offset)
  });
  return `/api/correlations/${encodeURIComponent(correlationId)}?${params.toString()}`;
}

function toTimelineItems(response: CorrelationTimelineResponse): CorrelationTimelineItem[] {
  const executionItems: CorrelationTimelineItem[] = (response.executions ?? []).map((execution) => ({
    kind: "execution",
    summary: `${execution.flowName ?? "Flow"} | ${execution.status ?? "UNKNOWN"}`,
    message: execution.executionId ?? "",
    occurredAt: execution.updatedAtEpochMs ? new Date(execution.updatedAtEpochMs).toISOString() : undefined
  }));

  const eventItems: CorrelationTimelineItem[] = (response.events ?? []).map((event) => ({
    kind: "event",
    summary: event.eventName ?? "Event",
    message: event.eventId ?? "",
    occurredAt: event.timestampMs ? new Date(event.timestampMs).toISOString() : undefined
  }));

  return [...executionItems, ...eventItems].sort((left, right) => {
    const leftTime = left.occurredAt ? Date.parse(left.occurredAt) : 0;
    const rightTime = right.occurredAt ? Date.parse(right.occurredAt) : 0;
    return rightTime - leftTime;
  });
}

export function asApiError(error: unknown): ApiError {
  if (error instanceof HttpError) {
    return {
      message: error.message,
      status: error.status,
      statusText: error.statusText
    };
  }

  if (error instanceof Error) {
    return {
      message: error.message
    };
  }

  return {
    message: "Unexpected API error."
  };
}

export async function fetchUiModel(): Promise<UiModelResponse> {
  return get<UiModelResponse>("/api/admin/ui-model");
}

export async function fetchRuntimeEvents(): Promise<RuntimeEventItem[]> {
  return get<RuntimeEventItem[]>("/api/runtime/events");
}

export async function fetchAuditRecords(): Promise<AuditRecordItem[]> {
  return get<AuditRecordItem[]>("/api/audit");
}

export async function listAuditTimeline(correlationId: string, limit = 50, offset = 0): Promise<CorrelationTimelineResponse> {
  return get<CorrelationTimelineResponse>(buildCorrelationPath(correlationId, limit, offset));
}

export async function fetchCorrelationTimeline(correlationId: string, limit = 50, offset = 0): Promise<CorrelationTimelineItem[]> {
  const response = await listAuditTimeline(correlationId, limit, offset);
  return toTimelineItems(response);
}

export async function fetchModelEditorDraft(): Promise<ModelEditorDraft> {
  return get<ModelEditorDraft>("/api/admin/model/editor/draft");
}

export async function saveModelEditorDraft(payload: ModelEditorDraft): Promise<ModelEditorDraft> {
  return postJson<ModelEditorDraft>("/api/admin/model/editor/draft", payload);
}

export async function resetModelEditorDraft(): Promise<void> {
  return deleteRequest("/api/admin/model/editor/draft");
}

export async function fetchRuleEditorDraft(): Promise<RuleEditorDraft> {
  return get<RuleEditorDraft>("/api/admin/model/rules/draft");
}

export async function saveRuleEditorDraft(payload: RuleEditorDraft): Promise<RuleEditorDraft> {
  return postJson<RuleEditorDraft>("/api/admin/model/rules/draft", payload);
}

export async function resetRuleEditorDraft(): Promise<void> {
  return deleteRequest("/api/admin/model/rules/draft");
}

export async function fetchOrchestrationEditorDraft(): Promise<unknown> {
  return get<unknown>("/api/admin/model/orchestration/draft");
}

export async function saveOrchestrationEditorDraft(payload: unknown): Promise<unknown> {
  return postJson<unknown>("/api/admin/model/orchestration/draft", payload);
}

export async function resetOrchestrationEditorDraft(): Promise<void> {
  return deleteRequest("/api/admin/model/orchestration/draft");
}

export async function fetchPluginStatus(): Promise<unknown> {
  return get<unknown>("/api/admin/runtime/plugin-status");
}

export async function fetchPluginPackages(): Promise<unknown> {
  return get<unknown>("/api/admin/runtime/plugin-packages");
}

export async function fetchPluginExecutions(): Promise<unknown> {
  return get<unknown>("/api/admin/runtime/plugin-executions");
}

export async function fetchSchedules(): Promise<unknown> {
  return get<unknown>("/api/admin/schedules");
}

export async function fetchRuntimeRefreshStatus(): Promise<unknown> {
  return get<unknown>("/api/admin/runtime/refresh");
}

export async function requestRuntimeRefresh(): Promise<unknown> {
  return postJson<unknown>("/api/admin/runtime/refresh", {});
}

export async function getRuntimeEventEvidence(): Promise<RuntimeEventItem[]> {
  return fetchRuntimeEvents();
}

export async function getRuntimePluginStatus(): Promise<unknown> {
  return fetchPluginStatus();
}

export async function getRuntimePluginPackages(): Promise<unknown> {
  return fetchPluginPackages();
}

export async function getRuntimePluginExecutions(): Promise<unknown> {
  return fetchPluginExecutions();
}

export async function getSchedulesOverview(): Promise<unknown> {
  return fetchSchedules();
}

export const npdevClient = {
  listAuditTimeline,
  getModelEditorDraft: fetchModelEditorDraft,
  saveModelEditorDraft,
  resetModelEditorDraft,
  getRuleEditorDraft: fetchRuleEditorDraft,
  saveRuleEditorDraft,
  resetRuleEditorDraft
};
