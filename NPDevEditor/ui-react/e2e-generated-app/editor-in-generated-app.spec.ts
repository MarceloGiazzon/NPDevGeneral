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

// REG-139: on a FRESH boot, MODEL_EDITOR_DRAFT/RULE_EDITOR_DRAFT/ORCHESTRATION_EDITOR_DRAFT are
// all null (plain static fields, never persisted) -- readDraftOrModel used to fall back to the
// compiled model verbatim, a shape the model editor's default tab crashed on before painting
// anything, with no error boundary to contain it. This asserts REAL content for the default tab
// (not just the outer shell -- the shell alone was not proof, since a page-load failure this
// specific bug does NOT even necessarily fail the outer header if only assessed loosely) plus the
// other two panels the same fallback served, which is what a 200-on-index.html-only harness check
// would never have caught.
test("all three editor panels render real content on a genuinely fresh boot, not a blank mount", async ({ page }) => {
  await page.goto("/npdev-ui-react/");

  await expect(page.getByRole("heading", { name: "NPDev React Workbench" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Visual Model Editor" })).toBeVisible();
  await expect(page.getByText(/\d+ concepts/)).toBeVisible();

  const ruleDraftResponse = page.waitForResponse((response) =>
    response.url().includes("/api/admin/model/rules/draft") && response.request().method() === "GET"
  );
  await page.getByRole("button", { name: "Rule Editor" }).click();
  await expect(page.getByRole("heading", { name: "Visual Rule Editor" })).toBeVisible();
  expect((await ruleDraftResponse).status()).toBe(200);

  await page.getByRole("button", { name: "Orchestration Editor" }).click();
  await expect(page.getByRole("heading", { name: "Visual Orchestration Editor" })).toBeVisible();
  await expect(page.getByText("Flow name")).toBeVisible();
});

// REG-139 layer 3: layer 2 (npdevClient.ts) only validates the TOP-LEVEL draft shape (namespace/
// version strings, entities as an array) -- it deliberately does not re-implement full schema
// validation of every nested entity, so a response with a well-shaped top level but a malformed
// entity (missing `fields`) still reaches ModelEditorPanel's render and throws at
// `selectedConcept.fields.map(...)`. This is exactly the class of defect layer 3's error boundary
// exists for: one layers 1+2 don't (and shouldn't have to) catch. Proves the boundary contains the
// failure to the one panel while the rest of the shell -- header, tab navigation -- stays usable.
test("a malformed draft entity crashes only the model editor panel, not the whole shell", async ({ page }) => {
  await page.route("**/api/admin/model/editor/draft", async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ namespace: "broken", version: "1", entities: [{ name: "Broken" }] })
    });
  });

  await page.goto("/npdev-ui-react/");

  await expect(page.getByRole("heading", { name: "Model Editor failed to render" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "NPDev React Workbench" })).toBeVisible();

  await page.unroute("**/api/admin/model/editor/draft");
  await page.getByRole("button", { name: "Rule Editor" }).click();
  await expect(page.getByRole("heading", { name: "Visual Rule Editor" })).toBeVisible();
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
