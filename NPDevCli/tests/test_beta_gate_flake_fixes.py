"""REG-356 item 2a: the three defects that failed the 2026-11-24 full beta gate, each pinned by a behavioural test.

(a) a generated app's Gradle build that exits non-zero with NO error text is retried once (and only then);
(b) the stale-report check is a stage of its own that runs after every other producer;
(c) the slimness check can remove rebuildable directories before it measures.

Everything drives the real scripts through pwsh. Nothing here starts Gradle or an app: the Gradle executable is a fake
pwsh script. Skipped when pwsh is absent.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
QUALITY = REPO_ROOT / "scripts" / "quality"
SMOKE_SCRIPT = QUALITY / "invoke-ai-beta-app-smoke.ps1"
ORCHESTRATION = QUALITY / "run-beta-release-evidence-orchestration.ps1"
STALE_CHECK = QUALITY / "run-stale-report-check.ps1"
EXPANDED_EVIDENCE = QUALITY / "run-expanded-beta0-evidence.ps1"
LEGACY_CHAIN = QUALITY / "run-beta0-final-release-check.ps1"
SLIMNESS = REPO_ROOT / "scripts" / "hygiene" / "Test-WorkspaceSlimness.ps1"
GATE_POLICY = REPO_ROOT / "scripts" / "policy" / "beta-release-gate-policy.json"

PWSH = shutil.which("pwsh")
MARKER = "RESULT_JSON="

# Loads the two functions out of the smoke script by AST (the script itself needs mandatory arguments and starts a build,
# so it cannot be dot-sourced), then drives them against a fake Gradle executable.
DRIVER = r"""
param([string]$SmokeScript, [string]$WorkDir, [string]$Case, [string]$Mode, [string]$PwshPath)
$ErrorActionPreference = 'Stop'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($SmokeScript, [ref]$null, [ref]$null)
foreach ($name in @('Test-SilentGradleFailure', 'Invoke-GradleBuildWithSilentRetry')) {
    $fn = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    if ($null -eq $fn) { throw ('function not found in the smoke script: ' + $name) }
    . ([scriptblock]::Create($fn.Extent.Text))
}
$out = Join-Path $WorkDir 'out.log'
$err = Join-Path $WorkDir 'err.log'
if ($Case -eq 'classify') {
    $daemonNotice = "To honour the JVM settings for this build a single-use Daemon process will be forked.`nDaemon will be stopped at the end of the build `nConfiguration on demand is an incubating feature."
    $results = [ordered]@{}
    Set-Content -LiteralPath $out -Value $daemonNotice; Set-Content -LiteralPath $err -Value ''
    $results.exitZero = Test-SilentGradleFailure -ExitCode 0 -LogPaths @($out, $err)
    $results.daemonNoticeOnly = Test-SilentGradleFailure -ExitCode 1 -LogPaths @($out, $err)
    Remove-Item -LiteralPath $out, $err
    $results.noLogsAtAll = Test-SilentGradleFailure -ExitCode 1 -LogPaths @($out, $err)
    foreach ($text in @('FAILURE: Build failed with an exception.', 'BUILD FAILED in 3s', '* What went wrong:', 'App.java:3: error: cannot find symbol', '> Task :compileJava FAILED', 'java.lang.IllegalStateException: boom')) {
        Set-Content -LiteralPath $out -Value ($daemonNotice + "`n" + $text); Set-Content -LiteralPath $err -Value ''
        $results[$text] = Test-SilentGradleFailure -ExitCode 1 -LogPaths @($out, $err)
    }
    Set-Content -LiteralPath $out -Value $daemonNotice; Set-Content -LiteralPath $err -Value 'error: something on stderr'
    $results.errorOnStderr = Test-SilentGradleFailure -ExitCode 1 -LogPaths @($out, $err)
    Write-Output ('RESULT_JSON=' + ($results | ConvertTo-Json -Compress))
    exit 0
}
$counter = Join-Path $WorkDir 'counter.txt'
$fake = Join-Path $WorkDir 'fake-gradle.ps1'
Set-Content -LiteralPath $fake -Value @'
param([string]$Counter, [string]$Mode)
$n = 0
if (Test-Path -LiteralPath $Counter) { $n = [int](Get-Content -LiteralPath $Counter) }
$n++
Set-Content -LiteralPath $Counter -Value $n
switch ($Mode) {
    'silent-then-ok' { Write-Output 'Daemon will be stopped at the end of the build'; if ($n -eq 1) { exit 1 } else { exit 0 } }
    'silent-always' { Write-Output 'Daemon will be stopped at the end of the build'; exit 1 }
    'error-text' { Write-Output 'FAILURE: Build failed with an exception.'; exit 1 }
    'ok' { exit 0 }
}
'@
$arguments = @('-NoProfile', '-File', ('"' + $fake + '"'), ('"' + $counter + '"'), $Mode)
$result = Invoke-GradleBuildWithSilentRetry -Executable $PwshPath -ArgumentList $arguments -WorkingDirectory $WorkDir -StdoutPath $out -StderrPath $err -RetryDelaySeconds 0
$summary = [ordered]@{
    exitCode = $result.ExitCode
    attempts = $result.Attempts
    retried = $result.RetriedAfterSilentExit
    processRuns = [int](Get-Content -LiteralPath $counter)
    firstAttemptLogKept = (Test-Path -LiteralPath ($out + '.attempt1'))
}
Write-Output ('RESULT_JSON=' + ($summary | ConvertTo-Json -Compress))
"""


def _pwsh(args, cwd=None):
    return subprocess.run(
        [PWSH, "-NoProfile", *args],
        cwd=str(cwd) if cwd else None,
        capture_output=True,
        text=True,
        timeout=300,
    )


def _result_json(completed):
    for line in completed.stdout.splitlines():
        if line.startswith(MARKER):
            return json.loads(line[len(MARKER):])
    raise AssertionError("no result line in stdout. STDOUT " + completed.stdout + " STDERR " + completed.stderr)


@unittest.skipUnless(PWSH, "pwsh is required")
class SilentGradleBuildRetryTest(unittest.TestCase):
    """(a) a build that exits non-zero with no error text is retried once; a build that printed an error is not."""

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="npdev-gate-retry-"))
        self.driver = self.work / "driver.ps1"
        self.driver.write_text(DRIVER, encoding="utf-8")
        self.addCleanup(shutil.rmtree, str(self.work), True)

    def _run(self, case, mode=""):
        completed = _pwsh(["-File", str(self.driver), "-SmokeScript", str(SMOKE_SCRIPT), "-WorkDir", str(self.work),
                           "-Case", case, "-Mode", mode, "-PwshPath", PWSH])
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        return _result_json(completed)

    def test_classifier_treats_only_error_free_nonzero_exits_as_silent(self):
        verdict = self._run("classify")
        self.assertFalse(verdict["exitZero"])
        self.assertTrue(verdict["daemonNoticeOnly"], "the exact shape of the 2026-11-24 failure (daemon notice, empty stderr)")
        self.assertTrue(verdict["noLogsAtAll"])
        for text in ("FAILURE: Build failed with an exception.", "BUILD FAILED in 3s", "* What went wrong:",
                     "App.java:3: error: cannot find symbol", "> Task :compileJava FAILED",
                     "java.lang.IllegalStateException: boom"):
            self.assertFalse(verdict[text], "a build that printed '" + text + "' must never be retried")
        self.assertFalse(verdict["errorOnStderr"])

    def test_silent_exit_then_success_is_one_retry_and_a_pass(self):
        verdict = self._run("retry", "silent-then-ok")
        self.assertEqual(verdict, {"exitCode": 0, "attempts": 2, "retried": True, "processRuns": 2, "firstAttemptLogKept": True})

    def test_a_second_silent_exit_is_not_retried_again(self):
        verdict = self._run("retry", "silent-always")
        self.assertEqual(verdict["exitCode"], 1)
        self.assertEqual(verdict["attempts"], 2)
        self.assertEqual(verdict["processRuns"], 2)

    def test_a_build_that_printed_an_error_runs_once(self):
        verdict = self._run("retry", "error-text")
        self.assertEqual(verdict, {"exitCode": 1, "attempts": 1, "retried": False, "processRuns": 1, "firstAttemptLogKept": False})

    def test_a_passing_build_runs_once(self):
        verdict = self._run("retry", "ok")
        self.assertEqual(verdict, {"exitCode": 0, "attempts": 1, "retried": False, "processRuns": 1, "firstAttemptLogKept": False})

    def test_the_smoke_script_calls_the_retrying_runner_for_its_build(self):
        source = SMOKE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("Invoke-GradleBuildWithSilentRetry -Executable $gradlew", source)
        self.assertNotIn("Start-Process -FilePath $gradlew -ArgumentList @(\"--no-daemon\", \"clean\", \"build\"", source,
                         "the build must not bypass the retrying runner")


@unittest.skipUnless(PWSH, "pwsh is required")
class StaleReportCheckOrderTest(unittest.TestCase):
    """(b) the stale-report check judges report age, so it must run after every other producer."""

    @classmethod
    def setUpClass(cls):
        completed = _pwsh(["-File", str(ORCHESTRATION), "-PlanOnly"], cwd=REPO_ROOT)
        assert completed.returncode == 0, completed.stdout + completed.stderr
        cls.plan = json.loads(completed.stdout)

    def test_the_stale_check_is_the_last_producer_and_the_only_one_in_its_stage(self):
        names = [producer["name"] for producer in self.plan]
        self.assertEqual(names[-1], "stale-report-check")
        last_stage = self.plan[-1]["stage"]
        self.assertEqual([p["name"] for p in self.plan if p["stage"] >= last_stage], ["stale-report-check"])
        self.assertGreater(last_stage, max(p["stage"] for p in self.plan[:-1]))

    def test_no_other_producer_owns_the_stale_report_and_no_report_has_two_owners(self):
        owners = {}
        for producer in self.plan:
            for report in producer["reports"]:
                owners.setdefault(report, []).append(producer["name"])
        self.assertEqual(owners["stale-report-check-report.json"], ["stale-report-check"])
        self.assertEqual({r: o for r, o in owners.items() if len(o) > 1}, {})

    def test_every_required_report_still_has_an_owner(self):
        policy = json.loads(GATE_POLICY.read_text(encoding="utf-8"))
        required = {Path(entry["path"]).name for entry in policy["requiredReports"]}
        owned = {report for producer in self.plan for report in producer["reports"]}
        # ai-beta-reproducibility-report.json is refreshed by run-beta-release-gate.ps1 itself, by design.
        self.assertEqual(required - owned, {"ai-beta-reproducibility-report.json"})

    def test_the_declared_order_inside_a_stage_is_the_run_order(self):
        stage2 = [p["name"] for p in self.plan if p["stage"] == 2]
        self.assertLess(stage2.index("ai-beta-gate"), stage2.index("expanded-beta0-evidence"))

    def test_expanded_evidence_no_longer_writes_the_stale_report(self):
        source = EXPANDED_EVIDENCE.read_text(encoding="utf-8")
        self.assertNotIn('New-Report "stale-report-check-report.json"', source)

    def test_the_legacy_chain_runs_the_stale_check_after_its_last_producer(self):
        source = LEGACY_CHAIN.read_text(encoding="utf-8")
        self.assertIn("run-stale-report-check.ps1", source)
        self.assertGreater(source.index("run-stale-report-check.ps1"), source.index("run-report-provenance-tests.ps1"))


@unittest.skipUnless(PWSH, "pwsh is required")
class StaleReportCheckBehaviourTest(unittest.TestCase):
    """The producer itself: old reports fail, fresh pass, absent ones are not its business."""

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="npdev-stale-check-"))
        self.addCleanup(shutil.rmtree, str(self.work), True)
        (self.work / "scripts" / "policy").mkdir(parents=True)
        self.out = self.work / "scripts" / "reports" / "out"
        self.out.mkdir(parents=True)
        names = ["fresh-report.json", "old-report.json", "missing-report.json", "stale-report-check-report.json", "provenance-report.json"]
        (self.work / "scripts" / "policy" / "beta-release-gate-policy.json").write_text(
            json.dumps({"maxReportAgeHours": 24, "requiredReports": [{"path": "scripts/reports/out/" + n} for n in names]}), encoding="utf-8")
        (self.work / "scripts" / "policy" / "beta0-scope.json").write_text(
            json.dumps({"expandedBeta0ContractVersion": "contract-1"}), encoding="utf-8")
        (self.out / "fresh-report.json").write_text(json.dumps({"overallStatus": "passed"}), encoding="utf-8")
        self.old = self.out / "old-report.json"
        self.old.write_text(json.dumps({"overallStatus": "passed"}), encoding="utf-8")

    def _age(self, path, hours):
        stamp = time.time() - hours * 3600
        os.utime(path, (stamp, stamp))

    def _run(self):
        completed = _pwsh(["-File", str(STALE_CHECK), "-RunId", "unit"], cwd=self.work)
        report = json.loads((self.out / "stale-report-check-report.json").read_text(encoding="utf-8-sig"))
        return completed.returncode, report

    def test_a_report_older_than_the_limit_fails_and_names_the_file(self):
        self._age(self.old, 49)
        code, report = self._run()
        self.assertEqual(code, 1)
        self.assertEqual(report["overallStatus"], "failed")
        self.assertEqual(report["evidence"]["checkedReports"], 2, "fresh + old only: absent, self and provenance are skipped")
        self.assertEqual(len(report["failures"]), 1)
        self.assertIn("older than 24h", report["failures"][0])
        self.assertIn("old-report.json", report["failures"][0])
        self.assertEqual(report["scriptPath"], "scripts/quality/run-stale-report-check.ps1")
        self.assertEqual(report["schemaVersion"], "npdev-stale-report-check-report.v1")

    def test_fresh_reports_pass(self):
        self._age(self.old, 2)
        code, report = self._run()
        self.assertEqual(code, 0)
        self.assertEqual(report["overallStatus"], "passed")
        self.assertTrue(report["evidence"]["blockingReportsFresh"])

    def test_a_report_declaring_another_contract_version_fails(self):
        (self.out / "fresh-report.json").write_text(json.dumps({"expandedBeta0ContractVersion": "contract-0"}), encoding="utf-8")
        code, report = self._run()
        self.assertEqual(code, 1)
        self.assertIn("declares contract contract-0, expected contract-1", report["failures"][0])

    def test_an_unparseable_report_fails(self):
        (self.out / "fresh-report.json").write_text("{ not json", encoding="utf-8")
        code, report = self._run()
        self.assertEqual(code, 1)
        self.assertIn("not parseable JSON", report["failures"][0])


@unittest.skipUnless(PWSH, "pwsh is required")
class SlimnessCleanRebuildableTest(unittest.TestCase):
    """(c) the slimness check can clear rebuildable directories first, and does so only when asked."""

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="npdev-slimness-"))
        self.addCleanup(shutil.rmtree, str(self.work), True)
        (self.work / "NPDevGenerator" / "src").mkdir(parents=True)
        (self.work / "NPDevGenerator" / "src" / "Keep.java").write_text("class Keep {}", encoding="utf-8")
        gradle = self.work / "NPDevGenerator" / ".gradle"
        gradle.mkdir()
        for index in range(8):
            (gradle / ("file-" + str(index) + ".bin")).write_text("x", encoding="utf-8")
        self.report = self.work / "slimness.json"

    def _run(self, *extra):
        completed = _pwsh(["-File", str(SLIMNESS), "-WorkspaceRoot", str(self.work), "-ReportPath", str(self.report), *extra])
        return completed.returncode, json.loads(self.report.read_text(encoding="utf-8-sig"))

    @unittest.skipUnless(os.name == "nt", "the cleanup and slimness scripts address paths with backslashes (Windows-only today)")
    def test_without_the_switch_a_rebuildable_directory_still_fails_the_check(self):
        code, report = self._run()
        self.assertEqual(code, 1)
        self.assertEqual([v["code"] for v in report["violations"]], ["rebuildable-directory-inside-workspace"])
        self.assertTrue((self.work / "NPDevGenerator" / ".gradle").exists())

    @unittest.skipUnless(os.name == "nt", "the cleanup and slimness scripts address paths with backslashes (Windows-only today)")
    def test_with_the_switch_it_is_removed_recorded_and_the_check_passes(self):
        code, report = self._run("-CleanRebuildable")
        self.assertEqual(code, 0, report.get("failures"))
        self.assertEqual(report["overallStatus"], "passed")
        self.assertFalse((self.work / "NPDevGenerator" / ".gradle").exists())
        self.assertIn("NPDevGenerator\\.gradle", report["summary"]["cleanedRebuildableDirectories"])
        self.assertTrue((self.work / "NPDevGenerator" / "src" / "Keep.java").exists(), "source must never be touched")

    def test_the_beta_gate_evidence_step_asks_for_the_cleanup(self):
        source = EXPANDED_EVIDENCE.read_text(encoding="utf-8")
        call = source[source.index("Test-WorkspaceSlimness.ps1"):]
        call = call[call.index("& $workspaceSlimnessScript"):].split("| Out-Null")[0]
        self.assertIn("-CleanRebuildable", call)


if __name__ == "__main__":
    unittest.main()
