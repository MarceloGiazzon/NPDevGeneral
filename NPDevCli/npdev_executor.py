#!/usr/bin/env python3
"""`npdev verify --run <id>` -- the Phase 5 executor, and the only Phase with a security surface.

VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27 Phase 5. Re-runs one runnable verification item from
the repository's own panel document, through the platform's EXISTING controlled-command runner
(scripts/security/Invoke-ControlledCommand.ps1 + scripts/policy/ai-command-policy.json), then
records the real outcome into the same cadence ledger the gates themselves write to
(cadence_state.py record) -- so a completed Run appears as the item's new lastRun, with a measured
duration (Phase 2 pays off here).

SECURITY POSTURE (this is why the plan defers the executor to last and gates it on review):
  1. NO free-form command input, ever. The caller passes an ITEM ID; the command is looked up in a
     freshly produced panel document, whose producer (npdev_panel.py) derives commands ONLY from
     verification-cadence.json's declared `invokedBy`. You cannot make this run anything the
     cadence does not already declare, and `runnable` is false for every command the policy would
     refuse or that needs context (a model path, a filter).
  2. Every command executes THROUGH Invoke-ControlledCommand.ps1. The executable allowlist, the
     blocked argument patterns, the network-ban, the pwsh allowed-file-roots, the minimal
     environment, secret redaction and the timeout CAP (maxTimeoutSeconds in ai-command-policy.json)
     all belong to that reviewed, CI-tested runner -- the executor adds no second opinion and no
     bypass. In particular the runner's 600s cap is respected, never overridden: a long gate that
     exceeds it records a timed-out FAILED run, which is the honest answer.
  3. No HTTP surface. The executor is reachable from a terminal (`npdev verify --run`) and from the
     local Manager's own Tauri command only. The generated app's verification page (Phase 4) has NO
     Run control and must never get one -- a button that runs scripts from a served web page is
     remote-code-execution shaped (S5.3's reason), and it stays out of the app document by having
     runnable:false everywhere there.
  4. A BLOCKED run records nothing. If the controlled runner refuses the command, nothing ran, so
     the refusal must not be recorded as a failure -- that would turn a policy config bug into
     cadence evidence.
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
from pathlib import Path

RUN_RESULT_SCHEMA = "npdev-verify-run-result.v1"

REPO_ROOT = Path(__file__).resolve().parents[1]
CONTROLLED_COMMAND = REPO_ROOT / "scripts" / "security" / "Invoke-ControlledCommand.ps1"
CADENCE_SCRIPT = REPO_ROOT / "scripts" / "quality" / "cadence_state.py"


class ExecutorError(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# Resolution -- the id -> item -> command chain that keeps input trusted (posture 1)
# ---------------------------------------------------------------------------------------------

def resolve_item(repo_root: Path | None, item_id: str) -> dict:
    """The item from a FRESH panel document (never cached, so a Declared-but-changed item is seen
    immediately). Refuses ids that do not exist and items the panel marks not runnable."""
    import npdev_panel

    root = Path(repo_root).resolve() if repo_root is not None else REPO_ROOT
    document = npdev_panel.build_repo_panel(root)
    for item in document.get("items", []):
        if item.get("id") == item_id:
            if not item.get("runnable") or not item.get("command"):
                raise ExecutorError(
                    f"item '{item_id}' is not runnable from the panel -- either the controlled "
                    "runner's policy does not allow its command yet, or it needs context "
                    "(e.g. a model path) that a Run button cannot supply."
                )
            return item
    raise ExecutorError(
        f"no verification item with id '{item_id}' in the panel. Run `npdev verify --panel` to see "
        "the ids."
    )


def split_command(command: str) -> tuple[str, list[str]]:
    """(executable, args) for the controlled runner. Commands are `scripts/quality/*.ps1` paths
    (with optional `-flag value` args) -- produced by npdev_panel._command_for, which never emits a
    non-.ps1 command as runnable."""
    tokens = shlex.split(command)
    if not tokens:
        raise ExecutorError(f"empty command for item: {command!r}")
    script_path = tokens[0]
    if not script_path.endswith(".ps1"):
        raise ExecutorError(
            f"unsupported executable for {command!r}: only scripts/quality/*.ps1 commands are "
            "runnable in Phase 5 (see npdev_panel._command_for)."
        )
    return "pwsh", ["-NoProfile", "-File", script_path] + tokens[1:]


# ---------------------------------------------------------------------------------------------
# Execution through the controlled runner (posture 2)
# ---------------------------------------------------------------------------------------------

def run_controlled(repo_root: Path, executable: str, args: list[str],
                   timeout_seconds: int) -> dict:
    """Run the command through Invoke-ControlledCommand.ps1 and return its
    npdev-controlled-command-result.v1 document. The runner's own policy cap wins over any caller
    timeout -- the executor never asks for more."""
    runner_args = [
        "pwsh", "-NoProfile", "-File", str(CONTROLLED_COMMAND),
        "-Executable", executable,
        "-ArgumentsJson", json.dumps(args),
        "-WorkingDirectory", str(repo_root),
    ]
    if timeout_seconds and timeout_seconds > 0:
        runner_args += ["-TimeoutSeconds", str(timeout_seconds)]
    completed = subprocess.run(runner_args, cwd=repo_root, capture_output=True, text=True,
                               timeout=120)
    stdout = (completed.stdout or "").strip()
    if not stdout:
        raise ExecutorError(
            "controlled runner produced no result: "
            + ((completed.stderr or "").strip() or "(no output)")
        )
    try:
        result = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise ExecutorError(
            f"controlled runner did not return JSON ({exc}); stdout tail: {stdout[-400:]}"
        ) from exc
    return result


def map_controlled_result(controlled: dict) -> tuple[str, int, bool]:
    """(outcome, exit_code, should_record) for a controlled-runner result.

    Only COMPLETED runs are ledger evidence:
      * passed / failed -> recorded as-is (a genuinely finished run is what the cadence ledger is
        for -- Phase 2's duration + history land here);
      * timed-out      -> the security policy's timeout CAP cut the run short, so there is no real
        verdict (several declared gates legitimately run longer than the policy cap). Reported to
        the caller as a failed run, but NOT recorded -- recording a cap artifact as evidence would
        corrupt the very ledger the panel reads.
      * blocked        -> nothing ran at all; raised as a refusal, never recorded (posture 4)."""
    status = controlled.get("status")
    if status == "passed":
        return "passed", 0, True
    if status == "failed":
        return "failed", 1, True
    if status == "timed-out":
        return "failed", 1, False
    if status == "blocked":
        raise ExecutorError(
            "the controlled runner refused this command: "
            f"{controlled.get('blockedReason')} ({controlled.get('errorCode')}) -- nothing was "
            "run and nothing was recorded."
        )
    raise ExecutorError(f"unexpected controlled-runner status: {status!r}")


def record_run(repo_root: Path, item: dict, result: str, duration_seconds: float) -> dict:
    """Write the run into the cadence ledger through the SAME cadence_state.py the gates use
    (which validates the id/tier against verification-cadence.json and appends the bounded
    history). Returns the cadence record command's report line for the result doc."""
    py = sys.executable or "python"
    completed = subprocess.run(
        [py, str(CADENCE_SCRIPT), "record",
         "--id", str(item["id"]),
         "--tier", str(item["tier"]),
         "--result", result,
         "--duration-seconds", str(round(duration_seconds, 2))],
        cwd=repo_root, capture_output=True, text=True, check=False,
    )
    if completed.returncode != 0:
        detail = ((completed.stderr or completed.stdout or "").strip())
        # A failed RECORD must not be silently swallowed: the run happened, the ledger is what the
        # whole panel reads, so a record failure is a real problem to surface -- but the run result
        # is returned alongside so the caller can see both.
        return {"recorded": False, "detail": detail or f"cadence record exited {completed.returncode}"}
    return {"recorded": True,
            "detail": (completed.stdout or "").strip() or f"recorded {item['id']}: {result}"}


def execute_item(repo_root: Path | None, item_id: str, timeout_seconds: int = 0,
                 json_out: bool = False) -> int:
    root = Path(repo_root).resolve() if repo_root is not None else REPO_ROOT
    item = resolve_item(root, item_id)
    executable, args = split_command(item["command"])
    controlled = run_controlled(root, executable, args, timeout_seconds)
    recorded_result, exit_code, should_record = map_controlled_result(controlled)
    duration_seconds = (controlled.get("durationMs") or 0) / 1000.0
    ledger = record_run(root, item, recorded_result, duration_seconds) if should_record else {
        "recorded": False,
        "detail": "not recorded: the run did not complete inside the controlled runner's policy "
                  "cap (timed out), so it is not ledger evidence.",
    }

    document = {
        "schemaVersion": RUN_RESULT_SCHEMA,
        "itemId": item["id"],
        "itemName": item.get("name"),
        "command": item.get("command"),
        "result": recorded_result,
        "exitCode": controlled.get("exitCode"),
        "durationSeconds": round(duration_seconds, 2),
        "ledger": ledger,
        "controlled": {
            "status": controlled.get("status"),
            "blockedReason": controlled.get("blockedReason"),
            "timeoutSeconds": controlled.get("timedOut") and "timed-out" or None,
            "stdout": (controlled.get("stdout") or "")[-2000:],
            "stderr": (controlled.get("stderr") or "")[-2000:],
        },
    }
    if json_out:
        print(json.dumps(document, indent=2, ensure_ascii=False))
    else:
        print(f"verify --run {item['id']}: {recorded_result.upper()} in "
              f"{duration_seconds:g}s")
        print(f"  ledger: {ledger.get('detail')}")
        out = (controlled.get("stdout") or "").strip()
        if out:
            print(out[-4000:])
    if should_record and not ledger.get("recorded"):
        print(f"verify --run: WARNING -- cadence record failed: {ledger.get('detail')}",
              file=sys.stderr)
    return exit_code


# ---------------------------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--item-id", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=0,
                        help="Passed to the controlled runner, which caps it at its own policy max.")
    parser.add_argument("--json", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return execute_item(None, args.item_id, args.timeout_seconds, args.json)


if __name__ == "__main__":
    sys.exit(main())