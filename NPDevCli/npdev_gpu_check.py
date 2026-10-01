"""npdev_gpu_check.py -- data-rule sweep with GPU acceleration and an exact CPU twin (Track B, G3).

NPDev already knows every data rule of an app (required, min/max, lengths, enums, invariant
expressions, unique, references) -- the generator emits a check manifest + WGSL compute shaders per
app when `checks.gpuArtifacts` is on (G2, dsl/gpucheck + generator/GpuCheckEmitter). This module
reads that manifest, packs an app's exported data into u32 rows the manifest's own column layout
describes, and evaluates every check either on the GPU (running the GENERATED .wgsl file verbatim)
or with the numpy CPU twin below -- a port of `reference_translators.py`'s `NumpyTwin`, proven
against `golden-vectors.json` (helpers/gpu/golden-vectors.json; this module's own semantics are not
re-derived, they are copied).

`numpy`/`wgpu` are imported lazily inside the functions that need them (run/bench/gpu_available) so
`npdev gpu-check list/history/show` keep working on a Python with neither installed -- only `run`
and `bench` need them, and `run --engine cpu` needs only numpy.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
import uuid
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

UNKNOWN_ENUM = 0xFFFFFFFF
UNMATCHABLE_LITERAL = 0xFFFFFFFE

RESULT_SCHEMA_VERSION = "npdev-gpu-check-result.v1"
MANIFEST_REL = Path("npdev-generated") / "src" / "main" / "resources" / "npdev" / "gpu-checks" / "manifest.json"
RUNS_REL = Path("logs") / "gpu-checks"
GPU_SAMPLE_VERIFY_ROWS = 10_000
DEFAULT_ROW_CHECKS_BREAK_EVEN = 300_000_000
CALIBRATION_FILE = Path.home() / ".npdev" / "gpu-check-calibration.json"


class GpuCheckError(Exception):
    """User-facing error -- the CLI layer prints str(e) and exits non-zero."""


# =================================================================================================
# 1. CSV -- exact port of CsvRowSerializer.parseCsvLine (NPDevRuntimeHost runtimehost-core):
#    an unquoted empty cell is NULL; a quoted empty cell "" is the empty string.
# =================================================================================================
def parse_csv_line(line: str) -> list[str | None]:
    cells: list[str | None] = []
    current: list[str] = []
    in_quotes = False
    quoted = False
    i = 0
    while i < len(line):
        c = line[i]
        if in_quotes:
            if c == '"':
                if i + 1 < len(line) and line[i + 1] == '"':
                    current.append('"')
                    i += 1
                else:
                    in_quotes = False
            else:
                current.append(c)
        elif c == '"':
            in_quotes = True
            quoted = True
        elif c == ",":
            cells.append("".join(current) if (quoted or current) else None)
            current = []
            quoted = False
        else:
            current.append(c)
        i += 1
    cells.append("".join(current) if (quoted or current) else None)
    return cells


def read_table(csv_path: Path, wanted_columns: list[str]) -> dict[str, list[str | None]]:
    """Column-oriented read. Header names are matched case-insensitively (H2 may upper-case them)."""
    with csv_path.open("r", encoding="utf-8", newline="") as handle:
        header_line = handle.readline().rstrip("\n").rstrip("\r")
        header = [h.lower() for h in parse_csv_line(header_line)]
        index: dict[str, int] = {}
        for col in wanted_columns:
            if col.lower() not in header:
                raise GpuCheckError(f"{csv_path.name}: column '{col}' not in header {header}")
            index[col] = header.index(col.lower())
        out: dict[str, list[str | None]] = {col: [] for col in wanted_columns}
        for raw in handle:
            line = raw.rstrip("\n").rstrip("\r")
            if line == "" and raw.strip() == "":
                continue
            cells = parse_csv_line(line)
            for col, pos in index.items():
                out[col].append(cells[pos] if pos < len(cells) else None)
    return out


def row_count_of(table: dict[str, list[str | None]]) -> int:
    for values in table.values():
        return len(values)
    return 0


# =================================================================================================
# 2. manifest
# =================================================================================================
def load_manifest(app_dir: Path) -> dict:
    path = app_dir / MANIFEST_REL
    if not path.exists():
        raise GpuCheckError(
            f"{path} not found -- this app was generated without GPU check artifacts. Set "
            f'"checks.gpuArtifacts": true in config.json defaults and regenerate, or use '
            f"`npdev gpu-check plan --model <model.json>` for a pre-flight sweep with no regeneration.")
    return json.loads(path.read_text(encoding="utf-8"))


def pack_scale_of(pack: dict) -> int:
    return max([c.get("scale", 0) for c in pack["columns"] if c["encoding"] == "fixed32"] + [0])


# =================================================================================================
# 3. packing -- manifest-driven port of reference_translators.pack_rows
# =================================================================================================
def utf16_len(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def pack_concept_pack(pack: dict, table: dict[str, list[str | None]], row_count: int) -> "Any":
    """Returns a (rows, strideWords) uint32 ndarray, or raises OverflowError (caller falls back to
    CPU for THIS pack and records why -- a value that does not fit the manifest's declared scale is
    a data problem the sweep should still be able to report via `--engine cpu`, not a crash)."""
    import numpy as np

    scale = pack_scale_of(pack)
    packed = np.zeros((row_count, pack["strideWords"]), dtype=np.int64)
    for col in pack["columns"]:
        word, bit, enc = col["word"], col["nullBit"], col["encoding"]
        values = table[col["column"]]
        for r, text in enumerate(values):
            if text is None:
                packed[r, 0] |= 1 << bit
                continue
            if enc == "bool":
                packed[r, word] = 1 if text.strip().lower() == "true" else 0
            elif enc in ("i32", "fixed32"):
                try:
                    scaled = Decimal(text) * (Decimal(10) ** scale)
                except InvalidOperation as exc:
                    raise OverflowError(f"{col['column']}={text!r} is not a number") from exc
                if scaled != scaled.to_integral_value() or abs(scaled) > 2 ** 31 - 1:
                    raise OverflowError(f"{col['column']}={text} does not fit i32 at scale {scale}")
                packed[r, word] = int(scaled)
            elif enc == "enum_index":
                enum_values = col["enumValues"]
                packed[r, word] = enum_values.index(text) if text in enum_values else 0xFFFFFFFF
            elif enc == "str_len":
                packed[r, word] = utf16_len(text)
    return (packed & 0xFFFFFFFF).astype(np.uint32)


# =================================================================================================
# 4. CPU twin -- a line-by-line port of reference_translators.NumpyTwin, adapted to read
#    word/nullBit from the manifest's own columns instead of list position.
# =================================================================================================
class NumpyTwin:
    def __init__(self, packed: "Any", columns: list[dict], scale: int):
        import numpy as np  # noqa: F401 (type-only use below; real import needed for np.* calls)

        self.nulls = packed[:, 0]
        self.words = packed
        self.by_field = {c["field"]: c for c in columns}
        self.scale = scale
        self.n = len(packed)

    def value(self, node: dict):
        import numpy as np

        k = node["k"]
        if k == "lit":
            t = node["t"]
            if t == "num":
                scaled = Decimal(node["v"]) * (Decimal(10) ** self.scale)
                return "num", np.full(self.n, int(scaled), dtype=np.int64), np.zeros(self.n, bool)
            if t == "bool":
                return "bool", np.full(self.n, bool(node["v"])), np.zeros(self.n, bool)
            if t == "str":
                return "strlit", node["v"], np.zeros(self.n, bool)
            return "null", None, np.ones(self.n, bool)
        if k == "var":
            col = self.by_field[node["name"]]
            null = (self.nulls & (1 << col["nullBit"])) != 0
            raw = self.words[:, col["word"]]
            enc = col["encoding"]
            if enc in ("i32", "fixed32"):
                return "num", raw.view(np.int32).astype(np.int64), null
            if enc == "bool":
                return "bool", raw != 0, null
            if enc == "enum_index":
                return ("enum", tuple(col["enumValues"])), raw, null
            return "str", raw, null
        if k == "un" and node["op"] == "-":
            _, v, _ = self.value(node["a"])
            return "num", -v, np.zeros(self.n, bool)
        if k == "bin" and node["op"] in ("+", "-"):
            _, a, _ = self.value(node["l"])
            _, b, _ = self.value(node["r"])
            return "num", (a + b) if node["op"] == "+" else (a - b), np.zeros(self.n, bool)
        if k == "call" and node["name"] == "__len":
            _, v, n = self.value(node["args"][0])
            return "num", v, n
        return "bool", self.boolean(node), np.zeros(self.n, bool)

    def boolean(self, node: dict):
        k = node["k"]
        if k == "call":
            name = node["name"]
            if name == "__notNull":
                return ~self.value(node["args"][0])[2]
            if name == "__nullOr":
                return self.value(node["args"][0])[2] | self.boolean(node["args"][1])
            if name == "__inEnum":
                _, v, _ = self.value(node["args"][0])
                return v != UNKNOWN_ENUM
            raise GpuCheckError(f"NumpyTwin: unsupported function '{name}'")
        if k == "un" and node["op"] == "!":
            return ~self.boolean(node["a"])
        if k in ("var", "lit"):
            kind, v, null = self.value(node)
            import numpy as np
            return (v & ~null) if kind == "bool" else np.zeros(self.n, bool)
        if k == "bin":
            op = node["op"]
            if op == "&&":
                return self.boolean(node["l"]) & self.boolean(node["r"])
            if op == "||":
                return self.boolean(node["l"]) | self.boolean(node["r"])
            if op in ("<", "<=", ">", ">="):
                _, a, _ = self.value(node["l"])
                _, b, _ = self.value(node["r"])
                return {"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b}[op]
            if op in ("==", "!="):
                eq = self.equals(node["l"], node["r"])
                return eq if op == "==" else ~eq
        raise GpuCheckError(f"NumpyTwin: unsupported node {node}")

    def equals(self, left: dict, right: dict):
        lk, lv, ln = self.value(left)
        rk, rv, rn = self.value(right)
        if lk == "null":
            return rn.copy()
        if rk == "null":
            return ln.copy()
        if rk == "strlit" and isinstance(lk, tuple):
            values = lk[1]
            target = values.index(rv) if rv in values else UNMATCHABLE_LITERAL
            return ~ln & (lv == target)
        if lk == "strlit" and isinstance(rk, tuple):
            return self.equals(right, left)
        if rk == "strlit" and lk == "str":
            if rv != "":
                raise GpuCheckError("NumpyTwin: string comparison other than == ''")
            return ~ln & (lv == 0)
        return (ln & rn) | (~ln & ~rn & (lv == rv))


def cpu_fails(pack: dict, packed: "Any") -> "Any":
    """Runs every check tree of the pack through NumpyTwin; bit `check.bit` set where it is False."""
    import numpy as np

    twin = NumpyTwin(packed, pack["columns"], pack_scale_of(pack))
    fails = np.zeros(len(packed), dtype=np.uint32)
    for check in pack["checks"]:
        ok = twin.boolean(check["tree"])
        fails |= (~ok).astype(np.uint32) << np.uint32(check["bit"])
    return fails


# =================================================================================================
# 5. GPU engine
# =================================================================================================
def gpu_available() -> tuple[bool, str]:
    try:
        import wgpu  # noqa: F401
    except ImportError:
        return False, "the Python package 'wgpu' is not installed (pip install wgpu)"
    try:
        import wgpu
        adapter = wgpu.gpu.request_adapter_sync(power_preference="high-performance")
        if adapter is None:
            return False, "no GPU adapter found"
        return True, adapter.info.get("device", "gpu")
    except Exception as exc:  # noqa: BLE001
        return False, f"GPU init failed: {exc}"


def run_gpu_pack(packed: "Any", shader_code: str) -> tuple["Any", dict]:
    """Runs the GENERATED .wgsl shader verbatim against one pack's packed rows. Returns
    (fails_per_row_uint32, timingsMs: {gpuInit, gpuUpload, gpuDispatch})."""
    import numpy as np
    import wgpu

    timings: dict[str, float] = {}
    t0 = time.perf_counter()
    adapter = wgpu.gpu.request_adapter_sync(power_preference="high-performance")
    if adapter is None:
        raise GpuCheckError("no GPU adapter found")
    device = adapter.request_device_sync()
    pipeline = device.create_compute_pipeline(
        layout="auto",
        compute={"module": device.create_shader_module(code=shader_code), "entry_point": "main"})
    timings["gpuInit"] = (time.perf_counter() - t0) * 1000

    rows, stride = packed.shape
    t1 = time.perf_counter()
    data_buf = device.create_buffer_with_data(
        data=np.ascontiguousarray(packed).tobytes(), usage=wgpu.BufferUsage.STORAGE)
    out_buf = device.create_buffer(
        size=max(rows, 1) * 4, usage=wgpu.BufferUsage.STORAGE | wgpu.BufferUsage.COPY_SRC)
    param_buf = device.create_buffer_with_data(
        data=np.array([rows, stride, 0, 0], dtype=np.uint32).tobytes(), usage=wgpu.BufferUsage.UNIFORM)
    timings["gpuUpload"] = (time.perf_counter() - t1) * 1000

    t2 = time.perf_counter()
    bind_group = device.create_bind_group(layout=pipeline.get_bind_group_layout(0), entries=[
        {"binding": 0, "resource": {"buffer": data_buf, "offset": 0, "size": data_buf.size}},
        {"binding": 1, "resource": {"buffer": out_buf, "offset": 0, "size": out_buf.size}},
        {"binding": 2, "resource": {"buffer": param_buf, "offset": 0, "size": param_buf.size}},
    ])
    groups = (rows + 63) // 64
    encoder = device.create_command_encoder()
    compute_pass = encoder.begin_compute_pass()
    compute_pass.set_pipeline(pipeline)
    compute_pass.set_bind_group(0, bind_group)
    compute_pass.dispatch_workgroups(min(groups, 65535), (groups + 65534) // 65535, 1)
    compute_pass.end()
    device.queue.submit([encoder.finish()])
    fails = np.frombuffer(device.queue.read_buffer(out_buf), dtype=np.uint32)[:rows].copy()
    timings["gpuDispatch"] = (time.perf_counter() - t2) * 1000
    return fails, timings


# =================================================================================================
# host checks -- unique / reference / requiredOpaque, numpy-free (plain Python is plenty for the
# id-sample bookkeeping; the heavy lifting is just hashing CSV text).
# =================================================================================================
def run_host_checks(concept: dict, tables: dict[str, dict[str, list[str | None]]]) -> list[dict]:
    results: list[dict] = []
    own = tables.get(concept["table"])
    ids = own.get(concept["idColumn"]) if own else None
    for hc in concept.get("hostChecks", []):
        kind = hc["kind"]
        cols = hc["columns"]
        if kind == "requiredOpaque":
            values = own[cols[0]] if own else []
            bad = [i for i, v in enumerate(values) if v is None]
            results.append(_host_result(hc, bad, ids))
        elif kind == "unique":
            col_values = [own[c] if own else [] for c in cols]
            n = len(col_values[0]) if col_values else 0
            seen: dict[tuple, int] = {}
            bad_set: set[int] = set()
            for i in range(n):
                tup = tuple(cv[i] for cv in col_values)
                if any(v is None for v in tup):
                    continue
                if tup in seen:
                    bad_set.add(i)
                    bad_set.add(seen[tup])
                else:
                    seen[tup] = i
            results.append(_host_result(hc, sorted(bad_set), ids))
        elif kind == "reference":
            values = own[cols[0]] if own else []
            target_table = tables.get(hc.get("targetTable", ""))
            target_ids = set(target_table.get(hc.get("targetIdColumn", ""), [])) if target_table else set()
            bad = [i for i, v in enumerate(values) if v is not None and v not in target_ids]
            results.append(_host_result(hc, bad, ids))
    return results


def _host_result(hc: dict, bad_indices: list[int], ids: list[str | None] | None) -> dict:
    sample: list[str] = []
    if ids:
        sample = [ids[i] for i in bad_indices[:50] if i < len(ids) and ids[i] is not None]
    return {
        "id": hc["id"],
        "kind": hc["kind"],
        "message": hc.get("message", ""),
        "status": "failed" if bad_indices else "passed",
        "violations": len(bad_indices),
        "sampleIds": sample,
    }


# =================================================================================================
# export -- shells to `npdev db export` (this same CLI) so packing always reads a REAL export,
# never a second CSV-writing code path that could silently drift from the real one.
# =================================================================================================
def export_tables(app_dir: Path, out_dir: Path, table_names: list[str]) -> float:
    cli_script = Path(__file__).resolve().parent / "npdev_cli.py"
    cmd = [sys.executable, str(cli_script), "db", "export", "--app", str(app_dir),
           "--out", str(out_dir), "--format", "csv", "--scope", "all",
           "--tables", ",".join(sorted(set(table_names)))]
    t0 = time.perf_counter()
    completed = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    elapsed = (time.perf_counter() - t0) * 1000
    if completed.returncode != 0:
        raise GpuCheckError(
            "npdev db export failed (exit " + str(completed.returncode) + "): "
            + (completed.stderr.strip() or completed.stdout.strip()))
    return elapsed


def _tables_needed(concepts: list[dict]) -> list[str]:
    tables: set[str] = set()
    for concept in concepts:
        tables.add(concept["table"])
        for hc in concept.get("hostChecks", []):
            if hc.get("targetTable"):
                tables.add(hc["targetTable"])
    return sorted(tables)


def _columns_needed(concept: dict) -> list[str]:
    columns: set[str] = {concept["idColumn"]}
    for pack in concept.get("packs", []):
        for col in pack["columns"]:
            columns.add(col["column"])
    for hc in concept.get("hostChecks", []):
        for col in hc.get("columns", []):
            columns.add(col)
    return sorted(columns)


# =================================================================================================
# calibration (for --engine auto) and run records
# =================================================================================================
def load_calibration() -> dict:
    if CALIBRATION_FILE.exists():
        try:
            return json.loads(CALIBRATION_FILE.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
    return {}


def save_calibration(device: str, row_checks_break_even: float) -> None:
    CALIBRATION_FILE.parent.mkdir(parents=True, exist_ok=True)
    CALIBRATION_FILE.write_bytes(json.dumps(
        {"device": device, "rowChecksBreakEven": row_checks_break_even}, indent=2).encode("utf-8"))


def new_run_dir(app_dir: Path) -> tuple[str, Path]:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_id = f"{stamp}-{uuid.uuid4().hex[:6]}"
    run_dir = app_dir / RUNS_REL / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    return run_id, run_dir


# =================================================================================================
# 6. run -- the orchestration every subcommand but list/history/show/bundle goes through.
# =================================================================================================
def run(app_dir: Path, engine: str = "auto", concept_names: list[str] | None = None,
        data_dir: Path | None = None, verify: bool = False, manifest_override: dict | None = None,
        shader_dir: Path | None = None, mode: str = "app") -> dict:
    """Returns the result record (schema: gpu-check-result.schema.json). Raises GpuCheckError on a
    setup problem (bad manifest, export failure); a GPU/CPU mismatch is NOT raised -- it is recorded
    in verify.matched=false and the caller decides the exit code (2).

    `manifest_override`/`shader_dir` are G6's pre-flight seam: `plan` builds a manifest from a
    CANDIDATE model (never regenerating the app) and sweeps the app's CURRENT data against it --
    everything else about the sweep (export, packing, both engines, host checks) is identical."""
    timings: dict[str, float] = {}
    t_total = time.perf_counter()
    manifest = manifest_override if manifest_override is not None else load_manifest(app_dir)
    shader_dir = shader_dir if shader_dir is not None else (app_dir / MANIFEST_REL.parent)
    all_concepts = manifest["concepts"]
    if concept_names:
        wanted = set(concept_names)
        selected = [c for c in all_concepts if c["concept"] in wanted]
    else:
        selected = all_concepts

    run_id, run_dir = new_run_dir(app_dir)
    export_dir = data_dir if data_dir is not None else (run_dir / "export")
    if data_dir is None:
        timings["export"] = export_tables(app_dir, export_dir, _tables_needed(selected))

    gpu_ok, gpu_info = (False, "")
    if engine in ("gpu", "auto"):
        gpu_ok, gpu_info = gpu_available()

    calibration = load_calibration()
    break_even = calibration.get("rowChecksBreakEven", DEFAULT_ROW_CHECKS_BREAK_EVEN)

    engines_used: set[str] = set()
    verify_mismatches: list[dict] = []
    total_violations = 0
    total_rows_checked = 0
    concept_results: list[dict] = []
    t_read = t_pack = t_cpu = t_host = 0.0

    for concept in selected:
        table_path = export_dir / f"{concept['table']}.csv"
        columns_needed = _columns_needed(concept)
        tr0 = time.perf_counter()
        try:
            own_table = read_table(table_path, columns_needed) if table_path.exists() else None
        except GpuCheckError:
            own_table = None
        t_read += time.perf_counter() - tr0
        rows = row_count_of(own_table) if own_table else 0
        total_rows_checked += rows

        pack_summaries = []
        check_results: list[dict] = []
        for pack in concept.get("packs", []):
            checks_in_pack = len(pack["checks"])
            if own_table is None or rows == 0:
                for check in pack["checks"]:
                    check_results.append({
                        "id": check["id"], "kind": check["kind"], "message": check.get("message", ""),
                        "status": "skipped", "violations": 0, "skipReason": "no exported rows",
                    })
                pack_summaries.append({"packId": pack["packId"], "engine": "cpu",
                                        "fallbackReason": "no exported rows"})
                continue
            tp0 = time.perf_counter()
            try:
                packed = pack_concept_pack(pack, own_table, rows)
            except OverflowError as exc:
                t_pack += time.perf_counter() - tp0
                tc0 = time.perf_counter()
                # Can't even pack it at this scale -- nothing this pack's checks can safely run on.
                for check in pack["checks"]:
                    check_results.append({
                        "id": check["id"], "kind": check["kind"], "message": check.get("message", ""),
                        "status": "skipped", "violations": 0, "skipReason": str(exc),
                    })
                pack_summaries.append({"packId": pack["packId"], "engine": "cpu-fallback",
                                        "fallbackReason": str(exc)})
                t_cpu += time.perf_counter() - tc0
                continue
            t_pack += time.perf_counter() - tp0

            use_gpu = engine == "gpu" or (engine == "auto" and gpu_ok and rows * checks_in_pack >= break_even)
            if engine == "gpu" and not gpu_ok:
                raise GpuCheckError(f"--engine gpu requested but unavailable: {gpu_info}")

            pack_engine = "cpu"
            fails = None
            if use_gpu:
                shader_path = shader_dir / pack["shader"]
                if not shader_path.exists():
                    pack_engine = "cpu"
                else:
                    try:
                        fails, gpu_timings = run_gpu_pack(packed, shader_path.read_text(encoding="utf-8"))
                        for k, v in gpu_timings.items():
                            timings[k] = timings.get(k, 0.0) + v
                        pack_engine = "gpu"
                    except Exception as exc:  # noqa: BLE001
                        pack_engine = "cpu"
                        pack_summaries.append({"packId": pack["packId"], "engine": "cpu-fallback",
                                                "fallbackReason": f"GPU dispatch failed: {exc}"})
            if fails is None:
                tc0 = time.perf_counter()
                fails = cpu_fails(pack, packed)
                t_cpu += time.perf_counter() - tc0

            if pack_engine == "gpu":
                sample_n = min(GPU_SAMPLE_VERIFY_ROWS, rows)
                sample_pack = dict(pack)
                cpu_sample = cpu_fails(pack, packed[:sample_n])
                if not (cpu_sample == fails[:sample_n]).all():
                    verify_mismatches.append({"pack": pack["packId"], "mode": "sample"})
                engines_used.add("gpu")
            else:
                engines_used.add("cpu")

            if verify and pack_engine == "gpu":
                cpu_full = cpu_fails(pack, packed)
                if not (cpu_full == fails).all():
                    verify_mismatches.append({"pack": pack["packId"], "mode": "full"})

            pack_summaries.append({"packId": pack["packId"], "engine": pack_engine})
            for check in pack["checks"]:
                bit_mask = 1 << check["bit"]
                violation_rows = [i for i in range(rows) if int(fails[i]) & bit_mask]
                total_violations += len(violation_rows)
                id_col = own_table.get(concept["idColumn"], [])
                sample_ids = [id_col[i] for i in violation_rows[:50] if i < len(id_col) and id_col[i] is not None]
                check_results.append({
                    "id": check["id"], "kind": check["kind"], "message": check.get("message", ""),
                    "status": "failed" if violation_rows else "passed",
                    "violations": len(violation_rows), "sampleIds": sample_ids,
                })

        th0 = time.perf_counter()
        host_results = run_host_checks(concept, _collect_tables_for_host_checks(concept, export_dir, own_table))
        t_host += time.perf_counter() - th0
        for hr in host_results:
            total_violations += hr["violations"]
        check_results.extend(host_results)
        for skip in concept.get("skipped", []):
            check_results.append({
                "id": skip["id"], "kind": skip.get("kind", "invariant"), "message": skip.get("reason", ""),
                "status": "skipped", "violations": 0, "skipReason": skip.get("reason", ""),
            })

        concept_results.append({
            "concept": concept["concept"], "rows": rows, "packs": pack_summaries, "checks": check_results,
        })

    timings["read"] = t_read * 1000
    timings["pack"] = t_pack * 1000
    timings["cpuTwin"] = t_cpu * 1000
    timings["host"] = t_host * 1000
    timings["total"] = (time.perf_counter() - t_total) * 1000

    used = "mixed" if len(engines_used) > 1 else (next(iter(engines_used), "cpu"))
    verify_mode = "full" if verify else ("sample" if "gpu" in engines_used else "none")
    result = {
        "schemaVersion": RESULT_SCHEMA_VERSION,
        "runId": run_id,
        "startedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "app": app_dir.name,
        "namespace": manifest.get("namespace", ""),
        "mode": mode,
        "engine": {"requested": engine, "used": used, "device": gpu_info if gpu_ok else ""},
        "rowsChecked": total_rows_checked,
        "violations": total_violations,
        "verify": {
            "mode": verify_mode,
            "rows": GPU_SAMPLE_VERIFY_ROWS if verify_mode == "sample" else total_rows_checked,
            "matched": len(verify_mismatches) == 0,
            "mismatches": verify_mismatches,
        },
        "timingsMs": timings,
        "concepts": concept_results,
    }
    if not gpu_ok and engine in ("gpu", "auto"):
        result["engine"]["gpuUnavailableReason"] = gpu_info

    _write_run_record(run_dir, manifest, result)
    return result


def _collect_tables_for_host_checks(concept: dict, export_dir: Path, own_table) -> dict:
    tables: dict[str, dict] = {concept["table"]: own_table} if own_table else {}
    for hc in concept.get("hostChecks", []):
        target = hc.get("targetTable")
        target_id = hc.get("targetIdColumn")
        if target and target_id and target not in tables:
            path = export_dir / f"{target}.csv"
            if path.exists():
                try:
                    tables[target] = read_table(path, [target_id])
                except GpuCheckError:
                    pass
    return tables


def _write_run_record(run_dir: Path, manifest: dict, result: dict) -> None:
    (run_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    (run_dir / "summary.txt").write_text(_summary_text(result), encoding="utf-8")
    (run_dir / "ai-prompt.txt").write_text(_ai_prompt_text(result), encoding="utf-8")
    artifacts_dir = run_dir / "artifacts"
    artifacts_dir.mkdir(exist_ok=True)
    (artifacts_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def _summary_text(result: dict) -> str:
    lines = [
        f"npdev gpu-check run {result['runId']}",
        f"app: {result['app']}   engine: {result['engine']['used']} (requested {result['engine']['requested']})",
        f"rows checked: {result['rowsChecked']}   violations: {result['violations']}",
        f"verify: {result['verify']['mode']} -- matched={result['verify']['matched']}",
        "",
    ]
    for concept in result["concepts"]:
        failed = [c for c in concept["checks"] if c["status"] == "failed"]
        if failed:
            lines.append(f"{concept['concept']} ({concept['rows']} rows):")
            for check in failed:
                lines.append(f"  [{check['violations']}] {check['id']} -- {check['message']}")
    if result["violations"] == 0:
        lines.append("No violations found.")
    return "\n".join(lines) + "\n"


def _ai_prompt_text(result: dict) -> str:
    rows = []
    skipped = []
    for concept in result["concepts"]:
        for check in concept["checks"]:
            if check["status"] == "failed":
                rows.append(f"- {check['id']}: {check['violations']} row(s) -- {check['message']} "
                            f"(sample ids: {', '.join(check.get('sampleIds', [])[:10])})")
            elif check["status"] == "skipped":
                skipped.append(f"- {check['id']}: {check.get('skipReason', '')}")
    violations_table = "\n".join(rows) if rows else "(no violations)"
    skipped_list = "\n".join(skipped) if skipped else "(none)"
    return (
        f"You are reviewing data-rule violations found in an NPDev app named {result.get('namespace', '')}.\n"
        "NPDev apps are defined by a JSON model; the rules below come from that model.\n"
        "For each violated rule: say whether the DATA is probably wrong or the RULE is probably wrong, "
        "and propose either a data fix (an SQL UPDATE, using the ids given) or a model change (the JSON "
        "to change).\n\n"
        f"{violations_table}\n\n"
        "Rules that could not be checked by this sweep (the app still enforces them on every save):\n"
        f"{skipped_list}\n"
    )


# =================================================================================================
# 7. read-only views
# =================================================================================================
def list_checks(app_dir: Path) -> list[dict]:
    manifest = load_manifest(app_dir)
    out = []
    for concept in manifest["concepts"]:
        for pack in concept.get("packs", []):
            for check in pack["checks"]:
                out.append({"concept": concept["concept"], "id": check["id"], "kind": check["kind"],
                            "engine": "gpu", "message": check.get("message", "")})
        for hc in concept.get("hostChecks", []):
            out.append({"concept": concept["concept"], "id": hc["id"], "kind": hc["kind"],
                        "engine": "host", "message": hc.get("message", "")})
        for skip in concept.get("skipped", []):
            out.append({"concept": concept["concept"], "id": skip["id"], "kind": skip.get("kind", ""),
                        "engine": "skipped", "message": skip.get("reason", "")})
    return out


def history(app_dir: Path) -> list[dict]:
    root = app_dir / RUNS_REL
    if not root.exists():
        return []
    out = []
    for run_dir in sorted(root.iterdir(), reverse=True):
        result_path = run_dir / "result.json"
        if result_path.exists():
            try:
                data = json.loads(result_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            out.append({k: data.get(k) for k in
                        ("runId", "startedAt", "engine", "rowsChecked", "violations", "verify", "timingsMs")})
    return out


def show(app_dir: Path, run_id: str) -> dict:
    result_path = app_dir / RUNS_REL / run_id / "result.json"
    if not result_path.exists():
        raise GpuCheckError(f"no such run: {run_id}")
    return json.loads(result_path.read_text(encoding="utf-8"))


def bundle(app_dir: Path, run_id: str, out_zip: Path) -> Path:
    run_dir = app_dir / RUNS_REL / run_id
    if not run_dir.is_dir():
        raise GpuCheckError(f"no such run: {run_id}")
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(run_dir.rglob("*")):
            if path.is_file() and "export" not in path.relative_to(run_dir).parts:
                zf.write(path, path.relative_to(run_dir).as_posix())
    return out_zip


def bench(app_dir: Path, rows: int = 10_000_000) -> dict:
    """Times numpy vs GPU over the app's own exported data, tiled up to `rows`, in ONE process so
    the comparison is apples-to-apples. Writes the calibration file `run --engine auto` reads."""
    import numpy as np

    manifest = load_manifest(app_dir)
    concepts_with_packs = [c for c in manifest["concepts"] if c.get("packs")]
    if not concepts_with_packs:
        raise GpuCheckError("this app's manifest has no GPU-checkable packs to benchmark")
    concept = concepts_with_packs[0]
    pack = concept["packs"][0]

    run_id, run_dir = new_run_dir(app_dir)
    export_dir = run_dir / "export"
    export_tables(app_dir, export_dir, [concept["table"]])
    table_path = export_dir / f"{concept['table']}.csv"
    if not table_path.exists():
        shutil.rmtree(run_dir, ignore_errors=True)
        raise GpuCheckError(
            f"export produced no file for table '{concept['table']}' -- this app likely has no "
            f"configured/reachable database. Point --data at an existing CSV export directory, or "
            f"run this against an app with a real database connection.")
    own_table = read_table(table_path, _columns_needed(concept))
    base_rows = row_count_of(own_table)
    if base_rows == 0:
        shutil.rmtree(run_dir, ignore_errors=True)
        raise GpuCheckError(f"{concept['table']} has no exported rows to benchmark with")

    tile = max(1, rows // base_rows)
    tiled = {col: values * tile for col, values in own_table.items()}
    actual_rows = row_count_of(tiled)
    packed = pack_concept_pack(pack, tiled, actual_rows)

    t0 = time.perf_counter()
    cpu_fails(pack, packed)
    cpu_ms = (time.perf_counter() - t0) * 1000

    gpu_ok, device = gpu_available()
    result: dict[str, Any] = {
        "rows": actual_rows, "checks": len(pack["checks"]), "cpuMs": cpu_ms,
        "gpuAvailable": gpu_ok, "device": device if gpu_ok else "",
    }
    if gpu_ok:
        shader_path = app_dir / MANIFEST_REL.parent / pack["shader"]
        fails, gpu_timings = run_gpu_pack(packed, shader_path.read_text(encoding="utf-8"))
        gpu_ms = sum(gpu_timings.values())
        result["gpuMs"] = gpu_ms
        result["gpuTimingsMs"] = gpu_timings
        break_even = (actual_rows * len(pack["checks"])) * (cpu_ms / max(gpu_ms, 0.001))
        save_calibration(device, break_even)
        result["rowChecksBreakEven"] = break_even
    shutil.rmtree(run_dir, ignore_errors=True)
    return result


# =================================================================================================
# G6 -- pre-flight a model change, with no app regeneration. The headline use: "if I deploy this
# model, which existing rows would break the new rules?" -- answered from the CANDIDATE model's
# manifest, swept against the app's CURRENT data, with no generate/build/boot in between.
# =================================================================================================
def _check_identity(check: dict) -> tuple:
    """What makes a check 'the same rule' across two manifests -- id, kind and message match; the
    tree is compared separately (same id/kind/message but a different tree is reported as CHANGED,
    not NEW+REMOVED, since it is the same named rule evolving)."""
    return check["id"], check.get("kind", ""), check.get("message", "")


def _all_checks_by_id(manifest: dict) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for concept in manifest.get("concepts", []):
        for pack in concept.get("packs", []):
            for check in pack["checks"]:
                out[check["id"]] = check
        for hc in concept.get("hostChecks", []):
            out[hc["id"]] = {**hc, "tree": None}
    return out


def diff_manifests(old_manifest: dict, new_manifest: dict) -> dict:
    """Returns {"new": [...], "changed": [...], "removed": [...]} -- check ids only, for the
    headline summary line ('N existing rows break M rules, K of them new or changed')."""
    old_by_id = _all_checks_by_id(old_manifest)
    new_by_id = _all_checks_by_id(new_manifest)
    new_ids = sorted(set(new_by_id) - set(old_by_id))
    removed_ids = sorted(set(old_by_id) - set(new_by_id))
    changed_ids = sorted(
        cid for cid in (set(new_by_id) & set(old_by_id))
        if json.dumps(old_by_id[cid].get("tree"), sort_keys=True) != json.dumps(new_by_id[cid].get("tree"), sort_keys=True)
        or old_by_id[cid].get("message") != new_by_id[cid].get("message")
    )
    return {"new": new_ids, "changed": changed_ids, "removed": removed_ids}


def plan(app_dir: Path, model_path: Path, java_bin: Path, ai_tools_jar: Path,
         engine: str = "auto", concept_names: list[str] | None = None) -> dict:
    """G6: builds the candidate model's GPU check manifest via the Java CLI (GpuCheckManifestMain,
    G2.5 -- no app regeneration), sweeps the app's CURRENT data against that CANDIDATE manifest, and
    diffs it against the app's own current manifest by check id.

    `model_path` is passed straight to GpuCheckManifestMain, which parses + compiles it with the
    real DSL pipeline (JsonModelParser resolves packs AND contexts from the model's own location,
    then ModelCompiler) -- NOT a separate "canonicalize" pre-pass. An earlier version of this
    function ran the model through `:NPDevContract:dsl:canonicalizeModel` first (the same step
    `npdev monitor`'s sync-status check uses) and fed GpuCheckManifestMain that intermediate JSON;
    that JSON only resolves packs, not `contexts[].$ref` entries, and was written to a scratch
    directory with no sibling `contexts/*.json` files for a second resolution pass to find --
    GpuCheckManifestMain failed naming exactly that missing file. Passing model_path directly sidesteps
    the whole problem: the model's own directory already has every pack/context file a full
    resolution needs.

    `ai_tools_jar` is the staged `npdev-ai-tools.jar` (NPDevGenerator/generator's `aiToolsJar` task)
    -- a self-contained fat jar bundling GpuCheckManifestMain, its dsl/generator dependency classes
    AND Jackson, so it runs with a one-entry classpath. The plain `runtimehost-libs/*` directory
    that every other `java -cp` call in this CLI uses does NOT carry a standalone jackson-databind
    jar (WidgetCatalogueMain hand-builds its own JSON for exactly this reason) -- discovered live
    while building this phase (NoClassDefFoundError: com/fasterxml/jackson/databind/ObjectMapper).
    """
    run_id, run_dir = new_run_dir(app_dir)
    artifacts_dir = run_dir / "artifacts"
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    java_cmd = [str(java_bin), "-cp", str(ai_tools_jar),
                "com.npdev.dsl.v1.cli.GpuCheckManifestMain",
                "--canonical", str(model_path), "--out", str(artifacts_dir)]
    completed = subprocess.run(java_cmd, capture_output=True, text=True, timeout=120)
    if completed.returncode != 0:
        shutil.rmtree(run_dir, ignore_errors=True)
        raise GpuCheckError(
            "GpuCheckManifestMain failed: " + (completed.stderr.strip() or completed.stdout.strip()))

    new_manifest_path = artifacts_dir / "manifest.json"
    if not new_manifest_path.exists():
        shutil.rmtree(run_dir, ignore_errors=True)
        raise GpuCheckError(f"GpuCheckManifestMain did not write {new_manifest_path}")
    new_manifest = json.loads(new_manifest_path.read_text(encoding="utf-8"))

    try:
        old_manifest = load_manifest(app_dir)
    except GpuCheckError:
        old_manifest = {"concepts": []}
    diff = diff_manifests(old_manifest, new_manifest)

    shutil.rmtree(run_dir, ignore_errors=True)  # the real run() below makes its own run dir
    result = run(app_dir, engine=engine, concept_names=concept_names, data_dir=None, verify=False,
                 manifest_override=new_manifest, shader_dir=artifacts_dir, mode="preflight")
    result["planDiff"] = diff
    result["modelPath"] = str(model_path)
    # run() already wrote the record without planDiff/modelPath -- patch it in place so `show`
    # returns the complete picture.
    result_path = app_dir / RUNS_REL / result["runId"] / "result.json"
    if result_path.exists():
        result_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def plan_summary_line(result: dict) -> str:
    diff = result.get("planDiff", {})
    new_or_changed = len(diff.get("new", [])) + len(diff.get("changed", []))
    return (f"If you deploy this model, {result['violations']} existing row(s) break "
            f"{_failed_check_count(result)} rule(s) ({new_or_changed} of them new or changed).")


def _failed_check_count(result: dict) -> int:
    return sum(1 for concept in result["concepts"] for check in concept["checks"] if check["status"] == "failed")
