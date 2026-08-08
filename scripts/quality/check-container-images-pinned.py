#!/usr/bin/env python3
"""Container-image pinning gate: the conformance suite must run on digests, not moving tags.

WHY THIS EXISTS
---------------
storage/FULL_SUPPORT_PLAN.md W1.1 puts pinning BEFORE promoting the conformance workflow to a push
trigger, for one reason:

    a push-blocking gate on a moving tag cannot distinguish "we broke it" from "the image changed",
    and a gate people cannot trust is a gate they re-run instead of read.

So `mysql:8.4` became `mysql:8.4@sha256:...`. That pin is load-bearing, and a load-bearing string
that nothing checks is one careless edit away from being a tag again -- silently, because a moving
tag works perfectly right up until the day it does not.

It also enforces a TWIN PAIR. The digest lives in two places on purpose:

    NPDevKernel/.../DialectTestSupport.java   CONTAINER_IMAGES -- what actually starts the container
    .github/workflows/storage-dialect-conformance.yml   PINNED_* -- what the RUN LOG shows, and what
                                                        the pre-pull step verifies still resolves

The second is not decoration: F5 in the previous plan was an observability fix that produced nothing
because Gradle does not forward a forked test JVM's stdout. Printing the image from the workflow
itself is the fix that cannot be swallowed. But two copies of a digest is exactly the "one place
updated, its twin forgotten" family this repo has hit four times (REG-89, REG-104, REG-112,
REG-144) -- so they are checked against each other rather than trusted.

USAGE
    python scripts/quality/check-container-images-pinned.py
    python scripts/quality/check-container-images-pinned.py --json
    python scripts/quality/check-container-images-pinned.py --resolve   # quarterly refresh helper

`--resolve` reaches the registries and prints the CURRENT digest for each pinned tag. It never
edits anything and never fails the gate on a mismatch -- an image moving is not a defect, it is the
signal to review and re-pin deliberately. (It is also the only mode that needs network, which is why
it is not what the gate runs.)

Exit 0 = every image pinned and both copies agree. Exit 1 = at least one problem. Exit 2 = usage.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

JAVA_SOURCE = (
    "NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectTestSupport.java"
)
WORKFLOW = ".github/workflows/storage-dialect-conformance.yml"

DIGEST_RE = re.compile(r"@sha256:([0-9a-f]{64})$")

# Which PINNED_* env key corresponds to which dialect key in the Java map. Spelled out rather than
# derived from the name, so a renamed dialect fails here instead of silently dropping out of the
# comparison -- "the check quietly stopped covering it" is the same defect class as the one being
# guarded.
ENV_KEY_FOR_DIALECT = {
    "mysql": "PINNED_MYSQL",
    "postgres": "PINNED_POSTGRES",
    "sqlserver": "PINNED_SQLSERVER",
}


def _repo_root(explicit: str | None) -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144).

    Eleven resolvers keyed on the name `NPDev_General` produced THREE different build roots in a
    clone named `NPDevGeneral` and kept CI red for twelve days. This walk tests for the module
    directories instead, which is the predicate WorkspaceRootLocator.java already established.
    """
    if explicit:
        return Path(explicit).resolve()
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if all((candidate / m).is_dir()
               for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit(
        "could not identify the repo root by contents (a directory holding NPDevContract + "
        "NPDevGenerator + NPDevKernel). Pass --repo."
    )


def parse_java_map(text: str, declaration: str) -> dict[str, str]:
    """Read a `X = Map.of(...)` declaration out of the Java source.

    Deliberately reads the DECLARATION rather than running the code: this gate must work with no
    Gradle, no JVM and no compiled classes, because the knowledge gate runs before any build.
    """
    start = text.find(declaration + " = Map.of(")
    if start < 0:
        raise SystemExit(f"{JAVA_SOURCE}: {declaration} = Map.of( not found -- the map moved or "
                         "was renamed, and this checker is now looking at nothing")
    end = text.find(");", start)
    if end < 0:
        raise SystemExit(f"{JAVA_SOURCE}: {declaration} declaration is not terminated")
    body = text[start:end]
    # Java string concatenation across lines ("a" + "b") is joined before the literals are read, so
    # the sqlserver entry -- which is too long for one line -- is seen as one value.
    body = re.sub(r'"\s*\+\s*"', "", body)
    literals = re.findall(r'"([^"]*)"', body)
    if len(literals) < 2 or len(literals) % 2 != 0:
        raise SystemExit(f"{JAVA_SOURCE}: {declaration} did not parse as key/value pairs "
                         f"(got {len(literals)} string literals)")
    return {literals[i]: literals[i + 1] for i in range(0, len(literals), 2)}


def parse_workflow_images(text: str) -> dict[str, str]:
    """Read the PINNED_* env block out of the workflow."""
    found = {}
    for key in ENV_KEY_FOR_DIALECT.values():
        match = re.search(rf"^\s*{key}:\s*'([^']+)'\s*$", text, re.MULTILINE)
        if match:
            found[key] = match.group(1)
    return found


def check(root: Path) -> list[dict]:
    problems: list[dict] = []

    java_path = root / JAVA_SOURCE
    workflow_path = root / WORKFLOW
    for path in (java_path, workflow_path):
        if not path.exists():
            problems.append({
                "kind": "missing-file",
                "file": str(path.relative_to(root)),
                "message": "the checker's own input is gone -- it cannot be passing",
            })
    if problems:
        return problems

    java_text = java_path.read_text(encoding="utf-8")
    java_images = parse_java_map(java_text, "CONTAINER_IMAGES")
    java_tags = parse_java_map(java_text, "CONTAINER_IMAGE_TAGS")
    workflow_images = parse_workflow_images(workflow_path.read_text(encoding="utf-8"))

    if java_images.keys() != java_tags.keys():
        problems.append({
            "kind": "missing-tag",
            "file": JAVA_SOURCE,
            "message": (
                "CONTAINER_IMAGES and CONTAINER_IMAGE_TAGS cover different dialects "
                f"({sorted(java_images)} vs {sorted(java_tags)}). A digest with no recorded tag "
                "cannot be re-resolved without archaeology, which is how a quarterly refresh "
                "becomes a permanent pin."
            ),
        })

    for dialect, image in sorted(java_images.items()):
        match = DIGEST_RE.search(image)
        if not match:
            problems.append({
                "kind": "unpinned",
                "file": JAVA_SOURCE,
                "dialect": dialect,
                "image": image,
                "message": (
                    f"'{dialect}' is pinned to '{image}', which is a MOVING TAG. A conformance gate "
                    f"on a moving tag cannot tell a regression from an upstream image change. "
                    f"Re-resolve with --resolve and pin the digest."
                ),
            })
            continue

        env_key = ENV_KEY_FOR_DIALECT.get(dialect)
        if env_key is None:
            problems.append({
                "kind": "unmapped-dialect",
                "file": WORKFLOW,
                "dialect": dialect,
                "message": (
                    f"'{dialect}' has a pinned image in Java but no PINNED_* env key here. Add one "
                    f"to the workflow AND to ENV_KEY_FOR_DIALECT, or the run log stops showing the "
                    f"digest for an engine that is actually running."
                ),
            })
            continue

        if env_key not in workflow_images:
            problems.append({
                "kind": "missing-env",
                "file": WORKFLOW,
                "dialect": dialect,
                "message": f"{env_key} is not declared in the workflow's env block",
            })
        elif workflow_images[env_key] != image:
            problems.append({
                "kind": "twin-divergence",
                "file": WORKFLOW,
                "dialect": dialect,
                "message": (
                    f"the two pinned copies for '{dialect}' disagree:\n"
                    f"    {JAVA_SOURCE}: {image}\n"
                    f"    {WORKFLOW} ({env_key}): {workflow_images[env_key]}\n"
                    f"The Java map is what starts the container; the workflow value is what the run "
                    f"log claims it started. Make them equal."
                ),
            })

    for env_key, image in sorted(workflow_images.items()):
        if not DIGEST_RE.search(image):
            problems.append({
                "kind": "unpinned",
                "file": WORKFLOW,
                "dialect": env_key,
                "image": image,
                "message": f"{env_key} is a moving tag: {image}",
            })

    return problems


def resolve(root: Path) -> int:
    """Print the CURRENT digest for each pinned tag, straight from the registry.

    The quarterly refresh helper. Needs network; the gate never calls it.

    Reads the tags out of CONTAINER_IMAGE_TAGS rather than carrying its own list, so the refresh path
    cannot resolve a different image than the one that was pinned -- which is the failure mode that
    makes a "refreshed" digest worse than a stale one.
    """
    import urllib.request

    # THE ACCEPT HEADER IS PART OF THE ANSWER, and getting it wrong pins an unpullable digest.
    #
    # Measured 2026-08-08 against mcr.microsoft.com/mssql/server:2022-latest. Asking for only the
    # multi-arch types (manifest.list + oci.index) made MCR fall back to a schema-1
    # `prettyjws` manifest and return ITS digest -- sha256:0730f368..., a perfectly real digest that
    # modern Docker and containerd refuse to pull at all. Asking with Docker's own list produced
    # sha256:ba4c8329..., the v2 manifest, which is what a `docker pull` actually resolves.
    #
    # The two digests differ by nothing a human would notice, and the wrong one fails only in CI, at
    # pull time. So this mirrors Docker's real Accept order rather than a tidier subset, and the
    # content type is printed so a future schema-1 fallback is visible instead of inferred.
    accept = ("application/vnd.docker.distribution.manifest.v2+json,"
              "application/vnd.oci.image.manifest.v1+json,"
              "application/vnd.docker.distribution.manifest.list.v2+json,"
              "application/vnd.oci.image.index.v1+json")
    tags = parse_java_map((root / JAVA_SOURCE).read_text(encoding="utf-8"), "CONTAINER_IMAGE_TAGS")
    pinned = parse_java_map((root / JAVA_SOURCE).read_text(encoding="utf-8"), "CONTAINER_IMAGES")

    for dialect, tag in sorted(tags.items()):
        repository, _, version = tag.rpartition(":")
        if "/" in repository and "." in repository.split("/")[0]:
            # A registry-qualified name (mcr.microsoft.com/mssql/server): its own registry, no token.
            registry = repository.split("/")[0]
            path = repository.split("/", 1)[1]
            url = f"https://{registry}/v2/{path}/manifests/{version}"
            headers = {"Accept": accept}
        else:
            # Docker Hub: official images live under library/, and every read needs a pull token.
            path = repository if "/" in repository else f"library/{repository}"
            url = f"https://registry-1.docker.io/v2/{path}/manifests/{version}"
            headers = {"Accept": accept}
            token_url = ("https://auth.docker.io/token?service=registry.docker.io"
                         f"&scope=repository:{path}:pull")
            with urllib.request.urlopen(token_url, timeout=30) as response:
                headers["Authorization"] = "Bearer " + json.load(response)["token"]
        try:
            request = urllib.request.Request(url, method="HEAD", headers=headers)
            with urllib.request.urlopen(request, timeout=30) as response:
                current = response.headers.get("Docker-Content-Digest", "(none)")
                content_type = response.headers.get("Content-Type", "(none)")
        except Exception as exc:  # noqa: BLE001 - a refresh helper reports, it does not decide
            print(f"{dialect:10} {tag:45} could not resolve: {exc}")
            continue
        pinned_digest = "sha256:" + (DIGEST_RE.search(pinned.get(dialect, "")).group(1)
                                     if DIGEST_RE.search(pinned.get(dialect, "")) else "?")
        state = "unchanged" if current == pinned_digest else "MOVED"
        print(f"{dialect:10} {tag:45} {current}  [{state}]")
        if "manifest.v1" in content_type or "prettyjws" in content_type:
            print(f"{'':10} {'':45} !! schema-1 manifest ({content_type}) -- DO NOT PIN THIS DIGEST; "
                  "modern Docker cannot pull it")
        else:
            print(f"{'':10} {'':45}   {content_type}")

    print()
    print("An image having MOVED is not a defect -- it is the signal to review and re-pin")
    print("deliberately. Update BOTH DialectTestSupport.CONTAINER_IMAGES and the workflow's PINNED_*")
    print("env block, then re-run this checker without --resolve.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=None, help="Repo root (default: found by contents).")
    parser.add_argument("--json", action="store_true", help="Emit findings as JSON.")
    parser.add_argument("--resolve", action="store_true",
                        help="Print each pinned tag's CURRENT registry digest (needs network).")
    args = parser.parse_args(argv)

    root = _repo_root(args.repo)
    if args.resolve:
        return resolve(root)

    problems = check(root)

    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-container-image-pinning-report.v1",
            "ok": not problems,
            "problems": problems,
        }, indent=2))
        return 1 if problems else 0

    if not problems:
        print("check-container-images-pinned: OK -- every conformance image is digest-pinned, and "
              "the Java map and the workflow env block agree.")
        return 0

    print(f"check-container-images-pinned: {len(problems)} problem(s)")
    for problem in problems:
        print(f"\n  [{problem['kind']}] {problem['file']}")
        for line in problem["message"].splitlines():
            print(f"    {line}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
