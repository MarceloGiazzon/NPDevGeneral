"""Tests for `npdev service install|uninstall` (MON-22 follow-up / R9.6 CLI wiring).

R9.6 (OperationalRunbookEmitter) already writes Install-Service.ps1/Uninstall-Service.ps1 and
install-service.sh/uninstall-service.sh into every generated app's `_ops` -- proven live against a
real regenerated sample app in MON-22's own closure. What MON-22 explicitly left undone: no CLI
verb reached them at all. This is that thin wrapper: it locates the platform-appropriate script
the same way `npdev bench` locates the app (`npdev_monitor.probe_app`) and invokes it, never
reimplementing what the scripts do.

Deliberately does NOT install (or uninstall) anything for real anywhere in this suite -- installing
a service is privileged and hard to reverse. Every test either:
  - exercises a REAL --dry-run against a minimal STUB script (safe: the real Install-Service.ps1's
    own dry-run branch was independently proven side-effect-free in MON-22's own live verification;
    the stub here exists only so this suite does not depend on OperationalRunbookEmitter's actual
    generated content, which belongs to a different agent's ownership in this session), or
  - exercises the refusal / idempotent-no-op paths, which touch no OS service state by construction
    (an uninstall against nothing already-installed IS the no-op path, not a simulated one), or
  - proves the CLI-level uninstall --dry-run preview never calls subprocess at all (mocked and
    asserted not-called), since neither real script has a native dry-run flag for uninstall.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_service_cli -v
"""

from __future__ import annotations

import argparse
import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_monitor  # noqa: E402

FAKE_INSTALL_PS1 = """
param([switch]$DryRun, [switch]$Start)
if ($DryRun) {
  Write-Host "[dry run] would install (Start=$Start)"
  exit 0
}
Write-Host "installed (Start=$Start)"
exit 0
"""

FAKE_UNINSTALL_PS1 = """
Write-Host "No scheduled task named this app. Nothing to do."
exit 0
"""

FAKE_INSTALL_SH = """#!/bin/sh
DRY_RUN=0
START_NOW=0
PROFILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --start) START_NOW=1; shift ;;
    --profile) PROFILE="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done
if [ "$DRY_RUN" = "1" ]; then
  echo "[dry run] would install (start=$START_NOW profile=$PROFILE)"
  exit 0
fi
echo "installed (start=$START_NOW profile=$PROFILE)"
exit 0
"""

FAKE_UNINSTALL_SH = """#!/bin/sh
echo "No unit found for this app. Nothing to do."
exit 0
"""


def make_service_app(root: Path, *, with_ps1: bool = True, with_sh: bool = True) -> Path:
    """The minimal shape `npdev_monitor.discovery_rule`/`probe_app` need: `_ops/` plus
    `_ops/resolved-db-plan.json` (no `finalAppPath`/`appRoot` declared, so `finalAppRoot` defaults
    to the app root itself -- the current, post-QUAL-3 in-app layout, which is where
    OperationalRunbookEmitter actually writes `_ops` today)."""
    app = root / "demo-app"
    ops = app / "_ops"
    ops.mkdir(parents=True)
    (ops / "resolved-db-plan.json").write_text(json.dumps({"appId": "demo-app"}), encoding="utf-8")
    if with_ps1:
        (ops / "Install-Service.ps1").write_text(FAKE_INSTALL_PS1, encoding="utf-8")
        (ops / "Uninstall-Service.ps1").write_text(FAKE_UNINSTALL_PS1, encoding="utf-8")
    if with_sh:
        install_sh = ops / "install-service.sh"
        install_sh.write_text(FAKE_INSTALL_SH, encoding="utf-8", newline="\n")
        install_sh.chmod(0o755)
        uninstall_sh = ops / "uninstall-service.sh"
        uninstall_sh.write_text(FAKE_UNINSTALL_SH, encoding="utf-8", newline="\n")
        uninstall_sh.chmod(0o755)
    return app


def install_args(app: Path, **overrides) -> argparse.Namespace:
    args = argparse.Namespace(app_dir=str(app), dry_run=False, start=False, profile=None, json=True)
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


def uninstall_args(app: Path, **overrides) -> argparse.Namespace:
    args = argparse.Namespace(app_dir=str(app), dry_run=False, json=True)
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


class ServiceRefusalTest(unittest.TestCase):
    """Refusal paths need no scripts, no subprocess, no platform tool at all."""

    def test_refuses_when_not_a_generated_app(self):
        with tempfile.TemporaryDirectory() as tmp:
            not_an_app = Path(tmp) / "just-a-folder"
            not_an_app.mkdir()
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_service_install(install_args(not_an_app, dry_run=True))
            self.assertIn("not a generated NPDev app", str(ctx.exception))

    def test_refuses_when_install_script_is_absent_pre_r96_app(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp), with_ps1=False, with_sh=False)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_service_install(install_args(app, dry_run=True))
            message = str(ctx.exception)
            self.assertIn("does not exist", message)
            self.assertIn("R9.6", message)

    def test_refuses_when_uninstall_script_is_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp), with_ps1=False, with_sh=False)
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_service_uninstall(uninstall_args(app))


class ServiceScriptSelectionTest(unittest.TestCase):
    """Pure command-construction logic for both platforms -- no subprocess is ever started here."""

    def test_windows_selects_ps1_and_a_powershell_interpreter(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            probe = npdev_monitor.probe_app(app)
            with mock.patch.object(npdev_cli, "_is_windows_platform", return_value=True), \
                 mock.patch.object(npdev_cli, "_find_powershell", return_value="pwsh"):
                script, command = npdev_cli._service_script(probe, "install")
            self.assertEqual("Install-Service.ps1", script.name)
            self.assertEqual(["pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)], command)

    def test_windows_install_refuses_without_powershell(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            probe = npdev_monitor.probe_app(app)
            with mock.patch.object(npdev_cli, "_is_windows_platform", return_value=True), \
                 mock.patch.object(npdev_cli, "_find_powershell", return_value=None):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli._service_script(probe, "install")
            self.assertIn("no PowerShell found", str(ctx.exception))

    def test_posix_selects_sh_script(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            probe = npdev_monitor.probe_app(app)
            with mock.patch.object(npdev_cli, "_is_windows_platform", return_value=False):
                script, command = npdev_cli._service_script(probe, "uninstall")
            self.assertEqual("uninstall-service.sh", script.name)
            self.assertEqual(["sh", str(script)], command)


class ServiceInstallRealDryRunTest(unittest.TestCase):
    """Real subprocess execution of a STUB script -- never the real generated one (out of this
    session's file ownership), never a real registration (only ever --dry-run, which the stub
    mirrors the real script's own documented zero-side-effect contract for)."""

    @unittest.skipUnless(npdev_cli._find_powershell(), "no PowerShell (pwsh/powershell) on PATH")
    def test_dry_run_executes_the_stub_and_reports_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_service_install(install_args(app, dry_run=True))
            self.assertEqual(0, code)
            result = json.loads(captured.getvalue())
            self.assertTrue(result["ok"])
            self.assertEqual(0, result["exitCode"])
            self.assertTrue(result["dryRun"])
            self.assertIn("dry run", result["output"])
            self.assertIn("Install-Service.ps1", result["script"])

    @unittest.skipUnless(npdev_cli._find_powershell(), "no PowerShell (pwsh/powershell) on PATH")
    def test_dry_run_with_start_passes_dash_start_through(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            captured = io.StringIO()
            with redirect_stdout(captured):
                npdev_cli.run_service_install(install_args(app, dry_run=True, start=True))
            result = json.loads(captured.getvalue())
            self.assertIn("Start=True", result["output"])

    @unittest.skipUnless(npdev_cli._find_powershell(), "no PowerShell (pwsh/powershell) on PATH")
    def test_profile_on_windows_is_refused_before_running_anything(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            with mock.patch.object(npdev_cli, "subprocess") as fake_subprocess:
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli.run_service_install(install_args(app, dry_run=True, profile="prod"))
                fake_subprocess.run.assert_not_called()
            self.assertIn("--profile", str(ctx.exception))

    @unittest.skipUnless(npdev_cli._find_powershell(), "no PowerShell (pwsh/powershell) on PATH")
    def test_real_uninstall_against_nothing_installed_is_a_safe_noop(self):
        """Runs the REAL (non-dry-run) uninstall path -- safe because the stub, matching the real
        Uninstall-Service.ps1's own documented behaviour, checks for an existing registration
        FIRST and exits 0 doing nothing when none exists (which is always true here: this suite
        never installs anything)."""
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_service_uninstall(uninstall_args(app, dry_run=False))
            self.assertEqual(0, code)
            result = json.loads(captured.getvalue())
            self.assertTrue(result["ok"])
            self.assertIn("Nothing to do", result["output"])


class ServiceUninstallDryRunPreviewTest(unittest.TestCase):
    """Neither Uninstall-Service.ps1 nor uninstall-service.sh has a native dry-run flag -- prove
    the CLI's own --dry-run preview for uninstall never executes anything at all."""

    def test_uninstall_dry_run_never_calls_subprocess(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            with mock.patch.object(npdev_cli, "subprocess") as fake_subprocess:
                captured = io.StringIO()
                with redirect_stdout(captured):
                    code = npdev_cli.run_service_uninstall(uninstall_args(app, dry_run=True))
                fake_subprocess.run.assert_not_called()
            self.assertEqual(0, code)
            result = json.loads(captured.getvalue())
            self.assertTrue(result["dryRun"])
            self.assertIn("no native dry-run mode", result["note"])
            self.assertTrue(any("Uninstall-Service.ps1" in part or "uninstall-service.sh" in part
                                 for part in result["wouldRun"]))


@unittest.skipUnless(shutil.which("sh"), "no sh on PATH")
class ServicePosixRealExecutionTest(unittest.TestCase):
    """Real execution of the POSIX stub twin via `sh`, with os.name forced to "posix" so the same
    code this platform would use on Linux is what actually runs here -- proving the POSIX branch
    end to end, not just its command construction (covered above)."""

    def test_posix_install_dry_run_executes_for_real(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            captured = io.StringIO()
            with mock.patch.object(npdev_cli, "_is_windows_platform", return_value=False):
                with redirect_stdout(captured):
                    code = npdev_cli.run_service_install(install_args(app, dry_run=True, profile="prod"))
            self.assertEqual(0, code)
            result = json.loads(captured.getvalue())
            self.assertTrue(result["ok"])
            self.assertIn("dry run", result["output"])
            self.assertIn("profile=prod", result["output"])
            self.assertIn("install-service.sh", result["script"])

    def test_posix_uninstall_real_run_is_a_safe_noop(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_service_app(Path(tmp))
            captured = io.StringIO()
            with mock.patch.object(npdev_cli, "_is_windows_platform", return_value=False):
                with redirect_stdout(captured):
                    code = npdev_cli.run_service_uninstall(uninstall_args(app, dry_run=False))
            self.assertEqual(0, code)
            result = json.loads(captured.getvalue())
            self.assertIn("Nothing to do", result["output"])


if __name__ == "__main__":
    unittest.main()
