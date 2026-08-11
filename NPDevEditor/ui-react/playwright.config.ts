import { defineConfig, devices } from "@playwright/test";

const port = Number(process.env.PLAYWRIGHT_PORT ?? "4173");
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? `http://127.0.0.1:${port}/npdev-ui-react`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // MONITOR_PLAN C2: the npdev reporter runs ALONGSIDE the console ones, never instead of
  // them -- `list` is what a developer watches, and a run record is what history reads. It
  // records through `npdev explore record`, so this suite is judged by the same definition of
  // green as a ScrapForAI routine (R10). A recording failure warns and never fails the run.
  reporter: process.env.CI
    ? [["github"], ["list"], ["./e2e/npdev-run-reporter.ts", { suite: "editor-core" }]]
    : [["list"], ["./e2e/npdev-run-reporter.ts", { suite: "editor-core" }]],
  webServer: {
    command: `node ./scripts/stage-playwright-static-host.mjs && node ./scripts/serve-playwright-static.mjs ${port}`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  },
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
