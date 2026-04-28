import { expect, test, type Page } from "@playwright/test";

function authoringUrl(segment: string): string {
  return `/npdev-ui-react/#/authoring/${segment}`;
}

async function openAuthoringRoute(page: Page, segment: string): Promise<void> {
  await page.goto(authoringUrl(segment));
  await expect(page).toHaveURL(new RegExp(`#\\/authoring\\/${segment}$`));
  await expect(page.getByRole("heading", { name: "Authoring Studio Shell" })).toBeVisible();
}

test("loads the authoring home route", async ({ page }) => {
  await openAuthoringRoute(page, "home");

  await expect(page.getByRole("heading", { name: "Three safe ways to begin" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Open canonical demo" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Browse official samples" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Start a new model" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue with current selection" })).toBeVisible();
  await expect(page.getByText("Canonical Demo").first()).toBeVisible();
});

test("loads an official sample from the chooser into the editor", async ({ page }) => {
  await openAuthoringRoute(page, "models");

  await expect(page.getByRole("heading", { name: "Pick the safest entry mode for this session" })).toBeVisible();
  await page
    .locator("article")
    .filter({ has: page.getByRole("heading", { name: "Medium Expense Approval" }) })
    .getByRole("button", { name: "Load this sample" })
    .click();

  await expect(page).toHaveURL(/#\/authoring\/editor$/);
  await expect(page.getByRole("heading", { name: "Form-based model editor" })).toBeVisible();
  await expect(page.getByText(/Selected sample:\s*medium-expense-approval/)).toBeVisible();
});

test("adds a concept in the model editor", async ({ page }) => {
  await openAuthoringRoute(page, "editor");

  await expect(page.getByRole("heading", { name: "Form-based model editor" })).toBeVisible();
  const conceptEditor = page.locator("section").filter({ has: page.getByRole("heading", { name: "Concept editor" }) });
  await conceptEditor.getByRole("button", { name: "Add concept" }).click();

  const conceptNameInput = conceptEditor.getByLabel("Concept name");
  await expect(conceptNameInput).toHaveValue(/Concept\d+/);
  await expect(page.locator(".authoring-editor-list__item").filter({ hasText: /Concept\d+/ })).toHaveCount(1);
});

test("switches the config editor into raw JSON mode and back", async ({ page }) => {
  await openAuthoringRoute(page, "config");

  await expect(page.getByRole("heading", { name: "Form-based config editor" })).toBeVisible();
  await page.getByRole("button", { name: "Raw JSON mode" }).click();
  await expect(page.getByRole("heading", { name: "Raw config.json mode" })).toBeVisible();
  await expect(page.getByLabel("Raw JSON text")).toBeVisible();

  await page.getByRole("button", { name: "Return to forms" }).click();
  await expect(page.getByRole("heading", { name: "Config JSON preview" })).toBeVisible();
});

test("switches concepts inside the preview workspace", async ({ page }) => {
  await openAuthoringRoute(page, "preview");

  const previewConceptText = page.getByText(/Previewing concept:\s*/);
  await expect(page.getByRole("heading", { name: "Metadata-driven preview surfaces" })).toBeVisible();
  const conceptButtons = page
    .locator("section")
    .filter({ has: page.getByRole("heading", { name: "Preview controls" }) })
    .getByRole("button");
  const conceptCount = await conceptButtons.count();
  expect(conceptCount).toBeGreaterThan(0);

  if (conceptCount > 1) {
    const initialPreviewText = (await previewConceptText.textContent()) ?? "";
    const selectedButtonIndex = await conceptButtons.evaluateAll((buttons) =>
      buttons.findIndex((button) => button.classList.contains("is-selected"))
    );
    const targetButtonIndex = selectedButtonIndex === 0 ? 1 : 0;
    await conceptButtons.nth(targetButtonIndex).click();

    await expect(previewConceptText).not.toHaveText(initialPreviewText);
  } else {
    await expect(previewConceptText).toBeVisible();
  }
  await expect(page.getByRole("heading", { name: "Preview controls" })).toBeVisible();
});

test("applies validation workspace filters", async ({ page }) => {
  await openAuthoringRoute(page, "validation");

  await expect(page.getByRole("heading", { name: "Validation workspace" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Authoring pipeline" })).toBeVisible();
  const warningFilter = page.getByRole("button", { name: "warning", exact: true });
  const configScope = page.getByRole("button", { name: "config", exact: true });

  await warningFilter.click();
  await expect(warningFilter).toHaveClass(/is-selected/);

  await configScope.click();
  await expect(configScope).toHaveClass(/is-selected/);
  await expect(page.getByRole("heading", { name: "Diagnostics and trace linkage" })).toBeVisible();
});

test("saves a versioned snapshot in the import export workspace", async ({ page }) => {
  await openAuthoringRoute(page, "exchange");

  await expect(page.getByRole("heading", { name: "Bundle workspace" })).toBeVisible();
  await page.getByRole("button", { name: "Save versioned snapshot" }).click();

  await expect(page.getByText(/Saved version snapshot/)).toBeVisible();
  await expect.poll(async () => page.getByLabel("Saved snapshot").inputValue()).not.toBe("");
});
