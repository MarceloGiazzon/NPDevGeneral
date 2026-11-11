"""`npdev pack push` (P5): put a pack from "My parts" into a local clone of the PUBLIC catalog (NPR) -- and publish it
only when the owner says so, every time, in so many words.

The rule (owner, non-negotiable): publishing to the public catalog always needs an explicit confirmation of THIS pack and
THIS version. There is no flag, setting or remembered choice that skips it, and no batch form.

What a call does, in order:

  1. reads `<pack-dir>/pack.json` and checks it: no secret, no adapter binding, a version number, a sensible size,
     it composes (a probe application imports it and the model validator accepts it), and -- when the clone already
     holds an older version -- the version bump is big enough for what changed (the same gate `pack publish` uses);
  2. builds the summary of EXACTLY what would become public (the files, the plain-words preview, the changes);
  3. `--dry-run` stops here: nothing in the clone is touched;
  4. otherwise copies the pack into `<clone>/packs/<id>/pack.json`, rebuilds `catalog-index.json` with the catalog's own
     writer, commits locally and tags `<id>-<version>` (`_push_pack_to_catalog`, the code `pack publish --push` uses, so
     an already-published version is immutable here too), prints the summary and STOPS;
  5. only `--confirm-public <id>@<version>` -- matching this pack exactly -- pushes the commit and the tag to the
     clone's remote. A mismatch refuses before anything is committed. A push that fails (no credentials, offline) says so
     and that nothing was published.

A session of an AI assistant never runs step 5 itself: it hands the command to the owner. The push uses the owner's own
git credentials.
"""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

import npdev_pack_sheet
import npdev_relay_pack as relay_pack

MAX_PACK_BYTES = 1_000_000
TAG_TEMPLATE = "{pack}-{version}"


def _fail(problems: list[str], **extra) -> dict:
    return {"ok": False, "pushed": False, "committed": False, "problems": problems, **extra}


def check_pack(cli, pack_dir: Path, checkout: Path, work: Path, validate) -> tuple[dict | None, dict | None, list[str], dict | None]:
    """(pack, previous pack or None, problem lines, publish-gate report or None). A line is written for the owner."""
    path = pack_dir / "pack.json"
    if not path.is_file():
        return None, None, [f"There is no pack.json in {pack_dir}."], None
    raw = path.read_bytes()
    if len(raw) > MAX_PACK_BYTES:
        return None, None, [f"pack.json is {len(raw)} bytes; a public part must stay under {MAX_PACK_BYTES}."], None
    text = raw.decode("utf-8-sig")
    try:
        pack = json.loads(text)
    except ValueError:
        return None, None, ["pack.json is not valid JSON."], None
    if not isinstance(pack, dict) or not pack.get("pack"):
        return None, None, ['pack.json has no "pack" name.'], None
    pack_id = str(pack["pack"])
    problems = [line for line in relay_pack.static_errors(pack, pack_id, text) if "NPDev fills this" not in line]
    previous, report = None, None
    old_path = checkout / "packs" / pack_id / "pack.json"
    if not problems and old_path.is_file():
        old_text = old_path.read_text(encoding="utf-8-sig")
        try:
            previous = json.loads(old_text)
        except ValueError:
            previous = None
        if previous is not None and previous.get("version") != pack.get("version"):
            problems, report = relay_pack.version_errors(pack, old_text, work / "gate", cli)
    if not problems:
        folder = work / "probe"
        model_path = relay_pack.stage_probe(folder, pack_id, text, relay_pack.probe_model(pack_id))
        verdict = validate(model_path, work / "validation.json")
        if verdict is not None and verdict.get("status") == "failed":
            problems = relay_pack._lines(verdict, "probe")  # noqa: SLF001 -- one copy of the validator's wording
    return pack, previous, problems, report


def summary_of(pack: dict, previous: dict | None, report: dict | None, checkout: Path) -> dict:
    preview = npdev_pack_sheet.plain_preview(pack, previous, report)
    pack_id = pack["pack"]
    return {
        "packId": pack_id,
        "version": pack.get("version"),
        "description": pack.get("description") or (pack.get("metadata") or {}).get("summary") or "",
        "previousVersion": (previous or {}).get("version"),
        "files": [f"packs/{pack_id}/pack.json", "catalog-index.json"],
        "sentences": preview["sentences"],
        "warnings": [w["text"] for w in preview["warnings"]],
        "changes": [f"{f.get('classification')}: {f.get('message')}" for f in (report or {}).get("findings") or []],
        "catalog": str(checkout),
        "public": "Anyone on the internet will be able to see and use this part.",
    }


def push(cli, *, pack_dir, npr_checkout, dry_run=False, confirm_public=None, remote="origin", repository_url=None,
         validate=None) -> dict:
    """The whole of `pack push`. Returns the JSON document the command prints (ok / pushed / committed / problems / summary)."""
    pack_dir, checkout = Path(pack_dir).expanduser().resolve(), Path(npr_checkout).expanduser().resolve()
    if not (checkout / ".git").exists():
        return _fail([f"{checkout} is not a copy (git clone) of the public catalog."], error="not-a-catalog-clone")
    if validate is None:
        import npdev_relay_engine as engine

        validate = engine.run_validation
    with tempfile.TemporaryDirectory(prefix="npdev-pack-push-") as tmp:
        pack, previous, problems, report = check_pack(cli, pack_dir, checkout, Path(tmp), validate)
        if pack is None or problems:
            return _fail(problems, error="checks-failed")
        summary = summary_of(pack, previous, report, checkout)
    pack_id, version = pack["pack"], pack["version"]
    needed = f"{pack_id}@{version}"
    base = {"packId": pack_id, "version": version, "summary": summary, "confirmWith": needed}
    if confirm_public is not None and confirm_public != needed:
        # Refused before anything is written: a confirmation for some other pack or version is not this one's.
        return _fail([f'The confirmation "{confirm_public}" is not this part. To publish it, confirm "{needed}".'],
                     error="confirm-mismatch", **base)
    if dry_run:
        return {"ok": True, "pushed": False, "committed": False, "dryRun": True, **base}
    ns = argparse.Namespace(catalog_repo=str(checkout), remote=remote, repository_url=repository_url, tag_template=TAG_TEMPLATE,
                            branch=None, sign_with=None, push=False, git_user_name=None, git_user_email=None)
    try:
        local = cli._push_pack_to_catalog(pack_dir / "pack.json", ns)  # noqa: SLF001 -- the catalog's own writer; never a second one
    except cli.PackCatalogPublishRefusal as exc:
        return _fail([str(exc)], error="immutable-version", **base)
    except cli.CliError as exc:
        return _fail([str(exc)], error="catalog-error", **base)
    result = {"ok": True, "pushed": False, "committed": not local.get("alreadyPublished"),
              "localCommit": local.get("commit"), "tag": local.get("tag") or TAG_TEMPLATE.format(pack=pack_id, version=version),
              "alreadyInClone": bool(local.get("alreadyPublished")), **base}
    if confirm_public is None:
        result["message"] = (f"Nothing is public yet. To publish {needed}, run the same command with --confirm-public {needed}.")
        return result
    branch = cli._run_git(["rev-parse", "--abbrev-ref", "HEAD"], checkout).stdout.strip()  # noqa: SLF001
    tag = result["tag"]
    try:
        cli._run_git(["push", remote, branch], checkout)  # noqa: SLF001
        cli._run_git(["push", remote, tag], checkout)  # noqa: SLF001
    except cli.CliError as exc:
        return {**result, "ok": False, "error": "push-failed", "problems": [str(exc)[-400:]],
                "message": "The part could not be sent. Nothing was published."}
    return {**result, "pushed": True, "message": f"Published {needed}."}


def main(cli, args: argparse.Namespace) -> int:
    document = push(cli, pack_dir=args.pack_dir, npr_checkout=args.npr_checkout, dry_run=args.dry_run,
                    confirm_public=args.confirm_public, remote=args.remote, repository_url=args.repository_url)
    if getattr(args, "json", False):
        print(json.dumps(document, indent=2, ensure_ascii=False))
    else:
        summary = document.get("summary") or {}
        for line in summary.get("sentences", []):
            print(f"  {line}")
        for problem in document.get("problems", []):
            print(f"PROBLEM: {problem}")
        if document.get("message"):
            print(document["message"])
        elif document.get("dryRun"):
            print(f"Dry run: nothing was written. {summary.get('public', '')}")
    return 0 if document["ok"] else 2
