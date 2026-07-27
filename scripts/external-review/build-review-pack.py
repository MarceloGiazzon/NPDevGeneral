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
import re
import subprocess
import sys
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


def manifest_bytes(mission: Mission, source: dict, chunks: list[Chunk]) -> bytes:
    manifest_input = {
        "missionId": mission.mission_id,
        "source": source,
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

    source = {
        "kind": "platform-git",
        "repo": "NPDev_General",
        "commit": commit if commit else "WORKING_TREE",
    }

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


def ingest_verdict(mission_id: str, vendor_id: str, verdict_path: Path, pack_manifest_sha256: str | None) -> dict:
    """ADR-0009: validates a pasted-back (or API-returned) verdict carries the three honesty-critical
    fields -- recordKind, noRepoAccess, autoApplied -- and, only if all three pass, writes a RUN
    record to docs/external-ai-review/runs/<mission>.json for the P8 gate's mission-coverage check.
    The single Python-side place this validation lives, so `npdev review ingest` (which shells out
    here) doesn't re-derive it a second time in the CLI itself."""
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

    runs_dir = PLATFORM_REPO_ROOT / "docs" / "external-ai-review" / "runs"
    runs_dir.mkdir(parents=True, exist_ok=True)
    record = {
        "missionId": mission_id,
        "runStatus": "RUN",
        "packManifestSha256": manifest_sha256,
        "verdictRecordKind": "external-ai-verdict",
        "vendors": [vendor_id],
        "runAt": datetime.now(timezone.utc).isoformat(),
    }
    run_file = runs_dir / f"{mission_id}.json"
    run_file.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


def main(argv: list[str]) -> int:
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
    args = parser.parse_args(argv)

    if args.ingest_verdict_file:
        if not args.vendor_id:
            print("ERROR: --vendor-id is required with --ingest-verdict-file", file=sys.stderr)
            return 2
        verdict_path = Path(args.ingest_verdict_file)
        if not verdict_path.exists():
            print(f"ERROR: verdict file not found: {verdict_path}", file=sys.stderr)
            return 2
        record = ingest_verdict(args.mission_id, args.vendor_id, verdict_path, args.pack_manifest_sha256)
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
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
