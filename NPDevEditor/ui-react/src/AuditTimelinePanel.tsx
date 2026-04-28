
import { useMemo, useState } from "react";
import { asApiError, npdevClient } from "./api/npdevClient";
import type { CorrelationTimelineResponse, ExecutionSummaryResponse, TraceSummaryResponse } from "./types";

type Props = {
  executions?: ExecutionSummaryResponse[];
  traces?: TraceSummaryResponse[];
};

function formatEpoch(ms?: number | null): string {
  if (ms == null || ms <= 0) {
    return "-";
  }
  try {
    return new Date(ms).toISOString();
  } catch {
    return String(ms);
  }
}

export default function AuditTimelinePanel({ executions = [], traces = [] }: Props) {
  const [correlationId, setCorrelationId] = useState("");
  const [timeline, setTimeline] = useState<CorrelationTimelineResponse | null>(null);
  const [status, setStatus] = useState<string>("");
  const [isError, setIsError] = useState(false);

  const suggestedCorrelationIds = useMemo(() => {
    const values = new Set<string>();
    executions.forEach((item) => {
      if (item?.correlationId) {
        values.add(item.correlationId);
      }
    });
    traces.forEach((item) => {
      if (item?.correlationId) {
        values.add(item.correlationId);
      }
    });
    return Array.from(values).slice(0, 8);
  }, [executions, traces]);

  async function loadTimeline(value: string) {
    const normalized = value.trim();
    if (!normalized) {
      setStatus("Enter a correlation id to open the audit timeline.");
      setIsError(true);
      return;
    }
    try {
      const response = await npdevClient.listAuditTimeline(normalized);
      setTimeline(response);
      setStatus("Audit timeline loaded.");
      setIsError(false);
    } catch (error) {
      const apiError = asApiError(error);
      setTimeline(null);
      setStatus(apiError.message || "Failed to load audit timeline.");
      setIsError(true);
    }
  }

  return (
    <section className="panel">
      <div className="section-header">
        <div>
          <h2>Audit + Timeline UX</h2>
          <div className="hint">Concept Changes, Events Fired, Orchestration Decisions, Capability Calls, Plugin Execution</div>
        </div>
      </div>

      <div className="subpanel">
        <div className="form-grid two-col">
          <label htmlFor="audit-correlation">Correlation Timeline</label>
          <div>
            <input
              id="audit-correlation"
              value={correlationId}
              onChange={(event) => setCorrelationId(event.target.value)}
              placeholder="Enter correlation id"
            />
            <div className="button-row" style={{ marginTop: 8 }}>
              <button type="button" onClick={() => loadTimeline(correlationId)}>Open Timeline</button>
            </div>
          </div>
        </div>

        {suggestedCorrelationIds.length > 0 ? (
          <div>
            <div className="hint">Suggested correlation ids</div>
            <div className="button-row">
              {suggestedCorrelationIds.map((value) => (
                <button key={value} type="button" className="secondary-button" onClick={() => { setCorrelationId(value); void loadTimeline(value); }}>
                  {value}
                </button>
              ))}
            </div>
          </div>
        ) : null}

        {status ? (
          <div className={isError ? "transition-status error" : "transition-status"}>{status}</div>
        ) : null}
      </div>

      <div className="subpanel">
        <h3>Concept Changes</h3>
        <div className="hint">Use the timeline to inspect concept changes and supporting evidence around one correlation.</div>
      </div>

      <div className="subpanel">
        <h3>Events Fired</h3>
        <div className="hint">Events are shown directly from the correlation timeline response.</div>
      </div>

      <div className="subpanel">
        <h3>Orchestration Decisions</h3>
        <div className="hint">Execution summaries and timeline events reveal orchestration decisions and branch outcomes.</div>
      </div>

      <div className="subpanel">
        <h3>Capability Calls</h3>
        <div className="hint">Capability invocations can be correlated with execution and event data.</div>
      </div>

      <div className="subpanel">
        <h3>Plugin Execution</h3>
        <div className="hint">Plugin-backed effects can be explained through the same correlation timeline.</div>
      </div>

      <div className="subpanel">
        <h3>Timeline Details</h3>
        <pre className="json-pane small">{JSON.stringify(timeline, null, 2)}</pre>
      </div>

      <div className="trace-cards">
        {timeline?.executions?.map((execution) => (
          <div key={execution.executionId} className="trace-card">
            <div className="trace-card-header">Execution {execution.executionId}</div>
            <div className="trace-card-meta">Flow: {execution.flowName}</div>
            <div className="trace-card-meta">Status: {execution.status}</div>
            <div className="trace-card-meta">Updated: {formatEpoch(execution.updatedAtEpochMs)}</div>
          </div>
        ))}
        {timeline?.events?.map((event) => (
          <div key={event.eventId} className="trace-card">
            <div className="trace-card-header">Event {event.eventName}</div>
            <div className="trace-card-meta">Correlation: {event.correlationId}</div>
            <div className="trace-card-meta">Flow: {event.flowName ?? "-"}</div>
            <div className="trace-card-meta">At: {formatEpoch(event.timestampMs)}</div>
          </div>
        ))}
      </div>
    </section>
  );
}
