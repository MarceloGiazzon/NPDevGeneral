#!/usr/bin/env python3
"""The `pack-slice` arm of the AI authoring benchmark: can a model write or fix ONE NPDev pack from ONE slice? Stdlib only.

WHAT IT MEASURES
----------------
A slice (`npdev pack workshop slice <pack>`) is what the owner pastes into a browser chat: the rules (~33 KB) + slice.json
(the one task, the closest built-in example, and for a fix only that pack's checker feedback). This arm sends THE SAME BYTES
through the bench's own backends (`npdev ai`'s one client: free providers, spend guard, usage log), as ONE user message, then
scores the answer with the workshop's own code (npdev_pack_slice.evaluate):
    author   the answer holds pack + handoff for exactly the asked pack@version
    ingest   batch.json + zip built by the tool, `npdev pack workshop ingest` accepts it
    static / compose / access-lint / behavior-probes ...   the workshop ladder (`npdev pack workshop test`)
When a stage fails, the checker's lines go back in the SAME chat (history repair) up to --max-loops times.
PASS = the ladder did not reject (secure or higher); `reached` = the highest rung climbed.

Each case runs in a SCRATCH copy of the workshop (packs + batches + guide), so several models can deliver the same
pack@version and the real workshop keeps that version for the owner's browser run. Nothing in the real workshop changes.

OUTPUT -- the bench's shape, so `run_bench.py matrix | compare | mine | report` include these runs:
    <BuildRoot>/ai-bench/<run id>/summary.json                         arm pack-slice, backend, scorecard
    <BuildRoot>/ai-bench/<run id>/bench-pack-<id>/result.json          stages, tokens, loops, reached
    <BuildRoot>/ai-bench/<run id>/bench-pack-<id>/attempt-N/{prompt-sent.txt, author-reply.txt}   (zips/ beside it)
    <BuildRoot>/ai-bench/<run id>/bench-pack-<id>/workshop/            the scratch workshop (verdicts under runs/)
A browser answer taken with `npdev pack workshop take` is recorded the same way (backend kind `browser`, estimated tokens).

Run (free providers only unless --allow-paid; one call is capped by --model-timeout, default 240 s):
    python scripts/ai/bench/pack_slice_arm.py --provider gemini                       # every slice, newest per pack
    python scripts/ai/bench/pack_slice_arm.py --provider nvidia --only multi-company --max-loops 1
    python scripts/ai/bench/pack_slice_arm.py --provider groq=openai/gpt-oss-120b --effort high
    python scripts/ai/bench/pack_slice_arm.py --dry-run                               # pipeline test, canned answer
Tests: python -m unittest discover -s scripts/ai/bench -p "test_bench.py"
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import backends  # noqa: E402
import stages  # noqa: E402

REPO_ROOT = stages.REPO_ROOT
sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))
import npdev_pack_slice as sl  # noqa: E402
import npdev_pack_workshop as ws  # noqa: E402

ARM_ID = sl.BENCH_ARM
MAX_TOKENS = 24000   # a pack answer is ~6-8k tokens; a thinking model spends part of the cap on reasoning (the client default 4096 cuts it)


def default_workshop(build_root: Path) -> Path:
    env = os.environ.get(ws.ENV_ROOT)
    return Path(env).expanduser().resolve() if env and env.strip() else build_root.parent / "PackWorkshop"


def run_case(cli, workshop: Path, slice_id: str, backend, run_dir: Path, max_loops: int, tester=None,
             max_tokens: int | None = None) -> dict:
    meta = sl._slice_meta(workshop, slice_id)
    case_dir = run_dir / sl.case_id(meta)
    case_dir.mkdir(parents=True, exist_ok=True)
    scratch = sl.scratch_workshop(workshop, case_dir / "workshop")
    prompt = sl.api_prompt(workshop, meta)
    messages = [{"role": "user", "content": prompt}]
    attempts: list[dict] = []
    tokens_in = tokens_out = 0
    infra_kind = None
    started = time.monotonic()
    for n in range(max_loops + 1):
        adir = case_dir / f"attempt-{n}"
        adir.mkdir(parents=True, exist_ok=True)
        (adir / "prompt-sent.txt").write_text(messages[-1]["content"], encoding="utf-8")
        try:
            completion = backend.complete(messages)
        except backends.BackendError as exc:
            infra_kind = backends.classify_backend_error(str(exc))
            attempts.append({"attempt": n, "failingStage": "author", "feedback": str(exc), "backendError": True,
                             "stages": [sl._stage("author", False, 0.0, str(exc))], "reached": "delivered"})
            break   # the model is unreachable: repairing cannot help
        tokens_in += completion.prompt_tokens
        tokens_out += completion.completion_tokens
        (adir / "author-reply.txt").write_text(completion.text, encoding="utf-8")
        if max_tokens and completion.completion_tokens >= max_tokens:
            # the answer used the WHOLE output budget: a thinking model can spend all of it on reasoning (0 characters of
            # answer, 2026-11-26 nemotron-3.5-lightning) or stop mid-JSON. That measures --max-tokens, not the author,
            # and a repair round cannot help: an infra outcome, rerun with a larger --max-tokens.
            detail = (f"output cap: the answer used all {max_tokens} tokens ({len(completion.text)} characters reached the "
                      f"reply); rerun with a larger --max-tokens")
            infra_kind = "output-cap"
            attempts.append({"attempt": n, "failingStage": "author", "feedback": detail, "backendError": True,
                             "stages": [sl._stage("author", False, completion.seconds, detail,
                                                  promptTokens=completion.prompt_tokens,
                                                  completionTokens=completion.completion_tokens)], "reached": "delivered"})
            break
        # the zip goes OUTSIDE attempt-N: run_bench.matrix_log prints every file of an attempt folder as text
        evaluation = sl.evaluate(cli, scratch, meta, completion.text, case_dir / "zips" / f"attempt-{n}",
                                 model=backend.info.model, mode="api",
                                 scratch=True, tester=tester)
        evaluation["stages"][0].update(promptTokens=completion.prompt_tokens, completionTokens=completion.completion_tokens,
                                       modelSeconds=round(completion.seconds, 2))
        attempts.append({"attempt": n, **evaluation})
        if evaluation["failingStage"] is None:
            break
        messages = messages + [{"role": "assistant", "content": completion.text},
                               {"role": "user", "content": sl.repair_message(meta, evaluation)}]
    result = sl.bench_case_result(meta, attempts, backend.info.describe(), wall=time.monotonic() - started,
                                  tokens_in=tokens_in, tokens_out=tokens_out, tokens_estimated=False,
                                  cost=backend.info.cost(tokens_in, tokens_out), infra_kind=infra_kind)
    result["scratchWorkshop"] = str(scratch)
    # which exact prompt was measured: a change to prompt.txt, the rules or slice.json gives a new hash
    result["promptSha12"] = hashlib.sha256(prompt.encode("utf-8")).hexdigest()[:12]
    result["maxLoops"] = max_loops
    (case_dir / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return result


def dry_backend(workshop: Path):
    """A canned answer: the previous pack.json re-labelled at the asked version (it will fail the ladder honestly)."""
    class Dry:
        info = backends.BackendInfo("dry-run", "fake", "", "canned")

        def complete(self, messages):
            slice_doc = json.loads(messages[0]["content"].split("===== FILE: slice.json =====\n", 1)[1])
            task = slice_doc["task"]
            pack = dict(task.get("previousPackJson") or {"pack": task["pack"], "concepts": []})
            pack.update(pack=task["pack"], version=task["version"], dslVersion="3.0.0")
            body = {"pack": pack, "handoff": {"schemaVersion": "npdev-pack-handoff.v1", "pack": task["pack"],
                                              "version": task["version"], "checklist": [], "limits": ["dry run"]}}
            return backends.Completion("```json\n" + json.dumps(body) + "\n```", 1000, 500, 0.0)
    return Dry()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    who = ap.add_mutually_exclusive_group()
    who.add_argument("--provider", help="provider[=model][,provider[=model]...] (failover in that order), as run_bench.py")
    who.add_argument("--profile", help="an `npdev ai` profile (its fallbacks fail over in order)")
    ap.add_argument("--effort", default=None, help="effort level; refused unless the model declares it")
    ap.add_argument("--allow-paid", action="store_true")
    ap.add_argument("--slice", dest="slices", action="append", help="slice id (repeatable; default: the newest slice per pack)")
    ap.add_argument("--only", action="append", help="only the newest slice of this pack (repeatable)")
    ap.add_argument("--workshop", help=f"workshop root (default: ${ws.ENV_ROOT}, else PackWorkshop next to the Build root)")
    ap.add_argument("--max-loops", type=int, default=1, help="repair rounds after the first answer (same chat)")
    ap.add_argument("--max-tokens", type=int, default=MAX_TOKENS)
    ap.add_argument("--model-timeout", type=float, default=240.0, help="seconds for one model call")
    ap.add_argument("--patience", default="20,40",
                    help="seconds to wait before each retry on 429/5xx (default 20,40: ~1 min). The client's own default is "
                         "~15 min, which hid a busy Gemini free tier for 13 min with nothing recorded (2026-11-26); a tier "
                         "still busy after this is recorded as infra:quota or infra:overload")
    ap.add_argument("--run-id")
    ap.add_argument("--out-root", help="bench root (default <BuildRoot>/ai-bench)")
    ap.add_argument("--dry-run", action="store_true", help="no model: a canned answer exercises the whole pipeline")
    args = ap.parse_args(argv)

    build_root = stages.build_root()
    workshop = Path(args.workshop).resolve() if args.workshop else default_workshop(build_root)
    slices = args.slices or sl.list_slices(workshop, args.only)
    if not slices:
        print(f"no slice in {workshop / 'slices'}; run `npdev pack workshop slice <pack>` first", file=sys.stderr)
        return 2
    if args.dry_run:
        backend = dry_backend(workshop)
    else:
        aicfg, _ = backends._ai_modules()
        try:
            patience = tuple(float(x) for x in args.patience.split(",") if x.strip())
            if args.provider:
                backend = backends.build_provider_backends(args.provider, args.allow_paid, args.model_timeout,
                                                           effort=args.effort, max_tokens=args.max_tokens, backoff=patience)
            else:
                backend = backends.ProfileBackend(aicfg.load(), args.profile or "fast", args.allow_paid, args.model_timeout,
                                                  max_tokens=args.max_tokens, backoff=patience)
        except (backends.BackendRefused, aicfg.AiConfigError) as exc:
            print(f"REFUSED: {exc}", file=sys.stderr)
            return 2
    bench_root = Path(args.out_root) if args.out_root else build_root / "ai-bench"
    label = "dry" if args.dry_run else (args.provider or args.profile or "fast").split(",")[0].split("=")[0]
    run_id = args.run_id or f"pack-slice-api-{sl._slug(label)}-{time.strftime('%Y%m%dT%H%M%S')}"
    run_dir = bench_root / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    import npdev_cli as cli   # the workshop asks the CLI module for its Build root and a validator run
    started_at, t0 = datetime.datetime.now().isoformat(timespec="seconds"), time.monotonic()
    results, harness_errors = [], []
    for slice_id in slices:
        print(f"[{slice_id}] ...", flush=True)
        try:
            res = run_case(cli, workshop, slice_id, backend, run_dir, args.max_loops,
                           max_tokens=None if args.dry_run else args.max_tokens)
        except ws.WorkshopError as exc:   # a broken slice must not cost the other cases
            print(f"    refused: {exc}", flush=True)
            harness_errors.append({"slice": slice_id, "error": f"refused: {exc}"})
            continue
        except Exception as exc:  # noqa: BLE001 -- a harness defect is recorded and fixed, never allowed to hide the rest
            print(f"    HARNESS ERROR: {type(exc).__name__}: {exc}", flush=True)
            harness_errors.append({"slice": slice_id, "error": f"{type(exc).__name__}: {exc}"})
            continue
        results.append(res)
        print(f"    {res['outcome']}{':' + res['infraKind'] if res['infraKind'] else ''}  reached={res['reached']}  "
              f"failedAt={res['failingStage']}  loops={res['loopsUsed']}  tokens={res['tokens']['prompt']}+{res['tokens']['completion']}  "
              f"{res['wallSeconds']}s", flush=True)
    import run_bench
    summary = {"runId": run_id, "startedAt": started_at, "backend": backend.info.describe(), "maxLoops": args.max_loops,
               "maxTokens": args.max_tokens, "modelTimeout": args.model_timeout, "patience": args.patience, "workshop": str(workshop),
               "dryRun": args.dry_run, "slices": slices, "wallSeconds": round(time.monotonic() - t0, 1),
               "scorecard": run_bench.scorecard(results), "harnessErrors": harness_errors}
    sl.write_bench_run(run_dir, summary, results)   # summary's dryRun wins: a dry run stays out of the matrix
    print(f"\nsummary: {run_dir / 'summary.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
