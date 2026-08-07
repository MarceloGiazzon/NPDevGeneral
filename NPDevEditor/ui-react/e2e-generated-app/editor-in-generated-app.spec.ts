import { expect, test } from "@playwright/test";

// editor/ANALYSIS.md E3: proves the editor works where a user actually reaches it -- inside a
// real generated FinalApp, at its real /npdev-ui-react/ base path, over its real REST API and
// dev-mode X-Api-Key auth. playwright.config.ts's default `npm run e2e` only proves the built
// bundle renders against a static file host with no backend at all.

test.beforeEach(({ page }) => {
  const pageErrors: Error[] = [];
  page.on("pageerror", (error) => pageErrors.push(error));
  page.on("close", () => {
    expect(pageErrors, `uncaught page error(s): ${pageErrors.map((e) => e.message).join("; ")}`).toEqual([]);
  });
});

test("workbench shell loads at the real /npdev-ui-react/ base path", async ({ page }) => {
  await page.goto("/npdev-ui-react/");
  await expect(page).toHaveURL(/\/npdev-ui-react\//);
  await expect(page.getByRole("heading", { name: "NPDev React Workbench" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Prompt History" })).toBeVisible();
});

test("prompt history tab makes a real REST round-trip against the running app", async ({ page }) => {
  await page.goto("/npdev-ui-react/");

  const semanticHistoryResponse = page.waitForResponse((response) =>
    response.url().includes("/api/admin/model/semantic-behavior-writeback/history")
  );

  await page.getByRole("button", { name: "Prompt History" }).click();
  await expect(page.getByRole("heading", { name: "Prompt History" })).toBeVisible();

  // The real assertion: this hit the running app's actual controller, not a stub or a static
  // host with nothing behind it. The response's status is not asserted here -- REG-138 records
  // that semantic-behavior-writeback is excluded from the default supported-core build profile,
  // so a 404 is an EXPECTED, gracefully-handled outcome (see PromptHistoryPanel's own warning
  // banner, not a crash) unless the app was built with npdev.runtime.surface-profile=non-default.
  const response = await semanticHistoryResponse;
  expect(response.request().method()).toBe("GET");

  // Either real records rendered, or the panel degraded gracefully with its own warning banner
  // (promptHistoryData.ts's fetchPromptHistorySource uses Promise.allSettled precisely so a
  // failed endpoint warns instead of crashing) -- both are acceptable; a silent infinite
  // "Loading..." or a thrown error are not.
  await expect(page.getByText("Loading previous prompts...")).toHaveCount(0, { timeout: 15_000 });
  const hasRecordsOrEmptyState = page.getByText("No previous prompts recorded.").or(page.getByText("Request ID:").first());
  const hasWarning = page.getByText("Partial history loaded.");
  await expect(hasRecordsOrEmptyState.or(hasWarning).first()).toBeVisible();
});

test("authoring surface loads under the real base path and auth", async ({ page }) => {
  await page.goto("/npdev-ui-react/#/authoring/home");
  await expect(page).toHaveURL(/#\/authoring\/home$/);
  await expect(page.getByRole("heading", { name: "Authoring Studio Shell" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Three safe ways to begin" })).toBeVisible();
});
