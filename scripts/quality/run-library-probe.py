#!/usr/bin/env python3
"""Prove a user capability CALLS an external library at runtime -- gap G, exit criterion E8.

WHAT WAS ACTUALLY MISSING
-------------------------
`build.dependencies[]` is real: the schema has it, `AppDependenciesEmitter` writes it, the
`npdev-dependencies.gradle` sidecar applies it at `build.gradle.template:181`, and
`simple-contact-intake` declares Guava -- so CI genuinely resolves a third-party dependency. But
every test asserted the EMITTED GRADLE TEXT:

    assertTrue(contents.contains("implementation 'com.google.guava:guava:33.0.0-jre'"))

No procedure anywhere imported an external class. The chain **declare -> import -> compile -> call at
runtime** had never run.

THE ASSERTION
-------------
The returned VALUE is right. A known input has a known SHA-256, computed by Guava's `Hashing`
rather than by the JDK's own `MessageDigest` -- the JDK would give the same answer while proving
nothing about the dependency. A correct digest proves the library was on the RUNTIME classpath and
executed. Compilation alone would pass even if the runtime classpath were wrong, which is exactly
what a `compileOnly`-instead-of-`implementation` mistake produces: a clean build and a
NoClassDefFoundError the first time the path runs.

2026-08-28 (SEC-3 Model A, B30): the former SECOND assertion -- "a `compileOnly` dependency is
ABSENT at runtime" -- measured that by REFLECTION from inside the capability (`Class.forName`),
which is precisely the capability-escape the plugin admission gate now refuses
(B30:plugin_bytecode_violation). A denylist cannot distinguish a benign measurement from a hostile
load, so the reflective half was REMOVED from the probe (capability, model flow, and this script)
rather than exempted. E8's own point -- a user capability CALLS an external library at runtime --
is fully covered by the remaining assertion; restoring the compileOnly runtime-absence measurement
would need a NON-plugin mechanism (a boot-time classpath scan by platform code).

Deliberately h2-local and kept SEPARATE from the engine probes: mixing them means a red run could be
the dialect or the dependency.

USAGE
    python scripts/quality/run-library-probe.py --port 18320 --report <path>

Exit 0 = both assertions passed. Exit 1 = at least one failed. Exit 2 = usage/setup problem.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# Reuse the engine proof's generate/build/boot machinery rather than writing a second copy: the two
# probes differ in what they ASSERT, not in how an app is produced, and two copies of "boot a
# generated app and wait for health" would drift the first time one is fixed.
import importlib.util

_ENGINE_PROOF = Path(__file__).resolve().parent / "run-engine-app-proof.py"
_spec = importlib.util.spec_from_file_location("npdev_engine_app_proof", _ENGINE_PROOF)
_engine_proof = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_engine_proof)

PAYLOAD = "npdev"
EXPECTED_DIGEST = hashlib.sha256(PAYLOAD.encode("utf-8")).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=18320)
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--report", default=None)
    parser.add_argument("--probe", default=None)
    args = parser.parse_args(argv)

    root = _engine_proof._repo_root()
    probe = Path(args.probe) if args.probe else root / "NPDevSamples" / "probes" / "lib-probe"
    app_input = probe / "Input"

    build_root = Path(os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build"))
    work = build_root / "engine-proof" / "lib-probe"
    work.mkdir(parents=True, exist_ok=True)
    output = work / "App"
    boot_log = work / "boot.log"
    if boot_log.exists():
        boot_log.unlink()

    results: list[dict] = []
    app = None
    failure: str | None = None
    try:
        _engine_proof.generate(root, app_input, output)
        jar = _engine_proof.build(output)
        app = _engine_proof.App(jar, output, args.port, boot_log)
        app.start(args.boot_timeout)

        base = app.base()

        # The library runs, and its answer is right.
        status, signed = _engine_proof.http(
            "POST", f"{base}/api/flows/SignWithLibrary/execute", {"payload": PAYLOAD})
        digest = _extract(signed, "digest")
        ok = status == 200 and digest == EXPECTED_DIGEST
        results.append({
            "assertion": "external-library-called-at-runtime",
            "ok": ok,
            "detail": (f"status={status}; SHA-256 of {PAYLOAD!r} came back {digest!r}, expected "
                       f"{EXPECTED_DIGEST!r}. A correct digest proves the library was on the RUNTIME "
                       f"classpath and executed -- compilation alone would pass with a wrong runtime "
                       f"scope. (Guava's loaded-from location was dropped 2026-08-28 with the "
                       f"compileOnlyProbe half: reporting it required reflection on a Class, which "
                       f"SEC-3/B30 refuses at plugin admission. The digest itself is logged to the "
                       f"boot log, uploaded by this job.)"),
        })
    except SystemExit as exc:
        failure = str(exc)
        print(f"[lib-probe] ABORTED: {failure}", flush=True)
    finally:
        if app is not None:
            app.stop()

    ok = failure is None and all(result["ok"] for result in results)
    report = {
        "schemaVersion": "npdev-library-probe.v1",
        "ok": ok,
        "failure": failure,
        "payload": PAYLOAD,
        "expectedDigest": EXPECTED_DIGEST,
        "assertions": results,
        "bootLog": str(boot_log),
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(f"\n### External library at runtime (E8): {'PASS' if ok else 'FAIL'}\n\n")
            handle.write("| assertion | result | detail |\n|---|---|---|\n")
            for result in results:
                handle.write(f"| {result['assertion']} | "
                             f"{'pass' if result['ok'] else '**FAIL**'} | {result['detail']} |\n")
            if failure:
                handle.write(f"\n**Aborted:** {failure}\n")
    return 0 if ok else 1


def _extract(payload, key):
    """Pull `key` out of a flow result without assuming the envelope's exact shape.

    A flow's response wraps its return value, and the wrapper's shape is not what this probe is
    testing. Searching for the key is deliberately forgiving about the envelope and strict about the
    value -- the opposite way round would make an envelope change look like a library failure.
    """
    if isinstance(payload, dict):
        if key in payload:
            return payload[key]
        for value in payload.values():
            found = _extract(value, key)
            if found is not None:
                return found
    elif isinstance(payload, list):
        for item in payload:
            found = _extract(item, key)
            if found is not None:
                return found
    return None


if __name__ == "__main__":
    sys.exit(main())
