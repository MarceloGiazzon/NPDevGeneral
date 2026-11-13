"""The `relay-f4` arm of the AI authoring benchmark: does a chat AI use what the phase-f4 prompt teaches? Stdlib only.

WHAT IT MEASURES
----------------
`relay_arm.py` plays the person for a NEW app (intent create, phase mvp). It cannot answer the question the f4 prompt
(NPDevCli/relay-prompts-f4.json, `more`) raises: when an owner asks an EXISTING app for a new look, a page of its own, a menu entry
or sample data, does the AI send the right files in the right shape, and does it stay quiet when the request is only a data change?
This arm plays the person for that: for each case it makes a throw-away copy of the canary app (whole / split into fragments / above
the answer budget, so each of the three prompt modes is exercised), asks the REAL engine for the round-1 prompt
(npdev_relay_engine.start, phase f4), sends it as ONE user message to the backend, gives the reply to the REAL engine.ingest
(checkers for themes, pages, menu, seeds, config proposals and the authoring gate), and, when the answer is not READY, sends the
engine's own repair prompt in the SAME chat, up to --max-loops times. Nothing is applied: the verdict is what ingest says.

TWO PROMPTS, SAME CASES (--text)
--------------------------------
  with     the prompt as shipped: files/upsert briefing + the `more` section
  without  the previous f4 prompt (no `more`): the control. If the AI does as well without it, the text is not earning its place.

SCORING (per case, last round)
------------------------------
Each case lists the `wants` it expects (theme, selection, page, pagesOrMenu, seed, dataChange) and whether `quiet` (nothing but a
data change) is required. A want is met only if ingest ACCEPTED the file (status allowed) and, for `selection`, the theme-name
proposal came back `applicable`. `ready` = the final outcome is READY. `extras` = accepted files nobody asked for.
`complete` = ready and every want met and no extras.

Writes <BuildRoot>/ai-bench/relay-f4/<run id>/{summary.json, <case>-<text>/round-N-{prompt,reply,result}} -- outside the repo.
Run:  python scripts/ai/bench/relay_f4_arm.py --profile fast [--only f4-look] [--text with,without] [--max-loops 2]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import backends  # noqa: E402
import stages  # noqa: E402

REPO_ROOT = stages.REPO_ROOT
CANARY = REPO_ROOT / "NPDevSamples" / "npdev-canary" / "Input"
sys.path.insert(0, str(REPO_ROOT / "NPDevCli"))

ARM_ID = "relay-f4"
TARGET = "generic"
SMALL_ANSWER = 4000          # the smallest size the engine accepts: the canary model (5 KB) is above 60% of it, so the prompt asks for an upsert
MAX_TOKENS = 12000           # a browser chat is not capped at the client's 4096, and a thinking model spends part of the cap on reasoning (16384 made nemotron-3-ultra answer with nothing)

LOOK = "make the whole app look calmer, in soft green tones"
ASK_ALL = ("Please give the app a calmer look in soft green tones. Also add a simple 'About us' page with two short paragraphs about "
           "our small business, and put it in the menu under a heading called Info. And add some sample tasks I can load with one click "
           "to show a customer how the app looks with data in it.")
CASES = [
    {"id": "f4-all-whole", "fixture": "whole", "ask": ASK_ALL, "wants": ["theme", "selection", "page", "pagesOrMenu", "seed"]},
    {"id": "f4-all-split", "fixture": "split", "ask": ASK_ALL, "wants": ["theme", "selection", "page", "pagesOrMenu", "seed"]},
    {"id": "f4-all-big", "fixture": "whole", "answerSize": SMALL_ANSWER, "ask": ASK_ALL,
     "wants": ["theme", "selection", "page", "pagesOrMenu", "seed"]},
    {"id": "f4-look", "fixture": "whole", "ask": "Make the whole app look dark, with teal as the highlight colour.",
     "wants": ["theme", "selection"]},
    {"id": "f4-quiet", "fixture": "whole", "quiet": True, "wants": ["dataChange"],
     "ask": "Each task should also have a due date, so we can see what is late."},
]


class WideBackend(backends.ProfileBackend):
    """ProfileBackend with a bigger output cap. The client's default (4096) cut a thinking model's answer mid-JSON in the first
    run of this arm (outcome TRUNCATED on a prompt a person would never see truncated), which measures the cap, not the prompt."""

    def complete(self, messages):
        _, client = backends._ai_modules()
        result = client.complete(self._config, "", messages=messages, profile=self._profile, feature="bench",
                                 allow_paid=self._allow_paid, timeout=self._timeout, max_tokens=MAX_TOKENS,
                                 key_resolver=self._key_resolver)
        completion = backends._completion(result, messages)
        if result.get("attempts") and result.get("profile"):
            self.retired += [a["profile"] or a["provider"] for a in result["attempts"]]
            self._profile = result["profile"]
        self.info = backends._info(self._config, result["provider"], result["model"], self._allow_paid)
        return completion


# ---------------------------------------------------------------------------------------------
# fixtures
# ---------------------------------------------------------------------------------------------

def make_app(root: Path, fixture: str) -> Path:
    """A throw-away copy of the canary app: `whole` (model.json), `split` (one fragment per concept)."""
    app = root / "app"
    app.mkdir(parents=True)
    model = json.loads((CANARY / "model.json").read_text(encoding="utf-8"))
    if fixture == "split":
        (app / "fragments").mkdir()
        head = {k: v for k, v in model.items() if k != "concepts"}
        head["fragments"] = []
        for concept in model["concepts"]:
            rel = f"fragments/{concept['name'].lower()}.json"
            head["fragments"].append({"$ref": rel})
            (app / rel).write_text(json.dumps({"concepts": [concept]}, indent=2) + "\n", encoding="utf-8")
        model = head
    (app / "model.json").write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    config = CANARY / "config.json"
    if config.is_file():
        shutil.copyfile(config, app / "config.json")
    return app


# ---------------------------------------------------------------------------------------------
# scoring
# ---------------------------------------------------------------------------------------------

def accepted(result: dict) -> list:
    return [f["path"] for f in result.get("files") or [] if f.get("status") == "allowed"]


def meets(result: dict) -> dict:
    """{want: bool} for every want this module knows, read from one ingest result."""
    paths = accepted(result)
    proposals = result.get("configProposals") or []
    texts = " ".join(str(f.get("content") or "") for f in result.get("files") or [] if f.get("status") == "allowed")
    return {
        "theme": any(re.fullmatch(r"themes/[^/]+\.json", p) for p in paths),
        "selection": any(i.get("property") == "ui.theme.name" and i.get("status") == "applicable" for i in proposals),
        "page": any(re.fullmatch(r"web/[^/]+\.html?", p) for p in paths),
        "pagesOrMenu": "pages.json" in paths or "menu.json" in paths,
        "seed": any(re.fullmatch(r"seeds/[^/]+\.json", p) for p in paths),
        "dataChange": "due" in texts.lower(),
    }


def extras(case: dict, result: dict) -> list:
    """Accepted files the case did not ask for: a data change must be only the model (or its parts), a look only theme + selection."""
    allowed = {"model.json", "npdev-submission.json", "npdev-elements.json"}
    if "theme" in case["wants"]:
        allowed |= {"npdev-config-proposal.json"}
    out = []
    for path in accepted(result):
        if path in allowed or path.startswith(("fragments/", "contexts/")):
            continue
        if re.fullmatch(r"themes/[^/]+\.json", path) and "theme" in case["wants"]:
            continue
        if re.fullmatch(r"web/[^/]+", path) and "page" in case["wants"]:
            continue
        if path in ("pages.json", "menu.json") and "pagesOrMenu" in case["wants"]:
            continue
        if path.startswith("seeds/") and "seed" in case["wants"]:
            continue
        out.append(path)
    return out


def verdict(case: dict, rounds: list) -> dict:
    last = rounds[-1]["result"] if rounds and rounds[-1].get("result") else {}
    met = meets(last) if last else {}
    wanted = {w: met.get(w, False) for w in case["wants"]}
    extra = extras(case, last) if last else []
    ready = bool(last) and last.get("outcome") == "READY"
    return {"ready": ready, "wants": wanted, "extras": extra, "complete": ready and all(wanted.values()) and not extra,
            "rounds": len(rounds), "firstRoundReady": bool(rounds) and (rounds[0].get("outcome") == "READY")}


# ---------------------------------------------------------------------------------------------
# one case
# ---------------------------------------------------------------------------------------------

def run_case(case: dict, text_mode: str, backend, run_dir: Path, max_loops: int) -> dict:
    import npdev_relay_engine as engine
    import npdev_relay_prompts as rp

    case_dir = run_dir / f"{case['id']}-{text_mode}"
    app = make_app(case_dir, case["fixture"])
    real_more = rp.compose_f4_more
    if text_mode == "without":
        rp.compose_f4_more = lambda p4: ""          # the previous f4 prompt: files/upsert briefing only
    try:
        started = engine.start(intent="change", ask=case["ask"], app_dir=str(app), phase="f4", target=TARGET,
                               answer_size=case.get("answerSize"))
    finally:
        rp.compose_f4_more = real_more
    code, prompt = started["code"], started["prompt"].rstrip() + "\n"
    messages = [{"role": "user", "content": prompt}]
    rounds, tokens_in, tokens_out = [], 0, 0
    note = ""
    for n in range(1, max_loops + 2):
        (case_dir / f"round-{n}-prompt.txt").write_text(messages[-1]["content"], encoding="utf-8")
        try:
            completion = backend.complete(messages)
        except backends.BackendError as exc:
            note = f"backend: {exc}"
            rounds.append({"round": n, "outcome": "BACKEND_ERROR", "infra": backends.classify_backend_error(str(exc))})
            break
        tokens_in += completion.prompt_tokens
        tokens_out += completion.completion_tokens
        (case_dir / f"round-{n}-reply.txt").write_text(completion.text, encoding="utf-8")
        if not completion.text.strip():          # a model that answers with nothing (a reasoning model out of room): not the prompt's fault
            note = "the model sent an empty reply"
            rounds.append({"round": n, "outcome": "EMPTY_REPLY", "infra": "empty-reply"})
            break
        result = engine.ingest(code, completion.text, source="paste")
        (case_dir / f"round-{n}-result.json").write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
        rounds.append({"round": n, "outcome": result.get("outcome"), "result": result,
                       "findings": sorted({f["code"] for f in result.get("findings") or []}),
                       "firstError": (result.get("errorLines") or [""])[0][:200]})
        follow = (result.get("next") or {}).get("prompt")
        if result.get("outcome") == "READY" or n > max_loops or not follow:
            break
        messages += [{"role": "assistant", "content": completion.text}, {"role": "user", "content": follow}]
    v = verdict(case, rounds)
    infra = next((r["infra"] for r in rounds if r.get("infra")), None)
    return {"case": case["id"], "text": text_mode, "fixture": case["fixture"], "answerMode": started.get("answerMode"),
            "promptChars": len(prompt), "tokensIn": tokens_in, "tokensOut": tokens_out, "infra": infra, "note": note,
            "outcomes": [r["outcome"] for r in rounds], "firstErrors": [r.get("firstError") for r in rounds if r.get("firstError")],
            "findings": [r.get("findings") for r in rounds], **v}


# ---------------------------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------------------------

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def table(results: list) -> str:
    head = f"{'case':<14} {'text':<8} {'mode':<7} {'ready':<6} {'1st':<4} {'rnds':<5} {'complete':<9} {'wants met':<34} extras"
    lines = [head, "-" * len(head)]
    for r in results:
        met = ",".join(w for w, ok in r["wants"].items() if ok) or "-"
        miss = ",".join(w for w, ok in r["wants"].items() if not ok)
        lines.append(f"{r['case']:<14} {r['text']:<8} {str(r['answerMode']):<7} {str(r['ready']):<6} {str(r['firstRoundReady']):<4} "
                     f"{r['rounds']:<5} {str(r['complete']):<9} {met + (' | MISSING ' + miss if miss else ''):<34} {','.join(r['extras']) or '-'}"
                     + (f"   [infra: {r['infra']}]" if r["infra"] else "") + f"  <{r.get('model', '')}>")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--profile", default="fast", help="an `npdev ai` profile (its fallbacks fail over in order)")
    ap.add_argument("--allow-paid", action="store_true")
    ap.add_argument("--only", action="append", help="only this case id (repeatable)")
    ap.add_argument("--text", default="with,without", help="with, without or with,without")
    ap.add_argument("--max-loops", type=int, default=2, help="repair rounds after the first answer")
    ap.add_argument("--run-id")
    ap.add_argument("--out-root")
    ap.add_argument("--model-timeout", type=float, default=300.0)
    args = ap.parse_args(argv)
    modes = [m for m in args.text.split(",") if m in ("with", "without")]
    cases = [c for c in CASES if not args.only or c["id"] in args.only]
    if not modes or not cases:
        print("nothing to run", file=sys.stderr)
        return 2
    aicfg, _ = backends._ai_modules()
    try:
        backend = WideBackend(aicfg.load(), args.profile, args.allow_paid, args.model_timeout)
    except (backends.BackendRefused, aicfg.AiConfigError) as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 2
    build_root = Path(args.out_root) if args.out_root else stages.build_root() / "ai-bench"
    run_id = args.run_id or time.strftime("relay-f4-%Y%m%dT%H%M%S")
    run_dir = build_root / "relay-f4" / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    os.environ["NPDEV_AI_CONFIG"] = str(run_dir / "ai.json")   # the sessions live in the run folder, not in the person's own
    results = []
    for case in cases:
        for mode in modes:
            print(f"[{case['id']} / {mode}] ...", flush=True)
            try:
                res = run_case(case, mode, backend, run_dir, args.max_loops)
            except Exception as exc:  # noqa: BLE001 -- one broken case must not cost the other nine
                res = {"case": case["id"], "text": mode, "fixture": case["fixture"], "answerMode": None, "promptChars": 0,
                       "tokensIn": 0, "tokensOut": 0, "infra": "harness", "note": f"{type(exc).__name__}: {exc}", "outcomes": [],
                       "firstErrors": [], "findings": [], "ready": False, "wants": {w: False for w in case["wants"]}, "extras": [],
                       "complete": False, "rounds": 0, "firstRoundReady": False}
            res["model"] = backend.info.model          # the model that served this case (a failover is visible, never silent)
            results.append(res)
            (run_dir / "summary.partial.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
            print(f"    ready={res['ready']} complete={res['complete']} outcomes={res['outcomes']} extras={res['extras']}", flush=True)
    summary = {"arm": ARM_ID, "runId": run_id, "backend": {"id": backend.info.id, "model": backend.info.model, "kind": backend.info.kind},
               "profile": args.profile, "maxLoops": args.max_loops,
               "hashes": {"relay-prompts-f4.json": sha(REPO_ROOT / "NPDevCli" / "relay-prompts-f4.json"),
                          "relay-prompts.json": sha(REPO_ROOT / "NPDevCli" / "relay-prompts.json"),
                          "relay-ingest-policy.json": sha(REPO_ROOT / "NPDevCli" / "relay-ingest-policy.json")},
               "results": results}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("\n" + table(results) + f"\n\nsummary: {run_dir / 'summary.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
