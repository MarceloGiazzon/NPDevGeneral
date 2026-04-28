import React, { useMemo, useState } from "react";

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

export type StructuralWriteBackEditorProps = {
  catalog: StructuralWriteBackCatalog;
  onSubmitRequest?: (request: Record<string, unknown>) => void;
};

export default function StructuralWriteBackEditor(
  props: StructuralWriteBackEditorProps
) {
  const { catalog, onSubmitRequest } = props;
  const [selectedType, setSelectedType] = useState<string>(
    catalog.requestTypes[0]?.id ?? ""
  );

  const selectedDefinition = useMemo(() => {
    return catalog.requestTypes.find((item) => item.id === selectedType);
  }, [catalog.requestTypes, selectedType]);

  const sampleRequests = useMemo(() => {
    return catalog.sampleRequests.filter(
      (item) => String(item.requestType) === selectedType
    );
  }, [catalog.sampleRequests, selectedType]);

  return (
    <div style={{ padding: "16px" }}>
      <h2>{catalog.catalogTitle}</h2>
      <p>
        Create a structural write-back request instead of editing the source
        model manually.
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
                  onClick={() => onSubmitRequest && onSubmitRequest(item)}
                >
                  Use this sample request
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
