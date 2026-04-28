import { useEffect, useState } from "react";
import {
  asApiError,
  getRuntimeEventEvidence,
  getRuntimePluginExecutions,
  getRuntimePluginPackages,
  getRuntimePluginStatus,
  getSchedulesOverview,
  type ApiError
} from "./api/npdevClient";

function printJson(value: unknown): string {
  return JSON.stringify(value, null, 2);
}

type StatusBanner = {
  message: string;
  isError: boolean;
};

export function OperatorConsolePanel(): JSX.Element {
  const [status, setStatus] = useState<StatusBanner>({ message: "", isError: false });
  const [events, setEvents] = useState<unknown>(null);
  const [pluginStatus, setPluginStatus] = useState<unknown>(null);
  const [pluginPackages, setPluginPackages] = useState<unknown>(null);
  const [pluginExecutions, setPluginExecutions] = useState<unknown>(null);
  const [schedules, setSchedules] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const pushStatus = (message: string, isError: boolean) => setStatus({ message, isError });

  const loadOperatorConsole = async () => {
    setLoading(true);
    pushStatus("", false);
    try {
      const [nextEvents, nextPluginStatus, nextPluginPackages, nextPluginExecutions, nextSchedules] = await Promise.all([
        getRuntimeEventEvidence(),
        getRuntimePluginStatus(),
        getRuntimePluginPackages(),
        getRuntimePluginExecutions(),
        getSchedulesOverview()
      ]);
      setEvents(nextEvents);
      setPluginStatus(nextPluginStatus);
      setPluginPackages(nextPluginPackages);
      setPluginExecutions(nextPluginExecutions);
      setSchedules(nextSchedules);
      pushStatus("Operator Console refreshed.", false);
    } catch (error) {
      const apiError: ApiError = asApiError(error);
      pushStatus(apiError.message || "Failed to load Operator Console.", true);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadOperatorConsole();
  }, []);

  return (
    <section className="panel">
      <div className="section-header">
        <h2>Operator Console</h2>
        <button type="button" onClick={() => void loadOperatorConsole()} disabled={loading}>
          {loading ? "Refreshing..." : "Refresh Operator Console"}
        </button>
      </div>

      {status.message ? <div className={status.isError ? "status-box error" : "status-box"}>{status.message}</div> : null}

      <div className="metadata-summary">
        <div className="metadata-summary-card">
          <strong>Events</strong>
          <span className="hint">Runtime event evidence and operator-facing event stream visibility.</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Orchestration Runs</strong>
          <span className="hint">Recent plugin-backed orchestration execution evidence.</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Schedules</strong>
          <span className="hint">Wake-up schedules and processing state overview.</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Plugin Status</strong>
          <span className="hint">Selected package, realization strategy, trust and compatibility summary.</span>
        </div>
        <div className="metadata-summary-card">
          <strong>Rejected Packages</strong>
          <span className="hint">Governance visibility for rejected external packages and reasons.</span>
        </div>
      </div>

      <div className="subpanel">
        <h3>Events</h3>
        <pre className="json-pane small">{printJson(events ?? { message: "No event evidence loaded yet." })}</pre>
      </div>

      <div className="subpanel">
        <h3>Orchestration Runs</h3>
        <pre className="json-pane small">{printJson(pluginExecutions ?? { message: "No orchestration execution data loaded yet." })}</pre>
      </div>

      <div className="subpanel">
        <h3>Schedules</h3>
        <pre className="json-pane small">{printJson(schedules ?? { message: "No schedule data loaded yet." })}</pre>
      </div>

      <div className="subpanel">
        <h3>Plugin Status</h3>
        <pre className="json-pane small">{printJson(pluginStatus ?? { message: "No plugin status loaded yet." })}</pre>
      </div>

      <div className="subpanel">
        <h3>Rejected Packages</h3>
        <pre className="json-pane small">{printJson(pluginPackages ?? { message: "No plugin package catalog loaded yet." })}</pre>
      </div>
    </section>
  );
}

export default OperatorConsolePanel;
