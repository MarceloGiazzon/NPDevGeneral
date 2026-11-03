"""JOBS-1..4: job records, lost detection, stop, restart, the prompt that started a job, the board.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_jobs -v
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
REPO = HERE.parents[1]
sys.path.insert(0, str(REPO / "scripts" / "ai"))

import npdev_jobs as jobs  # noqa: E402

DEAD_PID = 2 ** 22 - 3        # a pid no machine we run on has


def stamp(offset_seconds: float = 0.0) -> str:
    return datetime.fromtimestamp(time.time() + offset_seconds, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def record(job_id="20261103T100000Z-gates", **over):
    rec = jobs.new_record(job_id, "Quality gates", ["pwsh", "-File", "run-all-gates.ps1"], "/repo", "/log", os.getpid(),
                          os.getpid(), {"kind": "terminal"})
    rec.update(over)
    return rec


class RecordsAndLostTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def test_a_record_is_written_atomically_and_read_back(self):
        rec = record()
        jobs.write_record(self.root, rec)
        self.assertEqual(jobs.read_record(self.root, rec["jobId"])["title"], "Quality gates")
        self.assertEqual([p.name for p in (self.root / "jobs").iterdir()], [rec["jobId"] + ".json"], "no .tmp left behind")

    def test_a_job_id_cannot_escape_the_jobs_folder(self):
        with self.assertRaises(ValueError):
            jobs.record_path(self.root, "../../evil")

    def test_the_argv_is_redacted_before_it_is_stored(self):
        rec = jobs.new_record("20261103T100000Z-x", "x", ["tool", "--key", "gsk_abcdefghijklmnopqrstuvwx"], "/", "/l", 1, 1, {"kind": "terminal"})
        self.assertNotIn("gsk_abcdef", json.dumps(rec))
        self.assertIn("<redacted:api-key>", json.dumps(rec))

    def test_running_with_a_live_writer_is_running(self):
        self.assertEqual(jobs.effective_status(record(heartbeatAt=stamp(-600))), "running")

    def test_a_dead_writer_with_a_fresh_heartbeat_is_not_lost_yet(self):
        self.assertEqual(jobs.effective_status(record(pid=DEAD_PID, writerPid=DEAD_PID, heartbeatAt=stamp(-10))), "running")

    def test_a_dead_writer_and_a_stale_heartbeat_is_lost(self):
        self.assertEqual(jobs.effective_status(record(pid=DEAD_PID, writerPid=DEAD_PID, heartbeatAt=stamp(-120))), "lost")

    def test_a_finished_job_is_never_reclassified(self):
        self.assertEqual(jobs.effective_status(record(status="succeeded", pid=DEAD_PID, writerPid=DEAD_PID, heartbeatAt=stamp(-9999))), "succeeded")

    def test_list_decides_lost_and_orders_newest_first(self):
        jobs.write_record(self.root, record("20261103T090000Z-old", status="succeeded", startedAt=stamp(-7200)))
        jobs.write_record(self.root, record("20261103T100000Z-dead", pid=DEAD_PID, writerPid=DEAD_PID, heartbeatAt=stamp(-300), startedAt=stamp(-400)))
        jobs.write_record(self.root, record("20261103T110000Z-live", startedAt=stamp(-5)))
        rows = jobs.list_jobs(self.root)
        self.assertEqual([r["jobId"].split("-", 1)[1] for r in rows], ["live", "dead", "old"])
        self.assertEqual([r["status"] for r in rows], ["running", "lost", "succeeded"])
        self.assertEqual(rows[1]["recordedStatus"], "running", "the file still says running; the CLI decided lost")
        s = jobs.summary(self.root)
        self.assertEqual((s["running"], s["lost"]), (1, 1))
        self.assertEqual(s["newest"]["title"], "Quality gates")

    def test_a_job_lost_more_than_a_day_ago_stops_nagging(self):
        jobs.write_record(self.root, record(pid=DEAD_PID, writerPid=DEAD_PID, heartbeatAt=stamp(-200000), startedAt=stamp(-200100)))
        self.assertEqual(jobs.summary(self.root)["lost"], 0)
        self.assertEqual(jobs.list_jobs(self.root)[0]["status"], "lost")

    def test_finish_records_the_outcome_but_never_overwrites_a_stop(self):
        a = record("20261103T100000Z-a")
        jobs.write_record(self.root, a)
        jobs.finish(self.root, a["jobId"], 0)
        self.assertEqual(jobs.read_record(self.root, a["jobId"])["status"], "succeeded")
        b = record("20261103T100001Z-b", status="cancelled")
        jobs.write_record(self.root, b)
        jobs.finish(self.root, b["jobId"], 1)                     # the killed process exits non-zero a moment later
        self.assertEqual(jobs.read_record(self.root, b["jobId"])["status"], "cancelled")

    def test_the_heartbeat_redacts_the_last_line(self):
        rec = record()
        jobs.write_record(self.root, rec)
        jobs.heartbeat(self.root, rec["jobId"], 12, "calling with gsk_abcdefghijklmnopqrstuvwx now")
        got = jobs.read_record(self.root, rec["jobId"])
        self.assertEqual(got["progress"]["lines"], 12)
        self.assertNotIn("gsk_abcdef", got["progress"]["lastLine"])

    def test_the_launcher_comes_from_the_environment(self):
        self.assertEqual(jobs.launcher_info({"CLAUDE_CODE_SESSION_ID": "abc-123"}), {"kind": "claude-code", "sessionId": "abc-123"})
        self.assertEqual(jobs.launcher_info({"CI": "true", "CLAUDE_CODE_SESSION_ID": "x"})["kind"], "ci")
        self.assertEqual(jobs.launcher_info({"NPDEV_JOB_LAUNCHER": "manager"})["kind"], "manager")
        self.assertEqual(jobs.launcher_info({})["kind"], "terminal")
        self.assertEqual(jobs.new_record("20261103T100000Z-x", "x", [], "/", "/l", 1, 1, {"kind": "ci"})["actions"], ["openLog"])


class StopAndRestartTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.procs: list[subprocess.Popen] = []
        self.addCleanup(lambda: [jobs.kill_tree(p.pid) for p in self.procs])

    def _spawn_tree(self) -> subprocess.Popen:
        """A parent that starts a child of its own and waits, like a gate runner starting Gradle."""
        code = "import subprocess,sys,time;subprocess.Popen([sys.executable,'-c','import time;time.sleep(120)']);time.sleep(120)"
        kwargs = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        proc = subprocess.Popen([sys.executable, "-c", code], **kwargs)
        self.procs.append(proc)
        time.sleep(1.0)
        return proc

    def test_stop_ends_the_process_tree_and_records_cancelled(self):
        proc = self._spawn_tree()
        rec = record("20261103T100000Z-sleeper", pid=proc.pid, writerPid=os.getpid())
        jobs.write_record(self.root, rec)
        out = jobs.stop(self.root, rec["jobId"])
        self.assertTrue(out["ok"], out)
        proc.wait(timeout=10)
        self.assertFalse(jobs.pid_alive(proc.pid))
        got = jobs.read_record(self.root, rec["jobId"])
        self.assertEqual(got["status"], "cancelled")
        self.assertIsNotNone(got["endedAt"])
        self.assertFalse(jobs.stop(self.root, rec["jobId"])["ok"], "stopping an ended job says so")

    def test_a_job_started_elsewhere_offers_no_stop(self):
        rec = jobs.new_record("20261103T100000Z-ci", "x", ["a"], "/", "/l", os.getpid(), os.getpid(), {"kind": "ci"})
        jobs.write_record(self.root, rec)
        self.assertIn("started elsewhere", jobs.stop(self.root, rec["jobId"])["error"])

    def test_restart_replays_the_command_through_run_digest_as_a_new_job(self):
        rec = record("20261103T100000Z-again", status="failed", argv=[sys.executable, "-c", "print('again')"], cwd=str(REPO))
        jobs.write_record(self.root, rec)
        out = jobs.restart(self.root, rec["jobId"], REPO)
        self.assertTrue(out["ok"], out)
        deadline = time.time() + 30
        while time.time() < deadline and len(list((self.root / "jobs").glob("*.json"))) < 2:
            time.sleep(0.5)
        files = sorted((self.root / "jobs").glob("*.json"))
        self.assertEqual(len(files), 2, files)
        # let the detached run_digest finish and release its log before the temp folder is removed
        deadline = time.time() + 30
        while time.time() < deadline and any(json.loads(p.read_text(encoding="utf-8"))["status"] == "running" for p in files):
            time.sleep(0.3)
        time.sleep(1.0)

    def test_a_command_with_a_hidden_secret_cannot_be_replayed(self):
        rec = record("20261103T100000Z-secret", status="failed", argv=["tool", "<redacted:api-key>"])
        jobs.write_record(self.root, rec)
        self.assertIn("secret", jobs.restart(self.root, rec["jobId"], REPO)["error"])

    def test_a_running_job_cannot_be_restarted(self):
        rec = record("20261103T100000Z-busy")
        jobs.write_record(self.root, rec)
        self.assertIn("still running", jobs.restart(self.root, rec["jobId"], REPO)["error"])


class RunDigestWritesTheRecordTests(unittest.TestCase):
    def test_run_digest_creates_a_record_heartbeats_and_ends_it(self):
        import run_digest
        with tempfile.TemporaryDirectory() as td:
            env = {"NPDEV_BUILD_ROOT": td, "CLAUDE_CODE_SESSION_ID": "sess-0001-aaaa"}
            argv = ["run_digest.py", "--label", "jobtest", "--family", "generic", "--", sys.executable, "-c",
                    "import time;print('first');time.sleep(3);print('last gsk_abcdefghijklmnopqrstuvwx')"]
            with mock.patch.dict(os.environ, env), mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(jobs, "HEARTBEAT_SECONDS", 1), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(run_digest.main(), 0)
            files = list((Path(td) / "jobs").glob("*.json"))
            self.assertEqual(len(files), 1)
            rec = json.loads(files[0].read_text(encoding="utf-8"))
        self.assertEqual(rec["status"], "succeeded")
        self.assertEqual(rec["exitCode"], 0)
        self.assertEqual(rec["launcher"], {"kind": "claude-code", "sessionId": "sess-0001-aaaa"})
        self.assertEqual(rec["jobId"].split("-", 1)[1], "jobtest")
        self.assertEqual(rec["writerPid"], os.getpid())
        self.assertNotEqual(rec["heartbeatAt"], rec["startedAt"], "the end rewrote the heartbeat")
        self.assertTrue(rec["logPath"].endswith("jobtest.log"))
        self.assertNotIn("gsk_abcdef", json.dumps(rec))


class PromptExcerptTests(unittest.TestCase):
    def _transcript(self, root: Path, session: str, command: str, started: str) -> Path:
        project = root / ".claude" / "projects" / "d--repo"
        project.mkdir(parents=True)
        lines = [
            {"type": "user", "timestamp": "2026-11-03T09:00:00Z", "message": {"role": "user", "content": "please run the gates, my key is gsk_abcdefghijklmnopqrstuvwx"}},
            {"type": "assistant", "timestamp": "2026-11-03T09:00:05Z", "message": {"role": "assistant", "content": [
                {"type": "tool_use", "input": {"command": "ls"}}]}},
            {"type": "user", "timestamp": "2026-11-03T09:01:00Z", "message": {"role": "user", "content": [{"type": "tool_result", "content": "x"}]}},
            {"type": "assistant", "timestamp": "2026-11-03T09:01:10Z", "message": {"role": "assistant", "content": [
                {"type": "tool_use", "input": {"command": "cd /repo; " + command}}]}},
            {"type": "user", "timestamp": "2026-11-03T12:00:00Z", "message": {"role": "user", "content": "a LATER request that must not be picked"}},
        ]
        path = project / f"{session}.jsonl"
        path.write_text("\n".join(json.dumps(x) for x in lines), encoding="utf-8")
        return path

    def test_the_board_names_the_request_that_started_the_job(self):
        with tempfile.TemporaryDirectory() as td:
            home = Path(td)
            self._transcript(home, "sess-0001-aaaa", "python run_digest.py -- pwsh -File run-all-gates.ps1", "")
            rec = record(argv=["pwsh", "-File", "run-all-gates.ps1"], startedAt="2026-11-03T09:01:12Z",
                         launcher={"kind": "claude-code", "sessionId": "sess-0001-aaaa"})
            info = jobs.prompt_excerpt(rec, home)
            self.assertTrue(info["found"], info)
            self.assertIn("please run the gates", info["prompt"])
            self.assertNotIn("LATER", info["prompt"])
            self.assertNotIn("gsk_abcdef", info["prompt"])
            self.assertIn("run-all-gates.ps1", info["command"])
            self.assertLessEqual(len(info["prompt"]), 300)
            page = jobs.render_board([jobs.view(rec)], home=home)
            self.assertIn("please run the gates", page)

    def test_a_command_the_shell_unquoted_still_matches(self):
        """The recorded argv has no quotes (the shell removed them); the tool call that produced it does."""
        with tempfile.TemporaryDirectory() as td:
            home = Path(td)
            self._transcript(home, "sess-0003-cccc", "python run_digest.py -- python -c \"print('x y')\"", "")
            rec = record(argv=["python", "-c", "print('x y')"], startedAt="2026-11-03T09:01:12Z",
                         launcher={"kind": "claude-code", "sessionId": "sess-0003-cccc"})
            self.assertTrue(jobs.prompt_excerpt(rec, home)["found"])

    def test_it_never_modifies_the_transcript_and_says_why_when_there_is_none(self):
        with tempfile.TemporaryDirectory() as td:
            home = Path(td)
            path = self._transcript(home, "sess-0002-bbbb", "run-all-gates.ps1", "")
            before = path.read_bytes()
            jobs.prompt_excerpt(record(launcher={"kind": "claude-code", "sessionId": "sess-0002-bbbb"}), home)
            self.assertEqual(path.read_bytes(), before)
            self.assertFalse(jobs.prompt_excerpt(record(launcher={"kind": "claude-code", "sessionId": "sess-9999-zzzz"}), home)["found"])
            self.assertIn("only jobs started by an AI agent", jobs.prompt_excerpt(record(), home)["reason"])


class StatusLineTests(unittest.TestCase):
    """JOBS-2: a running gate shows in the status line with no chat message."""

    def test_the_segment_shows_what_runs_and_what_was_lost_and_is_silent_otherwise(self):
        import session_meter
        with tempfile.TemporaryDirectory() as td:
            with mock.patch.dict(os.environ, {"NPDEV_BUILD_ROOT": td}):
                self.assertEqual(session_meter.jobs_segment(), "", "no jobs folder: nothing to say")
                jobs.write_record(Path(td), record("20261103T090000Z-done", status="succeeded"))
                self.assertEqual(session_meter.jobs_segment(), "", "a finished job is not news")
                jobs.write_record(Path(td), record("20261103T100000Z-gates", startedAt=stamp(-390)))
                text = session_meter.jobs_segment()
                self.assertIn("Quality gates", text)
                self.assertIn("6m", text)
                self.assertNotIn("lost", text)
                jobs.write_record(Path(td), record("20261103T100100Z-dead", pid=DEAD_PID, writerPid=DEAD_PID,
                                                   heartbeatAt=stamp(-300), startedAt=stamp(-400)))
                text = session_meter.jobs_segment()
                self.assertIn("1 lost", text)
                self.assertIn("Quality gates", text)

    def test_a_broken_jobs_folder_never_breaks_the_status_line(self):
        import session_meter
        with tempfile.TemporaryDirectory() as td:
            (Path(td) / "jobs").mkdir()
            (Path(td) / "jobs" / "20261103T100000Z-bad.json").write_text("{ not json", encoding="utf-8")
            with mock.patch.dict(os.environ, {"NPDEV_BUILD_ROOT": td}):
                self.assertEqual(session_meter.jobs_segment(), "")


class BoardAndCliTests(unittest.TestCase):
    def test_the_board_shows_lost_in_words_and_refreshes_itself(self):
        rows = [jobs.view(record("20261103T100000Z-dead", pid=DEAD_PID, writerPid=DEAD_PID, heartbeatAt=stamp(-300), startedAt=stamp(-400)))]
        page = jobs.render_board(rows, with_prompts=False)
        self.assertIn("LOST", page)
        self.assertIn("http-equiv=refresh", page)
        self.assertIn("class='lost'", page)

    def test_the_cli_lists_and_shows_as_json(self):
        with tempfile.TemporaryDirectory() as td:
            jobs.write_record(Path(td), record("20261103T100000Z-gates"))
            env = dict(os.environ, NPDEV_BUILD_ROOT=td)
            listed = json.loads(subprocess.run([sys.executable, str(REPO / "NPDevCli" / "npdev_cli.py"), "jobs", "list", "--json"],
                                               capture_output=True, text=True, env=env, timeout=120).stdout)
            self.assertEqual([j["status"] for j in listed["jobs"]], ["running"])
            shown = json.loads(subprocess.run([sys.executable, str(REPO / "NPDevCli" / "npdev_cli.py"), "jobs", "show", "20261103T100000Z-gates", "--json"],
                                              capture_output=True, text=True, env=env, timeout=120).stdout)
            self.assertTrue(shown["job"]["prompt"]["found"] is False)
            board = json.loads(subprocess.run([sys.executable, str(REPO / "NPDevCli" / "npdev_cli.py"), "jobs", "board", "--json"],
                                              capture_output=True, text=True, env=env, timeout=120).stdout)
            self.assertTrue(Path(board["board"]).exists())


if __name__ == "__main__":
    unittest.main()
