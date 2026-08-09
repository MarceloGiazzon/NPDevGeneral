"""S3 -- the release-candidate gate: the one command that PRODUCES a handover.

storage/stabilize/STABILIZE_PLAN.md. `npdev verify --tier T3` already exists and is the declared
home for the release ceremony, but today it *evaluates* evidence and, by default, does not *produce*
it (`run-beta-release-gate.ps1` says so in its own header). This is the producing form, run once,
deliberately, against ONE sha.

WHY A MANIFEST AT ALL
---------------------
A second machine is about to be handed this state. When something breaks there, the first question
is "did we ever check this?" -- and a record that lists only successes cannot answer it. So
`notVerified` is REQUIRED and must be non-empty (see stability-manifest.schema.json): there is always
something that was not checked, and the failure mode this guards against is spending an afternoon
rediscovering that nobody had.

WHAT MUST NOT BE FAKED
----------------------
Step 4. Local greens are INPUTS; CI run ids are the EVIDENCE. This project has already watched three
fixes look green locally and do nothing, and watched `NPDev CI Validation` stay red for twelve days
while local T2 was green throughout. So the CI step resolves real runs AT THE EXACT SHA and records
their ids, and a run that is merely "recent on main" does not count.
"""
from __future__ import annotations

import json
import subprocess
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = "npdev-stability-manifest.v1"

# The three CI workflows whose green at the RC's sha is the release claim. Named by workflow FILE,
# not by display name: a display name is prose and can be edited without anyone noticing that a
# lookup now silently matches nothing.
REQUIRED_WORKFLOWS = {
    "main-ci": "npdev-ci-validation.yml",
    "engine-support": "engine-support.yml",
    "conformance": "storage-dialect-conformance.yml",
}


class StepFailed(Exception):
    """A gate step that did not pass. Carries the sentence a human should read."""

    def __init__(self, step: str, detail: str):
        super().__init__(f"{step}: {detail}")
        self.step = step
        self.detail = detail


def _git(root: Path, *args: str, strip: bool = True) -> str:
    """`strip=False` for `status --porcelain`, and that is not a detail.

    Porcelain v1 is a FIXED-WIDTH format: two status characters, a space, then the path. An
    unstaged modification is " M path", whose first character is a space -- so stripping the whole
    output eats it and every subsequent offset is wrong by one. This printed `LAUDE.md` for
    `CLAUDE.md` the first time it ran, which is exactly the sort of quiet off-by-one that makes a
    gate's own output untrustworthy at the moment someone needs to act on it.
    """
    completed = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise StepFailed("git", f"`git {' '.join(args)}` failed: {completed.stderr.strip()}")
    return completed.stdout.strip() if strip else completed.stdout


# ------------------------------------------------------------------------------------------------
# Step 1 -- the tree
# ------------------------------------------------------------------------------------------------

def check_tree_state(root: Path, tag: str) -> dict:
    """A manifest may only be emitted from a clean, pushed tree, and it describes the TAG.

    Not fussiness: a manifest describes a state someone else must be able to OBTAIN. A dirty tree
    describes a state that exists on exactly one disk, and an unpushed HEAD describes a sha the
    second machine cannot fetch -- in both cases every other line in the file is a claim about
    something the reader cannot get hold of.

    The subject is the TAG's sha, not HEAD's. Those differ the moment a tooling-only commit lands
    after tagging -- which is normal, because the thing that CERTIFIES a release does not have to
    live inside it. Recording HEAD's sha alongside when they differ keeps that visible instead of
    letting a reader assume the two were identical: the artifacts and the CI evidence are the tag's,
    and the local gates ran at HEAD.
    """
    status = _git(root, "status", "--porcelain", strip=False)
    lines = [line for line in status.splitlines() if line.strip()]
    untracked = [line[3:] for line in lines if line.startswith("??")]
    dirty = [line[3:] for line in lines if not line.startswith("??")]
    if dirty:
        raise StepFailed("tree-clean", f"uncommitted changes in {len(dirty)} file(s): {', '.join(dirty[:5])}")
    if untracked:
        raise StepFailed("tree-clean", f"untracked file(s): {', '.join(untracked[:5])}")

    head = _git(root, "rev-parse", "HEAD")
    branch = _git(root, "rev-parse", "--abbrev-ref", "HEAD")
    # Is this exact commit on the remote? `git branch -r --contains` answers "can the other machine
    # fetch this sha", which is the real question -- not "is my branch pointer up to date".
    contains = _git(root, "branch", "-r", "--contains", head)
    if not contains.strip():
        raise StepFailed("head-pushed", f"HEAD ({head[:8]}) is on no remote branch -- push it first")

    # `^{}` dereferences an ANNOTATED tag to the commit it points at. Without it this records the
    # tag OBJECT's sha, which is not a commit and which nothing else in the manifest refers to.
    tag_sha = _git(root, "rev-list", "-n", "1", f"{tag}^{{}}")
    if not tag_sha:
        raise StepFailed("tag", f"tag {tag} does not resolve to a commit -- create and push it first")

    state = {"clean": True, "pushed": True, "untrackedFiles": [], "sha": tag_sha, "branch": branch}
    if tag_sha != head:
        ahead = _git(root, "rev-list", "--count", f"{tag_sha}..{head}")
        state["headSha"] = head
        state["headCommitsAheadOfTag"] = int(ahead or 0)
    return state


# ------------------------------------------------------------------------------------------------
# Step 4 -- CI at this exact sha. The step that must not be faked.
# ------------------------------------------------------------------------------------------------

def _github_json(url: str, token: str | None) -> dict | None:
    headers = {"User-Agent": "npdev-cli", "Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError):
        return None


def check_ci_at_sha(repo: str, sha: str, token: str | None) -> list[dict]:
    """Resolve each required workflow's run AT THIS SHA, and record its run id.

    Deliberately queries by `head_sha`. Asking "is main green?" would pass on a green run of a
    DIFFERENT commit -- which is precisely the shape of evidence that let a red sit unnoticed for
    twelve days. A workflow with no run at this sha is a failure, not an omission: it means the
    claim was never tested here, and saying nothing would let the manifest imply it was.
    """
    verified: list[dict] = []
    for check_id, workflow_file in REQUIRED_WORKFLOWS.items():
        url = (f"https://api.github.com/repos/{repo}/actions/workflows/{workflow_file}"
               f"/runs?head_sha={sha}&per_page=10")
        data = _github_json(url, token)
        if data is None:
            raise StepFailed(check_id, "could not reach the GitHub API -- CI evidence cannot be faked "
                                       "or assumed, so this gate stops rather than recording a guess")
        runs = data.get("workflow_runs", [])
        if not runs:
            raise StepFailed(check_id, f"no run of {workflow_file} exists at {sha[:8]}. Push the sha and "
                                       f"let CI run, or trigger it -- a green run of another commit is not "
                                       f"evidence about this one")
        # Newest first is the API's own order; take the newest COMPLETED run.
        completed = [r for r in runs if r.get("status") == "completed"]
        if not completed:
            raise StepFailed(check_id, f"{workflow_file} is still running at {sha[:8]} -- wait for it")
        latest = completed[0]
        if latest.get("conclusion") != "success":
            raise StepFailed(
                check_id,
                f"{workflow_file} at {sha[:8]} concluded '{latest.get('conclusion')}': {latest.get('html_url')}")
        verified.append({
            "id": check_id,
            "result": "pass",
            "evidence": str(latest.get("id")),
            "note": f"{workflow_file} at {sha[:8]} -- {latest.get('html_url')}",
        })
    return verified


# ------------------------------------------------------------------------------------------------
# Step 6 -- the artifacts
# ------------------------------------------------------------------------------------------------

def collect_artifacts(repo: str, tag: str, token: str | None, launched: dict[str, bool]) -> list[dict]:
    """The published assets for `tag`, with the sha256 GitHub itself reports via its digest field.

    `launched` is passed in rather than inferred, and defaults to False everywhere, because nothing
    in an API response can tell you whether a human ever STARTED the thing. Publishing is not
    running: an AppImage that exists and has never been launched is a download, not an installer,
    and the manifest has a field for that distinction precisely so it cannot be glossed over.
    """
    data = _github_json(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", token)
    if data is None:
        raise StepFailed("artifacts", f"could not read the release for tag {tag}")
    assets = data.get("assets", [])
    if not assets:
        raise StepFailed("artifacts", f"the release for {tag} has no assets")

    out: list[dict] = []
    for asset in assets:
        name = asset.get("name", "")
        if name.endswith("SHA256SUMS"):
            continue
        digest = (asset.get("digest") or "")
        sha256 = digest.split(":", 1)[1] if digest.startswith("sha256:") else ""
        platform = "windows" if name.endswith(".exe") else "linux" if name.endswith(".AppImage") else "any"
        out.append({
            "name": name,
            "sha256": sha256,
            "sizeBytes": asset.get("size"),
            # Filled by the caller from the CI run that produced the release; an artifact built
            # locally and uploaded by hand is not reproducible and the schema requires saying so.
            "builtBy": "",
            "platform": platform,
            "launched": bool(launched.get(name, False)),
        })
    return out


# ------------------------------------------------------------------------------------------------
# The manifest
# ------------------------------------------------------------------------------------------------

def build_manifest(*, sha: str, tag: str, tree: dict, verified: list[dict],
                   not_verified: list[dict], artifacts: list[dict],
                   known_limitations: list[dict], open_items: list[dict]) -> dict:
    if not not_verified:
        # Enforced here as well as in the schema. An empty list is never true, and a manifest that
        # claims it is the exact artifact this whole exercise exists to avoid producing.
        raise StepFailed("manifest", "notVerified is empty. There is always something that was not "
                                     "checked; an empty list is a false claim, not a clean bill")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "sha": sha,
        "tag": tag,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "treeState": {"clean": tree["clean"], "pushed": tree["pushed"], "untrackedFiles": tree["untrackedFiles"]},
        "verified": verified,
        "notVerified": not_verified,
        "artifacts": artifacts,
        "knownLimitations": known_limitations,
        "openItems": open_items,
    }


def open_items_at_head(root: Path) -> list[dict]:
    """Ledger state at this sha, so a reader can tell an accepted backlog from a surprise."""
    items: list[dict] = []
    ledger = root / "ledger" / "items"
    if not ledger.is_dir():
        return items
    for path in sorted(ledger.glob("*.yml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        status = severity = title = ""
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith("status:") and not status:
                status = stripped.split(":", 1)[1].strip().strip("\"'")
            elif stripped.startswith("severity:") and not severity:
                severity = stripped.split(":", 1)[1].strip().strip("\"'")
            elif stripped.startswith("title:") and not title:
                title = stripped.split(":", 1)[1].strip().strip("\"'")
        if status.upper() == "OPEN":
            items.append({
                "id": path.stem,
                "severity": severity.upper() if severity.upper() in ("LOW", "MEDIUM", "HIGH", "CRITICAL") else "MEDIUM",
                "title": title or path.stem,
            })
    return items


def not_verified_entries(artifacts: list[dict]) -> list[dict]:
    """The most valuable field in the manifest, and the one a generator is tempted to leave empty.

    Everything here is something a reader could REASONABLY assume was checked. Written as a
    function, not a constant, so the AppImage entry can tell the truth about whether anyone actually
    launched the artifact in THIS run rather than repeating a sentence that was true once.
    """
    entries = [
        {
            "what": "any machine other than the one that produced this manifest",
            "why": "Every local step above ran on the author's machine, which has the Gradle caches, "
                   "the JDBC drivers, the containers and the directory layout that a fresh machine "
                   "does not. Six prior defects in this project were of exactly that shape.",
            "wouldBeFoundBy": "Installing from these artifacts on a machine that has never built NPDev.",
        },
        {
            "what": "a clean-VM install of the Windows installer",
            "why": "The container harness proves the CLI's instructions on a bare Linux image; the "
                   "Windows installer's own first-run path has no equivalent automated proof.",
            "wouldBeFoundBy": "Running the .exe on a fresh Windows VM with no Java and no Python.",
        },
        {
            "what": "the Manager's database toolbox driven through the WINDOW",
            "why": "M13/M14/M15 are proven at the CLI layer against real Postgres and MySQL, and the "
                   "Manager compiles and its UI ids resolve -- but no automated harness clicks the "
                   "buttons in a running Tauri window.",
            "wouldBeFoundBy": "Opening the Manager, picking an app folder, and pressing Start.",
        },
    ]
    appimage = next((a for a in artifacts if a["name"].endswith(".AppImage")), None)
    if appimage is not None and not appimage.get("launched"):
        entries.append({
            "what": f"{appimage['name']} has never been LAUNCHED",
            "why": "Publishing is not running. Pass --launched with the asset name once someone has "
                   "actually started it; nothing in an API response can tell you that.",
            "wouldBeFoundBy": "Running it.",
        })
    else:
        # Recorded even when `launched` is true, because "it started" and "it works on a real
        # desktop" are different claims and the gap between them is where the next defect lives.
        entries.append({
            "what": "the AppImage on a real Linux DESKTOP -- real GPU, real display server, a distro "
                    "other than Ubuntu 22.04",
            "why": "It has been started under a virtual display in a container with a curated library "
                   "set. That proves the bundle is self-contained (it carries its own webkit2gtk) but "
                   "says nothing about a real desktop session.",
            "wouldBeFoundBy": "One person double-clicking it on their own machine.",
        })
    return entries


def known_limitations() -> list[dict]:
    """Things that will not work and are not bugs.

    Given to the tester deliberately: a known limit reported as a bug costs a day, and an unknown
    one reported as a bug is the entire point of the trial. They need to be able to tell which.
    """
    return [
        {
            "limitation": "Nine SqlDialect methods have no production caller. They are dead surface, "
                          "not wrong behaviour, and nothing a second machine does will touch them.",
            "ledgerId": "STOR-13",
            "workaround": "None needed -- no user-visible effect.",
        },
        {
            "limitation": "MySQL and H2 COMMIT IMPLICITLY ON DDL, so a migration that fails partway "
                          "cannot be rolled back: earlier steps are already permanent. NPDev reports "
                          "this truthfully rather than claiming a rollback.",
            "ledgerId": "STOR-2",
            "workaround": "Ask SqlDialects.active().supports(DDL_IN_TRANSACTION) rather than assuming.",
        },
        {
            "limitation": "The Manager's five database buttons drive the generated PowerShell _ops "
                          "scripts, so they need a PowerShell (pwsh, or Windows PowerShell 5.1). On a "
                          "Linux desktop with neither, the buttons report that plainly instead of "
                          "working.",
            "ledgerId": "M14",
            "workaround": "Install PowerShell 7, or run the scripts in the app's _ops folder directly.",
        },
    ]
