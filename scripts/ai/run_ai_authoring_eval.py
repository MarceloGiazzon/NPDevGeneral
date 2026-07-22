#!/usr/bin/env python3
"""AI-authoring eval gate: prove an (AI-generated) model.json is actually buildable/runnable.

Chains the pipeline stages an authoring agent must clear, and produces a single pass/fail eval
report -- the regression net that turns "the AI emitted some JSON" into "the AI emitted a model
that validates, generates, and runs":

  validate  (default, fast) : full structural + semantic validation -> must not be `failed`.
  generate  (opt-in)        : run the real generator -> must exit 0. Needs --config + --output.
  smoke     (opt-in)        : build + REST smoke the generated app via the existing
                              scripts/quality/invoke-ai-beta-app-smoke.ps1 harness.

Report -> <Build>/npdev-ai/eval/<name>-eval.json. Exit 0 when every requested stage passes, else 1.

Usage:
    python scripts/ai/run_ai_authoring_eval.py --model path/to/model.json
    python scripts/ai/run_ai_authoring_eval.py --model m.json --config c.json --output out --generate
    python scripts/ai/run_ai_authoring_eval.py --model m.json --config c.json --output out --generate --smoke
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from npdev_ai_common import ai_out_dir, repo_root


def cli_cmd(*args: str) -> list[str]:
    return [sys.executable, str(repo_root() / "NPDevCli" / "npdev_cli.py"), *args]


def stage_validate(model: Path) -> dict[str, Any]:
    completed = subprocess.run(
        cli_cmd("validate", "model", str(model), "--semantic"),
        cwd=str(repo_root()), check=False, capture_output=True, text=True, timeout=600,
    )
    report: dict[str, Any] = {}
    stdout = (completed.stdout or "").strip()
    if stdout:
        try:
            report = json.loads(stdout)
        except json.JSONDecodeError:
            report = {"status": "unknown", "raw": stdout[-500:]}
    status = report.get("status")
    return {
        "stage": "validate",
        "passed": status in ("passed", "warning"),
        "status": status,
        "summary": report.get("summary"),
        "diagnostics": report.get("diagnostics", []),
    }


def stage_generate(model: Path, config: Path, output: Path) -> dict[str, Any]:
    completed = subprocess.run(
        cli_cmd("generate", "app", "--model", str(model), "--config", str(config), "--output", str(output)),
        cwd=str(repo_root()), check=False, capture_output=True, text=True, timeout=1800,
    )
    return {
        "stage": "generate",
        "passed": completed.returncode == 0,
        "exitCode": completed.returncode,
        "stderrTail": (completed.stderr or "")[-800:],
    }


def stage_smoke(output: Path, verification_plan: Path, report_path: Path) -> dict[str, Any]:
    """Run the real build+boot+REST-check harness against the generated app.

    invoke-ai-beta-app-smoke.ps1 requires -AppRoot/-VerificationPath/-ReportPath (a machine
    readable ai-verification-report.v1 plan of HTTP checks -- see schemas/ai/ai-verification-
    report.schema.json). It must run with cwd = repo root: it resolves
    scripts/ai/Invoke-AiRestSmokeVerifier.ps1 as a path relative to the working directory.
    """
    script = repo_root() / "scripts" / "quality" / "invoke-ai-beta-app-smoke.ps1"
    if not script.exists():
        return {"stage": "smoke", "passed": False, "skipped": True,
                "detail": f"smoke harness not found: {script}"}
    if not verification_plan.exists():
        return {"stage": "smoke", "passed": False, "skipped": True,
                "detail": f"verification plan not found: {verification_plan} "
                          "(an ai-verification-report.v1 JSON of HTTP checks; see "
                          "schemas/ai/ai-verification-report.schema.json)"}
    completed = subprocess.run(
        ["pwsh", "-NoProfile", "-File", str(script),
         "-AppRoot", str(output),
         "-VerificationPath", str(verification_plan),
         "-ReportPath", str(report_path)],
        cwd=str(repo_root()), check=False, capture_output=True, text=True, timeout=1800,
    )
    smoke_report: dict[str, Any] = {}
    if report_path.exists():
        try:
            smoke_report = json.loads(report_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            smoke_report = {}
    return {
        "stage": "smoke",
        "passed": completed.returncode == 0 and smoke_report.get("status") == "passed",
        "exitCode": completed.returncode,
        "harnessStatus": smoke_report.get("status"),
        "stderrTail": (completed.stderr or "")[-800:],
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="run_ai_authoring_eval")
    parser.add_argument("--model", required=True)
    parser.add_argument("--config")
    parser.add_argument("--output")
    parser.add_argument("--generate", action="store_true", help="Run the generate stage (needs --config + --output).")
    parser.add_argument("--smoke", action="store_true", help="Run the smoke stage (implies --generate; needs --verification-plan).")
    parser.add_argument("--verification-plan", dest="verification_plan",
                         help="Path to an ai-verification-report.v1 JSON of HTTP checks (see schemas/ai/ai-verification-report.schema.json).")
    parser.add_argument("--report", help="Write the eval report here (also written under <Build>/npdev-ai/eval).")
    args = parser.parse_args(argv[1:])

    model = Path(args.model).expanduser().resolve()
    stages: list[dict[str, Any]] = []

    # Validate always runs first and short-circuits the rest -- generating from an invalid model
    # is never meaningful.
    validate_result = stage_validate(model)
    stages.append(validate_result)

    want_generate = args.generate or args.smoke
    if validate_result["passed"] and want_generate:
        if not (args.config and args.output):
            stages.append({"stage": "generate", "passed": False,
                           "detail": "--config and --output are required for the generate stage"})
        else:
            config = Path(args.config).expanduser().resolve()
            output = Path(args.output).expanduser().resolve()
            generate_result = stage_generate(model, config, output)
            stages.append(generate_result)
            if generate_result["passed"] and args.smoke:
                if not args.verification_plan:
                    stages.append({"stage": "smoke", "passed": False,
                                   "detail": "--verification-plan is required for the smoke stage"})
                else:
                    plan = Path(args.verification_plan).expanduser().resolve()
                    smoke_report_path = ai_out_dir("eval") / f"{model.stem}-smoke-report.json"
                    stages.append(stage_smoke(output, plan, smoke_report_path))

    overall = all(stage["passed"] for stage in stages)
    report = {
        "contractVersion": "npdev-authoring-eval.v1",
        "model": str(model),
        "overall": "passed" if overall else "failed",
        "stages": stages,
    }

    name = model.stem or "model"
    default_out = ai_out_dir("eval") / f"{name}-eval.json"
    default_out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.report:
        report_path = Path(args.report).expanduser()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(report, indent=2))
    return 0 if overall else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
