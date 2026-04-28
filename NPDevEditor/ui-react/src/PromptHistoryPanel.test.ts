import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchPromptHistory, type PromptHistorySource } from "./PromptHistoryPanel";

const TEST_SOURCE: PromptHistorySource = {
  id: "structural",
  label: "Structural",
  requestEndpoint: "/requests",
  planEndpoint: "/plans",
  executionEndpoint: "/executions"
};

function okResponse(payload: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: async () => payload,
    text: async () => JSON.stringify(payload)
  } as Response;
}

function failResponse(message: string, status = 503): Response {
  return {
    ok: false,
    status,
    json: async () => ({ message }),
    text: async () => message
  } as Response;
}

describe("PromptHistoryPanel history loading", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("merges prompt requests with canonicalization and execution results", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/requests") {
        return okResponse({
          items: [
            {
              requestId: "request-1",
              requestType: "createConcept",
              status: "RECEIVED_FOR_EXECUTION",
              submittedAt: "2026-04-21T10:00:00Z",
              tenantId: "global",
              payload: {
                conceptName: "Customer",
                businessLabel: "Customer"
              }
            }
          ]
        });
      }
      if (url === "/plans") {
        return okResponse({
          items: [
            {
              requestId: "request-1",
              planId: "plan-1",
              outcome: "CANONICALIZABLE",
              plannedAt: "2026-04-21T10:00:02Z"
            }
          ]
        });
      }
      return okResponse({
        items: [
          {
            requestId: "request-1",
            executionId: "execution-1",
            status: "EXECUTED",
            executedAt: "2026-04-21T10:00:04Z",
            mutation: {
              mutationType: "createConcept"
            }
          }
        ]
      });
    }));

    const result = await fetchPromptHistory([TEST_SOURCE]);

    expect(result.warnings).toEqual([]);
    expect(result.records).toHaveLength(1);
    expect(result.records[0].payload).toMatchObject({ conceptName: "Customer" });
    expect(result.records[0].result.canonicalizationPlan).toMatchObject({
      outcome: "CANONICALIZABLE"
    });
    expect(result.records[0].result.execution).toMatchObject({
      status: "EXECUTED"
    });
  });

  it("keeps available prompt history visible when one result endpoint fails", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/requests") {
        return okResponse({
          items: [
            {
              requestId: "request-2",
              requestType: "addInvariant",
              status: "RECEIVED",
              submittedAt: "2026-04-21T11:00:00Z",
              tenantId: "global",
              payload: {
                targetName: "Invoice",
                ruleName: "AmountPositive"
              }
            }
          ]
        });
      }
      if (url === "/plans") {
        return failResponse("plan store unavailable");
      }
      return okResponse({ items: [] });
    }));

    const result = await fetchPromptHistory([TEST_SOURCE]);

    expect(result.records).toHaveLength(1);
    expect(result.records[0].requestType).toBe("addInvariant");
    expect(result.warnings).toEqual([
      "Structural canonicalization result history failed: plan store unavailable"
    ]);
  });
});
