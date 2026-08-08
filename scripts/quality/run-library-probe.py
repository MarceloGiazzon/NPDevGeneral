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

THE TWO ASSERTIONS
------------------
1. **The returned VALUE is right.** A known input has a known SHA-256, computed by Guava's `Hashing`
   rather than by the JDK's own `MessageDigest` -- the JDK would give the same answer while proving
   nothing about the dependency. A correct digest proves the library was on the RUNTIME classpath and
   executed. Compilation alone would pass even if the runtime classpath were wrong, which is exactly
   what a `compileOnly`-instead-of-`implementation` mistake produces: a clean build and a
   NoClassDefFoundError the first time the path runs.

2. **A `compileOnly` dependency is ABSENT at runtime -- and this is the interesting one.** The probe
   declares commons-lang3 as compileOnly and asks, by reflection, whether it is loadable. It must not
   be. Asserting an absence is the only way to catch a build that quietly promotes every dependency
   to the runtime classpath, and that failure is invisible until something depends on the scoping.

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

        # 1. The library runs, and its answer is right.
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
                       f"scope. (Where Guava was loaded from is printed to the boot log, which this "
                       f"job uploads: a diagnostic travelling with the PERSISTED record is a "
                       f"diagnostic that can break persistence, which is how run 31272063422 went "
                       f"red with the library working perfectly.)"),
        })

        # 2. The compileOnly dependency is NOT there. The interesting one: it fails silently in
        # production if the build promotes every dependency to runtime.
        status, probe_result = _engine_proof.http(
            "POST", f"{base}/api/flows/ProbeCompileOnlyAtRuntime/execute", {"payload": PAYLOAD})
        present = _extract(probe_result, "presentAtRuntime")
        results.append({
            "assertion": "compileOnly-absent-at-runtime",
            "ok": status == 200 and present is False,
            "detail": (f"status={status}; org.apache.commons.lang3.StringUtils presentAtRuntime="
                       f"{present!r}, expected False. Declared compileOnly, so it must compile and "
                       f"then be gone. Present would mean Gradle scoping is not doing what "
                       f"config.json says -- invisible until something depends on it."),
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
