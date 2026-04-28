import React, { useEffect, useMemo, useState } from "react";
import {
  fetchAuditRecords,
  fetchCorrelationTimeline,
  fetchRuntimeEvents,
  fetchUiModel
} from "./api/npdevClient";
import type {
  AuditRecordItem,
  CorrelationTimelineItem,
  RuntimeEventItem,
  UiModelConcept,
  UiModelResponse
} from "./types";

type WorkspaceCard = {
  id: string;
  title: string;
  description: string;
  hint: string;
};

const MAX_WORKSPACE_CARDS = 4;

function safeArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : [];
}

function formatTimestamp(value: string | null | undefined): string {
  if (!value) {
    return "—";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
}

function truncate(value: string | null | undefined, maxLength = 120): string {
  if (!value) {
    return "—";
  }
  if (value.length <= maxLength) {
    return value;
  }
  return value.slice(0, maxLength - 3) + "...";
}

function getConceptSummary(concepts: UiModelConcept[]): string {
  if (concepts.length === 0) {
    return "No guided concepts available.";
  }

  return concepts
    .map((concept) => `${concept.name} (${safeArray(concept.fields).length} fields)`)
    .join(" • ");
}

function formatConceptTitle(concept: UiModelConcept): string {
  return concept.ui?.label || concept.ui?.shortLabel || concept.name;
}

function getWorkspaceCards(concepts: UiModelConcept[]): WorkspaceCard[] {
  if (concepts.length === 0) {
    return [
      {
        id: "model-workspace",
        title: "Model workspace",
        description: "Load a generated model to start from its business concepts.",
        hint: "Cards are derived from UI model metadata."
      }
    ];
  }

  return concepts.slice(0, MAX_WORKSPACE_CARDS).map((concept) => {
    const fieldCount = safeArray(concept.fields).length;
    return {
      id: concept.name,
      title: formatConceptTitle(concept),
      description: concept.ui?.description || concept.ui?.helpText || `${fieldCount} fields available in this concept.`,
      hint: `Use this when the task starts from ${formatConceptTitle(concept)}.`
    };
  });
}

export default function BusinessWorkspacePanel(): JSX.Element {
  const [uiModel, setUiModel] = useState<UiModelResponse | null>(null);
  const [events, setEvents] = useState<RuntimeEventItem[]>([]);
  const [auditRecords, setAuditRecords] = useState<AuditRecordItem[]>([]);
  const [timelineItems, setTimelineItems] = useState<CorrelationTimelineItem[]>([]);
  const [selectedWorkspace, setSelectedWorkspace] = useState<string>("");
  const [selectedCorrelationId, setSelectedCorrelationId] = useState<string>("");
  const [loading, setLoading] = useState<boolean>(true);
  const [timelineLoading, setTimelineLoading] = useState<boolean>(false);
  const [error, setError] = useState<string>("");

  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setLoading(true);
      setError("");

      try {
        const [uiModelResult, runtimeEventsResult, auditResult] = await Promise.all([
          fetchUiModel(),
          fetchRuntimeEvents(),
          fetchAuditRecords()
        ]);

        if (cancelled) {
          return;
        }

        setUiModel(uiModelResult);
        setEvents(safeArray<RuntimeEventItem>(runtimeEventsResult));
        setAuditRecords(safeArray<AuditRecordItem>(auditResult));
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load business workspace.");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, []);

  const concepts = useMemo<UiModelConcept[]>(() => {
    if (!uiModel) {
      return [];
    }
    return safeArray<UiModelConcept>(uiModel.concepts);
  }, [uiModel]);

  const recentEvents = useMemo<RuntimeEventItem[]>(() => events.slice(0, 8), [events]);
  const recentAuditRecords = useMemo<AuditRecordItem[]>(() => auditRecords.slice(0, 8), [auditRecords]);
  const workspaceCards = useMemo<WorkspaceCard[]>(() => getWorkspaceCards(concepts), [concepts]);
  const currentWorkspaceCard = useMemo<WorkspaceCard | null>(() => {
    return workspaceCards.find((item) => item.id === selectedWorkspace) ?? workspaceCards[0] ?? null;
  }, [selectedWorkspace, workspaceCards]);

  useEffect(() => {
    if (workspaceCards.length === 0) {
      return;
    }
    if (!selectedWorkspace || !workspaceCards.some((item) => item.id === selectedWorkspace)) {
      setSelectedWorkspace(workspaceCards[0].id);
    }
  }, [selectedWorkspace, workspaceCards]);

  async function handleLoadTimeline(): Promise<void> {
    const correlationId = selectedCorrelationId.trim();
    if (!correlationId) {
      setTimelineItems([]);
      setError("Enter a correlation ID to preview the workspace timeline.");
      return;
    }

    setTimelineLoading(true);
    setError("");

    try {
      const result = await fetchCorrelationTimeline(correlationId);
      setTimelineItems(safeArray<CorrelationTimelineItem>(result));
    } catch (err) {
      setTimelineItems([]);
      setError(err instanceof Error ? err.message : "Failed to load workspace timeline preview.");
    } finally {
      setTimelineLoading(false);
    }
  }

  return (
    <section style={{ padding: 16 }}>
      <header style={{ marginBottom: 20 }}>
        <h2 style={{ marginBottom: 8 }}>Guided Business Workspace</h2>
        <p style={{ margin: 0, color: "#555" }}>
          What do you want to manage? Start from a business intention instead of navigating raw technical surfaces.
        </p>
      </header>

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

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))",
          gap: 12,
          marginBottom: 20
        }}
      >
        {workspaceCards.map((card) => {
          const active = selectedWorkspace === card.id;
          return (
            <button
              key={card.id}
              type="button"
              onClick={() => setSelectedWorkspace(card.id)}
              style={{
                textAlign: "left",
                border: active ? "2px solid #1f6feb" : "1px solid #d0d7de",
                borderRadius: 12,
                padding: 16,
                background: active ? "#eef6ff" : "#fff",
                cursor: "pointer"
              }}
            >
              <div style={{ fontWeight: 700, marginBottom: 8 }}>{card.title}</div>
              <div style={{ marginBottom: 8, color: "#444" }}>{card.description}</div>
              <div style={{ fontSize: 12, color: "#666" }}>{card.hint}</div>
            </button>
          );
        })}
      </div>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "2fr 1fr",
          gap: 16,
          alignItems: "start",
          marginBottom: 20
        }}
      >
        <div
          style={{
            border: "1px solid #d0d7de",
            borderRadius: 12,
            padding: 16,
            background: "#fff"
          }}
        >
          <h3 style={{ marginTop: 0 }}>Guided Concepts</h3>
          {loading ? (
            <p>Loading guided concepts...</p>
          ) : (
            <>
              <p style={{ marginTop: 0, color: "#555" }}>
                Business-facing concept summary generated from the UI model metadata.
              </p>
              <p style={{ fontWeight: 600 }}>{getConceptSummary(concepts)}</p>

              <div style={{ display: "grid", gap: 8 }}>
                {concepts.map((concept) => (
                  <div
                    key={concept.name}
                    style={{
                      border: "1px solid #e5e7eb",
                      borderRadius: 8,
                      padding: 12,
                      background: "#fafafa"
                    }}
                  >
                    <div style={{ fontWeight: 700 }}>{concept.name}</div>
                    <div style={{ fontSize: 13, color: "#666", marginTop: 4 }}>
                      Fields: {safeArray(concept.fields).length}
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>

        <div
          style={{
            border: "1px solid #d0d7de",
            borderRadius: 12,
            padding: 16,
            background: "#fff"
          }}
        >
          <h3 style={{ marginTop: 0 }}>Current workspace focus</h3>
          <div style={{ fontWeight: 700, marginBottom: 8 }}>
            {currentWorkspaceCard?.title ?? "—"}
          </div>
          <p style={{ marginTop: 0, color: "#555" }}>
            {currentWorkspaceCard?.description ?? "—"}
          </p>
          <div style={{ fontSize: 13, color: "#666" }}>
            This card selection is the starting point for future guided business tasks.
          </div>
        </div>
      </div>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "1fr 1fr",
          gap: 16,
          alignItems: "start",
          marginBottom: 20
        }}
      >
        <div
          style={{
            border: "1px solid #d0d7de",
            borderRadius: 12,
            padding: 16,
            background: "#fff"
          }}
        >
          <h3 style={{ marginTop: 0 }}>Recent Events for operators</h3>
          {loading ? (
            <p>Loading recent events...</p>
          ) : recentEvents.length === 0 ? (
            <p>No runtime events available.</p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr>
                  <th style={{ textAlign: "left", borderBottom: "1px solid #ddd", paddingBottom: 8 }}>Type</th>
                  <th style={{ textAlign: "left", borderBottom: "1px solid #ddd", paddingBottom: 8 }}>Concept</th>
                  <th style={{ textAlign: "left", borderBottom: "1px solid #ddd", paddingBottom: 8 }}>When</th>
                </tr>
              </thead>
              <tbody>
                {recentEvents.map((event, index) => (
                  <tr key={`${event.eventType ?? "event"}-${index}`}>
                    <td style={{ padding: "8px 0" }}>{event.eventType ?? "—"}</td>
                    <td style={{ padding: "8px 0" }}>{truncate(event.entityId ?? event.correlationId ?? "—", 36)}</td>
                    <td style={{ padding: "8px 0" }}>{formatTimestamp(event.occurredAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div
          style={{
            border: "1px solid #d0d7de",
            borderRadius: 12,
            padding: 16,
            background: "#fff"
          }}
        >
          <h3 style={{ marginTop: 0 }}>Audit preview</h3>
          {loading ? (
            <p>Loading audit preview...</p>
          ) : recentAuditRecords.length === 0 ? (
            <p>No audit records available.</p>
          ) : (
            <div style={{ display: "grid", gap: 10 }}>
              {recentAuditRecords.map((record, index) => (
                <div
                  key={`${record.recordType ?? "record"}-${index}`}
                  style={{
                    border: "1px solid #e5e7eb",
                    borderRadius: 8,
                    padding: 10,
                    background: "#fafafa"
                  }}
                >
                  <div style={{ fontWeight: 700 }}>{record.recordType ?? "Audit record"}</div>
                  <div style={{ fontSize: 13, color: "#666", marginTop: 4 }}>
                    {truncate(record.message ?? record.summary ?? "No summary", 90)}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div
        style={{
          border: "1px solid #d0d7de",
          borderRadius: 12,
          padding: 16,
          background: "#fff"
        }}
      >
        <h3 style={{ marginTop: 0 }}>Workspace timeline preview</h3>
        <p style={{ color: "#555" }}>
          Load a business timeline by correlation ID to preview the path that a user-facing process followed.
        </p>

        <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
          <input
            type="text"
            value={selectedCorrelationId}
            onChange={(event) => setSelectedCorrelationId(event.target.value)}
            placeholder="Enter correlation ID"
            style={{
              flex: 1,
              padding: 10,
              borderRadius: 8,
              border: "1px solid #d0d7de"
            }}
          />
          <button
            type="button"
            onClick={() => {
              void handleLoadTimeline();
            }}
            disabled={timelineLoading}
            style={{
              padding: "10px 14px",
              borderRadius: 8,
              border: "1px solid #1f6feb",
              background: "#1f6feb",
              color: "#fff",
              cursor: "pointer"
            }}
          >
            {timelineLoading ? "Loading..." : "Load timeline"}
          </button>
        </div>

        {timelineItems.length === 0 ? (
          <p style={{ marginBottom: 0, color: "#666" }}>No workspace timeline preview loaded.</p>
        ) : (
          <div style={{ display: "grid", gap: 8 }}>
            {timelineItems.map((item, index) => (
              <div
                key={`${item.kind ?? "timeline"}-${index}`}
                style={{
                  border: "1px solid #e5e7eb",
                  borderRadius: 8,
                  padding: 12,
                  background: "#fafafa"
                }}
              >
                <div style={{ fontWeight: 700 }}>{item.kind ?? "Timeline item"}</div>
                <div style={{ fontSize: 13, color: "#666", marginTop: 4 }}>
                  {truncate(item.summary ?? item.message ?? "No details", 120)}
                </div>
                <div style={{ fontSize: 12, color: "#888", marginTop: 4 }}>
                  {formatTimestamp(item.occurredAt)}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
