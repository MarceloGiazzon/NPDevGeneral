#!/usr/bin/env python3
r"""S5 (NPDEV_MEGA_ROADMAP.md): publish the built-in packs into a versioned pack repository.

The remote-consumption MACHINERY already exists and is live-verified (`PackCoordinate` +
`RemotePackFetcher.fetch`, git and OCI substrates, `PackCache`, `npdev.lock` pinning). What S5 adds
on top is the repo itself: a git repository where every built-in pack is committed under a semver
TAG (`<packId>-<version>`), so a generated app can declare

    "packs": [ { "from": "git+file:///D:/WorkSpace/NPDev/Build/npdev-pack-repo@identity-1.2.0", "as": "identity" } ]

and the real fetch->cache->lock pipeline resolves it with zero network I/O. Point the same script's
--repo-url at a real remote (git+https://...) and the identical command publishes there instead.

Usage:
    python publish-pack-repo.py                # publish to the default local repo (Build/npdev-pack-repo)
    python publish-pack-repo.py --repo <dir>   # publish to a chosen directory
    python publish-pack-repo.py --packs identity tracing workspace

Idempotent: re-running publishes only changed packs; a pack whose version already has a tag is
skipped unless --force. Tags are the pin a consumer locks to, so re-tagging a version is refused.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKS_SOURCE = REPO_ROOT / "NPDevContract" / "packs"
DEFAULT_REPO = REPO_ROOT.parent / "Build" / "npdev-pack-repo"


def _git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True,
                   capture_output=True, text=True)


def _git_quiet(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], check=False,
                            capture_output=True, text=True)
    return result.stdout.strip()


def publish(packs: list[str], repo: Path, force: bool = False) -> list[str]:
    """Copies the selected packs from NPDevContract/packs into {repo}/packs/<id>/pack.json, commits
    each changed pack under its own path and tags the version. Returns the published coordinates
    ('<packId>-<version>') so a consumer can see exactly what to import."""
    repo.mkdir(parents=True, exist_ok=True)
    if not (repo / ".git").is_dir():
        _git(repo, "init", "-b", "main")
        _git(repo, "config", "user.email", "packs@npdev.local")
        _git(repo, "config", "user.name", "NPDev Pack Publisher")

    published = []
    for pack_id in packs:
        source = PACKS_SOURCE / pack_id / "pack.json"
        if not source.is_file():
            raise SystemExit(f"no such built-in pack: {pack_id} (expected {source})")
        doc = json.loads(source.read_text(encoding="utf-8"))
        version = doc.get("version")
        if not version:
            raise SystemExit(f"{pack_id}/pack.json has no 'version' field")
        tag = f"{pack_id}-{version}"

        if _git_quiet(repo, "tag", "-l", tag) and not force:
            print(f"  skip  {pack_id}@{version} (tag {tag} already exists)")
            continue

        dest = repo / "packs" / pack_id
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest / "pack.json")

        # Multi-file packs (a pack.json referencing sibling files via $ref) copy their whole tree.
        for sibling in (PACKS_SOURCE / pack_id).iterdir():
            if sibling.is_file() and sibling != source:
                shutil.copy2(sibling, dest / sibling.name)

        _git(repo, "add", "-A")
        changed = _git_quiet(repo, "status", "--porcelain")
        if changed:
            _git(repo, "commit", "-m", f"publish {pack_id} {version}")
        if force or not _git_quiet(repo, "tag", "-l", tag):
            _git(repo, "tag", tag)
        print(f"  ok    {pack_id}@{version} -> {tag}")
        published.append(tag)
    return published


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=str(DEFAULT_REPO), help="target pack repository directory")
    ap.add_argument("--packs", nargs="*", default=["identity", "workspace", "tracing"],
                    help="built-in pack ids to publish")
    ap.add_argument("--force", action="store_true", help="re-tag an already-published version")
    args = ap.parse_args(argv[1:])

    repo = Path(args.repo).expanduser().resolve()
    print(f"Publishing {len(args.packs)} pack(s) to {repo}")
    published = publish(args.packs, repo, args.force)
    if not published:
        print("Nothing to publish -- every selected pack is already tagged. Use --force to re-tag.")
    else:
        print("Published coordinates (git+file:/// form for a consumer's packs[].from):")
        repo_path = repo.as_posix().lstrip("/")  # matches Path.toUri(): file:///D:/... or file:///home/...
        for tag in sorted(published):
            print(f"  git+file:///{repo_path}@{tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))