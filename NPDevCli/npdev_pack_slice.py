#!/usr/bin/env python3
"""`npdev pack workshop slice` / `take` -- ONE pack per chat message (owner order 2026-11-26: small slices).

A batch message to a chat AI carried a 182 KB file (the whole guide: rules + seven example packs + the whole roadmap)
and asked for twelve packs in one answer; one free quota died on it and another chat errored. A slice is the opposite:

    slice  ->  <root>/slices/<pack>@<version>__<stamp>/
                 prompt.txt   the message the owner pastes (short)
                 slice.json   the ONE task: roadmap entry, required packs, concept owners, ONE closest built-in example,
                              and for a fix: the previous pack.json + ONLY that pack's checker feedback
                 meta.json    for this tool (batch, roadmapId, versions, sizes); never sent
               plus <root>/guide/NPDEV_PACK_RULES.md (the rules alone, ~33 KB; built outside the repo by
               build_guide.py --rules-only, which also writes guide/rules.json -- the only part read here).
    take   ->  the chat's answer (one JSON block: pack + handoff [+ probes]) becomes batch.json + packs/<id>/... in a zip
               named by the delivery convention, then `ingest` + `test` take it. The chat never runs code or makes a zip.
    API    ->  scripts/ai/bench/pack_slice_arm.py sends the same bytes through the AI bench's backends, with repair rounds.

MEASUREMENT IS THE AI BENCH (owner, 2026-11-26: add to the existing mechanism, never a parallel log). Every take and every
arm case is an AI-bench run, arm `pack-slice`, in <BuildRoot>/ai-bench/<run id>/ with the shape run_bench.py writes
(summary.json + bench-pack-<id>/result.json + attempt-N/), so `run_bench.py matrix | compare | mine` include it. A browser
run has backend kind `browser` and estimated tokens (chars / 4). `trials` prints those runs next to the official chat
limits (NPDevCli/chat-ai-limits.json) and the API model facts (ai-providers.json).

Scripts never read markdown: the rules reach the API prompt as the `text` field of guide/rules.json.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

import npdev_pack_workshop as ws

SLICE_SCHEMA = "npdev-pack-slice.v1"
RULES_DIR = "guide"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _roadmap() -> dict:
    return json.loads((ws._repo_root() / "NPDevContract" / "pack-catalog" / "pack-roadmap.json").read_text(encoding="utf-8"))


def _builtin_packs() -> dict[str, dict]:
    out = {}
    for path in sorted((ws._repo_root() / "NPDevContract" / "packs").glob("*/pack.json")):
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            continue
        out[doc.get("pack", path.parent.name)] = {"doc": doc, "text": path.read_text(encoding="utf-8")}
    return out


STOPWORDS = {"with", "from", "that", "this", "they", "their", "them", "what", "when", "which", "where", "into", "same",
             "keep", "lives", "only", "every", "each", "have", "your", "about", "other", "record", "records"}


def _words(*texts) -> set[str]:
    return {w for t in texts for w in re.findall(r"[a-z]{4,}", str(t or "").lower()) if w not in STOPWORDS}


def pick_example(entry: dict, roadmap: dict, builtins: dict[str, dict]) -> tuple[str, str]:
    """The ONE built-in closest to the task: same kind (+3), same area (+2), shared requires (+1 each), word overlap of
    need/summary/concepts (+1 each). Ties go to the SMALLER pack (fewer bytes to send). Returns (pack id, why)."""
    road = {p["id"]: p for p in roadmap.get("packs", [])}
    mine = _words(entry.get("need"), entry.get("summary"), " ".join(entry.get("concepts") or []))
    best = None
    for pid, info in builtins.items():
        other = road.get(pid, {})
        score, why = 0, []
        if other.get("kind") and other.get("kind") == entry.get("kind"):
            score += 3
            why.append(f"same kind ({entry['kind']})")
        if other.get("area") and other.get("area") == entry.get("area"):
            score += 2
            why.append(f"same area ({entry['area']})")
        shared = set(other.get("requires") or []) & set(entry.get("requires") or [])
        if shared:
            score += len(shared)
            why.append("shares requires " + ", ".join(sorted(shared)))
        theirs = _words(other.get("need"), other.get("summary"), " ".join(c.get("name", "") for c in info["doc"].get("concepts", [])))
        overlap = mine & theirs
        score += len(overlap)
        if overlap:
            why.append("words " + ", ".join(sorted(overlap)[:4]))
        key = (score, -len(info["text"]))
        if best is None or key > best[0]:
            best = (key, pid, "; ".join(why) or "smallest built-in")
    if best is None:
        raise ws.WorkshopError("no built-in pack found under NPDevContract/packs to use as the example")
    return best[1], best[2]


def _pack_summary(doc: dict) -> dict:
    """What a dependent pack needs to know about a required pack, without its whole text."""
    major = str(doc.get("version") or "1").split(".")[0]
    # the exact `packs` entry to copy: a 2026-11-26 bench answer wrote "^1.0" for notification 2.0.0 and could not compose
    out = {"pack": doc.get("pack"), "version": doc.get("version"),
           "packsEntry": {"pack": doc.get("pack"), "version": f"^{major}.0"},
           "concepts": [{"name": c.get("name"), "fields": [f.get("name") for f in c.get("fields", []) if isinstance(f, dict)]}
                        for c in doc.get("concepts", []) if isinstance(c, dict)]}
    for key in ("roles", "capabilities", "domainTypes"):
        items = doc.get(key)
        if isinstance(items, list) and items:
            out[key] = [i.get("name") if isinstance(i, dict) else i for i in items]
    return out


def _refresh_dependency_entries(previous: dict, required: list[dict]) -> list[str]:
    """A fix slice carries the pack as it was delivered, and it may depend on `contacts ^1.0` while the built-in is 2.0.0
    now. The 2026-11-26 bench: payables kept the old constraint through two repair rounds ("Pack 'contacts' requires at
    least 1.0.0 ... the local copy declares 2.0.0") although `requiredPacks.packsEntry` said ^2.0. A dependency
    constraint is no authoring decision: the tool moves the previous pack's entries to the current ones, in place, and
    returns "pack ^x.0" for each it changed."""
    current = {r["pack"]: r["packsEntry"]["version"] for r in required if isinstance(r.get("packsEntry"), dict)}
    changed: list[str] = []
    for entry in previous.get("packs") or []:
        if isinstance(entry, dict) and entry.get("pack") in current and entry.get("version") != current[entry["pack"]]:
            entry["version"] = current[entry["pack"]]
            changed.append(f"{entry['pack']} {entry['version']}")
    return changed


def _concept_owners(builtins: dict[str, dict]) -> list[str]:
    return [f"{pid}::{c.get('name')}" for pid, info in sorted(builtins.items())
            for c in info["doc"].get("concepts", []) if isinstance(c, dict)]


def _batch_of(root: Path, pack_id: str) -> str | None:
    for record in sorted((root / "batches").glob("*.json")):
        doc = json.loads(record.read_text(encoding="utf-8"))
        if any(p.get("pack") == pack_id for d in doc.get("deliveries", []) for p in d.get("packs", [])):
            return doc.get("batchId")
    return None


def pack_feedback(root: Path, pack_id: str, version: str) -> tuple[str, list[str], list[str]]:
    """(headline, fixes, advice) from the newest verdict of pack@version -- only this pack's lines."""
    found = ws.latest_verdict(root, pack_id, version)
    if found is None:
        raise ws.WorkshopError(f"{pack_id}@{version} was never tested; run `npdev pack workshop test {pack_id}@{version}` first")
    verdict, _ = found
    lines = verdict.get("feedbackForAi", [])
    fixes = [f for f in lines if not f.startswith("(warning)")]
    advice = [f[len("(warning) "):] for f in lines if f.startswith("(warning)")]
    if verdict["status"] == "rejected":
        head = f"{pack_id}@{version}: REJECTED at {ws.failed_step(verdict)} (reached: {ws.highest_rung(verdict)})"
    else:
        head = f"{pack_id}@{version}: {verdict['status'].upper()}"
    return head, fixes if verdict["status"] == "rejected" else [], advice


def _rules(root: Path) -> dict:
    meta_path = root / RULES_DIR / "rules.json"
    if not meta_path.is_file():
        raise ws.WorkshopError(f"{meta_path} is missing: build the rules file first (build_guide.py --rules-only <workshop root>)")
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    rules_file = root / RULES_DIR / meta["file"]
    if not rules_file.is_file():
        raise ws.WorkshopError(f"{rules_file} is missing although rules.json names it; rebuild it")
    meta["path"] = str(rules_file)
    meta["bytes"] = rules_file.stat().st_size
    return meta


def _bump(version: str) -> str:
    major, minor, patch = ws._semver(version)
    return f"{major}.{minor}.{patch + 1}"


PROMPT = """I am attaching two files: `{rules}` (the NPDev pack rules; they win over this message) and `slice.json` (ONE task).

{task}

Answer with ONE fenced ```json block and nothing else around it that matters. The block is a single object:
  {{"pack": <the complete pack.json, never a diff>, "handoff": <handoff.json: schemaVersion "npdev-pack-handoff.v1", pack, version, one checklist entry per id of the rules' section 12, limits>, "probes": {{"<name>.json": <probe>, ...}}}}
Every checklist `answer` is exactly "yes", "no" or "n/a"; anything else goes in `evidence`. You cannot run the validator here: answer `validator-status` with "n/a" and say so in `evidence`.
Depend on the packs in `requiredPacks` by copying each one's `packsEntry` into your pack's `packs` list. `requires` is NOT for packs: it is an object `{{"roles": [...], "capabilities": [...]}}`, never a list of pack ids.
A concept of a required pack, and one of your own, is referenced as `<pack id>::<Concept>` in `reference.target`, the pack id spelled exactly as it is written (hyphen included); a required pack's `domainTypes` are used on a field as `{{"type": "string", "domainType": "<pack>::<Name>"}}` and are never a `reference` target.
This replaces the rules' "Delivering" section: do NOT make a zip, a batch.json or a download; my tool builds them from your block.
Every text inside the pack is English. Put open questions for me in the pack's `metadata.reviewNotes`, and anything the rules make impossible in `handoff.limits`.
"""


def slice_pack(cli, root: Path, spec: str, batch: str | None = None, example: str | None = None, stamp: str | None = None) -> dict:
    pack_id, _, wanted = spec.partition("@")
    roadmap = _roadmap()
    entry = next((p for p in roadmap.get("packs", []) if p.get("id") == pack_id), None)
    if entry is None:
        raise ws.WorkshopError(f"'{pack_id}' is not in pack-roadmap.json; a slice is always one roadmap pack")
    builtins = _builtin_packs()
    if pack_id in builtins and entry.get("status") == "existing":
        raise ws.WorkshopError(f"'{pack_id}' is a built-in pack (status existing); it is not authored by a chat AI")
    rules = _rules(root)
    previous_dir = ws.find_pack_dir(root, pack_id, wanted or None)
    if wanted and previous_dir is None:
        raise ws.WorkshopError(f"{pack_id}@{wanted} is not in the workshop")
    task: dict = {}
    if previous_dir is not None:
        prev_version = previous_dir.name
        head, fixes, advice = pack_feedback(root, pack_id, prev_version)
        if not fixes:
            raise ws.WorkshopError(f"{head}: nothing to fix, so there is no slice to send")
        target = _bump(prev_version)
        task = {"kind": "fix", "pack": pack_id, "version": target, "replaces": prev_version,
                "feedback": {"headline": head, "fix": fixes, "advice": advice},
                "previousPackJson": json.loads((previous_dir / "pack.json").read_text(encoding="utf-8")),
                "note": "The feedback is a FIRST list: the checker stops at the first failing step, so after these fixes more "
                        "problems of the same kind can appear. Re-read the rules for every concept instead of patching one line."}
        task_line = (f"Fix the pack `{pack_id}`: deliver it as version {target} (it replaces {prev_version}; my tool records that in batch.json, so pack.json has no `replaces` key). `slice.json` holds your "
                     f"previous version (`task.previousPackJson`) and our checker's feedback for this pack only (`task.feedback`). "
                     f"Fix what is listed, keep what the pack means.")
        batch = batch or _batch_of(root, pack_id)
    else:
        target = "1.0.0"
        task = {"kind": "new", "pack": pack_id, "version": target}
        task_line = f"Write the NEW pack `{pack_id}` version {target}, as described by `slice.json` (`roadmap`)."
    if not batch:
        raise ws.WorkshopError(f"no batch is known for '{pack_id}'; give --batch w<wave>-<lot>")
    if not re.match(r"^w[0-9]+-[a-z0-9-]+$", batch):
        raise ws.WorkshopError(f"--batch '{batch}' is not a batch id like w1-modules")
    if example and example not in builtins:
        raise ws.WorkshopError(f"--example '{example}' is not a built-in pack")
    example_id, why = (example, "chosen by the owner") if example else pick_example(entry, roadmap, builtins)
    find = ws.make_pack_finder(root)
    required = []
    for dep in entry.get("requires") or []:
        path = find(dep)
        required.append(_pack_summary(json.loads(path.read_text(encoding="utf-8"))) if path else
                        {"pack": dep, "missing": "not built yet: say you are blocked on it in handoff.limits"})
    if task.get("kind") == "fix":
        updated = _refresh_dependency_entries(task["previousPackJson"], required)
        if updated:
            task["note"] += (" The `packs` entries of previousPackJson were already moved to the versions the built-ins have now ("
                             + ", ".join(updated) + "): keep them.")
    doc = {"schemaVersion": SLICE_SCHEMA, "guideVersion": rules["guideVersion"], "task": task,
           "roadmap": {k: v for k, v in entry.items() if k != "status"}, "requiredPacks": required,
           "conceptOwners": _concept_owners(builtins),
           "example": {"pack": example_id, "why": why, "packJson": builtins[example_id]["doc"]}}
    stamp = stamp or _now().strftime("%Y%m%d-%H%M")
    folder = root / "slices" / f"{pack_id}@{target}__{stamp}"
    folder.mkdir(parents=True, exist_ok=True)
    slice_text = json.dumps(doc, separators=(",", ":"), ensure_ascii=False) + "\n"  # compact: ~30% fewer bytes than indented
    prompt = PROMPT.format(rules=rules["file"], task=task_line)
    (folder / "slice.json").write_text(slice_text, encoding="utf-8")
    (folder / "prompt.txt").write_text(prompt, encoding="utf-8")
    sizes = {"rules": rules["bytes"], "slice": len(slice_text.encode("utf-8")), "prompt": len(prompt.encode("utf-8"))}
    sizes["total"] = sum(sizes.values())
    meta = {"sliceId": folder.name, "batchId": batch, "pack": pack_id, "version": target, "roadmapId": entry["id"],
            "replaces": task.get("replaces"), "example": example_id, "guideVersion": rules["guideVersion"],
            "createdAt": _now().strftime("%Y-%m-%dT%H:%M:%SZ"), "bytesIn": sizes,
            "send": [rules["path"], str(folder / "slice.json")], "paste": str(folder / "prompt.txt")}
    (folder / "meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    meta["folder"] = str(folder)
    return meta


# ------------------------------------------------------------------------------------------------ take

def extract_answer(text: str) -> dict:
    """The chat's answer -> {"pack", "handoff", "probes"}. Accepts the one-object block, or separate blocks (a pack.json,
    a handoff.json), or the bare object; refuses anything else with a sentence."""
    # the closing fence is a ``` that STARTS a line: a pack description or reviewNote may hold an inline ``` of its own
    # (a Markdown example inside a JSON string), which a lazy `(.*?)```` took for the end of the block
    blocks = re.findall(r"```(?:json)?\s*\n(.*?)\n[ \t]*```", text, re.S) or [text]
    parsed = []
    broken: list[str] = []   # where each block stops being JSON: the one line an AI needs to repair it
    for block in blocks:
        try:
            parsed.append(json.loads(block))
        except ValueError:
            start, end = block.find("{"), block.rfind("}")
            if start >= 0 and end > start:
                try:
                    parsed.append(json.loads(block[start:end + 1]))
                except json.JSONDecodeError as exc:
                    near = exc.doc[max(0, exc.pos - 60):exc.pos + 40].replace("\n", " ")
                    broken.append(f"line {exc.lineno} column {exc.colno}: {exc.msg}, near `{near}`")
    if not parsed and blocks != [text]:
        # fenced blocks were found but none parsed (a closing fence glued to the last brace, say): the answer may still be
        # ONE object between its first `{` and its last `}`
        start, end = text.find("{"), text.rfind("}")
        if start >= 0 and end > start:
            try:
                parsed.append(json.loads(text[start:end + 1]))
            except ValueError:
                pass
    for obj in parsed:
        if isinstance(obj, dict) and isinstance(obj.get("pack"), dict) and isinstance(obj.get("handoff"), dict):
            probes = obj.get("probes") or {}
            if not isinstance(probes, dict):
                raise ws.WorkshopError("'probes' must be an object of {\"<name>.json\": <probe>}")
            return {"pack": obj["pack"], "handoff": obj["handoff"], "probes": probes}
    pack = next((o for o in parsed if isinstance(o, dict) and isinstance(o.get("pack"), str)
                 and ("concepts" in o or "dslVersion" in o)), None)
    handoff = next((o for o in parsed if isinstance(o, dict) and o.get("schemaVersion") == "npdev-pack-handoff.v1"), None)
    if pack and handoff:
        return {"pack": pack, "handoff": handoff, "probes": {}}
    if broken and not parsed:
        # 2026-11-26 bench: "0 parsable blocks" alone made a model resend the same invalid JSON; the position fixes it
        raise ws.WorkshopError("the answer's JSON does not parse: " + broken[0]
                               + (f" (+{len(broken) - 1} more broken block(s))" if len(broken) > 1 else "")
                               + ". Send the whole object again as valid JSON.")
    raise ws.WorkshopError("the answer holds no JSON object with 'pack' and 'handoff' (nor a pack.json plus a handoff.json "
                           f"block); found {len(parsed)} parsable JSON block(s). Ask the chat to answer in the slice's format.")


def _slice_meta(root: Path, slice_id: str | None) -> dict:
    folder = root / "slices"
    if slice_id:
        path = folder / slice_id / "meta.json"
        if not path.is_file():
            raise ws.WorkshopError(f"slice '{slice_id}' is unknown ({path})")
    else:
        metas = sorted(folder.glob("*/meta.json"), key=lambda p: p.stat().st_mtime)
        if not metas:
            raise ws.WorkshopError("no slice exists yet; run `npdev pack workshop slice <pack>` first")
        path = metas[-1]
    meta = json.loads(path.read_text(encoding="utf-8"))
    meta["folder"] = str(path.parent)
    return meta


# ------------------------------------------------------------------------------------------------ evaluate
# One answer -> the workshop's own checks, as bench stages. Shared by `take` (browser) and the bench arm
# scripts/ai/bench/pack_slice_arm.py (API), so both modes are scored by the same code.

def _batch_doc(meta: dict, model: str, mode: str) -> dict:
    return {"schemaVersion": "npdev-pack-batch.v1", "batchId": meta["batchId"],
            "author": {"kind": "api-ai" if mode == "api" else "browser-ai", "model": model or "unknown"},
            "guideVersion": meta["guideVersion"],
            "packs": [{"pack": meta["pack"], "version": meta["version"], "roadmapId": meta["roadmapId"],
                       **({"replaces": meta["replaces"]} if meta.get("replaces") else {})}]}


def build_zip(meta: dict, answer: dict, out_dir: Path, *, model: str, mode: str, stamp: str) -> Path:
    """batch.json + packs/<id>/{pack,handoff}.json + probes, named by the delivery convention."""
    pack, handoff = answer["pack"], dict(answer["handoff"])
    handoff.setdefault("pack", meta["pack"])
    handoff.setdefault("version", meta["version"])
    probe_files: dict[str, object] = {}   # zip file name -> probe; two names that collapse to one file would silently drop a probe
    chosen: dict[str, str] = {}
    for name, probe in sorted(answer["probes"].items()):
        safe = Path(str(name)).name
        safe = safe if safe.endswith(".json") else safe + ".json"
        if safe in probe_files:
            raise ws.WorkshopError(f"probes '{chosen[safe]}' and '{name}' would both be written as probes/{safe} -- give every probe its own file name")
        probe_files[safe], chosen[safe] = probe, str(name)
    if probe_files:
        handoff.setdefault("probesIncluded", sorted(probe_files))
    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f"npdev-packs__{meta['batchId']}__{stamp}.zip"
    base = f"packs/{meta['pack']}"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("batch.json", json.dumps(_batch_doc(meta, model, mode), indent=2) + "\n")
        zf.writestr(f"{base}/pack.json", json.dumps(pack, indent=2, ensure_ascii=False) + "\n")
        zf.writestr(f"{base}/handoff.json", json.dumps(handoff, indent=2, ensure_ascii=False) + "\n")
        for safe, probe in probe_files.items():
            zf.writestr(f"{base}/probes/{safe}", json.dumps(probe, indent=2, ensure_ascii=False) + "\n")
    return zip_path


def _clear_version(root: Path, pack_id: str, version: str) -> None:
    """SCRATCH ONLY: a repair round re-delivers the same pack@version, which the real workshop would refuse."""
    import shutil
    for folder in (root / "packs").glob(f"*/{pack_id}/{version}"):
        shutil.rmtree(folder)
    shutil.rmtree(root / "runs" / f"{pack_id}@{version}", ignore_errors=True)
    # a model that repeats its answer word for word sends the same zip bytes: the inbox would call it a duplicate and
    # place nothing, and the ladder would then test a pack that is not there
    shutil.rmtree(root / "inbox", ignore_errors=True)


def scratch_workshop(root: Path, dest: Path) -> Path:
    """A throw-away workshop (packs + batches + guide): several models can deliver the same pack@version, and the real
    workshop keeps that version free for the owner's own browser run."""
    import shutil
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    for part in ("packs", "batches", "guide"):
        if (root / part).is_dir():
            shutil.copytree(root / part, dest / part)
    return dest


def _stage(name: str, ok: bool, seconds: float, detail: str = "", **extra) -> dict:
    """The bench's StageResult shape (scripts/ai/bench/stages.py), so `matrix`, `compare` and `mine` read it as-is."""
    return {"stage": name, "ok": ok, "seconds": round(seconds, 2), "detail": detail[:1200], **extra}


def evaluate(cli, ingest_root: Path, meta: dict, text: str, out_dir: Path, *, model: str, mode: str,
             scratch: bool, tester=None, stamp: str | None = None) -> dict:
    """Stages: author (the answer holds a pack + handoff for the asked pack@version) -> ingest -> the ladder's own steps
    (static, compose, access-lint, behavior-probes...). failingStage = the first that failed, None = accepted."""
    stages: list[dict] = []
    started = time.monotonic()
    out = {"stages": stages, "failingStage": None, "feedback": "", "fixes": [], "status": None, "reached": "delivered"}

    def fail(stage: str, detail: str) -> dict:
        stages.append(_stage(stage, False, time.monotonic() - started, detail))
        out.update(failingStage=stage, feedback=detail, fixes=[detail])
        return out

    try:
        answer = extract_answer(text)
    except ws.WorkshopError as exc:
        return fail("author", str(exc))
    pack = answer["pack"]
    if pack.get("pack") != meta["pack"] or str(pack.get("version")) != meta["version"]:
        return fail("author", f"the answer's pack.json is {pack.get('pack')}@{pack.get('version')}, but the slice asked for "
                              f"{meta['pack']}@{meta['version']}")
    stages.append(_stage("author", True, time.monotonic() - started, f"pack + handoff + {len(answer['probes'])} probe(s)"))
    t0 = time.monotonic()
    try:
        if scratch:
            _clear_version(ingest_root, meta["pack"], meta["version"])
        zip_path = build_zip(meta, answer, out_dir, model=model, mode=mode, stamp=stamp or _now().strftime("%Y%m%d-%H%M"))
        ingested = ws.ingest_zip(ingest_root, zip_path)
    except ws.WorkshopError as exc:
        return fail("ingest", str(exc))
    out["zip"] = str(zip_path)
    stages.append(_stage("ingest", True, time.monotonic() - t0, "; ".join(ingested.get("warnings", []))))
    try:
        verdict = (tester or ws.test_pack)(cli, ingest_root, meta["pack"], meta["version"])
    except ws.WorkshopError as exc:
        return fail("ladder", f"the workshop could not test it: {exc}")
    for step in verdict.get("steps", []):
        stages.append(_stage(step["step"], step["result"] != "fail", step.get("seconds", 0),
                             f"{step['result']}: {step.get('detail', '')}".rstrip(": ")))
    fixes = [f for f in verdict.get("feedbackForAi", []) if not f.startswith("(warning)")]
    out.update(status=verdict["status"], reached=ws.highest_rung(verdict), verdictRun=verdict.get("_run"))
    if verdict["status"] == "rejected":
        out.update(failingStage=ws.failed_step(verdict) or "ladder", fixes=fixes, feedback="\n".join(fixes))
    return out


def repair_message(meta: dict, evaluation: dict) -> str:
    """The checker's answer, sent back in the SAME chat (the bench's history repair mode)."""
    lines = "\n".join(f"- {f}" for f in evaluation["fixes"]) or "- (no detail)"
    return (f"Our checker did not accept `{meta['pack']}` {meta['version']}: the step `{evaluation['failingStage']}` failed.\n"
            f"{lines}\n\nFix what is listed and keep what the pack means. This list is a FIRST list: the checker stops at the "
            f"first failing step, so re-read the rules for every concept instead of patching one line. Keep the pack id and "
            f"version {meta['version']}, and answer again in the same format: ONE fenced ```json block with pack, handoff "
            f"and probes, the complete pack.json, never a diff.")


# ------------------------------------------------------------------------------------------------ bench records
# A slice trial IS a bench run: <BuildRoot>/ai-bench/<run id>/{summary.json, bench-pack-<id>/result.json, attempt-N/}
# in the shape scripts/ai/bench/run_bench.py writes, so `run_bench.py matrix | compare | mine | report` include it.

BENCH_ARM = "pack-slice"


def bench_root_for(cli) -> Path:
    return ws._build_root(cli) / "ai-bench"


def case_id(meta: dict) -> str:
    return f"bench-pack-{meta['pack']}"


def bench_case_result(meta: dict, attempts: list[dict], backend: dict, *, wall: float, tokens_in: int, tokens_out: int,
                      tokens_estimated: bool, cost: float = 0.0, infra_kind: str | None = None) -> dict:
    """run_bench.run_prompt's result.json, plus the pack fields (pack, version, sliceId, sliceKb, reached)."""
    last, first = attempts[-1], attempts[0]
    passed = infra_kind is None and last["failingStage"] is None
    return {
        "promptId": case_id(meta), "tier": "pack-fix" if meta.get("replaces") else "pack-new",
        "title": f"{meta['pack']} {meta['version']}" + (f" (fixes {meta['replaces']})" if meta.get("replaces") else ""),
        "passed": passed, "outcome": "pass" if passed else ("infra" if infra_kind else "fail"), "infraKind": infra_kind,
        "loopsUsed": len(attempts) - 1, "stoppedStuck": False,
        "firstAttemptValid": first["failingStage"] not in ("author", "ingest", "static") and not first.get("backendError"),
        "firstAttemptPassed": first["failingStage"] is None and not first.get("backendError"),
        "firstAttemptFailingStage": first["failingStage"],
        "failingStage": None if passed else last["failingStage"],
        "tokens": {"prompt": tokens_in, "completion": tokens_out, "total": tokens_in + tokens_out, "estimated": tokens_estimated},
        "costUsd": cost, "wallSeconds": round(wall, 2), "arm": BENCH_ARM, "ablation": "none", "repairMode": "history",
        "backend": backend,
        "pack": meta["pack"], "version": meta["version"], "replaces": meta.get("replaces"), "sliceId": meta["sliceId"],
        "sliceKb": round(meta["bytesIn"]["total"] / 1024, 1), "example": meta.get("example"),
        "reached": last.get("reached", "delivered"),
        "attempts": [{"attempt": a["attempt"], "failingStage": a["failingStage"], "reached": a.get("reached"),
                      "feedbackExcerpt": (a.get("feedback") or "")[:600], "stages": a["stages"]} for a in attempts],
    }


def write_bench_run(run_dir: Path, summary: dict, results: list[dict]) -> Path:
    run_dir.mkdir(parents=True, exist_ok=True)
    for result in results:
        folder = run_dir / result["promptId"]
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    summary = {"arm": BENCH_ARM, "dryRun": False, "ablation": "none", "repairMode": "history",
               "knowledge": {"source": "pack-rules slice"}, **summary,
               "results": [{k: r.get(k) for k in ("promptId", "pack", "passed", "outcome", "failingStage", "reached",
                                                  "loopsUsed", "wallSeconds")} | {"tokens": r["tokens"]["total"]}
                           for r in results]}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return run_dir


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9.-]+", "-", str(text).lower()).strip("-")[:60]


def take_answer(cli, root: Path, answer_path: Path, slice_id: str | None = None, *, provider: str = "", model: str = "unknown",
                effort: str = "", minutes: float | None = None, bench_root: Path | None = None, tester=None,
                stamp: str | None = None) -> dict:
    """A BROWSER answer: into the real workshop (immutable versions), tested, and recorded as a one-case bench run with
    backend kind `browser`. Tokens are estimated (chars / 4) because a chat UI reports none; minutes are the owner's."""
    meta = _slice_meta(root, slice_id)
    text = answer_path.read_bytes().decode("utf-8", errors="replace")
    stamp = stamp or _now().strftime("%Y%m%dT%H%M%S")
    run_id = f"pack-slice-browser-{_slug(provider or 'chat')}-{stamp}"
    run_dir = (bench_root or bench_root_for(cli)) / run_id
    adir = run_dir / case_id(meta) / "attempt-0"
    adir.mkdir(parents=True, exist_ok=True)
    (adir / "author-reply.txt").write_text(text, encoding="utf-8")
    # the zip goes OUTSIDE attempt-0: run_bench.matrix_log prints every file of an attempt folder as text
    evaluation = evaluate(cli, root, meta, text, run_dir / case_id(meta) / "zips", model=model, mode="browser",
                          scratch=False, tester=tester,
                          stamp=stamp[:8] + "-" + stamp[9:13])
    attempt = {"attempt": 0, **evaluation}
    backend = {"id": provider or "chat", "kind": "browser", "baseUrl": "", "model": model, "effort": effort or None,
               "pricePer1kTokens": {"input": 0.0, "output": 0.0}, "allowPaid": False}
    result = bench_case_result(meta, [attempt], backend, wall=(minutes or 0) * 60,
                               tokens_in=meta["bytesIn"]["total"] // 4, tokens_out=len(text) // 4, tokens_estimated=True)
    result["kbOut"] = round(len(text.encode("utf-8")) / 1024, 1)
    folder = Path(meta["folder"])
    sent = b"".join((folder / n).read_bytes() for n in ("prompt.txt", "slice.json")) + str(meta["bytesIn"]["rules"]).encode()
    result["promptSha12"] = hashlib.sha256(sent).hexdigest()[:12]   # which exact slice was measured
    write_bench_run(run_dir, {"runId": run_id, "startedAt": _now().isoformat(timespec="seconds"), "backend": backend,
                              "maxLoops": 0, "wallSeconds": result["wallSeconds"], "workshop": str(root)}, [result])
    return {"runDir": str(run_dir), "result": result, "evaluation": evaluation}


# ------------------------------------------------------------------------------------------------ API prompt

API_HEADER = ("You are answering through an API, so the two files are inlined below instead of attached; each starts "
              "with a line `===== FILE: <name> =====`.\n\n")


def api_prompt(root: Path, meta: dict) -> str:
    """The same bytes the browser gets (prompt.txt + the rules + slice.json), inlined: the two modes stay comparable."""
    rules = _rules(root)
    if "text" not in rules:
        raise ws.WorkshopError("rules.json carries no 'text'; rebuild it with build_guide.py --rules-only (the API prompt "
                               "needs the rules as data, and no NPDev script reads markdown)")
    folder = Path(meta["folder"])
    return (API_HEADER + (folder / "prompt.txt").read_text(encoding="utf-8")
            + f"\n===== FILE: {rules['file']} =====\n" + rules["text"]
            + "\n===== FILE: slice.json =====\n" + (folder / "slice.json").read_text(encoding="utf-8"))


def list_slices(root: Path, packs: list[str] | None = None) -> list[str]:
    """Slice ids, the newest per pack (optionally only these packs)."""
    newest: dict[str, tuple[float, str]] = {}
    for meta_path in (root / "slices").glob("*/meta.json"):
        try:   # one half-written meta.json must not stop the bench arm from listing every other slice
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            _ = (meta["pack"], meta["sliceId"])   # both keys are used below: a meta without them is a broken one
        except (OSError, ValueError, KeyError, TypeError) as exc:
            print(f"warning: slice {meta_path.parent.name} skipped: {type(exc).__name__}: {exc}", file=sys.stderr)
            continue
        if packs and meta["pack"] not in packs:
            continue
        stamp = meta_path.stat().st_mtime
        if meta["pack"] not in newest or stamp > newest[meta["pack"]][0]:
            newest[meta["pack"]] = (stamp, meta["sliceId"])
    return [sid for _, sid in sorted(newest.values(), key=lambda v: v[1])]


# ------------------------------------------------------------------------------------------------ CLI

def add_arguments(sub) -> None:
    sl = sub.add_parser("slice", help="ONE pack per chat message: write prompt.txt + slice.json (+ the shared rules file) for the owner to send.")
    sl.add_argument("pack", help="<packId> (fix: newest workshop version; new: a proposed roadmap pack) or <packId>@<version> to fix")
    sl.add_argument("--batch", default=None, help="batch id (default: the batch that already holds the pack)")
    sl.add_argument("--example", default=None, help="built-in pack to send as the example (default: the closest one)")
    sl.add_argument("--root", default=None, help="workshop root")
    sl.add_argument("--json", action="store_true")
    tk = sub.add_parser("take", help="A BROWSER chat's answer (saved to a file) -> zip -> ingest -> test, recorded as an AI-bench run (arm pack-slice).")
    tk.add_argument("answer", help="text file holding the chat's whole answer (or just its JSON block)")
    tk.add_argument("--slice", dest="slice_id", default=None, help="slice id (folder name under slices/; default: the newest)")
    tk.add_argument("--provider", default="", help="which chat, e.g. gemini, claude, chatgpt (relay-targets.json ids)")
    tk.add_argument("--model", default="unknown", help="model as the chat names it, e.g. 'Neon'")
    tk.add_argument("--effort", default="", help="effort/mode as the chat UI names it, e.g. 'Alto'")
    tk.add_argument("--minutes", type=float, default=None, help="wall-clock minutes the chat took")
    tk.add_argument("--root", default=None, help="workshop root")
    tk.add_argument("--json", action="store_true")
    tr = sub.add_parser("trials", help="The providers table: official chat limits (chat-ai-limits.json), API model facts, and every pack-slice AI-bench run (browser and API).")
    tr.add_argument("--root", default=None, help="workshop root")
    tr.add_argument("--json", action="store_true")


def main(cli, args, command: str) -> int:
    root = ws.workshop_root(cli, args.root)
    try:
        if command == "slice":
            meta = slice_pack(cli, root, args.pack, args.batch, args.example)
            if args.json:
                print(json.dumps(meta, indent=2))
            else:
                b = meta["bytesIn"]
                print(f"slice {meta['sliceId']}  (batch {meta['batchId']}, example: {meta['example']})")
                print(f"  attach:  {meta['send'][0]}  ({b['rules'] // 1024} KB, the same for every slice)")
                print(f"  attach:  {meta['send'][1]}  ({b['slice'] // 1024} KB)")
                print(f"  paste:   {meta['paste']}")
                print(f"  total in: {b['total'] // 1024} KB")
                print(f"  browser: save the answer to a file, then: npdev pack workshop take <file> --slice {meta['sliceId']} --provider <p> --model <m>")
                print(f"  API:     python scripts/ai/bench/pack_slice_arm.py --provider <p> --slice {meta['sliceId']}")
            return 0
        if command == "take":
            out = take_answer(cli, root, Path(args.answer), args.slice_id, provider=args.provider, model=args.model,
                              effort=args.effort, minutes=args.minutes)
            result = out["result"]
            if args.json:
                print(json.dumps(out, indent=2, default=str))
            else:
                print(f"{result['outcome'].upper()}  {result['pack']}@{result['version']}  reached {result['reached']}"
                      + (f", failed at {result['failingStage']}" if result["failingStage"] else ""))
                for fix in out["evaluation"]["fixes"][:5]:
                    print(f"  fix: {fix[:200]}")
                print(f"  bench run: {out['runDir']}")
            return 0 if result["passed"] else 1
        rows, limits = bench_rows(bench_root_for(cli)), read_limits()
        if args.json:
            print(json.dumps({"limits": limits, "runs": rows}, indent=2))
        else:
            print(format_limits(limits) + "\n\nPACK-SLICE BENCH RUNS (" + str(bench_root_for(cli)) + ")\n" + format_runs(rows))
        return 0
    except ws.WorkshopError as exc:
        print(f"refused: {exc}")
        return 2


LIMITS_PATH = Path(__file__).resolve().parent / "chat-ai-limits.json"


def read_limits(path: Path = LIMITS_PATH) -> dict:
    doc = json.loads(path.read_text(encoding="utf-8"))
    api = json.loads((path.parent / doc["apiFacts"]).read_text(encoding="utf-8"))
    doc["apiModels"] = [{"provider": m.get("provider"), "model": m.get("id"), "effortLevels": m.get("effortLevels") or [],
                         "contextTokens": m.get("contextTokens"), "maxOutputTokens": m.get("maxOutputTokens"),
                         "free": (m.get("price") or {}).get("in") == 0 and (m.get("price") or {}).get("out") == 0}
                        for m in api.get("models", [])]
    return doc


def _table(head: tuple, rows: list[tuple]) -> str:
    table = [head] + rows
    widths = [max(len(str(row[i])) for row in table) for i in range(len(head))]
    return "\n".join("  ".join(str(c).ljust(w) for c, w in zip(row, widths)).rstrip() for row in table)


def format_limits(doc: dict) -> str:
    browser = _table(("provider", "plan", "model", "effort", "mode", "context", "uploads", "answer size", "free quota"),
                     [(r["provider"], r["plan"], r["model"], r["effort"], r["mode"], r["contextTokens"], r["uploads"][:40],
                       r["answerSize"], r["freeQuota"]) for r in doc["rows"]])
    measured = [f"  {r['provider']} {r['plan']} {r['model']} {r['effort']}: {m}" for r in doc["rows"] for m in r.get("measured", [])]
    api = _table(("provider", "model", "effort levels", "context", "max output", "free"),
                 [(m["provider"], m["model"], ",".join(m["effortLevels"]) or "-", m["contextTokens"] or "?",
                   m["maxOutputTokens"] or "?", "yes" if m["free"] else "no") for m in doc["apiModels"]])
    return "\n".join(["BROWSER CHATS (official cells; 'measured' = from runs)", browser, "", "MEASURED BEFORE SLICES (batch requests)",
                      *measured, "", "API MODELS (from ai-providers.json)", api])


def bench_rows(bench_root: Path) -> list[dict]:
    """One row per case of every pack-slice bench run (browser `take` and the API arm alike)."""
    rows = []
    if not bench_root.is_dir():
        return rows
    for summary_path in sorted(bench_root.glob("*/summary.json")):
        try:
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
        except ValueError:
            continue
        if summary.get("arm") != BENCH_ARM or summary.get("dryRun"):
            continue
        for result_path in sorted(summary_path.parent.glob("bench-*/result.json")):
            try:   # a run killed while writing its result.json must cost that one row, not the whole listing
                r = json.loads(result_path.read_text(encoding="utf-8"))
                b = r.get("backend") or {}
                rows.append({"runId": summary.get("runId"), "startedAt": summary.get("startedAt"), "pack": r.get("pack"),
                             "version": r.get("version"), "kind": b.get("kind"), "provider": b.get("id"), "model": b.get("model"),
                             "effort": b.get("effort") or "", "kbIn": r.get("sliceKb"), "tokensIn": r["tokens"]["prompt"],
                             "tokensOut": r["tokens"]["completion"], "estimated": r["tokens"].get("estimated", False),
                             "seconds": r.get("wallSeconds"), "loops": r.get("loopsUsed"), "outcome": r.get("outcome"),
                             "infraKind": r.get("infraKind"), "reached": r.get("reached"), "failingStage": r.get("failingStage"),
                             "prompt": (r.get("promptSha12") or "")[:6]})
            except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
                print(f"warning: {result_path} skipped: {type(exc).__name__}: {exc}", file=sys.stderr)
    return rows


def format_runs(rows: list[dict]) -> str:
    if not rows:
        return "(no pack-slice runs yet)"
    return _table(("started", "prompt", "pack", "kind", "provider", "model", "effort", "KB in", "tok in", "tok out", "s", "loops", "outcome", "reached / failed at"),
                  [((r["startedAt"] or "")[:16], r["prompt"], f"{r['pack']}@{r['version']}", r["kind"], r["provider"], r["model"], r["effort"],
                    r["kbIn"], f"{r['tokensIn']}{'~' if r['estimated'] else ''}", f"{r['tokensOut']}{'~' if r['estimated'] else ''}",
                    r["seconds"], r["loops"], r["outcome"] + (f":{r['infraKind']}" if r["infraKind"] else ""),
                    r["reached"] + (f" / {r['failingStage']}" if r["failingStage"] else "")) for r in rows])
