import React, { useEffect, useMemo, useState } from "react";

type RequestTypeDefinition = {
  id: string;
  title: string;
  description: string;
  requiredProperties: string[];
};

type SampleRequest = {
  requestType: string;
  [key: string]: unknown;
};

type StructuralWriteBackCatalog = {
  version: number;
  catalogTitle: string;
  requestTypes: RequestTypeDefinition[];
  sampleRequests: SampleRequest[];
};

type StructuralWriteBackResponse = {
  requestId: string;
  requestType: string;
  status: string;
  submittedAt: string;
  message: string;
};

type WriteBackHistoryResponse = {
  count: number;
  items: Record<string, unknown>[];
};

export type StructuralWriteBackPanelProps = {
  catalog: StructuralWriteBackCatalog;
  apiKey?: string;
};

export default function StructuralWriteBackPanel(
  props: StructuralWriteBackPanelProps
) {
  const { catalog, apiKey = "dev-key" } = props;

  const [selectedType, setSelectedType] = useState<string>(
    catalog.requestTypes[0]?.id ?? ""
  );
  const [historyText, setHistoryText] = useState<string>("");
  const [responseText, setResponseText] = useState<string>("");
  const [errorText, setErrorText] = useState<string>("");

  const selectedDefinition = useMemo(() => {
    return catalog.requestTypes.find((item) => item.id === selectedType);
  }, [catalog.requestTypes, selectedType]);

  const sampleRequests = useMemo(() => {
    return catalog.sampleRequests.filter(
      (item) => String(item.requestType) === selectedType
    );
  }, [catalog.sampleRequests, selectedType]);

  async function loadHistory() {
    const response = await fetch("/api/admin/model/structural-writeback/history", {
      headers: {
        "X-API-Key": apiKey
      }
    });

    const text = await response.text();

    if (!response.ok) {
      throw new Error(text || ("HTTP " + response.status));
    }

    const parsed: WriteBackHistoryResponse = JSON.parse(text);
    setHistoryText(JSON.stringify(parsed, null, 2));
  }

  useEffect(() => {
    loadHistory().catch((err: Error) => {
      setErrorText(err.message);
    });
  }, [apiKey]);

  async function submitRequest(request: Record<string, unknown>) {
    setErrorText("");
    setResponseText("");

    try {
      const payload = {
        requestType: request.requestType,
        payload: request
      };

      const response = await fetch("/api/admin/model/structural-writeback", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-API-Key": apiKey
        },
        body: JSON.stringify(payload)
      });

      const text = await response.text();

      if (!response.ok) {
        throw new Error(text || ("HTTP " + response.status));
      }

      const parsed: StructuralWriteBackResponse = JSON.parse(text);
      setResponseText(JSON.stringify(parsed, null, 2));
      await loadHistory();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorText(message);
    }
  }

  return (
    <div style={{ padding: "16px" }}>
      <h2>{catalog.catalogTitle}</h2>
      <p>
        Submit a structural write-back request to the runtime-backed API.
      </p>

      <label htmlFor="writeback-type">
        <strong>Request type</strong>
      </label>
      <div style={{ margin: "8px 0 16px 0" }}>
        <select
          id="writeback-type"
          value={selectedType}
          onChange={(event) => setSelectedType(event.target.value)}
        >
          {catalog.requestTypes.map((item) => (
            <option key={item.id} value={item.id}>
              {item.title}
            </option>
          ))}
        </select>
      </div>

      {selectedDefinition ? (
        <section
          style={{
            border: "1px solid #d0d7de",
            borderRadius: "8px",
            padding: "12px",
            marginBottom: "16px"
          }}
        >
          <h3>{selectedDefinition.title}</h3>
          <p>{selectedDefinition.description}</p>
          <p>
            <strong>Required properties:</strong>{" "}
            {selectedDefinition.requiredProperties.join(", ")}
          </p>
        </section>
      ) : null}

      <section
        style={{
          border: "1px solid #d0d7de",
          borderRadius: "8px",
          padding: "12px"
        }}
      >
        <h3>Sample requests</h3>
        {sampleRequests.length === 0 ? (
          <p>No sample requests available for this type yet.</p>
        ) : (
          <ul>
            {sampleRequests.map((item, index) => (
              <li key={index} style={{ marginBottom: "12px" }}>
                <pre style={{ whiteSpace: "pre-wrap" }}>
                  {JSON.stringify(item, null, 2)}
                </pre>
                <button
                  type="button"
                  onClick={() => submitRequest(item)}
                >
                  Submit request
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {responseText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Response</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{responseText}</pre>
        </section>
      ) : null}

      {historyText ? (
        <section style={{ marginTop: "16px" }}>
          <h3>Previous prompts</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{historyText}</pre>
        </section>
      ) : null}

      {errorText ? (
        <section style={{ marginTop: "16px", color: "#b42318" }}>
          <h3>Error</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>{errorText}</pre>
        </section>
      ) : null}
    </div>
  );
}
