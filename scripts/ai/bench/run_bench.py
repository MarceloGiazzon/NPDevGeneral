#!/usr/bin/env python3
"""AI authoring benchmark runner (BENCH-2): prompt -> model authors model.json -> validate ->
generate -> build -> boot -> acceptance probes, with up to 5 repair loops (the failing stage's error
text is fed back to the model). Stdlib only.

  python scripts/ai/bench/run_bench.py run --dry-run                       # pipeline test, no model
  python scripts/ai/bench/run_bench.py run --prompt bench-001              # real model (env below)
  python scripts/ai/bench/run_bench.py run --ablation no-knowledge ...     # knowledge cards off
  python scripts/ai/bench/run_bench.py report <run-dir> [<run-dir> ...]    # scorecard JSON

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
and fake backends), stages.py (validate/generate/build/boot), probes.py (acceptance probes), test_bench.py
(unit tests: python -m unittest discover -s scripts/ai/bench -p "test_bench.py").

Output: <BuildRoot>/ai-bench/<run-id>/<prompt-id>/{result.json, attempt-N/...} and
<BuildRoot>/ai-bench/<run-id>/summary.json. Never inside the repo.
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import author  # noqa: E402
import backends  # noqa: E402
import probes  # noqa: E402
import stages  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_PROMPTS = REPO_ROOT / "NPDevSamples" / "ai-bench" / "prompts"
DRY_RUN_PROMPTS = Path(__file__).resolve().parent / "fixtures"
MAX_LOOPS_CAP = 5
STAGE_ORDER = ["author", "validate", "generate", "build", "boot", "acceptance"]


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
    paths = stages.write_workspace(attempt_dir, prompt["id"], port, model_text)
    try:
        model_obj = json.loads(model_text)
    except ValueError:
        model_obj = None
    roles = [str(r.get("name")) for r in (model_obj or {}).get("roles", []) if isinstance(r, dict) and r.get("name")]
    running = None
    out = {"stages": stage_log, "failingStage": None, "feedback": ""}

    def fail(res: stages.StageResult, feedback: str | None = None) -> dict:
        out["stages"].append(res.to_json())
        out["failingStage"] = res.stage
        out["feedback"] = feedback if feedback is not None else res.detail
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


def run_prompt(prompt: dict, backend, knowledge: str | None, run_dir: Path, args) -> dict:
    prompt_dir = run_dir / prompt["id"]
    prompt_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    messages = author.initial_messages(prompt["prompt"], knowledge)
    attempts: list[dict] = []
    tokens_in = tokens_out = 0
    author_seconds = 0.0
    passed = False
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
        messages = author.repair_messages(messages, completion.text, attempt["failingStage"], attempt["feedback"])
    wall = time.monotonic() - started
    infra_kind = None if passed else attempts[-1].get("infraKind")
    first = attempts[0]
    first_valid = first["failingStage"] not in ("author", "validate")
    result = {
        "promptId": prompt["id"], "tier": prompt.get("tier"), "title": prompt.get("title"),
        "passed": passed,
        # pass | fail | infra. infra = the run could not finish this prompt for a reason that is not
        # the model's authoring (quota, overload, unreachable, truncated context), even after real
        # attempts -- it is excluded from pass rates and re-run by --skip-done.
        "outcome": "pass" if passed else ("infra" if infra_kind else "fail"),
        "infraKind": infra_kind,
        "loopsUsed": len(attempts) - 1,
        "firstAttemptValid": bool(first_valid and not first.get("backendError")),
        "firstAttemptPassed": first["failingStage"] is None,
        "firstAttemptFailingStage": first["failingStage"],
        "failingStage": None if passed else attempts[-1]["failingStage"],
        "tokens": {"prompt": tokens_in, "completion": tokens_out, "total": tokens_in + tokens_out},
        "costUsd": backend.info.cost(tokens_in, tokens_out),
        "wallSeconds": round(wall, 2), "authorSeconds": round(author_seconds, 2),
        "ablation": args.ablation, "backend": backend.info.describe(),
        "attempts": [{"attempt": a["attempt"], "failingStage": a["failingStage"],
                      "feedbackExcerpt": (a.get("feedback") or "")[:600], "stages": a["stages"]} for a in attempts],
    }
    (prompt_dir / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
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
# run
# ---------------------------------------------------------------------------------------------

def build_backend(args):
    if args.dry_run:
        canary = REPO_ROOT / "NPDevSamples" / "npdev-canary" / "Input" / "model.json"
        return backends.FakeBackend(canary)
    if args.claude_cli:
        return backends.ClaudeCliBackend(args.claude_cli, timeout=args.model_timeout)
    if args.provider:
        return backends.build_provider_backends(args.provider, args.allow_paid, args.model_timeout)
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
    if args.ablation == "none":
        knowledge = (author.load_knowledge_file(Path(args.knowledge_file)) if args.knowledge_file
                     else author.load_core_context(stages.build_root()))
    knowledge_info = author.describe_knowledge(knowledge, args.knowledge_file)
    print(f"run: {run_dir}  prompts={len(prompts)} ablation={args.ablation} maxLoops={args.max_loops}")
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
        r = run_prompt(prompt, backend, knowledge, run_dir, args)
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
    summary = {"runId": run_id, "startedAt": started_at, "dryRun": args.dry_run, "ablation": args.ablation,
               "backend": info.describe(), "knowledge": knowledge_info, "maxLoops": args.max_loops,
               "wallSeconds": round(time.monotonic() - t0, 1), "stopReason": stop_reason, "notRun": not_run,
               "scorecard": scorecard(results), "results": [
                   {**{k: r.get(k) for k in ("promptId", "passed", "loopsUsed", "failingStage", "wallSeconds", "costUsd")},
                    "outcome": outcome_of(r), "infraKind": infra_kind_of(r), "tokens": r["tokens"]["total"]}
                   for r in results]}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary["scorecard"], indent=2))
    return 0 if all(r["passed"] for r in results) else 1


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
    r.add_argument("--allow-paid", action="store_true",
                   help="permit a PAID provider or a base URL that is not a free/local provider in NPDevCli/ai-providers.json (paid spend: owner approval required)")
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
    rep = sub.add_parser("report", help="render scorecard JSON for one or two run dirs")
    rep.add_argument("run_dirs", nargs="+")
    return p


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--report":      # alias: run_bench.py --report <run-dir> ...
        argv[0] = "report"
    args = make_parser().parse_args(argv)
    if args.command == "report":
        return cmd_report(args)
    if args.command == "run":
        return cmd_run(args)
    make_parser().print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
