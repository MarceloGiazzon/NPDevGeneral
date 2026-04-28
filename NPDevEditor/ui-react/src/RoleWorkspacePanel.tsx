import React, { useEffect, useState } from "react";

type RoleDefinition = {
  id: string;
  title: string;
  description: string;
  sections: string[];
  preferredTasks: string[];
  hiddenComplexity: string[];
};

type SummaryResponse = {
  catalogPath: string;
  version: number;
  roles: RoleDefinition[];
  roleCount: number;
};

type RoleResponse = {
  catalogPath: string;
  role: RoleDefinition;
};

export default function RoleWorkspacePanel() {
  const [summary, setSummary] = useState<SummaryResponse | null>(null);
  const [selectedRole, setSelectedRole] = useState<string>("operator");
  const [roleText, setRoleText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  useEffect(() => {
    fetch("/api/admin/workspace/roles", {
      headers: {
        "X-API-Key": "dev-key"
      }
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Failed to load workspace role summary: HTTP " + response.status);
        }
        return response.json();
      })
      .then((payload: SummaryResponse) => {
        setSummary(payload);
      })
      .catch((err: Error) => {
        setErrorText(err.message);
      });
  }, []);

  async function loadRole(roleId: string) {
    setErrorText("");
    setRoleText("");
    setSelectedRole(roleId);

    try {
      const response = await fetch("/api/admin/workspace/roles/" + roleId, {
        headers: {
          "X-API-Key": "dev-key"
        }
      });

      const text = await response.text();
      if (!response.ok) {
        throw new Error(text || ("HTTP " + response.status));
      }

      const parsed: RoleResponse = JSON.parse(text);
      setRoleText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Role-Shaped Workspaces</h2>
      <p>
        Inspect workspace surfaces shaped for different roles.
      </p>

      {summary ? (
        <div style={{ marginBottom: "16px" }}>
          <strong>Role count:</strong> {summary.roleCount}
        </div>
      ) : (
        <div>Loading workspace role summary...</div>
      )}

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={() => loadRole("operator")}>Load operator</button>{" "}
        <button type="button" onClick={() => loadRole("business-owner")}>Load business-owner</button>{" "}
        <button type="button" onClick={() => loadRole("auditor")}>Load auditor</button>{" "}
        <button type="button" onClick={() => loadRole("specialist")}>Load specialist</button>{" "}
        <button type="button" onClick={() => loadRole("support")}>Load support</button>
      </div>

      <div style={{ marginBottom: "12px" }}>
        <strong>Selected role:</strong> {selectedRole}
      </div>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {roleText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Role workspace</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{roleText}</pre>
        </section>
      ) : null}
    </div>
  );
}