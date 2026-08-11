/**
 * MONITOR_PLAN C2 -- the Playwright adapter for the shared exploration run record.
 *
 * WHY THIS EXISTS
 * ---------------
 * Explorations happen two ways in this project, and only one of them left a trace. ScrapForAI
 * routines are declarative JSON the engine interprets, and every run produced structured evidence.
 * Direct Playwright specs -- `editor-core.spec.ts`, `editor-in-generated-app.spec.ts` -- produced
 * console text and nothing else: `list` reporter, trace on-first-retry, screenshots only-on-failure.
 * **A green run evaporated.** There was no way to see "this suite was green for twelve runs and went
 * red today", and no way to attribute the change.
 *
 * THE DESIGN RULE (EXPLORATIONS_ANALYSIS.md 2.6): unify the RUN RECORD, not the definition format.
 * A routine is data and a spec is code; forcing either into the other loses what makes it good. So
 * both drivers emit the same `run.json`, and the filmstrip renders one shape.
 *
 * AND THE VERDICT IS NOT COMPUTED HERE. This reporter collects; `npdev explore record` judges,
 * applying the same D5 rules the harness and the Manager get. That is R10 stated as code: one
 * definition of green, three drivers. A `green = failures === 0` line in this file would be a second
 * opinion, and the two would drift the first time an allowlist entry was added on one side.
 *
 * Wire it alongside `list` rather than replacing it -- console output is what a developer watches:
 *
 *     reporter: [["list"], ["./e2e/npdev-run-reporter.ts", { suite: "editor-core" }]]
 *
 * Set NPDEV_CLI to point at `npdev_cli.py` if it is not at the default repo-relative location.
 */

import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import type {
  FullConfig,
  FullResult,
  Reporter,
  Suite,
  TestCase,
  TestResult,
  TestStep,
} from "@playwright/test/reporter";

interface Options {
  /** Which suite this is, in the store. Platform-scoped: these test NPDev, not any one app. */
  suite?: string;
  /** App-scoped instead: record against a generated app's own history. */
  appDir?: string;
}

interface CollectedStep {
  index: number;
  action: string | null;
  label: string | null;
  status: string;
  durationMs: number;
  screenshot: string | null;
  error: string | null;
}

export default class NpdevRunReporter implements Reporter {
  private readonly suite: string;
  private readonly appDir?: string;
  private startedAt = "";
  private steps: CollectedStep[] = [];
  private consoleErrors: { type: string; text: string }[] = [];
  private pageErrors: string[] = [];
  private networkFailures: unknown[] = [];
  private screenshots: { name: string; path: string }[] = [];
  private failedStepIndex: number | null = null;
  private firstError: { type: string; message: string } | null = null;
  private specFiles = new Set<string>();

  constructor(options: Options = {}) {
    this.suite = options.suite ?? "playwright";
    this.appDir = options.appDir;
  }

  onBegin(_config: FullConfig, suite: Suite): void {
    this.startedAt = new Date().toISOString().replace(/\.\d+Z$/, "Z");
    for (const test of suite.allTests()) {
      this.specFiles.add(test.location.file);
    }
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    // Each `test.step()` becomes a step in the record; a test with no steps still contributes one,
    // so a spec that simply asserts is not invisible in the filmstrip.
    const testSteps = result.steps.filter((s) => s.category === "test.step");
    const entries: TestStep[] = testSteps.length > 0 ? testSteps : [];

    if (entries.length === 0) {
      this.pushStep({
        action: "test",
        label: test.titlePath().slice(1).join(" › "),
        status: result.status,
        durationMs: result.duration,
        error: result.error?.message ?? null,
      });
    } else {
      for (const step of entries) {
        this.pushStep({
          action: "step",
          label: `${test.title} › ${step.title}`,
          status: step.error ? "failed" : "passed",
          durationMs: step.duration,
          error: step.error?.message ?? null,
        });
      }
    }

    for (const attachment of result.attachments) {
      if (attachment.path && /screenshot|png$/i.test(attachment.name + attachment.path)) {
        this.screenshots.push({ name: attachment.name, path: attachment.path });
      }
    }

    if (result.status !== "passed" && result.status !== "skipped") {
      if (this.failedStepIndex === null) {
        this.failedStepIndex = Math.max(0, this.steps.length - 1);
      }
      if (!this.firstError && result.error) {
        this.firstError = {
          type: result.error.name ?? "Error",
          message: (result.error.message ?? "").slice(0, 4000),
        };
      }
    }

    // Console/page/network evidence arrives here only if a shared fixture forwarded it as an
    // attachment (see e2e/npdev-evidence-fixture.ts). Playwright's reporter API cannot observe page
    // events on its own -- stating that plainly matters, because an empty evidence array must mean
    // "nothing happened", never "nobody was listening".
    for (const attachment of result.attachments) {
      if (attachment.name !== "npdev-evidence" || !attachment.body) continue;
      try {
        const evidence = JSON.parse(attachment.body.toString("utf8"));
        this.consoleErrors.push(...(evidence.consoleErrors ?? []));
        this.pageErrors.push(...(evidence.pageErrors ?? []));
        this.networkFailures.push(...(evidence.networkFailures ?? []));
      } catch {
        /* a malformed evidence attachment must not take the whole report down */
      }
    }
  }

  private pushStep(partial: Omit<CollectedStep, "index" | "screenshot">): void {
    this.steps.push({ index: this.steps.length, screenshot: null, ...partial });
  }

  async onEnd(result: FullResult): Promise<void> {
    const payload = {
      jobId: `playwright-${this.suite}`,
      scenarioName: this.suite,
      // The DRIVER's own verdict on the steps. `verdict.green` is computed by the CLI, from this
      // plus the evidence below.
      status: result.status === "passed" ? "passed" : "failed",
      startedAt: this.startedAt,
      finishedAt: new Date().toISOString().replace(/\.\d+Z$/, "Z"),
      durationMs: Date.now() - Date.parse(this.startedAt),
      targetUrl: process.env.PLAYWRIGHT_BASE_URL ?? process.env.PLAYWRIGHT_GENERATED_APP_URL ?? "",
      failedStepIndex: this.failedStepIndex,
      error: this.firstError,
      steps: this.steps,
      extracted: {},
      evidence: {
        screenshots: this.screenshots,
        consoleErrors: this.consoleErrors,
        pageErrors: this.pageErrors,
        networkFailures: this.networkFailures,
        unexpectedExternalRequests: [],
      },
      // definition.contentSha256 is the routine hash for a routine; for a spec it is the file
      // content, which is what makes a spec-driven run just as attributable.
      routine: {
        scenarioName: this.suite,
        specFiles: [...this.specFiles].map((file) => path.basename(file)),
        source: [...this.specFiles]
          .map((file) => (existsSync(file) ? readFileSync(file, "utf8") : ""))
          .join("\n"),
      },
    };

    const cli = resolveCli();
    if (!cli) {
      console.warn(
        "[npdev-run-reporter] npdev_cli.py not found -- this run was NOT recorded. " +
          "Set NPDEV_CLI to record it."
      );
      return;
    }

    const dir = mkdtempSync(path.join(tmpdir(), "npdev-run-"));
    const file = path.join(dir, "result.json");
    writeFileSync(file, JSON.stringify(payload), "utf8");
    try {
      const args = [
        cli, "explore", "record", "--json", "--from-file", file,
        "--driver", "playwright", "--definition-kind", "playwright-spec",
      ];
      if (this.appDir) {
        args.push("--app-dir", this.appDir);
      } else {
        args.push("--scope", "platform", "--suite", this.suite);
      }
      const completed = spawnSync(process.env.PYTHON ?? "python", args, { encoding: "utf8" });
      if (completed.status !== 0) {
        // Never fail the TEST RUN because bookkeeping failed. The suite already passed or failed on
        // its own merits, and turning a green suite red for a missing history line would be the
        // tool problem dressed as a test result that D4 exists to prevent.
        console.warn(
          `[npdev-run-reporter] could not record this run: ${(completed.stderr || completed.stdout || "").trim()}`
        );
        return;
      }
      const record = JSON.parse(completed.stdout);
      console.log(
        `[npdev-run-reporter] recorded ${record.runId} (green=${record.verdict?.green}) ` +
          `-- npdev explore show --run ${record.runId}`
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
}

/** NPDEV_CLI, else walk up for the repo by its CONTENTS. Never by directory name (REG-144). */
function resolveCli(): string | null {
  if (process.env.NPDEV_CLI && existsSync(process.env.NPDEV_CLI)) {
    return process.env.NPDEV_CLI;
  }
  let cursor = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const holdsModules = ["NPDevContract", "NPDevGenerator", "NPDevKernel"].every((name) =>
      existsSync(path.join(cursor, name))
    );
    if (holdsModules) {
      const candidate = path.join(cursor, "NPDevCli", "npdev_cli.py");
      return existsSync(candidate) ? candidate : null;
    }
    const parent = path.dirname(cursor);
    if (parent === cursor) break;
    cursor = parent;
  }
  return null;
}
