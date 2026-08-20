"""Tests for `npdev dev`'s watch loop (`dev_loop.py`) -- specifically RUN-24 gap 1: a METADATA_ONLY
change against an ALREADY-RUNNING app must hot swap (`MetadataHotSwapController#apply` via
`cli._metadata_hotswap_apply`) instead of the unconditional stop/generate/build/boot cycle that ran
on every single save before this fix, restarting the app (new PID) regardless of classification.

Filesystem-and-stub level, same shape as `test_monitor_hotswap.py`: what `run_cycle`/`_try_hot_swap`
own is deciding WHETHER to attempt the swap and wiring the (mocked) classify-emit-apply pipeline
together, not `RuntimeMetadataService`/`MetadataHotSwapController` themselves (RUN-22's own live
boot-edit-swap-observe proof, plus `MetadataHotSwapControllerStandaloneTest.java`, own that), nor the
HTTP/manifest-patch mechanics of `_metadata_hotswap_apply`/`_patch_generated_ui_manifest`
(`test_monitor_hotswap.py` owns those directly). Nothing green here proves a real running JVM's
catalogs -- or a real browser's rendered grid -- actually changed; see RUN-24.yml's own verification
section for the live proof that closes that gap.
"""

from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import dev_loop


class FakeAppProcess:
    """Enough of `dev_loop.AppProcess`'s surface for `run_cycle` to drive: `.alive()` (checked
    before attempting a hot swap and again by the Windows-only stop-first branch below it),
    `.stop()` (must NOT be called on a successful hot swap -- that is the entire point of RUN-24;
    IS called on a fallback-to-restart, which this repo's Windows environment always takes before
    GENERATE), and `.jar` (read by the swap phase on a full restart)."""

    def __init__(self):
        self.stopped = False
        self.jar = Path("previous.jar")

    def alive(self) -> bool:
        return not self.stopped

    def stop(self, timeout: float = 20.0) -> None:  # noqa: ARG002
        self.stopped = True


def make_options(tmp: Path) -> dev_loop.DevOptions:
    model = tmp / "model.json"
    model.write_text("{}", encoding="utf-8")
    config = tmp / "config.json"
    config.write_text("{}", encoding="utf-8")
    output = tmp / "app"
    output.mkdir()
    options = dev_loop.DevOptions(model=model, config=config, output=output, port=18099)
    options.state_dir.mkdir(parents=True, exist_ok=True)
    # `fast` is only even evaluated once a baseline exists (cycle 2+, matching a real dev session --
    # cycle 1 always takes the full path since there is nothing to diff against yet).
    options.baseline.write_bytes(model.read_bytes())
    return options


def make_cli(**overrides) -> mock.MagicMock:
    """A stand-in for the `npdev_cli` module `run_cycle` is injected with (`sys.modules[__name__]`
    in real use) -- a MagicMock so every call is both observable (`assert_called`/`call_count`) and
    safely a no-op unless overridden."""
    cli = mock.MagicMock()
    cli.repo_root.return_value = Path("/repo")
    cli.run_validate_semantic.return_value = 0
    cli._classify_model_change.return_value = "METADATA_ONLY"
    for key, value in overrides.items():
        setattr(cli, key, value)
    return cli


def silent_output() -> dev_loop.Output:
    return dev_loop.Output(json_events=False)


class HotSwapFastPath(unittest.TestCase):
    def test_metadata_only_change_against_a_running_app_hot_swaps_with_no_restart(self):
        with TemporaryDirectory() as tmp:
            options = make_options(Path(tmp))
            current = FakeAppProcess()
            cli = make_cli(
                _classify_model_change=mock.MagicMock(return_value="METADATA_ONLY"),
                _emit_metadata_only_catalogs=mock.MagicMock(return_value=("METADATA_ONLY", True, "")),
                _metadata_hotswap_apply=mock.MagicMock(return_value={
                    "ok": True, "code": "APPLIED", "metadataGeneration": 3,
                    "uiManifestPatch": {"patched": True, "fieldsChanged": ["WidgetShipmentEvent.warehouseId"]},
                }),
            )
            out = silent_output()
            buf = io.StringIO()
            with mock.patch.object(dev_loop, "_super_user_key", return_value="a-key"), \
                    redirect_stderr(buf):
                result, app = dev_loop.run_cycle(options, current, out, cli)

            self.assertTrue(result.ok)
            self.assertEqual(result.phase, "READY")
            self.assertTrue(result.fast)
            # SAME app object -- no restart, no new process, same PID (RUN-24's whole point).
            self.assertIs(app, current)
            self.assertFalse(current.stopped)
            # GENERATE/BUILD must never run for a hot-swapped cycle -- that is what "fast path"
            # means; running them anyway would defeat the entire point of this item.
            cli._generate_phase_captured.assert_not_called()
            cli._build_phase.assert_not_called()
            cli._metadata_hotswap_apply.assert_called_once()
            # The baseline is still advanced on a successful hot swap, exactly as a normal
            # successful cycle would, so the NEXT save classifies against the right prior state.
            self.assertEqual(options.baseline.read_bytes(), options.model.read_bytes())

    def test_hot_swap_refusal_falls_back_to_the_existing_restart_path(self):
        """Never silent: a refused/failed hot swap must still reach GENERATE (the pre-RUN-24
        behavior), not just quietly do nothing."""
        with TemporaryDirectory() as tmp:
            options = make_options(Path(tmp))
            current = FakeAppProcess()
            cli = make_cli(
                _classify_model_change=mock.MagicMock(return_value="METADATA_ONLY"),
                _emit_metadata_only_catalogs=mock.MagicMock(
                    return_value=(None, False, "no Gradle wrapper and no staged npdev-ai-tools.jar")),
                _generate_phase_captured=mock.MagicMock(return_value=(False, "stub generate failure")),
                _log_excerpt=mock.MagicMock(return_value="stub generate failure"),
            )
            out = silent_output()
            buf = io.StringIO()
            with mock.patch.object(dev_loop, "_super_user_key", return_value="a-key"), \
                    redirect_stderr(buf):
                result, app = dev_loop.run_cycle(options, current, out, cli)

            # The hot swap was attempted (classify-emit ran) and refused, so this cycle fell through
            # to the ordinary GENERATE path -- proven by GENERATE actually having been called, not
            # merely by the cycle failing (which could also happen for an unrelated reason).
            cli._emit_metadata_only_catalogs.assert_called_once()
            cli._metadata_hotswap_apply.assert_not_called()
            cli._generate_phase_captured.assert_called_once()
            self.assertFalse(result.ok)
            self.assertEqual(result.phase, "GENERATE")
            self.assertIn("hot swap not applied -- falling back to restart", buf.getvalue())

    def test_no_super_user_key_falls_back_without_calling_the_classifier_or_the_endpoint(self):
        """The ordinary case for the default in-memory dev database (SuperUserBootstrapper never
        issues a credential without a physical database) -- refused BEFORE spending a classifier
        run or an HTTP call on a swap that could never authenticate."""
        with TemporaryDirectory() as tmp:
            options = make_options(Path(tmp))
            current = FakeAppProcess()
            cli = make_cli(
                _classify_model_change=mock.MagicMock(return_value="METADATA_ONLY"),
                _generate_phase_captured=mock.MagicMock(return_value=(False, "stub generate failure")),
                _log_excerpt=mock.MagicMock(return_value="stub generate failure"),
            )
            out = silent_output()
            buf = io.StringIO()
            with mock.patch.object(dev_loop, "_super_user_key", return_value=None), \
                    redirect_stderr(buf):
                dev_loop.run_cycle(options, current, out, cli)

            cli._emit_metadata_only_catalogs.assert_not_called()
            cli._metadata_hotswap_apply.assert_not_called()
            cli._generate_phase_captured.assert_called_once()

    def test_structural_change_never_attempts_a_hot_swap(self):
        """`fast=False` (anything other than METADATA_ONLY) must take the untouched full path --
        the hot-swap branch is gated on `fast`, and this proves the gate actually holds."""
        with TemporaryDirectory() as tmp:
            options = make_options(Path(tmp))
            current = FakeAppProcess()
            cli = make_cli(
                _classify_model_change=mock.MagicMock(return_value="SAFE_ADDITIVE"),
                _generate_phase_captured=mock.MagicMock(return_value=(False, "stub generate failure")),
                _log_excerpt=mock.MagicMock(return_value="stub generate failure"),
            )
            out = silent_output()
            buf = io.StringIO()
            with redirect_stderr(buf):
                dev_loop.run_cycle(options, current, out, cli)

            cli._emit_metadata_only_catalogs.assert_not_called()
            cli._metadata_hotswap_apply.assert_not_called()
            cli._generate_phase_captured.assert_called_once()

    def test_first_cycle_with_no_running_app_never_attempts_a_hot_swap(self):
        """`current is None` on the very first cycle (nothing to swap into) -- must go straight to
        the full path with no crash and no hot-swap attempt, even if classification somehow reports
        METADATA_ONLY (it will not in practice: `fast` is only evaluated once a baseline exists)."""
        with TemporaryDirectory() as tmp:
            options = make_options(Path(tmp))
            cli = make_cli(
                _classify_model_change=mock.MagicMock(return_value="METADATA_ONLY"),
                _generate_phase_captured=mock.MagicMock(return_value=(False, "stub generate failure")),
                _log_excerpt=mock.MagicMock(return_value="stub generate failure"),
            )
            out = silent_output()
            buf = io.StringIO()
            with redirect_stderr(buf):
                dev_loop.run_cycle(options, None, out, cli)

            cli._emit_metadata_only_catalogs.assert_not_called()
            cli._metadata_hotswap_apply.assert_not_called()
            cli._generate_phase_captured.assert_called_once()


class SuperUserKeyDiscovery(unittest.TestCase):
    def test_reads_the_raw_location_dev_loop_boot_actually_produces(self):
        """`SuperUserBootstrapper` writes into the booted process's own CWD -- `boot()` launches
        `java -jar` with `cwd=options.output` directly, never through `Start-App.ps1` (the only
        thing that relocates the file into `_ops/`)."""
        with TemporaryDirectory() as tmp:
            app_root = Path(tmp)
            (app_root / "SUPER_USER_KEY.txt").write_text("raw-key\n", encoding="utf-8")
            self.assertEqual(dev_loop._super_user_key(app_root), "raw-key")

    def test_falls_back_to_the_ops_relocated_location(self):
        with TemporaryDirectory() as tmp:
            app_root = Path(tmp)
            (app_root / "_ops").mkdir()
            (app_root / "_ops" / "SUPER_USER_KEY.txt").write_text("relocated-key\n", encoding="utf-8")
            self.assertEqual(dev_loop._super_user_key(app_root), "relocated-key")

    def test_none_when_no_key_exists_anywhere(self):
        with TemporaryDirectory() as tmp:
            self.assertIsNone(dev_loop._super_user_key(Path(tmp)))


if __name__ == "__main__":
    unittest.main()
