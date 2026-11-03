#!/usr/bin/env python3
"""AI authoring benchmark runner (BENCH-2): prompt -> model authors model.json -> validate ->
generate -> build -> boot -> acceptance probes, with up to 5 repair loops (the failing stage's error
text is fed back to the model). Stdlib only.

  python scripts/ai/bench/run_bench.py run --dry-run                       # pipeline test, no model
  python scripts/ai/bench/run_bench.py run --prompt bench-001              # real model (env below)
  python scripts/ai/bench/run_bench.py run --ablation no-knowledge ...     # knowledge cards off
  python scripts/ai/bench/run_bench.py run --arm direct-python --dry-run   # the AI writes the app directly (BENCH-1)
  python scripts/ai/bench/run_bench.py compare <run-dir> [<run-dir> ...]   # per tier x arm report (BENCH-3)
  python scripts/ai/bench/run_bench.py report <run-dir> [<run-dir> ...]    # scorecard JSON
  python scripts/ai/bench/run_bench.py mine <run-dir> [<run-dir> ...]      # rank what AI authors get wrong

NO PAID API SPEND (owner decision 2026-10-19). Calls go through `npdev ai`'s one client (NPDevCli/
npdev_ai_client.py): every catalogue protocol, retry, spend guard, and the usage log (`npdev ai usage`).
Default: the `bench` binding (`npdev ai bindings`); or name it --
  python scripts/ai/bench/run_bench.py run --profile deep                # a profile and its fallbacks
  python scripts/ai/bench/run_bench.py run --provider nvidia,gemini     # failover in that order
  python scripts/ai/bench/run_bench.py run --provider groq=openai/gpt-oss-120b
(keys: `npdev providers add <id>`; catalogue: `npdev ai providers`). Or, env only (legacy, any
OpenAI-compatible URL):
  NPDEV_BENCH_BASE_URL   e.g. http://localhost:11434/v1   (local Ollama)
  NPDEV_BENCH_MODEL      e.g. qwen2.5-coder:14b
  NPDEV_BENCH_API_KEY    optional for local; NEVER printed or logged
The base URL must be a free/local provider of NPDevCli/ai-providers.json unless --allow-paid is passed.

Modules next to this file: author.py (prompts + model extraction), backends.py (allowlist + OpenAI-compatible
and fake backends), direct.py (the "AI writes the app directly" arm), evolve.py (changes applied after a pass),
compare.py (cross-arm report), stages.py (validate/generate/build/boot), probes.py (acceptance probes), test_bench.py
(unit tests: python -m unittest discover -s scripts/ai/bench -p "test_bench.py").

Output: <BuildRoot>/ai-bench/<run-id>/<prompt-id>/{result.json, attempt-N/...} and
<BuildRoot>/ai-bench/<run-id>/summary.json. Never inside the repo.
"""
from __future__ import annotations

import argparse
import copy
import datetime
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import author  # noqa: E402
import backends  # noqa: E402
import compare  # noqa: E402
import direct  # noqa: E402
import evolve  # noqa: E402
import probes  # noqa: E402
import stages  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
import npdev_desire_paths  # noqa: E402
DEFAULT_PROMPTS = REPO_ROOT / "NPDevSamples" / "ai-bench" / "prompts"
DRY_RUN_PROMPTS = Path(__file__).resolve().parent / "fixtures"
MAX_LOOPS_CAP = 5
STAGE_ORDER = ["author", "validate", "generate", "build", "boot", "acceptance"]
# BENCH-1/3: which route the model takes to a working app. `npdev` writes a model.json; the direct arms write a
# whole application (direct.py). Same prompts, same loop cap, same acceptance probes.
ARMS = ["npdev", "npdev-no-knowledge"] + direct.arm_ids()


def now_id() -> str:
    return datetime.datetime.now().strftime("%Y%m%dT%H%M%S")


def load_prompts(directory: Path, only: list[str] | None, limit: int | None) -> list[dict]:
    items = []
    for path in sorted(directory.glob("bench-*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if only and data["id"] not in only:
            continue
        items.append(data)
    return items[:limit] if limit else items


# ---------------------------------------------------------------------------------------------
# one prompt
# ---------------------------------------------------------------------------------------------

def run_attempt(n: int, model_text: str, prompt: dict, prompt_dir: Path, args, stage_log: list) -> dict:
    """validate -> generate -> build -> boot -> acceptance for one authored model. Returns
    {"stages": [...], "failingStage": str|None, "feedback": str}."""
    attempt_dir = prompt_dir / f"attempt-{n}"
    port = stages.free_port()
    try:
        model_obj = json.loads(model_text)
    except ValueError:
        model_obj = None
    fixes: list[dict] = []
    if getattr(args, "autofix", True) and model_obj is not None:
        # desire paths: the canonical rewrite of shapes AIs keep guessing, saved next to the model
        # so every change stays inspectable; the next repair turn is told about them.
        model_obj, fixes = npdev_desire_paths.fix_model(model_obj)
        if fixes:
            attempt_dir.mkdir(parents=True, exist_ok=True)
            (attempt_dir / "author-model.json").write_text(model_text, encoding="utf-8")
            (attempt_dir / "fixes-applied.json").write_text(json.dumps(fixes, indent=2), encoding="utf-8")
            model_text = json.dumps(model_obj, indent=2)
    paths = stages.write_workspace(attempt_dir, prompt["id"], port, model_text)
    roles = [str(r.get("name")) for r in (model_obj or {}).get("roles", []) if isinstance(r, dict) and r.get("name")]
    running = None
    out = {"stages": stage_log, "failingStage": None, "feedback": "", "fixesApplied": len(fixes),
           "modelText": model_text}

    def fail(res: stages.StageResult, feedback: str | None = None) -> dict:
        out["stages"].append(res.to_json())
        out["failingStage"] = res.stage
        out["feedback"] = feedback if feedback is not None else res.detail
        if fixes:
            out["feedback"] = ("NPDev rewrote these shapes of your model to the canonical form before "
                               "checking it -- use the canonical form from now on:\n"
                               + "\n".join(npdev_desire_paths.summary_lines(fixes)) + "\n\n" + out["feedback"])
        return out

    try:
        res = stages.stage_validate(paths["model"], attempt_dir)
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        res = stages.stage_generate(paths, attempt_dir, args.generate_timeout)
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        res = stages.stage_build(paths, attempt_dir, args.build_timeout)
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        res, running = stages.stage_boot(paths, port, attempt_dir, args.boot_timeout, roles)
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        started = time.monotonic()
        acc = probes.run_acceptance(prompt["acceptance"], running.base_url, running.api_key,
                                    running.role_keys, model_obj)
        (attempt_dir / "acceptance.json").write_text(json.dumps(acc, indent=2), encoding="utf-8")
        res = stages.StageResult("acceptance", acc["ok"], time.monotonic() - started,
                                 "" if acc["ok"] else probes.feedback_text(acc),
                                 str(attempt_dir / "acceptance.json"),
                                 {"probeCounts": acc["probeCounts"], "ruleCounts": acc["ruleCounts"],
                                  "conceptProblems": len(acc["conceptProblems"])})
        if not res.ok:
            return fail(res)
        out["stages"].append(res.to_json())
        return out
    finally:
        if running is not None:
            running.stop()
        if not args.keep_apps:
            shutil.rmtree(attempt_dir / "Output", ignore_errors=True)


def _first_error_line(feedback: str) -> str:
    """The trail's one line per attempt: the first line that is not the desire-path rewrite note."""
    for line in (feedback or "").splitlines():
        line = line.strip()
        if line and not line.startswith(("NPDev rewrote", "- $.", "- ...")):
            return line[:160]
    return ""


def run_prompt(prompt: dict, backend, knowledge: str | None, run_dir: Path, args) -> dict:
    prompt_dir = run_dir / prompt["id"]
    prompt_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    messages = author.initial_messages(prompt["prompt"], knowledge)
    attempts: list[dict] = []
    tokens_in = tokens_out = 0
    author_seconds = 0.0
    passed = False
    stateless = getattr(args, "repair_mode", None) == "stateless"   # a Mock args is never "stateless"
    trail: list[str] = []
    last_model: str | None = None
    stuck_limit = getattr(args, "stop_when_stuck", 0)
    stuck_limit = stuck_limit if isinstance(stuck_limit, int) else 0   # a Mock args has no real value
    signatures: list[tuple[str, str]] = []
    stopped_stuck = False
    for n in range(0, args.max_loops + 1):
        stage_log: list[dict] = []
        try:
            completion = backend.complete(messages)
        except backends.BackendError as exc:
            infra_kind = backends.classify_backend_error(str(exc))
            stage_log.append(stages.StageResult("author", False, 0.0, str(exc)).to_json())
            attempts.append({"attempt": n, "stages": stage_log, "failingStage": "author",
                             "feedback": str(exc), "backendError": True, "infraKind": infra_kind})
            break   # the model is unreachable: repairing cannot help
        expected = backends._estimate_tokens(messages)
        if backend.info.kind != "fake" and completion.prompt_tokens and completion.prompt_tokens < 0.6 * expected:
            # chars/4 UNDER-counts real tokens, so a reported prompt this much smaller means the server
            # dropped part of it: Ollama's default num_ctx (2048/4096) silently keeps only the tail of a
            # 16k-token prompt, and the model then answers a request it never fully saw.
            detail = (f"context truncated: the backend reports {completion.prompt_tokens} prompt tokens for a "
                      f"prompt of ~{expected}+ (raise the server's context window, e.g. OLLAMA_CONTEXT_LENGTH)")
            stage_log.append(stages.StageResult("author", False, completion.seconds, detail).to_json())
            attempts.append({"attempt": n, "stages": stage_log, "failingStage": "author", "feedback": detail,
                             "backendError": True, "infraKind": "context-truncated"})
            tokens_in += completion.prompt_tokens
            tokens_out += completion.completion_tokens
            break
        tokens_in += completion.prompt_tokens
        tokens_out += completion.completion_tokens
        author_seconds += completion.seconds
        adir = prompt_dir / f"attempt-{n}"
        adir.mkdir(parents=True, exist_ok=True)
        (adir / "author-reply.txt").write_text(completion.text, encoding="utf-8")
        model_text, extract_error = author.extract_model(completion.text)
        stage_log.append(stages.StageResult("author", model_text is not None, completion.seconds, extract_error,
                                            str(adir / "author-reply.txt"),
                                            {"promptTokens": completion.prompt_tokens,
                                             "completionTokens": completion.completion_tokens}).to_json())
        if model_text is None:
            attempt = {"attempt": n, "stages": stage_log, "failingStage": "author", "feedback": extract_error}
        else:
            attempt = run_attempt(n, model_text, prompt, prompt_dir, args, stage_log)
            attempt["attempt"] = n
        attempts.append(attempt)
        if attempt["failingStage"] is None:
            passed = True
            break
        # stop-loss: the same failure N attempts in a row means the feedback is not helping, and on a
        # free tier every further loop burns quota (72 % of haiku55 tokens went to prompts that failed)
        signatures.append((attempt["failingStage"], _first_error_line(attempt["feedback"])))
        if stuck_limit >= 2 and len(signatures) >= stuck_limit and len(set(signatures[-stuck_limit:])) == 1:
            stopped_stuck = True
            break
        if stateless:
            last_model = attempt.get("modelText") or last_model
            messages = author.stateless_repair_messages(prompt["prompt"], knowledge,
                                                        last_model or completion.text[-8000:],
                                                        attempt["failingStage"], attempt["feedback"], trail)
            trail.append(f"- attempt {n}: {attempt['failingStage']}: {_first_error_line(attempt['feedback'])}")
        else:
            messages = author.repair_messages(messages, completion.text, attempt["failingStage"],
                                              attempt["feedback"])
    wall = time.monotonic() - started
    infra_kind = None if passed else attempts[-1].get("infraKind")
    first = attempts[0]
    first_valid = first["failingStage"] not in ("author", "validate")
    passed_model = next((a.get("modelText") for a in reversed(attempts) if a["failingStage"] is None), None)
    result = {
        "promptId": prompt["id"], "tier": prompt.get("tier"), "title": prompt.get("title"),
        "passed": passed,
        # pass | fail | infra. infra = the run could not finish this prompt for a reason that is not
        # the model's authoring (quota, overload, unreachable, truncated context), even after real
        # attempts -- it is excluded from pass rates and re-run by --skip-done.
        "outcome": "pass" if passed else ("infra" if infra_kind else "fail"),
        "infraKind": infra_kind,
        "loopsUsed": len(attempts) - 1,
        "stoppedStuck": stopped_stuck,
        "firstAttemptValid": bool(first_valid and not first.get("backendError")),
        "firstAttemptPassed": first["failingStage"] is None,
        "firstAttemptFailingStage": first["failingStage"],
        "failingStage": None if passed else attempts[-1]["failingStage"],
        "tokens": {"prompt": tokens_in, "completion": tokens_out, "total": tokens_in + tokens_out},
        "costUsd": backend.info.cost(tokens_in, tokens_out),
        "wallSeconds": round(wall, 2), "authorSeconds": round(author_seconds, 2),
        "arm": "npdev" if args.ablation == "none" else "npdev-no-knowledge",
        "ablation": args.ablation, "repairMode": "stateless" if stateless else "history",
        "backend": backend.info.describe(),
        "attempts": [{"attempt": a["attempt"], "failingStage": a["failingStage"],
                      "feedbackExcerpt": (a.get("feedback") or "")[:600], "stages": a["stages"]} for a in attempts],
    }
    if passed and passed_model:
        (prompt_dir / "final-model.json").write_text(passed_model, encoding="utf-8")
    (prompt_dir / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    result["_modelText"] = passed_model      # in memory only: the evolution phase starts from it
    return result


class NpdevEvolution:
    """The NPDev arm's adapter for evolve.apply_changes: the model edits its own working model.json."""

    def __init__(self, prompt: dict, knowledge: str | None, model_text: str, args):
        self.prompt, self.knowledge, self.model_text, self.args = prompt, knowledge, model_text, args

    def messages(self, change: dict) -> list[dict]:
        return author.change_messages(self.prompt["prompt"], self.knowledge, self.model_text, change["request"])

    def repair(self, messages: list[dict], reply: str, stage: str, feedback: str) -> list[dict]:
        return author.repair_messages(messages, reply, stage, feedback)

    def attempt(self, n: int, reply: str, prompt: dict, change_dir: Path, stage_log: list) -> dict:
        model_text, error = author.extract_model(reply)
        if model_text is None:
            stage_log.append(stages.StageResult("author", False, 0.0, error).to_json())
            return {"failingStage": "author", "feedback": error, "stages": stage_log}
        return run_attempt(n, model_text, prompt, change_dir, self.args, stage_log)

    def commit(self, attempt: dict) -> None:
        self.model_text = attempt.get("modelText") or self.model_text


def run_evolution(prompt: dict, result: dict, backend, knowledge: str | None, run_dir: Path, args, arm: str) -> dict:
    """BENCH-4: after a prompt passes, apply its `changes[]` and record what each one cost. Mutates and rewrites
    the prompt's result.json; returns the result."""
    prompt_dir = run_dir / prompt["id"]
    if arm.startswith("direct-"):
        spec = direct.load_spec()
        adapter = direct.DirectEvolution(spec, direct.stack_for(spec, arm), prompt, Path(result["_finalAppDir"]), args)
    else:
        adapter = NpdevEvolution(prompt, knowledge, result["_modelText"], args)
    try:
        block = evolve.apply_changes(prompt, adapter, backend, args, prompt_dir)
    except evolve.EvolutionError as exc:
        block = {"changes": [], "changesDeclared": len(prompt.get("changes", [])), "changesAttempted": 0,
                 "changePassRate": None, "changeTokens": 0, "error": str(exc)}
    result.update(block)
    (prompt_dir / "result.json").write_text(json.dumps({k: v for k, v in result.items() if not k.startswith("_")}, indent=2),
                                            encoding="utf-8")
    return result


# ---------------------------------------------------------------------------------------------
# scorecard
# ---------------------------------------------------------------------------------------------

def scorecard(results: list[dict]) -> dict:
    n = len(results)
    if n == 0:
        return {"prompts": 0}

    def rate(count):
        return round(count / n, 4)

    passed = [r for r in results if r["passed"]]
    completed = [r for r in results if outcome_of(r) != "infra"]
    infra_hist: dict[str, int] = {}
    for r in results:
        if outcome_of(r) == "infra":
            infra_hist[infra_kind_of(r)] = infra_hist.get(infra_kind_of(r), 0) + 1
    passed_tokens = sorted(r["tokens"]["total"] for r in passed)
    final_hist, first_hist, tier = {}, {}, {}
    for r in results:
        if not r["passed"]:
            final_hist[r["failingStage"] or "unknown"] = final_hist.get(r["failingStage"] or "unknown", 0) + 1
        if r.get("firstAttemptFailingStage"):
            first_hist[r["firstAttemptFailingStage"]] = first_hist.get(r["firstAttemptFailingStage"], 0) + 1
        t = tier.setdefault(r.get("tier") or "?", {"prompts": 0, "passed": 0, "completed": 0})
        t["prompts"] += 1
        t["passed"] += 1 if r["passed"] else 0
        t["completed"] += 0 if outcome_of(r) == "infra" else 1
    total_cost = round(sum(r.get("costUsd", 0) for r in results), 6)
    return {
        "prompts": n,
        # the headline: of the prompts the run could actually finish, how many reached a passing app
        "completedPrompts": len(completed),
        "passRateCompleted": round(len(passed) / len(completed), 4) if completed else None,
        "infraHistogram": infra_hist,
        "medianTokensPerPassedApp": passed_tokens[len(passed_tokens) // 2] if passed_tokens else None,
        "firstAttemptValidRate": rate(sum(1 for r in results if r["firstAttemptValid"])),
        "firstAttemptPassRate": rate(sum(1 for r in results if r.get("firstAttemptPassed"))),
        "passWithin5LoopsRate": rate(sum(1 for r in passed if r["loopsUsed"] <= MAX_LOOPS_CAP)),
        "meanLoopsAllPrompts": round(sum(r["loopsUsed"] for r in results) / n, 3),
        "meanLoopsPassedPrompts": round(sum(r["loopsUsed"] for r in passed) / len(passed), 3) if passed else None,
        "totalTokens": sum(r["tokens"]["total"] for r in results),
        "totalCostUsd": total_cost,
        "meanCostUsdPerPrompt": round(total_cost / n, 6),
        "costUsdPerPassedApp": round(total_cost / len(passed), 6) if passed else None,
        "meanWallSeconds": round(sum(r["wallSeconds"] for r in results) / n, 1),
        "failingStageHistogram": final_hist,
        "firstAttemptFailingStageHistogram": first_hist,
        "byTier": tier,
    }


def infra_kind_of(r: dict) -> str | None:
    """infraKind, also for result.json files written before it existed (classified from the error text)."""
    if r.get("infraKind") or r.get("outcome"):
        return r.get("infraKind")
    if r.get("passed") or not r.get("attempts"):
        return None
    last = r["attempts"][-1]
    if last.get("failingStage") != "author":
        return None
    kind = backends.classify_backend_error(last.get("feedbackExcerpt") or "")
    return kind if kind != "other" else None


def outcome_of(r: dict) -> str:
    if r.get("outcome"):
        return r["outcome"]
    return "pass" if r.get("passed") else ("infra" if infra_kind_of(r) else "fail")


_DIAG = re.compile(r"^- (\w+) \| (\S+) \| (.*)$")
_BOOT_CAUSE = re.compile(r"Caused by: ([\w.$]+(?:Exception|Error)): (.*)")


def _generic(text: str) -> str:
    """'Model flow 'CreateRoom' persists via ...' -> "Model flow '<x>' persists via ..." so the same
    failure in different apps groups together."""
    return re.sub(r"'[^']*'", "'<x>'", re.sub(r'"[^"]*"', '"<x>"', text))[:160]


def _value_at(model: dict | None, path: str):
    """The value the model wrote at a validator JSON path ($.flows[2].input.mode)."""
    node = model
    for key, index in re.findall(r"\.([A-Za-z_$][\w$]*)|\[(\d+)\]", path.removeprefix("$")):
        try:
            node = node[int(index)] if index else node[key]
        except (KeyError, IndexError, TypeError, ValueError):
            return None
    return node


def _diag_where(path: str, message: str) -> str:
    return re.sub(r"\[\d+\]", "[]", path) if path.startswith("$") else _generic(message)


def mine_failures(run_dirs: list[Path], keep_attempts: bool = False) -> list[dict]:
    """Every failed attempt of every prompt, grouped by (stage, kind, generic location) and ranked by
    how many distinct prompts hit it: the "desire paths" an AI author walks that the DSL or its
    messages do not accept. For validator errors the value the model actually wrote is read back from
    that attempt's model.json (the validator's message does not say it)."""
    groups: dict[tuple, dict] = {}

    def add(stage, kind, where, prompt, run, example=None, wrote=None):
        g = groups.setdefault((stage, kind, where), {"stage": stage, "kind": kind, "where": where, "hits": 0,
                                                     "prompts": set(), "runs": set(), "wrote": {},
                                                     "example": example, "attempts": set()})
        g["hits"] += 1
        g["attempts"].add(str(adir))
        g["prompts"].add(prompt)
        g["runs"].add(run)
        if wrote is not None:
            key = json.dumps(wrote, sort_keys=True)[:80]
            g["wrote"][key] = g["wrote"].get(key, 0) + 1

    for run_dir in run_dirs:
        for result_path in sorted(run_dir.glob("bench-*/result.json")):
            r = json.loads(result_path.read_text(encoding="utf-8"))
            for a in r.get("attempts", []):
                stage = a.get("failingStage")
                if not stage or a.get("infraKind") or (stage == "author" and backends.classify_backend_error(
                        a.get("feedbackExcerpt") or "") != "other"):
                    continue
                adir = result_path.parent / f"attempt-{a['attempt']}"
                if stage == "validate":
                    model = None
                    if (adir / "model.json").exists():
                        try:
                            model = json.loads((adir / "model.json").read_text(encoding="utf-8"))
                        except ValueError:
                            pass
                    detail = next((s.get("detail") for s in a.get("stages", []) if s.get("stage") == "validate"), "")
                    for line in (detail or a.get("feedbackExcerpt") or "").splitlines():
                        m = _DIAG.match(line.strip())
                        if m:
                            code, path, message = m.groups()
                            where = _diag_where(path, message)
                            add(stage, code, where, r["promptId"], run_dir.name, _generic(message),
                                _value_at(model, path) if path.startswith("$") else None)
                elif stage in ("boot", "generate", "build"):
                    log = adir / f"{stage}.log"
                    causes = _BOOT_CAUSE.findall(log.read_text(encoding="utf-8", errors="replace")) if log.exists() else []
                    if causes:
                        exc, message = causes[-1]
                        add(stage, exc.rsplit(".", 1)[-1], _generic(message), r["promptId"], run_dir.name)
                    else:
                        add(stage, "no-cause-line", "", r["promptId"], run_dir.name)
                else:
                    detail = next((s.get("detail") for s in a.get("stages", []) if s.get("stage") == stage), "")
                    for line in (detail or a.get("feedbackExcerpt") or "").splitlines():
                        line = line.strip().lstrip("- ")
                        if line and " unresolved" not in line:   # unresolved = a consequence of another line
                            add(stage, line.split(" ", 1)[0], _generic(re.sub(r"\d+", "N", line)), r["promptId"], run_dir.name)
    ranked = sorted(groups.values(), key=lambda g: (-len(g["prompts"]), -g["hits"]))
    return [{**{k: v for k, v in g.items() if k != "attempts" or keep_attempts},
             **({"attempts": sorted(g["attempts"])} if keep_attempts else {}),
             "prompts": sorted(g["prompts"]), "runs": sorted(g["runs"]),
             "wrote": dict(sorted(g["wrote"].items(), key=lambda kv: -kv[1])[:6])} for g in ranked]


_REPLAY_CACHE: dict[str, frozenset] = {}


def replay_validate(model_obj: dict) -> frozenset:
    """Validator diagnostics of a model as a set of (code, where) keys; empty = valid. Runs the real
    `npdev validate model` (one JVM call per UNIQUE model, cached by content hash)."""
    import hashlib
    import tempfile
    text = json.dumps(model_obj, indent=2, sort_keys=True)
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if digest not in _REPLAY_CACHE:
        with tempfile.TemporaryDirectory(prefix="bench-replay-") as td:
            model_path = Path(td) / "model.json"
            model_path.write_text(text, encoding="utf-8")
            res = stages.stage_validate(model_path, Path(td))
        keys = set()
        if not res.ok:
            for line in (res.detail or "").splitlines():
                m = _DIAG.match(line.strip())
                if m:
                    keys.add((m.group(1), _diag_where(m.group(2), m.group(3))))
            keys = keys or {("?", "?")}   # failed with no parsable diagnostic: not proven fixed
        _REPLAY_CACHE[digest] = frozenset(keys)
    return _REPLAY_CACHE[digest]


def _replay_model(adir: Path) -> dict | None:
    """The model as the AI wrote it: author-model.json when the fixer rewrote it, else model.json."""
    for name in ("author-model.json", "model.json"):
        if (adir / name).exists():
            try:
                obj = json.loads((adir / name).read_text(encoding="utf-8"))
            except ValueError:
                return None
            return obj if isinstance(obj, dict) else None
    return None


def resolve_groups(rows: list[dict], validator=None) -> list[dict]:
    """Add stillFailing/resolved/now to every group (needs mine_failures(keep_attempts=True)): each
    validate-group attempt's authored model goes through the CURRENT desire-path fixer, then the
    validator, and the group counts as still failing for that attempt only if its (code, where) recurs."""
    validator = validator or replay_validate
    for g in rows:
        if g["stage"] != "validate":
            g.update(stillFailing=None, resolved=None, now="not replayed")
            continue
        still = fixed = 0
        for adir in g["attempts"]:
            model = _replay_model(Path(adir))
            if model is None:
                still += 1   # nothing to replay: cannot claim fixed
                continue
            fixed_model, _ = npdev_desire_paths.fix_model(copy.deepcopy(model))
            if (g["kind"], g["where"]) in validator(fixed_model):
                still += 1
            else:
                fixed += 1
        g.update(stillFailing=still, resolved=fixed,
                 now="FIXED" if still == 0 else f"{still}/{still + fixed} still fail")
    return rows


def cmd_mine(args) -> int:
    resolve = getattr(args, "resolve", False)
    rows = mine_failures([Path(d) for d in args.run_dirs], keep_attempts=resolve)
    if resolve:
        started = time.monotonic()
        # replay only the groups that will be shown: --top applies after --only-open
        shown, kept = [], []
        for g in rows:
            if len(shown) >= (args.top if not args.json else len(rows)):
                break
            resolve_groups([g])
            if getattr(args, "only_open", False) and g["now"] == "FIXED":
                continue
            shown.append(g)
        rows = shown
        for g in rows:
            g.pop("attempts", None)
        print(f"# replayed through the current fixer + validator in {time.monotonic() - started:.0f}s", file=sys.stderr)
    if args.json:
        print(json.dumps(rows, indent=2))
        return 0
    for g in rows[: args.top]:
        wrote = ("  wrote: " + ", ".join(f"{k} x{v}" for k, v in g["wrote"].items())) if g["wrote"] else ""
        now = f"{g['now']:<16} " if resolve else ""
        print(f"{len(g['prompts']):3d} prompts {g['hits']:4d} hits  {now}{g['stage']:<10} {g['kind']:<34} {g['where']}{wrote}")
    return 0


def load_results(run_dir: Path) -> list[dict]:
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(run_dir.glob("bench-*/result.json"))]


def cmd_report(args) -> int:
    out = {}
    for d in args.run_dirs:
        run_dir = Path(d)
        summary = {}
        sp = run_dir / "summary.json"
        if sp.exists():
            s = json.loads(sp.read_text(encoding="utf-8"))
            summary = {k: s.get(k) for k in ("runId", "backend", "ablation", "dryRun", "startedAt")}
        out[run_dir.name] = {**summary, "scorecard": scorecard(load_results(run_dir))}
    if len(out) == 2:
        (a, sa), (b, sb) = list(out.items())
        ca, cb = sa["scorecard"], sb["scorecard"]
        keys = ("firstAttemptValidRate", "firstAttemptPassRate", "passWithin5LoopsRate", "meanLoopsAllPrompts")
        out["comparison"] = {"a": a, "b": b, **{k: {"a": ca.get(k), "b": cb.get(k)} for k in keys}}
    print(json.dumps(out, indent=2))
    return 0


# ---------------------------------------------------------------------------------------------
# matrix: every recorded run as one row source for the provider/model/effort results page
# ---------------------------------------------------------------------------------------------

PROVIDER_ALIASES = {"gemini-free-tier": "gemini", "ollama-localhost": "ollama", "claude-cli": "anthropic-cli"}
ATTEMPT_FILE_ORDER = ("author-reply.txt", "model.json", "fixes-applied.json", "validate.log", "generate.log",
                      "build.log", "boot.log", "acceptance.json")


def matrix_score(card: dict) -> float | None:
    """0-100: 60 pass rate on finished prompts, 25 first-attempt pass rate, 15 token economy (full marks at
    or under 5k tokens per passed app, the AI-efficiency goal). None when no prompt finished."""
    if not card.get("completedPrompts"):
        return None
    pass_rate = card.get("passRateCompleted") or 0.0
    first = card.get("firstAttemptPassRate") or 0.0
    median = card.get("medianTokensPerPassedApp")
    economy = min(1.0, 5000 / median) if median else 0.0
    return round(60 * pass_rate + 25 * first + 15 * economy, 1)


def matrix_observations(summary: dict, card: dict) -> list[str]:
    notes = []
    knowledge = (summary.get("knowledge") or {}).get("source") or ""
    if summary.get("ablation") not in (None, "none"):
        notes.append(f"ablation: {summary['ablation']}")
    if summary.get("stopReason"):
        notes.append(f"stopped: {summary['stopReason']}")
    if summary.get("notRun"):
        notes.append(f"not run: {', '.join(summary['notRun'])}")
    if card.get("infraHistogram"):
        notes.append("infra: " + ", ".join(f"{k} x{v}" for k, v in card["infraHistogram"].items()))
    if card.get("failingStageHistogram"):
        notes.append("failed at: " + ", ".join(f"{k} x{v}" for k, v in card["failingStageHistogram"].items()))
    if card.get("meanLoopsPassedPrompts"):
        notes.append(f"repair loops per pass: {card['meanLoopsPassedPrompts']}")
    if summary.get("repairMode"):
        notes.append(f"repair: {summary['repairMode']}")
    if not knowledge and summary.get("ablation", "none") == "none":
        notes.append("knowledge not recorded (older runner)")
    return notes


def matrix_log(run_dir: Path, summary: dict, results: list[dict]) -> str:
    """The whole execution as one text: settings, then per prompt its verdict and every attempt's stages
    followed by each stage's own log file."""
    out = [f"run {run_dir.name}  started {summary.get('startedAt')}  wall {summary.get('wallSeconds')}s",
           "backend " + json.dumps(summary.get("backend")),
           "knowledge " + json.dumps(summary.get("knowledge")),
           f"maxLoops={summary.get('maxLoops')} repairMode={summary.get('repairMode')} "
           f"stopWhenStuck={summary.get('stopWhenStuck')} autofix={summary.get('autofix')} "
           f"stopReason={summary.get('stopReason')}", ""]
    for r in results:
        label = "PASS" if r["passed"] else (f"INFRA:{infra_kind_of(r)}" if outcome_of(r) == "infra"
                                            else f"FAIL@{r.get('failingStage')}")
        out += ["=" * 100, f"{r['promptId']}  {r.get('title', '')}  [{r.get('tier', '')}]  {label}  "
                f"loops={r.get('loopsUsed')} tokens={r['tokens']['total']} wall={r.get('wallSeconds')}s", "=" * 100]
        for attempt in r.get("attempts") or []:
            n = attempt.get("attempt")
            out.append(f"--- attempt {n}: failingStage={attempt.get('failingStage')}")
            for st in attempt.get("stages") or []:
                out.append(f"  {st.get('stage'):<11} {'ok ' if st.get('ok') else 'BAD'} {st.get('seconds')}s  "
                           + (st.get("detail") or "").replace("\n", "\n              "))
            adir = run_dir / r["promptId"] / f"attempt-{n}"
            names = [name for name in ATTEMPT_FILE_ORDER if (adir / name).exists()]
            names += sorted(p.name for p in adir.glob("*") if p.is_file() and p.name not in names) if adir.exists() else []
            for name in names:
                out += [f"  ----- attempt-{n}/{name}", (adir / name).read_text(encoding="utf-8", errors="replace").rstrip()]
            out.append("")
    # undecodable bytes in a stage log (a console code page) would otherwise reach the page as U+FFFD
    return ("\n".join(out) + "\n").replace("�", "?")


def cmd_matrix(args) -> int:
    root = Path(args.out_root) if args.out_root else stages.build_root() / "ai-bench"
    out_dir = Path(args.out) if args.out else root / "_matrix"
    (out_dir / "logs").mkdir(parents=True, exist_ok=True)
    runs = []
    for run_dir in sorted(p for p in root.iterdir() if p.is_dir() and not p.name.startswith("_")):
        sp = run_dir / "summary.json"
        if not sp.exists():
            continue
        summary = json.loads(sp.read_text(encoding="utf-8"))
        if summary.get("dryRun"):
            continue
        results = load_results(run_dir)
        if not results:
            continue
        card = scorecard(results)
        backend = summary.get("backend") or {}
        knowledge = (summary.get("knowledge") or {}).get("source") or ""
        bundle = ("none" if summary.get("ablation") == "no-knowledge" else
                  "compact" if "compact" in knowledge else "full" if knowledge else "unknown")
        (out_dir / "logs" / f"{run_dir.name}.txt").write_text(matrix_log(run_dir, summary, results), encoding="utf-8")
        runs.append({
            "runId": run_dir.name, "startedAt": summary.get("startedAt"),
            "provider": PROVIDER_ALIASES.get(backend.get("id"), backend.get("id")), "kind": backend.get("kind"),
            "model": backend.get("model"), "effort": backend.get("effort") or "default", "bundle": bundle,
            "prompts": card["prompts"], "completed": card["completedPrompts"],
            "passed": sum(1 for r in results if r["passed"]),
            "firstAttemptPassRate": card["firstAttemptPassRate"], "passRateCompleted": card["passRateCompleted"],
            "medianTokensPerPassedApp": card["medianTokensPerPassedApp"], "totalTokens": card["totalTokens"],
            "costUsd": card["totalCostUsd"], "wallSeconds": summary.get("wallSeconds"),
            "score": matrix_score(card), "observations": matrix_observations(summary, card),
            "log": f"logs/{run_dir.name}.txt"})
    runs.sort(key=lambda x: x["startedAt"] or "", reverse=True)
    data = {"generatedAt": datetime.datetime.now().isoformat(timespec="seconds"),
            "scoreFormula": "60 x pass rate on finished prompts + 25 x first-attempt pass rate "
                            "+ 15 x min(1, 5000 / median tokens per passed app)",
            "runs": runs}
    (out_dir / "matrix.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"matrix: {len(runs)} runs -> {out_dir}")
    return 0


# ---------------------------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------------------------

def build_backend(args):
    if getattr(args, "effort", None) and not args.provider:
        raise backends.BackendRefused("--effort needs --provider (the model's declared effort levels are checked there)")
    if args.dry_run and getattr(args, "arm", "npdev").startswith("direct-"):
        return direct.FakeDirectBackend()
    if args.dry_run:
        canary = REPO_ROOT / "NPDevSamples" / "npdev-canary" / "Input" / "model.json"
        return backends.FakeBackend(canary)
    if args.claude_cli:
        return backends.ClaudeCliBackend(args.claude_cli, timeout=args.model_timeout)
    if args.provider:
        return backends.build_provider_backends(args.provider, args.allow_paid, args.model_timeout, effort=args.effort)
    if args.profile or not os.environ.get("NPDEV_BENCH_BASE_URL", "").strip():
        aicfg, _ = backends._ai_modules()
        try:
            config = aicfg.load()
        except aicfg.AiConfigError as exc:
            raise backends.BackendRefused(str(exc)) from None
        return backends.ProfileBackend(config, args.profile, args.allow_paid, args.model_timeout)
    info = backends.resolve_backend(os.environ.get("NPDEV_BENCH_BASE_URL", "").strip(),
                                    os.environ.get("NPDEV_BENCH_MODEL", "").strip(), args.allow_paid)
    return backends.OpenAICompatBackend(info, timeout=args.model_timeout)


def cmd_run(args) -> int:
    arm = getattr(args, "arm", "npdev") or "npdev"
    if arm == "npdev-no-knowledge":
        args.ablation = "no-knowledge"
    is_direct = arm.startswith("direct-")
    if is_direct and not args.dry_run and direct.is_admin():
        print("REFUSED: the direct arm runs code an AI wrote; it will not run as an administrator "
              "(owner decision D3). Start a normal, unelevated terminal.", file=sys.stderr)
        return 3
    if args.max_loops is None:
        args.max_loops = 0 if args.dry_run else MAX_LOOPS_CAP
    args.max_loops = max(0, min(args.max_loops, MAX_LOOPS_CAP))
    try:
        backend = build_backend(args)
    except backends.BackendRefused as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 3
    info = backend.info
    est_prompt_tokens = 15000 if args.ablation == "none" else 1500
    est = info.cost(est_prompt_tokens, 6000)
    print(f"backend: {info.id} ({info.kind}) {info.base_url} model={info.model}")
    print(f"price per 1k tokens (in/out): {info.price_in_per_1k}/{info.price_out_per_1k} USD; "
          f"estimated cost per authoring call: {est} USD" + (" (free/local)" if est == 0 else ""))
    if info.kind == "paid-unlisted":
        print("WARNING: --allow-paid given for a backend not on the free/local allowlist; cost is NOT tracked "
              "(price unknown, estimate 0).")
    directory = Path(args.prompts_dir) if args.prompts_dir else (DRY_RUN_PROMPTS if args.dry_run else DEFAULT_PROMPTS)
    prompts = load_prompts(directory, args.prompt, args.limit)
    if not prompts:
        print(f"no prompts found in {directory}", file=sys.stderr)
        return 2
    run_id = args.run_id or (("dry-" if args.dry_run else "") + now_id())
    root = Path(args.out_root) if args.out_root else stages.build_root() / "ai-bench"
    run_dir = root / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    knowledge = None
    if args.ablation == "none" and not is_direct:
        knowledge = (author.load_knowledge_file(Path(args.knowledge_file)) if args.knowledge_file
                     else author.load_core_context(stages.build_root()))
    knowledge_info = author.describe_knowledge(knowledge, args.knowledge_file)
    print(f"run: {run_dir}  arm={arm} prompts={len(prompts)} ablation={args.ablation} maxLoops={args.max_loops}")
    started_at = datetime.datetime.now().isoformat(timespec="seconds")
    t0 = time.monotonic()
    results = []
    not_run: list[str] = []
    stop_reason = None
    for prompt in prompts:
        if stop_reason:
            not_run.append(prompt["id"])
            continue
        done = run_dir / prompt["id"] / "result.json"
        if args.skip_done and done.exists():
            previous = json.loads(done.read_text(encoding="utf-8"))
            if outcome_of(previous) != "infra":      # an infra outcome is unfinished business: re-run it
                results.append(previous)
                print(f"{prompt['id']}: skipped (already done)")
                continue
        r = (direct.run_prompt_direct(prompt, backend, arm, run_dir, args) if is_direct
             else run_prompt(prompt, backend, knowledge, run_dir, args))
        if getattr(args, "evolve", False) and r["passed"] and prompt.get("changes"):
            r = run_evolution(prompt, r, backend, knowledge, run_dir, args, arm)
            print(f"{prompt['id']}: evolution {sum(1 for c in r['changes'] if c['passed'])}/{r['changesDeclared']} changes, "
                  f"{r['changeTokens']} tokens", flush=True)
        results.append(r)
        label = "PASS" if r["passed"] else (f"INFRA:{infra_kind_of(r)}" if outcome_of(r) == "infra"
                                            else "FAIL@" + str(r["failingStage"]))
        print(f"{prompt['id']}: {label} loops={r['loopsUsed']} tokens={r['tokens']['total']} "
              f"cost=${r['costUsd']} wall={r['wallSeconds']}s", flush=True)
        if infra_kind_of(r) == "quota":
            # every later prompt would fail in under a second and be scored as a failure (full-gemini-1:
            # 20 of 30). Stop, and say how to resume once the quota resets.
            stop_reason = "quota exhausted"
            print(f"STOP: quota exhausted. Resume later with: --run-id {run_id} --skip-done", flush=True)
    summary = {"runId": run_id, "arm": arm, "startedAt": started_at, "dryRun": args.dry_run, "ablation": args.ablation,
               "backend": info.describe(), "knowledge": knowledge_info, "maxLoops": args.max_loops,
               "autofix": args.autofix, "repairMode": args.repair_mode,
               "stopWhenStuck": args.stop_when_stuck,
               "wallSeconds": round(time.monotonic() - t0, 1), "stopReason": stop_reason, "notRun": not_run,
               "scorecard": scorecard(results), "results": [
                   {**{k: r.get(k) for k in ("promptId", "passed", "loopsUsed", "failingStage", "wallSeconds", "costUsd")},
                    "outcome": outcome_of(r), "infraKind": infra_kind_of(r), "tokens": r["tokens"]["total"]}
                   for r in results]}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary["scorecard"], indent=2))
    return 0 if all(r["passed"] for r in results) else 1


def cmd_compare(args) -> int:
    """BENCH-3: one report across arms -- per tier and arm: pass rate, tokens per passing app (median, p90),
    tokens per passing probe, the input/output split and wall time -- as comparison.json + comparison.html."""
    runs = [compare.load_run(Path(d)) for d in args.run_dirs]
    empty = [r["dir"] for r in runs if not r["results"]]
    if empty:
        print(f"no results in: {', '.join(empty)}", file=sys.stderr)
        return 2
    out = Path(args.out) if args.out else Path(runs[0]["dir"]).parent / "_compare"
    result = compare.write(runs, out, common_only=not args.all_prompts)
    print(f"comparison: {out / 'comparison.json'}  and  {out / 'comparison.html'}")
    print(f"arms: {', '.join(result['arms'])}; prompts compared: {result['promptsCompared']}")
    for line in result["reading"]:
        print("  " + line)
    return 0


def make_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command")
    r = sub.add_parser("run", help="run the benchmark")
    r.add_argument("--dry-run", action="store_true",
                   help="use the built-in deterministic FAKE backend (replies with the canary model.json); "
                        "defaults to the bundled fixture prompt and 0 repair loops")
    r.add_argument("--provider", metavar="ID[=MODEL][,ID[=MODEL]...]",
                   help="use providers from the NPDev catalogue with keys from the NPDev vault "
                        "(`npdev providers`); several = failover in order when one runs out of quota. "
                        "Replaces the NPDEV_BENCH_* environment variables.")
    r.add_argument("--profile", help="an `npdev ai` profile (its fallbacks fail over in order). With no --profile, "
                                     "--provider or NPDEV_BENCH_BASE_URL, the `bench` binding decides "
                                     "(`npdev ai bindings`).")
    r.add_argument("--claude-cli", metavar="MODEL",
                   help="simulated external AI: the Claude Code CLI one-shot (-p), isolated (empty dir, no tools, "
                        "no settings, no MCP), e.g. haiku or claude-haiku-4-5. Uses the owner's subscription, "
                        "not an API key")
    r.add_argument("--knowledge-file", metavar="PATH",
                   help="system-prompt knowledge from this file instead of the core-context bundle: a bundle.json "
                        "(its 'markdown' key) or any text file -- for A/B runs of knowledge variants")
    r.add_argument("--effort", choices=["none", "low", "medium", "high", "xhigh", "max"],
                   help="reasoning effort sent to the model (with --provider; refused unless the catalogue model "
                        "declares that level). Recorded in summary.json as backend.effort")
    r.add_argument("--allow-paid", action="store_true",
                   help="permit a PAID provider or a base URL that is not a free/local provider in NPDevCli/ai-providers.json (paid spend: owner approval required)")
    r.add_argument("--repair-mode", choices=["history", "stateless"], default="history",
                   help="history: re-send the whole conversation each repair (default). stateless: slim "
                        "knowledge + request + one line per earlier attempt + current model + errors (F6)")
    r.add_argument("--evolve", action="store_true",
                   help="after a prompt passes, apply its changes[] (BENCH-4) and record changeTokens and changePassRate; "
                        "the full acceptance (original + every change so far) is re-run after each change")
    r.add_argument("--stop-when-stuck", type=int, default=0, metavar="N",
                   help="stop a prompt after N consecutive attempts fail the same way (same stage, same first "
                        "error line); 0 = off (default), 2 or more to enable")
    r.add_argument("--no-autofix", dest="autofix", action="store_false",
                   help="validate the model exactly as authored (skip the desire-path rewrite) -- the A/B arm")
    r.add_argument("--arm", choices=ARMS, default="npdev",
                   help="which route the model takes to a working app: npdev (writes a model.json), "
                        "npdev-no-knowledge (same, knowledge cards off), or direct-python / direct-node / "
                        "direct-spring (writes the whole application; BENCH-1). Same prompts, loop cap and acceptance")
    r.add_argument("--ablation", choices=["none", "no-knowledge"], default="none",
                   help="no-knowledge omits the knowledge-card context from the system prompt")
    r.add_argument("--prompts-dir", help="directory of bench-NNN-*.json (default NPDevSamples/ai-bench/prompts)")
    r.add_argument("--prompt", action="append", help="only this prompt id (repeatable)")
    r.add_argument("--limit", type=int, help="only the first N prompts")
    r.add_argument("--max-loops", type=int, help=f"repair loops, capped at {MAX_LOOPS_CAP} (default {MAX_LOOPS_CAP}; 0 for --dry-run)")
    r.add_argument("--run-id")
    r.add_argument("--out-root", help="default <BuildRoot>/ai-bench")
    r.add_argument("--skip-done", action="store_true", help="skip prompts whose result.json already exists in this run id")
    r.add_argument("--keep-apps", action="store_true", help="keep each attempt's generated app dir (large)")
    r.add_argument("--model-timeout", type=float, default=900.0)
    r.add_argument("--generate-timeout", type=float, default=900.0)
    r.add_argument("--build-timeout", type=float, default=1500.0)
    r.add_argument("--boot-timeout", type=float, default=300.0)
    mine = sub.add_parser("mine", help="rank the failures of one or more run dirs (what AI authors get wrong)")
    mine.add_argument("run_dirs", nargs="+")
    mine.add_argument("--top", type=int, default=40)
    mine.add_argument("--json", action="store_true", help="every group as JSON")
    mine.add_argument("--resolve", action="store_true",
                      help="replay each validate-stage attempt's authored model through the CURRENT desire-path fixer "
                           "+ validator and report per group how many attempts still fail (adds a 'now' column)")
    mine.add_argument("--only-open", action="store_true", help="with --resolve: hide groups that are now fully fixed")
    rep = sub.add_parser("report", help="render scorecard JSON for one or two run dirs")
    rep.add_argument("run_dirs", nargs="+")
    cmp_ = sub.add_parser("compare", help="compare runs across arms (npdev vs direct-python/node/spring): "
                                          "comparison.json + comparison.html per tier and arm")
    cmp_.add_argument("run_dirs", nargs="+")
    cmp_.add_argument("--out", help="output dir (default <first run's parent>/_compare)")
    cmp_.add_argument("--all-prompts", action="store_true",
                      help="count every prompt each arm attempted (default: only prompts every arm finished)")
    mat = sub.add_parser("matrix", help="every recorded run as matrix.json + one full log per run "
                                        "(the provider/model/effort results page reads them)")
    mat.add_argument("--out-root", help="runs root (default <BuildRoot>/ai-bench)")
    mat.add_argument("--out", help="output dir (default <out-root>/_matrix)")
    return p


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--report":      # alias: run_bench.py --report <run-dir> ...
        argv[0] = "report"
    args = make_parser().parse_args(argv)
    if args.command == "report":
        return cmd_report(args)
    if args.command == "matrix":
        return cmd_matrix(args)
    if args.command == "compare":
        return cmd_compare(args)
    if args.command == "mine":
        return cmd_mine(args)
    if args.command == "run":
        return cmd_run(args)
    make_parser().print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
