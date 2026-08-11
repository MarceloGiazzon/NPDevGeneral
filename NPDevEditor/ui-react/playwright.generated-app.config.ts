import { defineConfig, devices } from "@playwright/test";

// editor/ANALYSIS.md E3: the editor's real home is /npdev-ui-react/ INSIDE a generated FinalApp
// -- a different base path, real auth (the app's own X-Api-Key dev-mode check), and real REST
// data, none of which playwright.config.ts's static-host webServer exercises (it serves the built
// dist/ directly, with no Spring Boot backend behind it at all). This config targets a real,
// already-running generated app instead of starting its own webServer -- generating, building,
// and booting a FinalApp is a multi-minute Gradle build, not something a `playwright test` process
// should own. Boot one first (see NPDevEditor/ui-react/e2e-generated-app/README.md), then:
//
//   PLAYWRIGHT_GENERATED_APP_URL=http://localhost:8080 npm run e2e:generated-app
const baseURL = process.env.PLAYWRIGHT_GENERATED_APP_URL;
if (!baseURL) {
  throw new Error(
    "PLAYWRIGHT_GENERATED_APP_URL is required for playwright.generated-app.config.ts -- " +
      "point it at a real, already-running generated FinalApp (e.g. http://localhost:8080). " +
      "See NPDevEditor/ui-react/e2e-generated-app/README.md."
  );
}

export default defineConfig({
  testDir: "./e2e-generated-app",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // MONITOR_PLAN C2: the npdev reporter runs ALONGSIDE the console ones, never instead of
  // them -- `list` is what a developer watches, and a run record is what history reads. It
  // records through `npdev explore record`, so this suite is judged by the same definition of
  // green as a ScrapForAI routine (R10). A recording failure warns and never fails the run.
  reporter: process.env.CI
    ? [["github"], ["list"], ["./e2e/npdev-run-reporter.ts", { suite: "editor-in-generated-app" }]]
    : [["list"], ["./e2e/npdev-run-reporter.ts", { suite: "editor-in-generated-app" }]],
  use: {
    baseURL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure"
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"]
      }
    }
  ]
});
