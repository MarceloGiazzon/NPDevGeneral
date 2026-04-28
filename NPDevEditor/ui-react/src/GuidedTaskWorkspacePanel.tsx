import React, { useEffect, useState } from "react";

type GuidedTask = {
  id: string;
  title: string;
  intent: string;
  status: string;
};

type GuidedTaskFamily = {
  id: string;
  title: string;
  description: string;
  tasks: GuidedTask[];
};

type GuidedTaskWorkspaceResponse = {
  catalogPath: string;
  version: number;
  workspaceTitle: string;
  taskFamilyCount: number;
  taskCount: number;
  taskFamilies: GuidedTaskFamily[];
};

function statusLabel(status: string): string {
  switch (status) {
    case "foundation":
      return "Foundation";
    case "planned":
      return "Planned";
    case "ready":
      return "Ready";
    default:
      return status;
  }
}

export default function GuidedTaskWorkspacePanel() {
  const [data, setData] = useState<GuidedTaskWorkspaceResponse | null>(null);
  const [error, setError] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/workspace/tasks")
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load guided task workspace: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload) => {
        setData(payload);
        setError("");
      })
      .catch((err: Error) => {
        setError(err.message);
      });
  }, []);

  return (
    <div style={{ padding: "16px" }}>
      <h2>Guided Task Workspace</h2>
      <p>
        Start from a business goal instead of a raw technical editor.
      </p>

      {error ? (
        <div style={{ color: "#b42318", marginBottom: "12px" }}>
          {error}
        </div>
      ) : null}

      {!data ? (
        <div>Loading workspace...</div>
      ) : (
        <>
          <div style={{ marginBottom: "16px" }}>
            <strong>Catalog path:</strong> {data.catalogPath}
            <br />
            <strong>Version:</strong> {data.version}
            <br />
            <strong>Task families:</strong> {data.taskFamilyCount}
            <br />
            <strong>Total tasks:</strong> {data.taskCount}
          </div>

          {data.taskFamilies.map((family) => (
            <section
              key={family.id}
              style={{
                border: "1px solid #d0d7de",
                borderRadius: "8px",
                padding: "12px",
                marginBottom: "12px"
              }}
            >
              <h3 style={{ marginTop: 0 }}>{family.title}</h3>
              <p>{family.description}</p>

              <ul>
                {family.tasks.map((task) => (
                  <li key={task.id} style={{ marginBottom: "10px" }}>
                    <div>
                      <strong>{task.title}</strong>{" "}
                      <span>({statusLabel(task.status)})</span>
                    </div>
                    <div>{task.intent}</div>
                    <button type="button" style={{ marginTop: "6px" }}>
                      Open task
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </>
      )}
    </div>
  );
}
