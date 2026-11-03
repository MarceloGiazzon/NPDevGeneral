"""The evolution phase of the token benchmark (BENCH-4). Stdlib only.

WHAT IT MEASURES
----------------
Writing an app once is the easy half. A working app is changed again and again, and the claim NPDev most needs to
prove (hypothesis H3 of the benchmark plan) is that changing a working app is much cheaper when the change is an
edit to a small declarative model than when it is an edit to a pile of code -- and breaks fewer things that already
worked. A prompt may therefore declare `changes[]`:

    {"id": "c1", "request": "<what the owner now wants, in plain words>",
     "acceptanceDelta": {"concepts": [...], "rules": [...], "probes": [...]}}

After a prompt PASSES, each change is applied in order -- NPDev arm: the model edits model.json; direct arms: the
model edits the files -- and the FULL acceptance (the original plus every delta so far) is run again. A change
passes only when the new behaviour works AND everything that worked before still does. Per change the run records
the tokens it cost (input and output, every call), the repair loops it needed and which earlier probes broke.
`changeTokens` and `changePassRate` per arm are what `run_bench.py compare` reports.

The loop here is arm-neutral; each arm supplies a small adapter (`messages`, `repair`, `attempt`, `commit`).
"""
from __future__ import annotations

import copy
import json
import time
from pathlib import Path

LIST_KEYS = ("rules", "probes", "roles", "flows", "views")


class EvolutionError(Exception):
    pass


def merge_acceptance(base: dict, delta: dict | None) -> dict:
    """The acceptance after one change: concepts merge by name (new fields are appended, a field of the same
    name is replaced, a new concept is added); rules, probes, roles, flows and views are appended and must carry
    NEW ids/names; `minimums` in the delta replace the base's. The base is never modified."""
    merged = copy.deepcopy(base)
    delta = delta or {}
    concepts = {c["name"]: c for c in merged.get("concepts", [])}
    for c in delta.get("concepts", []):
        have = concepts.get(c["name"])
        if have is None:
            merged.setdefault("concepts", []).append(copy.deepcopy(c))
            continue
        fields = {f["name"]: f for f in have.get("fields", [])}
        for f in c.get("fields", []):
            if f["name"] in fields:
                have["fields"][have["fields"].index(fields[f["name"]])] = copy.deepcopy(f)
            else:
                have.setdefault("fields", []).append(copy.deepcopy(f))
        if c.get("aliases"):
            have["aliases"] = sorted(set(have.get("aliases", [])) | set(c["aliases"]))
    for key in LIST_KEYS:
        existing = {x.get("id") or x.get("name") for x in merged.get(key, [])}
        for item in delta.get(key, []):
            ident = item.get("id") or item.get("name")
            if ident in existing:
                raise EvolutionError(f"acceptanceDelta.{key}: {ident!r} already exists; a change must use new ids")
            merged.setdefault(key, []).append(copy.deepcopy(item))
            existing.add(ident)
    if delta.get("minimums"):
        merged["minimums"] = {**merged.get("minimums", {}), **delta["minimums"]}
    return merged


def validate_changes(prompt: dict) -> list[str]:
    """Problems with a prompt's `changes[]` (used by the prompt checker): ids unique, request present, and every
    delta must merge cleanly into the acceptance it extends."""
    problems: list[str] = []
    seen: set[str] = set()
    acceptance = prompt["acceptance"]
    for change in prompt.get("changes", []):
        cid = change.get("id", "?")
        if cid in seen:
            problems.append(f"{prompt['id']}: duplicate change id {cid}")
        seen.add(cid)
        if len(str(change.get("request", "")).strip()) < 30:
            problems.append(f"{prompt['id']} {cid}: the request is missing or too short")
        try:
            acceptance = merge_acceptance(acceptance, change.get("acceptanceDelta"))
        except EvolutionError as exc:
            problems.append(f"{prompt['id']} {cid}: {exc}")
            continue
        probe_ids = {p["id"] for p in acceptance.get("probes", [])}
        for rule in acceptance.get("rules", []):
            for pid in rule["check"].get("probes", []):
                if pid not in probe_ids:
                    problems.append(f"{prompt['id']} {cid}: rule {rule['id']} names unknown probe {pid}")
    return problems


def _tokens(completion, into: dict) -> None:
    into["prompt"] += completion.prompt_tokens
    into["completion"] += completion.completion_tokens
    into["seconds"] += completion.seconds


def apply_changes(prompt: dict, adapter, backend, args, change_root: Path) -> dict:
    """Apply `prompt['changes']` in order to an app that already passed. Stops at the first change that cannot
    be applied (later changes build on it). Returns the block stored in result.json."""
    changes = prompt.get("changes") or []
    acceptance = prompt["acceptance"]
    done: list[dict] = []
    max_loops = getattr(args, "max_loops", 0)
    for change in changes:
        started = time.monotonic()
        acceptance = merge_acceptance(acceptance, change.get("acceptanceDelta"))
        cdir = change_root / f"change-{change['id']}"
        cdir.mkdir(parents=True, exist_ok=True)
        used = {"prompt": 0, "completion": 0, "seconds": 0.0}
        messages = adapter.messages(change)
        passed, attempts = False, []
        regressions: list[str] = []
        for n in range(0, max_loops + 1):
            try:
                completion = backend.complete(messages)
            except Exception as exc:    # backend errors are infrastructure, not the model's change
                attempts.append({"attempt": n, "failingStage": "author", "feedback": str(exc), "backendError": True})
                break
            _tokens(completion, used)
            (cdir / f"reply-{n}.txt").write_text(completion.text, encoding="utf-8")
            stage_log: list[dict] = []
            attempt = adapter.attempt(n, completion.text, dict(prompt, acceptance=acceptance), cdir, stage_log)
            attempts.append({"attempt": n, "failingStage": attempt.get("failingStage"),
                             "feedback": (attempt.get("feedback") or "")[:600]})
            if attempt.get("failingStage") is None:
                passed = True
                adapter.commit(attempt)
                break
            acc = attempt.get("acceptance") or {}
            regressions = [p["id"] for p in acc.get("probes", [])
                           if p.get("state") != "passed" and p["id"] in {q["id"] for q in prompt["acceptance"].get("probes", [])}]
            messages = adapter.repair(messages, completion.text, attempt["failingStage"], attempt.get("feedback") or "")
        done.append({"id": change["id"], "passed": passed, "loopsUsed": len(attempts) - 1,
                     "tokens": {"prompt": used["prompt"], "completion": used["completion"],
                                "total": used["prompt"] + used["completion"]},
                     "authorSeconds": round(used["seconds"], 2), "wallSeconds": round(time.monotonic() - started, 2),
                     "regressions": [] if passed else regressions,
                     "failingStage": None if passed else attempts[-1].get("failingStage")})
        if not passed:
            break
    attempted = len(done)
    return {"changes": done, "changesDeclared": len(changes), "changesAttempted": attempted,
            "changePassRate": round(sum(1 for c in done if c["passed"]) / len(changes), 4) if changes else None,
            "changeTokens": sum(c["tokens"]["total"] for c in done),
            "changeTokensInput": sum(c["tokens"]["prompt"] for c in done),
            "changeTokensOutput": sum(c["tokens"]["completion"] for c in done)}
