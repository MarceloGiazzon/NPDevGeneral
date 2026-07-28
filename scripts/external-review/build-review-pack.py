#!/usr/bin/env python3
"""ADR-0009 / P3: builds a redacted, chunked external-ai-pack.schema.json pack for one mission.

WHY THIS EXISTS
---------------
Every mission in `missions.json` (PLAN_EXTERNAL_AI_REVIEW_2026-07-26.md \u00a73) needs the same three
things done to its source content before anything may leave this machine: check it isn't something
that must never be shown (our own conclusions), sweep it for anything that looks like a live secret,
and chunk+hash it into a manifest a vendor call and a verdict can both point back to. This script is
that one producer -- git-pinned, so a calibration mission (P4) can target the exact pre-fix parent
commit of a known bug rather than the working tree.

FAIL CLOSED
-----------
Any secret-content-pattern hit, or any requested path matching the never-include class (findings
docs, the register, this very plan), is a build failure: exit 1, no pack file written. This is the
same "be loud, never launder a miss into a green" posture as check-register-consistency.py and
security-pattern-sweep.py.

DETERMINISM
-----------
manifestSha256 is computed over {missionId, source, redactionPolicyVersion, chunks-without-text} --
deliberately EXCLUDING generatedAt, which is wall-clock and would otherwise make two builds of
identical input hash differently. main() always builds the manifest twice in-process and refuses to
write output if the two hashes disagree, so P2's own verification bullet ("second build -> identical
hash") is self-checking on every run rather than something a human has to remember to test.

WHERE OUTPUT GOES
-----------------
Packs are evidence-shaped build output, not source -- per this repo's Build Output Policy they never
land inside NPDev_General. Default --output-dir is under
D:\\WorkSpace\\NPDev\\NPDev_General__OutsideRepo\\external-ai-review\\packs\\, matching ADR-0009
honesty rule 7 (raw transcripts live outside the repo too).

USAGE
-----
    python scripts/external-review/build-review-pack.py --mission-id M2-SEC-ROWAUTHZ
    python scripts/external-review/build-review-pack.py --mission-id M0-CALIB-LNCH13   # uses the mission's pinned commit
    python scripts/external-review/build-review-pack.py --mission-id M1-SEC-GENCODE \\
        --repo-root D:\\WorkSpace\\NPDev\\Build\\some-app --paths src/main/java/.../FooService.java

Exit codes: 0 = pack written, 1 = sanitizer/forbidden-path/determinism failure, 2 = bad mission/config.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_MISSIONS_FILE = Path(__file__).parent / "missions.json"
DEFAULT_OUTPUT_DIR = Path(
    r"D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-ai-review\packs"
)

# This platform repo's own root (NPDev_General) -- scripts/external-review/build-review-pack.py is
# two levels down from it. Deliberately NOT the same thing as --repo-root: a mission whose content
# lives elsewhere (M1/M6 read a generated app under D:\WorkSpace\NPDev\Build) still redacts and
# sanitizes using THIS repo's rule files, never a copy that may or may not exist at --repo-root.
PLATFORM_REPO_ROOT = Path(__file__).resolve().parent.parent.parent

# The single redaction rule source (ADR-0009 / P2) -- one file, read by this Python producer AND
# by the Java SensitiveKeyPolicy in tracing-redaction-default, so "what is sensitive" never drifts
# into a fourth independent copy. (sensitive-key-patterns.json, the field-NAME sibling of this file,
# is not read here: M1-M6's content is raw source text, not structured key/value data. P6/P7 will
# need it once the product producer redacts an app's own structured records for M7.)
SECRET_CONTENT_PATTERNS_FILE = Path(
    "NPDevKernel/adapters/tracing-redaction-default/src/main/resources/npdev/redaction/secret-content-patterns.json"
)
REDACTION_POLICY_VERSION = "sensitive-key-patterns.json+secret-content-patterns.json@2026-07-26"

DEFAULT_CHUNK_LINES = 400

# Never-include class: a pack must never carry our own conclusions about ourselves (honesty rule 5
# -- findings are filed, never fed back to a reviewer as a hint). Same idea as
# check-register-consistency.py's LEDGER_EXCLUSION_PATTERNS, applied here to egress instead of to
# doc-drift checking.
FORBIDDEN_PATH_PATTERNS = [
    re.compile(r"_ADVERSARIAL_REVIEW\.md$"),
    re.compile(r"NPDEV_OPEN_ITEMS_REGISTER\.md$"),
    re.compile(r"THREAD_SUMMARY.*\.md$"),
    re.compile(r"PLAN_EXTERNAL_AI_REVIEW.*\.md$"),
    re.compile(r"POST_BETA0_HUMAN_ACTION_REGISTER\.md$"),
    re.compile(r"OPEN_ITEMS_SNAPSHOT\.md$"),
    re.compile(r"POST_PROGRAMME_AUDIT_PLAN\.md$"),
]


class BuildFailure(SystemExit):
    def __init__(self, message: str):
        super().__init__(f"BUILD FAILURE: {message}")


@dataclass
class Mission:
    mission_id: str
    instance: str
    is_calibration: bool
    git_pinned: bool
    source_commit: str | None
    paths: list[str]
    pack_contents: list[str]
    excluded: list[str]
    min_vendors: int

    @staticmethod
    def load(missions_file: Path, mission_id: str) -> "Mission":
        data = json.loads(missions_file.read_text(encoding="utf-8"))
        for entry in data["missions"]:
            if entry["missionId"] == mission_id:
                return Mission(
                    mission_id=entry["missionId"],
                    instance=entry["instance"],
                    is_calibration=entry.get("isCalibration", False),
                    git_pinned=entry.get("gitPinned", False),
                    source_commit=entry.get("sourceCommit"),
                    paths=list(entry.get("paths", [])),
                    pack_contents=list(entry["packContents"]),
                    excluded=list(entry["excluded"]),
                    min_vendors=entry.get("vendorRequirements", {}).get("minVendors", 1),
                )
        known = ", ".join(e["missionId"] for e in data["missions"])
        raise SystemExit(f"ERROR: unknown missionId '{mission_id}' in {missions_file}. Known: {known}")


def load_secret_patterns() -> list[tuple[str, "re.Pattern[str]"]]:
    path = PLATFORM_REPO_ROOT / SECRET_CONTENT_PATTERNS_FILE
    data = json.loads(path.read_text(encoding="utf-8"))
    return [(p["id"], re.compile(p["regex"])) for p in data["patterns"]]


def check_forbidden_paths(paths: list[str]) -> None:
    for path in paths:
        for pattern in FORBIDDEN_PATH_PATTERNS:
            if pattern.search(path):
                raise BuildFailure(
                    f"'{path}' matches the never-include class ({pattern.pattern}) -- a pack must "
                    f"never carry our own findings/conclusions. Remove it from --paths or the "
                    f"mission's paths in missions.json."
                )


def _git_rev_parse_head(cwd: Path) -> str | None:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=cwd, capture_output=True, text=True, encoding="utf-8",
    )
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else None


def _newest_commit_touching(paths: list[str]) -> tuple[str, str] | None:
    """(commit, ISO8601 commit timestamp) of the newest commit touching any of the given
    platform-repo-relative paths -- always run against PLATFORM_REPO_ROOT, since the generator's
    own templates/emitters are platform source regardless of what --repo-root a mission slices."""
    result = subprocess.run(
        ["git", "log", "-1", "--format=%H%x09%cI", "--", *paths],
        cwd=PLATFORM_REPO_ROOT, capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode != 0 or not result.stdout.strip():
        return None
    commit, _, timestamp = result.stdout.strip().partition("\t")
    return commit, timestamp


# REG-51: the generator/template files whose commit history decides whether a GENERATED app's
# already-emitted code might be stale relative to a fix (this is exactly what let REG-49 through --
# M1's pack sliced wmsoffice's emitted Java, generated 62 minutes before the LNCH13-F1 template fix).
GENERATOR_PROVENANCE_PATHS = [
    "NPDevGenerator/generator/src/main/resources/npdev-templates",
    "NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters",
]


def _find_build_info(start: Path) -> dict[str, str] | None:
    """Walks upward from a generated-app slice's --repo-root looking for the FinalApp root's own
    npdev-build-info.properties (BuildInfoEmitter's output, at <appRoot>/src/main/resources/...) --
    the generation timestamp this mission's sliced content actually came from."""
    current = start.resolve()
    for _ in range(12):
        candidate = current / "src" / "main" / "resources" / "npdev-build-info.properties"
        if candidate.is_file():
            properties: dict[str, str] = {}
            for line in candidate.read_text(encoding="utf-8").splitlines():
                if "=" in line:
                    key, _, value = line.partition("=")
                    properties[key.strip()] = value.strip()
            return properties
        if current.parent == current:
            break
        current = current.parent
    return None


def resolve_provenance(repo_root: Path, commit: str | None) -> dict:
    """REG-51: every pack records where its sliced content actually came from, and -- for a mission
    slicing a GENERATED app's emitted code (M1/M7-shape) -- whether that generated code predates the
    newest commit to touch the generator templates/emitters that produced it. A stale slice is exactly
    how REG-49 became a false positive: the vendor correctly found LNCH13-F1's bug shape in code that
    simply hadn't been regenerated since the fix landed, and nothing recorded that gap for a reviewer
    (human or AI) to catch before scoring the finding as live."""
    try:
        repo_root.resolve().relative_to(PLATFORM_REPO_ROOT)
        is_platform_source = True
    except ValueError:
        is_platform_source = False

    if is_platform_source:
        resolved_commit = commit or _git_rev_parse_head(PLATFORM_REPO_ROOT)
        return {
            "kind": "platform-git",
            "repo": "NPDev_General",
            "commit": resolved_commit if resolved_commit else "WORKING_TREE",
        }

    build_info = _find_build_info(repo_root)
    newest = _newest_commit_touching(GENERATOR_PROVENANCE_PATHS)
    source: dict = {
        "kind": "generated-app",
        "repo": str(repo_root),
    }
    if build_info is None:
        source["provenanceVerified"] = False
        source["provenanceNote"] = (
            "no npdev-build-info.properties found walking up from --repo-root -- generation "
            "timestamp unknown, staleness cannot be checked"
        )
        return source

    generated_at = build_info.get("npdev.generator.generatedAtUtc")
    source["generatedAtUtc"] = generated_at
    source["generatorCommit"] = build_info.get("npdev.commit")
    if newest is not None:
        source["newestTemplateCommit"], source["newestTemplateCommitAt"] = newest
    if generated_at and newest is not None:
        try:
            stale = datetime.fromisoformat(generated_at) < datetime.fromisoformat(newest[1])
        except ValueError:
            stale = None
        source["provenanceVerified"] = stale is not None
        source["stale"] = stale
    else:
        source["provenanceVerified"] = False
        source["provenanceNote"] = "missing generatedAtUtc or no template history found; staleness cannot be checked"
    return source


def read_file_content(repo_root: Path, path: str, commit: str | None) -> str:
    if commit:
        result = subprocess.run(
            ["git", "show", f"{commit}:{path}"],
            cwd=repo_root,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if result.returncode != 0:
            raise SystemExit(f"ERROR: git show {commit}:{path} failed: {result.stderr.strip()}")
        return result.stdout
    full_path = repo_root / path
    if not full_path.exists():
        raise SystemExit(f"ERROR: path does not exist: {full_path}")
    return full_path.read_text(encoding="utf-8", errors="replace")


def sanitize(contents: dict[str, str], secret_patterns: list[tuple[str, "re.Pattern[str]"]]) -> int:
    """Hard-fail sweep for secret-SHAPED content. Never prints the matched text itself -- only
    which pattern and which file -- so a sanitizer failure doesn't leak the very secret it caught
    into a build log."""
    hit_count = 0
    for path, text in contents.items():
        for pattern_id, pattern in secret_patterns:
            if pattern.search(text):
                hit_count += 1
                print(
                    f"SANITIZER HIT: pattern '{pattern_id}' matched in {path} "
                    f"(match text withheld from this output)",
                    file=sys.stderr,
                )
    return hit_count


@dataclass
class Chunk:
    chunk_id: str
    sha256: str
    line_count: int
    label: str
    text: str


def chunk_content(path: str, text: str, chunk_lines: int) -> list[Chunk]:
    lines = text.splitlines(keepends=True)
    if not lines:
        return [Chunk(f"{path}#chunk0", hashlib.sha256(b"").hexdigest(), 0, path, "")]
    starts = list(range(0, len(lines), chunk_lines))
    multi = len(starts) > 1
    chunks: list[Chunk] = []
    for index, start in enumerate(starts):
        piece = lines[start : start + chunk_lines]
        chunk_text = "".join(piece)
        label = f"{path} (chunk {index + 1}/{len(starts)})" if multi else path
        chunks.append(
            Chunk(
                chunk_id=f"{path}#chunk{index}",
                sha256=hashlib.sha256(chunk_text.encode("utf-8")).hexdigest(),
                line_count=len(piece),
                label=label,
                text=chunk_text,
            )
        )
    return chunks


# REG-51: these two describe "how stale is this pack relative to ongoing platform work as of the
# moment it was built" -- a judgment that keeps changing as unrelated platform commits land, not a
# property of the sliced CONTENT itself. Excluded from the hash for the same reason generatedAt is:
# a manifestSha256 identifies what content a pack is about, not when/against-what-else it was built.
_PROVENANCE_VOLATILE_KEYS = ("newestTemplateCommit", "newestTemplateCommitAt")


def manifest_bytes(mission: Mission, source: dict, chunks: list[Chunk]) -> bytes:
    stable_source = {k: v for k, v in source.items() if k not in _PROVENANCE_VOLATILE_KEYS}
    manifest_input = {
        "missionId": mission.mission_id,
        "source": stable_source,
        "redactionPolicyVersion": REDACTION_POLICY_VERSION,
        "chunks": [
            {"chunkId": c.chunk_id, "sha256": c.sha256, "lineCount": c.line_count, "label": c.label}
            for c in chunks
        ],
    }
    return json.dumps(manifest_input, sort_keys=True, separators=(",", ":")).encode("utf-8")


def build_pack(
    mission: Mission,
    repo_root: Path,
    commit_override: str | None,
    extra_paths: list[str],
    chunk_lines: int,
) -> dict:
    commit = commit_override or (mission.source_commit if mission.git_pinned else None)
    paths = list(mission.paths) + list(extra_paths)
    if not paths:
        raise SystemExit(
            f"ERROR: mission {mission.mission_id} has no paths configured in missions.json "
            f"(by design -- see its description); pass --paths"
        )
    check_forbidden_paths(paths)

    contents = {p: read_file_content(repo_root, p, commit) for p in paths}

    secret_patterns = load_secret_patterns()
    hit_count = sanitize(contents, secret_patterns)
    if hit_count:
        raise BuildFailure(f"sanitizer found {hit_count} secret-pattern hit(s); pack not written")

    chunks: list[Chunk] = []
    for path in paths:
        chunks.extend(chunk_content(path, contents[path], chunk_lines))

    source = resolve_provenance(repo_root, commit)
    if source.get("stale"):
        raise BuildFailure(
            f"REG-51: pack refused -- {repo_root} was generated at "
            f"{source.get('generatedAtUtc')}, which predates {source.get('newestTemplateCommit')} "
            f"({source.get('newestTemplateCommitAt')}), the newest commit to touch the generator "
            f"templates/emitters that produced this code. Regenerate the app before building this "
            f"pack, or this mission risks reviewing already-fixed code as if it were live (REG-49's "
            f"exact failure mode)."
        )

    first_hash = hashlib.sha256(manifest_bytes(mission, source, chunks)).hexdigest()
    second_hash = hashlib.sha256(manifest_bytes(mission, source, chunks)).hexdigest()
    if first_hash != second_hash:
        # Only reachable if manifest_bytes is non-deterministic (e.g. dict ordering) -- this is the
        # automatic proof of P2's "second build -> identical hash" requirement, not a human's job to
        # remember to run separately.
        raise BuildFailure(
            f"manifest hash was NOT stable across two in-process builds ({first_hash} != "
            f"{second_hash}) -- the chunker or manifest serialization is non-deterministic"
        )

    return {
        "missionId": mission.mission_id,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "redactionPolicyVersion": REDACTION_POLICY_VERSION,
        "sanitizer": {
            "secretHitCount": hit_count,
            "patternsChecked": [pattern_id for pattern_id, _ in secret_patterns],
        },
        "chunks": [
            {
                "chunkId": c.chunk_id,
                "sha256": c.sha256,
                "lineCount": c.line_count,
                "label": c.label,
                "text": c.text,
            }
            for c in chunks
        ],
        "manifestSha256": first_hash,
        "budgetLines": sum(c.line_count for c in chunks),
        "shown": mission.pack_contents,
        "notShown": mission.excluded,
    }


def verify_second_build_matches(
    mission: Mission,
    repo_root: Path,
    commit_override: str | None,
    extra_paths: list[str],
    chunk_lines: int,
    first_pack: dict,
) -> None:
    """Builds the whole pack a second time from scratch (re-reading files, re-chunking) and asserts
    the manifest hash still matches -- the fuller proof of determinism, beyond the in-process
    double-hash inside build_pack() which only re-serializes the same chunk objects."""
    second_pack = build_pack(mission, repo_root, commit_override, extra_paths, chunk_lines)
    if second_pack["manifestSha256"] != first_pack["manifestSha256"]:
        raise BuildFailure(
            f"a fresh second build of mission {mission.mission_id} produced a different "
            f"manifestSha256 ({second_pack['manifestSha256']} != {first_pack['manifestSha256']}) -- "
            f"the input is not stable (working-tree file changed mid-build?) or the pipeline is "
            f"non-deterministic"
        )


# ADR-0009 D1 (revised 2026-07-27): NVIDIA Build + Google Gemini. Model ids are overridable via env
# var since vendor catalogs move faster than this script; the defaults here are a best-known-good
# starting point, not a guarantee the exact string is still current.
VENDOR_PROFILES = {
    "nvidia": {
        "base_url": "https://integrate.api.nvidia.com/v1/chat/completions",
        "model_env": "NPDEV_EXTERNALAI_NVIDIA_MODEL",
        "default_model": "meta/llama-3.3-70b-instruct",  # confirmed working against this key 2026-07-27
        "api_key_env": "NPDEV_EXTERNALAI_NVIDIA_API_KEY",
        "format": "openai_chat",
    },
    "gemini": {
        "base_url": "https://generativelanguage.googleapis.com/v1beta",
        "model_env": "NPDEV_EXTERNALAI_GEMINI_MODEL",
        "default_model": "gemini-3.5-flash",
        "api_key_env": "NPDEV_EXTERNALAI_GEMINI_API_KEY",
        "format": "gemini_generate_content",
    },
}

REVIEW_PROMPT_TEMPLATE = """You are an independent security reviewer with NO access to any filesystem, repository, network, or tool beyond the text below. Do not assume anything about this codebase beyond what is shown here.

TASK: Conduct an adversarial security review of the code excerpt(s) below. Look specifically for authorization/access-control bypasses, injection vulnerabilities, tenant-isolation failures, or other exploitable security defects. Do not comment on code style, performance, or non-security matters.

CODE EXCERPT(S) (chunk id -> content):
{chunks_text}

RESPONSE FORMAT: Respond with ONLY a single JSON object and nothing else (no markdown fences, no commentary), matching exactly this shape:
{{"recordKind": "external-ai-verdict", "noRepoAccess": true, "autoApplied": false, "model": "<your model identifier>", "findings": [{{"findingId": "F1", "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO", "claim": "<precise, falsifiable description>", "evidenceChunkIds": ["<chunk id(s)>"], "confidence": "LOW|MEDIUM|HIGH"}}]}}
If you find no vulnerabilities, return findings as an empty array. Do not include anything outside the JSON object.
"""


def build_review_prompt(pack: dict) -> str:
    chunks_text = "\n\n".join(
        f"--- chunk {c['chunkId']} ({c['label']}) ---\n{c['text']}"
        for c in pack["chunks"]
    )
    return REVIEW_PROMPT_TEMPLATE.format(chunks_text=chunks_text)


def _http_post_json(url: str, headers: dict[str, str], body: dict) -> dict:
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=480) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"ERROR: vendor HTTP call failed ({exc.code}): {detail[:1000]}")


def _extract_json_object(text: str) -> str:
    """Vendors sometimes wrap JSON in markdown fences or add prose; extract the outermost {...}."""
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = stripped.split("```")[1]
        if stripped.lower().startswith("json"):
            stripped = stripped[4:]
    start = stripped.find("{")
    end = stripped.rfind("}")
    if start == -1 or end == -1:
        raise SystemExit(f"ERROR: no JSON object found in vendor response: {text[:500]}")
    return stripped[start:end + 1]


def _resolve_env(name: str) -> str | None:
    """Reads a config value from this process's own environment first; on Windows, falls back to
    the User-scope registry value directly (winreg, no subprocess) if this process's environment
    doesn't have it. This lets a caller invoke this script as a single, stable command with no
    inline env-var-fetching wrapper needed -- the secret never has to appear on any command line."""
    value = os.environ.get(name)
    if value:
        return value
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
                value, _ = winreg.QueryValueEx(key, name)
                return value
        except OSError:
            return None
    return None


def _call_vendor(vendor_id: str, prompt: str) -> tuple[str, str]:
    """Real network egress -- the actual publish action (honesty rule 6). Prompt-agnostic: callers
    supply the task-specific prompt (a security-review ask for submit_pack_to_vendor, an authoring
    ask for submit_coldstart_pack_to_vendor). Returns (model, raw_response_text)."""
    if vendor_id not in VENDOR_PROFILES:
        raise SystemExit(f"ERROR: unknown vendor '{vendor_id}'; known: {sorted(VENDOR_PROFILES)}")
    profile = VENDOR_PROFILES[vendor_id]
    model = _resolve_env(profile["model_env"]) or profile["default_model"]
    api_key = _resolve_env(profile["api_key_env"])
    if not api_key:
        raise SystemExit(f"ERROR: {profile['api_key_env']} is not set")

    if profile["format"] == "openai_chat":
        response = _http_post_json(
            profile["base_url"],
            {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            {
                "model": model,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.2,
                "top_p": 1,
                "max_tokens": 16384,
                "seed": 0,
                "stream": False,
            },
        )
        raw_text = response["choices"][0]["message"]["content"]
    elif profile["format"] == "gemini_generate_content":
        url = f"{profile['base_url']}/models/{model}:generateContent"
        response = _http_post_json(
            url,
            {"x-goog-api-key": api_key, "Content-Type": "application/json"},
            {"contents": [{"parts": [{"text": prompt}]}]},
        )
        raw_text = response["candidates"][0]["content"]["parts"][0]["text"]
    else:
        raise SystemExit(f"ERROR: unsupported vendor format {profile['format']}")
    return model, raw_text


AUDIT_PROMPT_TEMPLATE = """You are an independent auditor with NO access to any filesystem, repository, network, or tool beyond the text below. Do not assume anything beyond what is shown here.

TASK: The text below is an evidence bundle followed by an explicit list of claims. For each claim, judge whether the evidence actually supports it, partially supports it, or does not support it -- do not simply trust the claim because it is stated confidently. Be specific about what evidence is missing when a claim is not fully supported.

EVIDENCE BUNDLE AND CLAIMS (chunk id -> content):
{chunks_text}

RESPONSE FORMAT: Respond with ONLY a single JSON object and nothing else (no markdown fences, no commentary), matching exactly this shape:
{{"recordKind": "external-ai-verdict", "noRepoAccess": true, "autoApplied": false, "model": "<your model identifier>", "findings": [{{"findingId": "F1", "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO", "claim": "<which numbered claim this is about, and your judgment: supported / partially supported / not supported, and why>", "evidenceChunkIds": ["<chunk id(s)>"], "confidence": "LOW|MEDIUM|HIGH"}}]}}
Produce one finding per numbered claim in the evidence bundle, even if fully supported (severity INFO in that case). Do not include anything outside the JSON object.
"""


def build_audit_prompt(pack: dict) -> str:
    chunks_text = "\n\n".join(
        f"--- chunk {c['chunkId']} ({c['label']}) ---\n{c['text']}"
        for c in pack["chunks"]
    )
    return AUDIT_PROMPT_TEMPLATE.format(chunks_text=chunks_text)


def submit_pack_to_vendor(vendor_id: str, pack: dict, audit: bool = False) -> tuple[str, str, str]:
    """Security-review missions (M0-M3): sends REVIEW_PROMPT_TEMPLATE, expects an
    external-ai-verdict-shaped response. With audit=True (M6), sends AUDIT_PROMPT_TEMPLATE instead --
    same verdict shape (findings), different task, closing E4's AI-review half rather than a security
    finding. Returns (model, raw_response_text, cleaned_verdict_json)."""
    prompt = build_audit_prompt(pack) if audit else build_review_prompt(pack)
    model, raw_text = _call_vendor(vendor_id, prompt)

    cleaned = _extract_json_object(raw_text)
    parsed = json.loads(cleaned)  # fail loudly here if the vendor's JSON is malformed, before ingest_verdict
    # LLM self-identification is unreliable (seen live: gemini-3.5-flash self-reported as "gpt-4o") --
    # the platform knows definitively which model it called, so that always wins over anything the
    # model claimed about itself.
    parsed["model"] = model
    cleaned = json.dumps(parsed)
    return model, raw_text, cleaned


COLDSTART_PROMPT_TEMPLATE = """You are authoring an NPDev app model from documentation alone. You have NO access to the NPDev source code, its schemas, or any tool beyond the two documents below. Do not assume any DSL feature that isn't shown in these docs.

TASK: Build the tutorial app described in TUTORIAL_FIRST_APP.md, following DSL_REFERENCE.md for the exact JSON shape. Author a single, complete model.json for it.

DOCUMENTS (chunk id -> content):
{chunks_text}

RESPONSE FORMAT: Respond with ONLY the model.json content -- a single JSON object -- and nothing else. No markdown fences, no commentary, no explanation before or after.
"""


def build_coldstart_prompt(pack: dict) -> str:
    chunks_text = "\n\n".join(
        f"--- chunk {c['chunkId']} ({c['label']}) ---\n{c['text']}"
        for c in pack["chunks"]
    )
    return COLDSTART_PROMPT_TEMPLATE.format(chunks_text=chunks_text)


def submit_coldstart_pack_to_vendor(vendor_id: str, pack: dict) -> tuple[str, str, str]:
    """M5 cold-start authoring (E3): sends COLDSTART_PROMPT_TEMPLATE, expects back a model.json --
    not a verdict. We (the platform) then run it through the real validator ourselves and relay
    errors verbatim, per the mission's own rule -- this function only gets the authored text back.
    Returns (model, raw_response_text, model_json_text)."""
    prompt = build_coldstart_prompt(pack)
    model, raw_text = _call_vendor(vendor_id, prompt)
    model_json_text = _extract_json_object(raw_text)
    json.loads(model_json_text)  # fail loudly here if the vendor's JSON is malformed
    return model, raw_text, model_json_text


def run_coldstart_validation(model_json_text: str, work_dir: Path) -> dict:
    """Feeds an authored model.json through the platform's own real semantic validator
    (:NPDevContract:dsl:validateModel via the portable CLI) and returns the typed report verbatim --
    'we build and relay errors verbatim' per the mission's own description, not our summary of them."""
    work_dir.mkdir(parents=True, exist_ok=True)
    model_path = work_dir / "coldstart-model.json"
    report_path = work_dir / "coldstart-validation-report.json"
    model_path.write_text(model_json_text, encoding="utf-8")

    cli = PLATFORM_REPO_ROOT / "NPDevCli" / "npdev_cli.py"
    completed = subprocess.run(
        [sys.executable, str(cli), "validate", "model", str(model_path),
         "--semantic", "--report", str(report_path)],
        cwd=PLATFORM_REPO_ROOT, capture_output=True, text=True,
    )
    if not report_path.exists():
        detail = (completed.stderr or completed.stdout or "").strip()
        raise SystemExit(
            f"ERROR: validator did not produce a report (exit {completed.returncode})"
            + (f": {detail[-1000:]}" if detail else "")
        )
    return json.loads(report_path.read_text(encoding="utf-8"))


def record_coldstart_run(
    mission_id: str,
    vendor_id: str,
    model: str,
    pack_manifest_sha256: str,
    validation_report: dict,
) -> dict:
    """Distinct from ingest_verdict: this is NOT a security verdict (recordKind stays its own
    'external-ai-coldstart-run', never 'external-ai-verdict' -- honesty rule 1 only governs verdicts,
    but conflating the two record kinds would be exactly the kind of laundering it exists to prevent)."""
    runs_dir = PLATFORM_REPO_ROOT / "docs" / "external-ai-review" / "runs"
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_file = runs_dir / f"{mission_id}.json"

    existing_vendors: list[str] = []
    if run_file.exists():
        try:
            existing_vendors = json.loads(run_file.read_text(encoding="utf-8")).get("vendors", [])
        except json.JSONDecodeError:
            existing_vendors = []
    vendors = existing_vendors + [vendor_id] if vendor_id not in existing_vendors else existing_vendors

    record = {
        "missionId": mission_id,
        "runStatus": "RUN",
        "packManifestSha256": pack_manifest_sha256,
        "recordKind": "external-ai-coldstart-run",
        "vendors": vendors,
        "vendor": vendor_id,
        "model": model,
        "details": {
            "validationStatus": validation_report.get("status"),
            "validationErrorCount": validation_report.get("summary", {}).get("errors", 0),
        },
        "runAt": datetime.now(timezone.utc).isoformat(),
    }
    run_file.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


COMMAND_PLAN_PROMPT_TEMPLATE = """You are planning a blind reproduction of a software project's setup from a single documentation file. You have NO access to the project's source code, repository, or any tool beyond the text below -- you have never seen this project before.

TASK: Based only on the document below, write the exact sequence of shell commands you would run, in order, to clone the repository, build it, and run its verification/quality gates on a clean Linux machine. Where the document is ambiguous or silent, say so explicitly instead of guessing silently.

DOCUMENT (chunk id -> content):
{chunks_text}

RESPONSE FORMAT: Respond with the command plan as plain text (a numbered or shell-block list of commands), followed by a short section titled "AMBIGUITIES" listing anything the document did not make clear. No JSON needed.
"""


def build_command_plan_prompt(pack: dict) -> str:
    chunks_text = "\n\n".join(
        f"--- chunk {c['chunkId']} ({c['label']}) ---\n{c['text']}"
        for c in pack["chunks"]
    )
    return COMMAND_PLAN_PROMPT_TEMPLATE.format(chunks_text=chunks_text)


def submit_command_plan_to_vendor(vendor_id: str, pack: dict) -> tuple[str, str]:
    """M4 blind reproduction (E2): sends COMMAND_PLAN_PROMPT_TEMPLATE, returns (model, raw_text) --
    freeform text, not JSON. This only gets the PLAN; per the mission's own rule the plan must be
    executed by a clean Linux container or GH runner, never this session -- record_command_plan_run
    marks the mission RUN (a real vendor call happened and was ingested, per external-ai-run.schema.json's
    binary RUN/NOT_RUN design) with a `note` recording that the mission's fuller DoD (actual execution)
    remains outstanding -- that nuance belongs in the plan doc's prose, not as a silent third run-status."""
    prompt = build_command_plan_prompt(pack)
    return _call_vendor(vendor_id, prompt)


def record_command_plan_run(
    mission_id: str,
    vendor_id: str,
    model: str,
    pack_manifest_sha256: str,
    command_plan_path: Path,
) -> dict:
    runs_dir = PLATFORM_REPO_ROOT / "docs" / "external-ai-review" / "runs"
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_file = runs_dir / f"{mission_id}.json"

    existing_vendors: list[str] = []
    if run_file.exists():
        try:
            existing_vendors = json.loads(run_file.read_text(encoding="utf-8")).get("vendors", [])
        except json.JSONDecodeError:
            existing_vendors = []
    vendors = existing_vendors + [vendor_id] if vendor_id not in existing_vendors else existing_vendors

    record = {
        "missionId": mission_id,
        "runStatus": "RUN",
        "note": "command plan obtained from an independent, repo-blind vendor; execution "
                "on a clean Linux container/GH runner has NOT happened -- per the mission's "
                "own rule, that execution must never run in this session",
        "packManifestSha256": pack_manifest_sha256,
        "recordKind": "external-ai-command-plan",
        "vendors": vendors,
        "vendor": vendor_id,
        "model": model,
        "details": {
            "commandPlanPath": str(command_plan_path),
        },
        "runAt": datetime.now(timezone.utc).isoformat(),
    }
    run_file.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


def ingest_verdict(
    mission_id: str,
    vendor_id: str,
    verdict_path: Path,
    pack_manifest_sha256: str | None,
    missions_file: Path = DEFAULT_MISSIONS_FILE,
) -> dict:
    """ADR-0009: validates a pasted-back (or API-returned) verdict carries the three honesty-critical
    fields -- recordKind, noRepoAccess, autoApplied -- and, only if all three pass, writes a RUN
    record to docs/external-ai-review/runs/<mission>.json for the P8 gate's mission-coverage check.
    The single Python-side place this validation lives, so `npdev review ingest` (which shells out
    here) doesn't re-derive it a second time in the CLI itself.

    Also STAMPS the platform-known context fields (missionId, vendor, packManifestSha256, receivedAt,
    shown, notShown) onto the on-disk verdict file, overwriting anything a vendor guessed at them --
    an LLM has no reliable way to know its own ingestion timestamp or this repo's mission bookkeeping,
    so trusting vendor-supplied values for these would be exactly the kind of unverified claim this
    whole feature exists to avoid laundering into the record. Found live: the first real verdict
    (M0-CALIB-LNCH13) was written to disk missing receivedAt entirely because this function only ever
    checked three fields and never stamped/completed the rest."""
    try:
        verdict = json.loads(verdict_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"ERROR: verdict file is not valid JSON: {exc}")

    if verdict.get("recordKind") != "external-ai-verdict":
        raise BuildFailure(
            f"verdict recordKind must be 'external-ai-verdict', got: {verdict.get('recordKind')!r}")
    if verdict.get("noRepoAccess") is not True:
        raise BuildFailure(f"verdict noRepoAccess must be true, got: {verdict.get('noRepoAccess')!r}")
    if verdict.get("autoApplied") is not False:
        raise BuildFailure(f"verdict autoApplied must be false, got: {verdict.get('autoApplied')!r}")

    manifest_sha256 = pack_manifest_sha256 or verdict.get("packManifestSha256")
    if not manifest_sha256:
        raise SystemExit(
            "ERROR: --pack-manifest-sha256 is required (or the verdict file must carry packManifestSha256)")

    verdict["missionId"] = mission_id
    verdict["vendor"] = vendor_id
    verdict["packManifestSha256"] = manifest_sha256
    verdict.setdefault("receivedAt", datetime.now(timezone.utc).isoformat())
    if "shown" not in verdict or "notShown" not in verdict:
        try:
            mission = Mission.load(Path(missions_file), mission_id)
            verdict.setdefault("shown", mission.pack_contents)
            verdict.setdefault("notShown", mission.excluded)
        except SystemExit:
            verdict.setdefault("shown", [])
            verdict.setdefault("notShown", [])
    verdict_path.write_text(json.dumps(verdict, indent=2), encoding="utf-8")

    runs_dir = PLATFORM_REPO_ROOT / "docs" / "external-ai-review" / "runs"
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_file = runs_dir / f"{mission_id}.json"

    # A mission can legitimately be run against more than one vendor (the plan's own multi-vendor
    # mitigation) -- ACCUMULATE vendors across calls rather than overwrite, or a second vendor's run
    # would silently erase the record that a prior vendor ran this same mission at all.
    existing_vendors: list[str] = []
    if run_file.exists():
        try:
            existing_vendors = json.loads(run_file.read_text(encoding="utf-8")).get("vendors", [])
        except json.JSONDecodeError:
            existing_vendors = []
    vendors = existing_vendors + [vendor_id] if vendor_id not in existing_vendors else existing_vendors

    record = {
        "missionId": mission_id,
        "runStatus": "RUN",
        "packManifestSha256": manifest_sha256,
        "verdictRecordKind": "external-ai-verdict",
        "vendors": vendors,
        "runAt": datetime.now(timezone.utc).isoformat(),
    }
    run_file.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


def main(argv: list[str]) -> int:
    # A vendor's own prose (a finding's "claim" text) can contain arbitrary Unicode (arrows, em-dashes,
    # accented characters) that Windows' console default encoding (cp1252) cannot display -- found live
    # when M7's real verdict crashed printing "->" as a literal U+2192 arrow. The verdict/run record are
    # already safely written to disk by this point (UTF-8 throughout); only the console mirror needs
    # this, so reconfigure rather than touch any file-writing path.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--mission-id", required=True)
    parser.add_argument("--missions-file", default=str(DEFAULT_MISSIONS_FILE))
    parser.add_argument("--repo-root", default=".", help="repo root the mission's paths are relative to")
    parser.add_argument("--commit", default=None, help="override the mission's pinned commit")
    parser.add_argument("--paths", nargs="*", default=[], help="additional/override repo-relative paths")
    parser.add_argument("--chunk-lines", type=int, default=DEFAULT_CHUNK_LINES)
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_DIR))
    parser.add_argument(
        "--skip-second-build-check",
        action="store_true",
        help="skip the full re-read-from-disk determinism proof (still runs the in-process double-hash)",
    )
    parser.add_argument(
        "--ingest-verdict-file",
        default=None,
        help="skip pack-building entirely; validate this verdict JSON file and write a RUN record instead",
    )
    parser.add_argument("--vendor-id", default=None, help="required with --ingest-verdict-file")
    parser.add_argument(
        "--pack-manifest-sha256", default=None,
        help="required with --ingest-verdict-file unless the verdict file itself carries packManifestSha256",
    )
    parser.add_argument(
        "--submit-to-vendor",
        default=None,
        metavar="VENDOR_ID",
        help="REAL NETWORK EGRESS: after building the pack, send it to this vendor (nvidia|gemini) "
             "and ingest the response as a verdict. Requires the vendor's API key env var to be set.",
    )
    parser.add_argument(
        "--coldstart",
        action="store_true",
        help="M5-shape mission: --submit-to-vendor sends an authoring prompt (not a review prompt), "
             "then validates the returned model.json with the platform's own real validator instead "
             "of ingesting it as a verdict.",
    )
    parser.add_argument(
        "--command-plan",
        action="store_true",
        help="M4-shape mission: --submit-to-vendor sends a blind-reproduction planning prompt and "
             "records the returned plan as PARTIAL (plan only -- never executes it in this session).",
    )
    parser.add_argument(
        "--audit",
        action="store_true",
        help="M6-shape mission: --submit-to-vendor sends AUDIT_PROMPT_TEMPLATE (claims-vs-evidence "
             "judgment) instead of the security-review prompt; verdict shape/ingestion unchanged.",
    )
    args = parser.parse_args(argv)

    if args.ingest_verdict_file:
        if not args.vendor_id:
            print("ERROR: --vendor-id is required with --ingest-verdict-file", file=sys.stderr)
            return 2
        verdict_path = Path(args.ingest_verdict_file)
        if not verdict_path.exists():
            print(f"ERROR: verdict file not found: {verdict_path}", file=sys.stderr)
            return 2
        record = ingest_verdict(
            args.mission_id, args.vendor_id, verdict_path, args.pack_manifest_sha256,
            Path(args.missions_file))
        print(f"OK: verdict ingested for {args.mission_id}")
        print(f"  vendor:            {args.vendor_id}")
        print(f"  packManifestSha256: {record['packManifestSha256']}")
        print(f"  run record:        docs/external-ai-review/runs/{args.mission_id}.json")
        return 0

    missions_file = Path(args.missions_file)
    if not missions_file.exists():
        print(f"ERROR: missions file not found: {missions_file}", file=sys.stderr)
        return 2
    repo_root = Path(args.repo_root).resolve()

    mission = Mission.load(missions_file, args.mission_id)

    pack = build_pack(mission, repo_root, args.commit, args.paths, args.chunk_lines)

    if not args.skip_second_build_check:
        verify_second_build_matches(mission, repo_root, args.commit, args.paths, args.chunk_lines, pack)

    output_dir = Path(args.output_dir) / mission.mission_id
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{pack['manifestSha256']}.json"
    output_path.write_text(json.dumps(pack, indent=2), encoding="utf-8")

    print(f"OK: pack written for {mission.mission_id}")
    print(f"  output:            {output_path}")
    print(f"  manifestSha256:    {pack['manifestSha256']}")
    print(f"  chunks:            {len(pack['chunks'])}")
    print(f"  budgetLines:       {pack['budgetLines']}")
    print(f"  sanitizer hits:    {pack['sanitizer']['secretHitCount']} (of {len(pack['sanitizer']['patternsChecked'])} patterns checked)")
    print(f"  second-build check: {'skipped' if args.skip_second_build_check else 'passed'}")

    if args.submit_to_vendor and not args.coldstart and not args.command_plan:
        kind = "audit" if args.audit else "security review"
        print(f"\n>>> SUBMITTING ({kind}) to vendor '{args.submit_to_vendor}' (real network egress) <<<")
        model, raw_text, cleaned_verdict = submit_pack_to_vendor(args.submit_to_vendor, pack, audit=args.audit)

        raw_dir = (PLATFORM_REPO_ROOT.parent / "NPDev_General__OutsideRepo" / "external-ai-review"
                   / datetime.now(timezone.utc).strftime("%Y-%m-%d") / "raw")
        raw_dir.mkdir(parents=True, exist_ok=True)
        raw_path = raw_dir / f"{mission.mission_id}-{args.submit_to_vendor}-{pack['manifestSha256'][:12]}.txt"
        raw_path.write_text(raw_text, encoding="utf-8")

        verdict_path = output_dir / f"{pack['manifestSha256']}-{args.submit_to_vendor}-verdict.json"
        verdict_path.write_text(cleaned_verdict, encoding="utf-8")

        record = ingest_verdict(
            mission.mission_id, args.submit_to_vendor, verdict_path, pack["manifestSha256"], missions_file)

        print(f"OK: verdict received and ingested for {mission.mission_id}")
        print(f"  vendor:            {args.submit_to_vendor}")
        print(f"  model:             {model}")
        print(f"  raw transcript:    {raw_path}")
        print(f"  verdict file:      {verdict_path}")
        print(f"  run record:        docs/external-ai-review/runs/{mission.mission_id}.json")
        findings = json.loads(cleaned_verdict).get("findings", [])
        print(f"  findings reported: {len(findings)}")
        for finding in findings:
            print(f"    - [{finding.get('severity')}] {finding.get('claim')}")

    elif args.submit_to_vendor and args.coldstart:
        print(f"\n>>> SUBMITTING (cold-start authoring) to vendor '{args.submit_to_vendor}' (real network egress) <<<")
        model, raw_text, model_json_text = submit_coldstart_pack_to_vendor(args.submit_to_vendor, pack)

        outside_dir = PLATFORM_REPO_ROOT.parent / "NPDev_General__OutsideRepo" / "external-ai-review"
        raw_dir = outside_dir / datetime.now(timezone.utc).strftime("%Y-%m-%d") / "raw"
        raw_dir.mkdir(parents=True, exist_ok=True)
        raw_path = raw_dir / f"{mission.mission_id}-{args.submit_to_vendor}-{pack['manifestSha256'][:12]}.txt"
        raw_path.write_text(raw_text, encoding="utf-8")

        model_json_path = output_dir / f"{pack['manifestSha256']}-{args.submit_to_vendor}-authored-model.json"
        model_json_path.write_text(model_json_text, encoding="utf-8")

        validation_report = run_coldstart_validation(
            model_json_text, outside_dir / "coldstart-work" / mission.mission_id / args.submit_to_vendor)
        validation_report_path = output_dir / f"{pack['manifestSha256']}-{args.submit_to_vendor}-validation-report.json"
        validation_report_path.write_text(json.dumps(validation_report, indent=2), encoding="utf-8")

        record = record_coldstart_run(
            mission.mission_id, args.submit_to_vendor, model, pack["manifestSha256"], validation_report)

        print(f"OK: cold-start run recorded for {mission.mission_id}")
        print(f"  vendor:              {args.submit_to_vendor}")
        print(f"  model:               {model}")
        print(f"  raw transcript:      {raw_path}")
        print(f"  authored model.json: {model_json_path}")
        print(f"  validation report:   {validation_report_path}")
        print(f"  validation status:   {validation_report.get('status')}")
        for diag in (validation_report.get("diagnostics") or []):
            print(f"    - [{diag.get('severity')}] {diag.get('code')}: {diag.get('message')}")
        print(f"  run record:          docs/external-ai-review/runs/{mission.mission_id}.json")

    elif args.submit_to_vendor and args.command_plan:
        print(f"\n>>> SUBMITTING (command-plan request) to vendor '{args.submit_to_vendor}' (real network egress) <<<")
        model, raw_text = submit_command_plan_to_vendor(args.submit_to_vendor, pack)

        raw_dir = (PLATFORM_REPO_ROOT.parent / "NPDev_General__OutsideRepo" / "external-ai-review"
                   / datetime.now(timezone.utc).strftime("%Y-%m-%d") / "raw")
        raw_dir.mkdir(parents=True, exist_ok=True)
        raw_path = raw_dir / f"{mission.mission_id}-{args.submit_to_vendor}-{pack['manifestSha256'][:12]}.txt"
        raw_path.write_text(raw_text, encoding="utf-8")

        command_plan_path = output_dir / f"{pack['manifestSha256']}-{args.submit_to_vendor}-command-plan.txt"
        command_plan_path.write_text(raw_text, encoding="utf-8")

        record = record_command_plan_run(
            mission.mission_id, args.submit_to_vendor, model, pack["manifestSha256"], command_plan_path)

        print(f"OK: command plan recorded for {mission.mission_id} (PARTIAL -- not yet executed)")
        print(f"  vendor:            {args.submit_to_vendor}")
        print(f"  model:             {model}")
        print(f"  raw transcript:    {raw_path}")
        print(f"  command plan file: {command_plan_path}")
        print(f"  run record:        docs/external-ai-review/runs/{mission.mission_id}.json (runStatus: PARTIAL)")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
