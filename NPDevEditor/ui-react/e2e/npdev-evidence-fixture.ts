/**
 * MONITOR_PLAN C2 -- the shared evidence fixture.
 *
 * Playwright's reporter API can see steps, timings and attachments; it CANNOT see page events. So
 * the console errors, uncaught page exceptions and failed requests that a ScrapForAI run gets for
 * free have to be wired once, here, and attached to the test result where the reporter can read
 * them.
 *
 * That wiring is not optional decoration. Without it the recorded evidence arrays are empty, and an
 * empty array reads as "nothing went wrong" when it actually means "nobody was listening" -- the
 * silent-answer defect, in the one place whose entire job is to notice things going wrong.
 *
 * The field NAMES match the engine's exactly (`consoleErrors`, `pageErrors`, `networkFailures`), so
 * one verdict function judges both drivers without a translation layer to get wrong.
 *
 * Use it in place of the bare `test`:
 *
 *     import { test, expect } from "./npdev-evidence-fixture";
 */

import { test as base, expect } from "@playwright/test";

export interface CollectedEvidence {
  consoleErrors: { type: string; text: string }[];
  pageErrors: string[];
  networkFailures: { origin: string; pathname: string; method: string; failureText?: string; status?: number }[];
}

export const test = base.extend<{ npdevEvidence: CollectedEvidence }>({
  npdevEvidence: async ({ page }, use, testInfo) => {
    const evidence: CollectedEvidence = { consoleErrors: [], pageErrors: [], networkFailures: [] };

    page.on("console", (message) => {
      if (message.type() === "error") {
        evidence.consoleErrors.push({ type: "error", text: message.text() });
      }
    });
    page.on("pageerror", (error) => {
      evidence.pageErrors.push(error.message);
    });
    page.on("requestfailed", (request) => {
      const url = new URL(request.url());
      evidence.networkFailures.push({
        origin: url.origin,
        pathname: url.pathname,
        method: request.method(),
        failureText: request.failure()?.errorText,
      });
    });
    page.on("response", (response) => {
      if (response.status() >= 400) {
        const url = new URL(response.url());
        evidence.networkFailures.push({
          origin: url.origin,
          pathname: url.pathname,
          method: response.request().method(),
          status: response.status(),
        });
      }
    });

    await use(evidence);

    // Attached rather than returned: the reporter runs in a different process from the test, and an
    // attachment is the only channel Playwright gives that crosses it.
    await testInfo.attach("npdev-evidence", {
      body: Buffer.from(JSON.stringify(evidence), "utf8"),
      contentType: "application/json",
    });
  },
});

export { expect };
