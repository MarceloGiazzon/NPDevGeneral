"""XPLAT-2b: the non-Windows branches of the CLI's process / port / path helpers.

The CLI runs on Linux and macOS CI runners, but the developer box is Windows, so a POSIX-only branch
can rot unseen. Each test swaps the module's `os` binding for a tiny stand-in that reports
`name == "posix"` (or `"nt"`) -- NEVER `os.name` itself, because on Windows pathlib reads the real
`os.name` to pick its flavour and patching it globally breaks every `Path(...)`. The branch is
exercised via monkeypatch; nothing here claims to have run on a real Linux/macOS kernel.
"""

from __future__ import annotations

import os
import signal
import sys
import types
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import dev_loop  # noqa: E402
import npdev_cli  # noqa: E402
import npdev_explore  # noqa: E402
import npdev_monitor  # noqa: E402
import npdev_probe  # noqa: E402
import npdev_tunnel  # noqa: E402


def _fake_os(name: str, **extra):
    """A stand-in for the `os` module: real environ/path helpers, overridable `name` and extras."""
    ns = types.SimpleNamespace(name=name, environ=dict(os.environ), path=os.path,
                               getpid=os.getpid, devnull=os.devnull)
    for key, value in extra.items():
        setattr(ns, key, value)
    return ns


class ProbeJavaExecutableTests(unittest.TestCase):
    def test_posix_uses_java_without_exe_suffix(self):
        with mock.patch.object(npdev_probe, "os", _fake_os("posix")):
            java = npdev_probe._java_executable(str(Path(__file__).resolve().parent))
        self.assertEqual(Path(java).name, "java")
        self.assertEqual(Path(java).parent.name, "bin")

    def test_windows_keeps_java_exe(self):
        with mock.patch.object(npdev_probe, "os", _fake_os("nt")):
            java = npdev_probe._java_executable(str(Path(__file__).resolve().parent))
        self.assertEqual(Path(java).name, "java.exe")

    def test_missing_or_unset_java_home_falls_back_to_path_lookup(self):
        for name in ("posix", "nt"):
            with mock.patch.object(npdev_probe, "os", _fake_os(name)):
                self.assertEqual(npdev_probe._java_executable(None), "java")
                self.assertEqual(npdev_probe._java_executable(os.path.join(os.sep, "definitely", "not", "here")), "java")


class TerminateBranchTests(unittest.TestCase):
    """taskkill is only ever invoked on Windows; POSIX signals the process directly."""

    def test_probe_terminate_posix_never_calls_taskkill(self):
        with mock.patch.object(npdev_probe, "os", _fake_os("posix")), \
                mock.patch.object(npdev_probe.subprocess, "run") as run:
            npdev_probe._terminate(4242)
        self.assertEqual(run.call_args.args[0], ["kill", "-9", "4242"])

    def test_probe_terminate_windows_uses_taskkill_tree(self):
        with mock.patch.object(npdev_probe, "os", _fake_os("nt")), \
                mock.patch.object(npdev_probe.subprocess, "run") as run:
            npdev_probe._terminate(4242)
        self.assertEqual(run.call_args.args[0], ["taskkill", "/PID", "4242", "/T", "/F"])

    def test_explore_stop_process_posix_kills_the_process_group(self):
        killpg = mock.Mock()
        fake = _fake_os("posix", killpg=killpg, getpgid=lambda pid: pid + 1)
        process = mock.Mock(pid=777)
        process.wait.return_value = 0
        with mock.patch.object(npdev_explore, "os", fake), \
                mock.patch.object(npdev_explore.subprocess, "run") as run:
            npdev_explore._stop_process(process)
        killpg.assert_called_once_with(778, signal.SIGTERM)
        run.assert_not_called()

    def test_dev_loop_reclaim_posix_sends_sigterm_not_taskkill(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp)
            (state / "app.pid").write_text("999", encoding="utf-8")
            options = types.SimpleNamespace(state_dir=state, port=8123)
            kill = mock.Mock()
            fake = _fake_os("posix", kill=kill)
            out = mock.Mock()
            with mock.patch.object(dev_loop, "os", fake), \
                    mock.patch.object(dev_loop.subprocess, "run") as run, \
                    mock.patch.object(dev_loop, "_health_ok", side_effect=[True, False]), \
                    mock.patch.object(dev_loop.time, "sleep"):
                dev_loop._reclaim_orphan(options, out)
            kill.assert_called_once_with(999, signal.SIGTERM)
            run.assert_not_called()
            self.assertFalse((state / "app.pid").exists())

    def test_tunnel_stop_posix_sends_sigterm_not_taskkill(self):
        kill = mock.Mock()
        fake = _fake_os("posix", kill=kill)
        with mock.patch.object(npdev_tunnel, "os", fake), \
                mock.patch.dict(sys.modules, {"psutil": None}), \
                mock.patch.object(npdev_tunnel.subprocess, "run") as run, \
                mock.patch.object(npdev_tunnel, "is_alive", side_effect=[True, False, False]), \
                mock.patch.object(npdev_tunnel.time, "sleep"):
            self.assertTrue(npdev_tunnel.stop({"pid": 31337}))
        kill.assert_called_once_with(31337, signal.SIGTERM)
        run.assert_not_called()


class ListenerEnumerationTests(unittest.TestCase):
    LSOF = (
        "COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME\n"
        "java    1234 me    45u  IPv6 0xabc      0t0  TCP *:8080 (LISTEN)\n"
        "node    5678 me    22u  IPv4 0xdef      0t0  TCP 127.0.0.1:3010 (LISTEN)\n"
        "Chrome  9999 me    30u  IPv4 0x123      0t0  TCP 10.0.0.2:5555->1.2.3.4:443 (ESTABLISHED)\n"
    )

    def test_parse_lsof_listeners(self):
        self.assertEqual(npdev_monitor._parse_lsof_listeners(self.LSOF),
                         [(8080, 1234), (3010, 5678)])

    def _snapshot(self, run_side_effect):
        with mock.patch.object(npdev_monitor, "os", _fake_os("posix")), \
                mock.patch.dict(sys.modules, {"psutil": None}), \
                mock.patch.object(npdev_monitor.subprocess, "run", side_effect=run_side_effect) as run:
            snap = npdev_monitor.MachineSnapshot(want_docker=False)
        return snap, run

    def test_macos_without_ss_falls_back_to_lsof(self):
        def run(cmd, **_kwargs):
            if cmd[0] == "ss":
                raise FileNotFoundError("ss")
            self.assertEqual(cmd[0], "lsof")
            return types.SimpleNamespace(stdout=self.LSOF, returncode=0)

        snap, _ = self._snapshot(run)
        self.assertTrue(snap.enumerated)
        self.assertEqual(snap.listeners_on(8080), [1234])
        self.assertEqual(snap.listeners_on(3010), [5678])

    def test_ss_is_still_preferred_when_present(self):
        ss_out = 'LISTEN 0 100 *:8080 *:* users:(("java",pid=77,fd=5))\n'
        calls = []

        def run(cmd, **_kwargs):
            calls.append(cmd[0])
            return types.SimpleNamespace(stdout=ss_out, returncode=0)

        snap, _ = self._snapshot(run)
        self.assertEqual(calls, ["ss"])
        self.assertEqual(snap.listeners_on(8080), [77])

    def test_neither_ss_nor_lsof_reports_unenumerated_not_stopped(self):
        snap, _ = self._snapshot(FileNotFoundError("nothing"))
        self.assertFalse(snap.enumerated)
        self.assertEqual(snap.listeners_on(8080), [])


class ClaudeDesktopConfigPathTests(unittest.TestCase):
    def _path(self, platform: str, osname: str, **env):
        fake = _fake_os(osname)
        fake.environ = dict(env)
        with mock.patch.object(npdev_cli, "os", fake), \
                mock.patch.object(npdev_cli.sys, "platform", platform):
            return npdev_cli._claude_desktop_config_path()

    def test_linux_honours_xdg_config_home(self):
        path = self._path("linux", "posix", XDG_CONFIG_HOME="/xdg/cfg")
        self.assertEqual(path, Path("/xdg/cfg") / "Claude" / "claude_desktop_config.json")

    def test_linux_defaults_to_dot_config(self):
        path = self._path("linux", "posix")
        self.assertEqual(path, Path.home() / ".config" / "Claude" / "claude_desktop_config.json")

    def test_macos_uses_application_support(self):
        path = self._path("darwin", "posix", XDG_CONFIG_HOME="/ignored")
        self.assertEqual(path, Path.home() / "Library" / "Application Support" / "Claude"
                         / "claude_desktop_config.json")

    def test_windows_still_uses_appdata(self):
        roaming = os.path.join(os.sep, "roaming-appdata")
        path = self._path("win32", "nt", APPDATA=roaming, XDG_CONFIG_HOME="/ignored")
        self.assertEqual(path, Path(roaming) / "Claude" / "claude_desktop_config.json")
        self.assertIsNone(self._path("win32", "nt"))


if __name__ == "__main__":
    unittest.main()
