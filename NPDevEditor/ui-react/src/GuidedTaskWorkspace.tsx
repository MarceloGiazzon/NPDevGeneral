import React from "react";

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

type GuidedTaskCatalog = {
  version: number;
  workspaceTitle: string;
  taskFamilies: GuidedTaskFamily[];
};

export type GuidedTaskWorkspaceProps = {
  catalog: GuidedTaskCatalog;
  onSelectTask?: (taskId: string) => void;
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

export default function GuidedTaskWorkspace(props: GuidedTaskWorkspaceProps) {
  const { catalog, onSelectTask } = props;

  return (
    <div style={{ padding: "16px" }}>
      <h2>{catalog.workspaceTitle}</h2>
      <p>
        Start from a business goal instead of from a raw technical editor.
      </p>

      {catalog.taskFamilies.map((family) => (
        <section
          key={family.id}
          style={{
            border: "1px solid #d0d7de",
            borderRadius: "8px",
            padding: "12px",
            marginBottom: "12px"
          }}
        >
          <h3>{family.title}</h3>
          <p>{family.description}</p>

          <ul>
            {family.tasks.map((task) => (
              <li key={task.id} style={{ marginBottom: "10px" }}>
                <div>
                  <strong>{task.title}</strong>{" "}
                  <span>({statusLabel(task.status)})</span>
                </div>
                <div>{task.intent}</div>
                <button
                  type="button"
                  onClick={() => onSelectTask && onSelectTask(task.id)}
                  style={{ marginTop: "6px" }}
                >
                  Open task
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
