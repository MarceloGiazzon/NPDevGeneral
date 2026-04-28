import React, { useState } from "react";

type EvaluationResponse = {
  mode: string;
  allowed: boolean;
  reason: string;
  userId: string;
  userTenantId: string;
  userDepartment: string;
  recordOwnerId: string;
  recordTenantId: string;
  recordDepartment: string;
  admin: boolean;
};

export default function OwnershipIsolationPanel() {
  const [responseText, setResponseText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  async function runSample(mode: string) {
    setErrorText("");
    setResponseText("");

    const body = {
      mode,
      userId: "user-a",
      userTenantId: "tenant-1",
      userDepartment: "finance",
      recordOwnerId: "user-b",
      recordTenantId: "tenant-1",
      recordDepartment: "finance",
      admin: false
    };

    try {
      const response = await fetch("/api/admin/security/ownership-isolation/evaluate", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-API-Key": "dev-key"
        },
        body: JSON.stringify(body)
      });

      const text = await response.text();
      if (!response.ok) {
        throw new Error(text || ("HTTP " + response.status));
      }

      const parsed: EvaluationResponse = JSON.parse(text);
      setResponseText(JSON.stringify(parsed, null, 2));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>Ownership and Tenant Isolation</h2>
      <p>
        Evaluate access decisions for owner, tenant, and department scope modes.
      </p>

      <div style={{ marginBottom: "16px" }}>
        <button type="button" onClick={() => runSample("owner")}>Owner</button>{" "}
        <button type="button" onClick={() => runSample("owner-or-tenant")}>Owner or tenant</button>{" "}
        <button type="button" onClick={() => runSample("tenant-only")}>Tenant only</button>{" "}
        <button type="button" onClick={() => runSample("tenant-and-department")}>Tenant and department</button>
      </div>

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}

      {responseText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Evaluation response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{responseText}</pre>
        </section>
      ) : null}
    </div>
  );
}