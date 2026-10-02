"""Tests for `npdev gpu-check` (GPU-1, G3/G5/G6) -- the data-rule sweep over an app's exported CSV
data: packing, the numpy CPU twin, host checks, run records, and the CLI verbs.

The fixture is a hand-built manifest in exactly the shape GpuCheckManifestBuilder emits (its own
Java test pins that side), over CSV files written in `npdev db export`'s format. Every expected
violation count below is derived by hand from the rows, so a twin that drifts from the WGSL
semantics fails here on a number, not on a crash. `export_tables` (a subprocess to this same CLI)
and the Java manifest builder `plan` shells to are mocked -- both are exercised for real by the
gates that build and boot apps.

The GPU-vs-twin test runs only where a real adapter exists (`wgpu` + a GPU); everywhere else it is
skipped, never faked.
"""

from __future__ import annotations

import copy
import io
import json
import shutil
import sys
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli  # noqa: E402
import npdev_gpu_check as gpu_check  # noqa: E402

try:
    import numpy  # noqa: F401
    HAVE_NUMPY = True
except ImportError:
    HAVE_NUMPY = False


# ------------------------------------------------------------------ portable-tree builders
def var(name):
    return {"k": "var", "name": name}


def num(v):
    return {"k": "lit", "t": "num", "v": v}


def strlit(v):
    return {"k": "lit", "t": "str", "v": v}


def boollit(v):
    return {"k": "lit", "t": "bool", "v": v}


NULL = {"k": "lit", "t": "null", "v": None}


def bin_(op, left, right):
    return {"k": "bin", "op": op, "l": left, "r": right}


def un(op, a):
    return {"k": "un", "op": op, "a": a}


def call(name, *args):
    return {"k": "call", "name": name, "args": list(args)}


def check(bit, cid, kind, tree):
    return {"bit": bit, "id": cid, "kind": kind, "fields": [], "message": cid + " message", "tree": tree}


ORDER_COLUMNS = [
    {"word": 1, "nullBit": 0, "field": "qty", "column": "qty", "encoding": "i32"},
    {"word": 2, "nullBit": 1, "field": "price", "column": "price", "encoding": "fixed32", "scale": 2},
    {"word": 3, "nullBit": 2, "field": "active", "column": "active", "encoding": "bool"},
    {"word": 4, "nullBit": 3, "field": "status", "column": "status", "encoding": "enum_index",
     "enumValues": ["OPEN", "CLOSED"]},
    {"word": 5, "nullBit": 4, "field": "code", "column": "code", "encoding": "str_len"},
]

ORDER_CHECKS = [
    check(0, "Order.qty.required", "required", call("__notNull", var("qty"))),
    check(1, "Order.qty.min", "min", call("__nullOr", var("qty"), bin_(">=", var("qty"), num("1")))),
    check(2, "Order.price.min", "min", call("__nullOr", var("price"), bin_(">=", var("price"), num("0.5")))),
    check(3, "Order.status.enum", "enum", call("__nullOr", var("status"), call("__inEnum", var("status")))),
    check(4, "Order.code.maxLength", "maxLength",
          call("__nullOr", var("code"), bin_("<=", call("__len", var("code")), num("5")))),
    # (qty + 1 > 1) && !(code == '')
    check(5, "Order.inv.positive", "invariant",
          bin_("&&", bin_(">", bin_("+", var("qty"), num("1")), num("1")),
               un("!", bin_("==", var("code"), strlit(""))))),
    # status == 'OPEN' || active
    check(6, "Order.inv.openOrActive", "invariant",
          bin_("||", bin_("==", var("status"), strlit("OPEN")), var("active"))),
    # 'CLOSED' == status || (-qty < 0 && status != null)
    check(7, "Order.inv.closedOrNegated", "invariant",
          bin_("||", bin_("==", strlit("CLOSED"), var("status")),
               bin_("&&", bin_("<", un("-", var("qty")), num("0")), bin_("!=", var("status"), NULL)))),
    # qty == qty && true && !(null == code) && price - 1 < price && status != 'GONE'
    check(8, "Order.inv.tautology", "invariant",
          bin_("&&", bin_("&&", bin_("&&", bin_("==", var("qty"), var("qty")), boollit(True)),
                             un("!", bin_("==", NULL, var("code")))),
               bin_("&&", bin_("<", bin_("-", var("price"), num("1")), var("price")),
                    bin_("!=", var("status"), strlit("GONE"))))),
]


def order_concept():
    return {
        "concept": "Order", "table": "orders", "idColumn": "id",
        "packs": [{"packId": "Order-p0", "shader": "Order-p0.wgsl", "strideWords": 6,
                   "columns": ORDER_COLUMNS, "checks": ORDER_CHECKS}],
        "hostChecks": [
            {"id": "Order.placedAt.required", "kind": "requiredOpaque", "fields": ["placedAt"],
             "columns": ["placed_at"], "message": "placedAt is required"},
            {"id": "Order.code.unique", "kind": "unique", "fields": ["code"], "columns": ["code"],
             "message": "code must be unique"},
            {"id": "Order.customer.reference", "kind": "reference", "fields": ["customer"],
             "columns": ["customer_id"], "message": "customer must exist", "targetConcept": "Customer",
             "targetTable": "customers", "targetIdColumn": "id"},
        ],
        "skipped": [{"id": "Order.inv.upper", "kind": "invariant", "source": "upper(code) == 'X'",
                     "reason": "uses the function 'upper', which only the app can evaluate"}],
    }


def manifest():
    return copy.deepcopy({
        "schemaVersion": "npdev-gpu-check-manifest.v1",
        "namespace": "gpu.check.demo",
        "concepts": [
            order_concept(),
            {"concept": "Widget", "table": "widgets", "idColumn": "id",
             "packs": [{"packId": "Widget-p0", "shader": "Widget-p0.wgsl", "strideWords": 2,
                        "columns": [{"word": 1, "nullBit": 0, "field": "weight", "column": "weight",
                                     "encoding": "fixed32", "scale": 0}],
                        "checks": [check(0, "Widget.weight.min", "min",
                                         call("__nullOr", var("weight"), bin_(">=", var("weight"), num("0"))))]}],
             "hostChecks": [], "skipped": []},
            {"concept": "Ghost", "table": "ghosts", "idColumn": "id",
             "packs": [{"packId": "Ghost-p0", "shader": "Ghost-p0.wgsl", "strideWords": 2,
                        "columns": [{"word": 1, "nullBit": 0, "field": "n", "column": "n", "encoding": "i32"}],
                        "checks": [check(0, "Ghost.n.required", "required", call("__notNull", var("n")))]}],
             "hostChecks": [], "skipped": []},
        ],
    })


# Rows chosen so every check's verdict is hand-derivable (see the expected counts in the tests):
#   a: all rules pass.
#   b: qty NULL, price below min, status not in the enum, inactive, customer missing, placed_at NULL.
#   c: same code as a (unique), qty 200.
#   d: code `ab,"cdef` -- a quoted cell with an escaped quote and a comma, 8 UTF-16 units (> 5).
ORDERS_CSV = (
    "ID,QTY,PRICE,ACTIVE,STATUS,CODE,CUSTOMER_ID,PLACED_AT\n"
    "a,5,1.25,true,OPEN,abc,c1,2026-01-01\n"
    "b,,0.10,false,BOGUS,\"\",c9,\n"
    "c,200,3,true,CLOSED,abc,c1,2026-01-02\n"
    "d,7,2.5,TRUE,CLOSED,\"ab,\"\"cdef\",c1,2026-01-03\n"
    "\n"
)
CUSTOMERS_CSV = "id,name\nc1,Ann\n"
WIDGETS_CSV = "id,weight\nw1,1.5\n"  # 1.5 does not fit fixed32 at scale 0 -> the pack is skipped

EXPECTED_PACKED_VIOLATIONS = {
    "Order.qty.required": 1, "Order.qty.min": 0, "Order.price.min": 1, "Order.status.enum": 1,
    "Order.code.maxLength": 1, "Order.inv.positive": 1, "Order.inv.openOrActive": 1,
    "Order.inv.closedOrNegated": 1, "Order.inv.tautology": 0,
}
EXPECTED_HOST_VIOLATIONS = {"Order.placedAt.required": 1, "Order.code.unique": 2, "Order.customer.reference": 1}

# One-check shader in exactly GpuCheckManifestBuilder.buildShaderText's shape, for the GPU twin test.
QTY_SHADER = """\
@group(0) @binding(0) var<storage, read> data : array<u32>;
@group(0) @binding(1) var<storage, read_write> fails : array<u32>;
@group(0) @binding(2) var<uniform> params : vec4<u32>;
fn isNull(base : u32, bit : u32) -> bool { return (data[base] & (1u << bit)) != 0u; }
fn i(base : u32, word : u32) -> i32 { return bitcast<i32>(data[base + word]); }
fn u(base : u32, word : u32) -> u32 { return data[base + word]; }
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid : vec3<u32>) {
    let row = gid.x + gid.y * (65535u * 64u);
    if (row >= params.x) { return; }
    let base = row * params.y;
    var f : u32 = 0u;
    if (!((isNull(base, 0u) || (i(base, 1u) >= 100)))) { f = f | 1u; }
    if (!((isNull(base, 4u) || ((u(base, 5u) * 100u) <= 500)))) { f = f | 2u; }
    fails[row] = f;
}
"""


class _AppFixture:
    def __init__(self, root: Path):
        self.app = root / "App"
        manifest_path = self.app / gpu_check.MANIFEST_REL
        manifest_path.parent.mkdir(parents=True)
        manifest_path.write_text(json.dumps(manifest()), encoding="utf-8")
        (manifest_path.parent / "Order-p0.wgsl").write_text("// order shader\n", encoding="utf-8")
        self.data = root / "export"
        self.data.mkdir()
        (self.data / "orders.csv").write_text(ORDERS_CSV, encoding="utf-8")
        (self.data / "customers.csv").write_text(CUSTOMERS_CSV, encoding="utf-8")
        (self.data / "widgets.csv").write_text(WIDGETS_CSV, encoding="utf-8")


class CsvAndPackingTest(unittest.TestCase):
    def test_csv_parser_matches_csv_row_serializer(self):
        self.assertEqual(gpu_check.parse_csv_line('a,,"",x'), ["a", None, "", "x"])
        self.assertEqual(gpu_check.parse_csv_line('"a,""b"",c"'), ['a,"b",c'])
        self.assertEqual(gpu_check.parse_csv_line(""), [None])

    def test_read_table_matches_headers_case_insensitively_and_refuses_a_missing_column(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "orders.csv"
            path.write_text(ORDERS_CSV, encoding="utf-8")
            table = gpu_check.read_table(path, ["id", "code"])
            self.assertEqual(table["id"], ["a", "b", "c", "d"])
            self.assertEqual(table["code"][3], 'ab,"cdef')
            self.assertEqual(gpu_check.row_count_of(table), 4)
            self.assertEqual(gpu_check.row_count_of({}), 0)
            with self.assertRaises(gpu_check.GpuCheckError):
                gpu_check.read_table(path, ["nope"])

    def test_utf16_length_counts_surrogate_pairs_like_java(self):
        self.assertEqual(gpu_check.utf16_len("abc"), 3)
        self.assertEqual(gpu_check.utf16_len("\U0001F600"), 2)

    @unittest.skipUnless(HAVE_NUMPY, "numpy is not installed")
    def test_packing_refuses_values_that_do_not_fit_the_declared_scale(self):
        pack = manifest()["concepts"][1]["packs"][0]
        with self.assertRaisesRegex(OverflowError, "does not fit"):
            gpu_check.pack_concept_pack(pack, {"weight": ["1.5"]}, 1)
        with self.assertRaisesRegex(OverflowError, "not a number"):
            gpu_check.pack_concept_pack(pack, {"weight": ["heavy"]}, 1)

    def test_missing_manifest_names_the_config_switch(self):
        with TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(gpu_check.GpuCheckError, "checks.gpuArtifacts"):
                gpu_check.load_manifest(Path(tmp))


@unittest.skipUnless(HAVE_NUMPY, "numpy is not installed")
class RunOnTheCpuTwinTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.fx = _AppFixture(Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def _by_id(self, result):
        return {c["id"]: c for concept in result["concepts"] for c in concept["checks"]}

    def test_every_check_counts_exactly_the_hand_derived_violations(self):
        result = gpu_check.run(self.fx.app, engine="cpu", data_dir=self.fx.data)

        by_id = self._by_id(result)
        for cid, expected in {**EXPECTED_PACKED_VIOLATIONS, **EXPECTED_HOST_VIOLATIONS}.items():
            self.assertEqual(by_id[cid]["violations"], expected, cid)
        self.assertEqual(by_id["Order.qty.required"]["sampleIds"], ["b"])
        self.assertEqual(sorted(by_id["Order.code.unique"]["sampleIds"]), ["a", "c"])
        self.assertEqual(by_id["Order.code.maxLength"]["sampleIds"], ["d"])
        self.assertEqual(by_id["Order.inv.upper"]["status"], "skipped")
        self.assertIn("does not fit", by_id["Widget.weight.min"]["skipReason"])
        self.assertEqual(by_id["Ghost.n.required"]["skipReason"], "no exported rows")

        self.assertEqual(result["violations"], sum(EXPECTED_PACKED_VIOLATIONS.values())
                         + sum(EXPECTED_HOST_VIOLATIONS.values()))
        self.assertEqual(result["rowsChecked"], 5)  # 4 orders + 1 widget; ghosts has no export
        self.assertEqual(result["engine"]["used"], "cpu")
        self.assertEqual(result["verify"], {"mode": "none", "rows": 5, "matched": True, "mismatches": []})

        run_dir = self.fx.app / gpu_check.RUNS_REL / result["runId"]
        self.assertIn("Order.price.min", (run_dir / "summary.txt").read_text(encoding="utf-8"))
        prompt = (run_dir / "ai-prompt.txt").read_text(encoding="utf-8")
        self.assertIn("gpu.check.demo", prompt)
        self.assertIn("Order.inv.upper", prompt)
        self.assertTrue((run_dir / "artifacts" / "Order-p0.wgsl").exists(),
                        "every declared shader is copied, even when the CPU twin ran")

    def test_concept_filter_and_a_clean_run(self):
        result = gpu_check.run(self.fx.app, engine="cpu", data_dir=self.fx.data, concept_names=["Ghost"])
        self.assertEqual([c["concept"] for c in result["concepts"]], ["Ghost"])
        self.assertEqual(result["violations"], 0)
        run_dir = self.fx.app / gpu_check.RUNS_REL / result["runId"]
        self.assertIn("No violations found.", (run_dir / "summary.txt").read_text(encoding="utf-8"))

    def test_gpu_requested_without_a_gpu_is_a_setup_error_not_a_silent_cpu_run(self):
        with mock.patch.object(gpu_check, "gpu_available", return_value=(False, "no GPU adapter found")):
            with self.assertRaisesRegex(gpu_check.GpuCheckError, "--engine gpu requested but unavailable"):
                gpu_check.run(self.fx.app, engine="gpu", data_dir=self.fx.data, concept_names=["Order"])
            result = gpu_check.run(self.fx.app, engine="auto", data_dir=self.fx.data, concept_names=["Order"])
        self.assertEqual(result["engine"]["gpuUnavailableReason"], "no GPU adapter found")
        self.assertEqual(result["engine"]["used"], "cpu")

    def test_a_failed_gpu_dispatch_falls_back_to_the_twin_and_says_why(self):
        with mock.patch.object(gpu_check, "gpu_available", return_value=(True, "fake gpu")), \
                mock.patch.object(gpu_check, "run_gpu_pack", side_effect=RuntimeError("device lost")):
            result = gpu_check.run(self.fx.app, engine="gpu", data_dir=self.fx.data, concept_names=["Order"])
        packs = result["concepts"][0]["packs"]
        self.assertIn("GPU dispatch failed: device lost", json.dumps(packs))
        self.assertEqual(self._by_id(result)["Order.price.min"]["violations"], 1)

    def test_records_list_history_show_and_bundle(self):
        first = gpu_check.run(self.fx.app, engine="cpu", data_dir=self.fx.data)

        listed = gpu_check.list_checks(self.fx.app)
        engines = {c["id"]: c["engine"] for c in listed}
        self.assertEqual(engines["Order.qty.min"], "gpu")
        self.assertEqual(engines["Order.code.unique"], "host")
        self.assertEqual(engines["Order.inv.upper"], "skipped")

        history = gpu_check.history(self.fx.app)
        self.assertEqual(history[0]["runId"], first["runId"])
        broken = self.fx.app / gpu_check.RUNS_REL / "0-broken"
        broken.mkdir()
        (broken / "result.json").write_text("{not json", encoding="utf-8")
        self.assertEqual(len(gpu_check.history(self.fx.app)), 1, "an unreadable record is skipped")

        shown = gpu_check.show(self.fx.app, first["runId"])
        self.assertIn("aiPrompt", shown)
        self.assertIn("Order-p0.wgsl", shown["shaders"])
        with self.assertRaises(gpu_check.GpuCheckError):
            gpu_check.show(self.fx.app, "no-such-run")

        export_dir = self.fx.app / gpu_check.RUNS_REL / first["runId"] / "export"
        export_dir.mkdir()
        (export_dir / "orders.csv").write_text(ORDERS_CSV, encoding="utf-8")
        zip_path = gpu_check.bundle(self.fx.app, first["runId"], Path(self._tmp.name) / "out" / "run.zip")
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertIn("result.json", names)
        self.assertFalse(any(n.startswith("export/") for n in names), "the CSV export never leaves the app")
        with self.assertRaises(gpu_check.GpuCheckError):
            gpu_check.bundle(self.fx.app, "no-such-run", Path(self._tmp.name) / "x.zip")
        self.assertEqual(gpu_check.history(Path(self._tmp.name) / "nowhere"), [])

    def test_bench_times_the_twin_and_writes_calibration_only_with_a_gpu(self):
        def fake_export(app_dir, out_dir, tables):
            out_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy(self.fx.data / "orders.csv", out_dir / "orders.csv")
            return 1.0

        calibration = Path(self._tmp.name) / "calibration.json"
        with mock.patch.object(gpu_check, "export_tables", side_effect=fake_export), \
                mock.patch.object(gpu_check, "gpu_available", return_value=(False, "no GPU adapter found")), \
                mock.patch.object(gpu_check, "CALIBRATION_FILE", calibration):
            result = gpu_check.bench(self.fx.app, rows=400)
        self.assertEqual(result["rows"], 400)
        self.assertEqual(result["checks"], len(ORDER_CHECKS))
        self.assertFalse(result["gpuAvailable"])
        self.assertFalse(calibration.exists())

        with mock.patch.object(gpu_check, "CALIBRATION_FILE", calibration):
            gpu_check.save_calibration("fake gpu", 123.0)
            self.assertEqual(gpu_check.load_calibration()["rowChecksBreakEven"], 123.0)
            calibration.write_text("{broken", encoding="utf-8")
            self.assertEqual(gpu_check.load_calibration(), {})

    def test_break_even_separates_the_gpus_fixed_cost_from_its_per_row_cost(self):
        # The live G4 numbers (MX330, 10M rows x 2 checks): numpy 317 ms; GPU init 1745 + upload 243
        # + dispatch 252. The GPU's per-row-check cost (495 ms) is above numpy's, so it never wins.
        live = {"gpuInit": 1745.0, "gpuUpload": 243.0, "gpuDispatch": 252.0}
        self.assertIsNone(gpu_check.row_checks_break_even(20_000_000, 317.0, live))
        # A GPU that is 10x cheaper per row-check pays its 2 s init back at ~3.7M row-checks:
        # 2000 / (300/20M - 30/20M) = 2000 / 1.35e-5.
        fast = {"gpuInit": 2000.0, "gpuUpload": 10.0, "gpuDispatch": 20.0}
        self.assertAlmostEqual(gpu_check.row_checks_break_even(20_000_000, 300.0, fast), 2000 / (270 / 20_000_000))
        self.assertIsNone(gpu_check.row_checks_break_even(0, 1.0, fast))

    def test_auto_never_picks_the_gpu_when_calibration_says_it_never_wins(self):
        calibration = Path(self._tmp.name) / "calibration.json"
        with mock.patch.object(gpu_check, "CALIBRATION_FILE", calibration), \
                mock.patch.object(gpu_check, "gpu_available", return_value=(True, "fake gpu")), \
                mock.patch.object(gpu_check, "run_gpu_pack") as gpu:
            gpu_check.save_calibration("fake gpu", None)
            result = gpu_check.run(self.fx.app, engine="auto", data_dir=self.fx.data, concept_names=["Order"])
            gpu.assert_not_called()
            gpu_check.save_calibration("fake gpu", 1.0)
            gpu.return_value = (numpy.zeros(4, dtype=numpy.uint32), {"gpuDispatch": 1.0})
            gpu_check.run(self.fx.app, engine="auto", data_dir=self.fx.data, concept_names=["Order"])
            gpu.assert_called_once()
        self.assertEqual(result["engine"]["used"], "cpu")

    def test_bench_refuses_an_app_with_no_export(self):
        with mock.patch.object(gpu_check, "export_tables", return_value=1.0):
            with self.assertRaisesRegex(gpu_check.GpuCheckError, "export produced no file"):
                gpu_check.bench(self.fx.app, rows=10)

    def test_plan_sweeps_current_data_against_a_candidate_manifest_and_diffs_it(self):
        candidate = manifest()
        order = candidate["concepts"][0]
        order["packs"][0]["checks"][1]["tree"] = call("__nullOr", var("qty"), bin_(">=", var("qty"), num("10")))
        order["packs"][0]["checks"].append(check(9, "Order.qty.max", "max",
                                                 call("__nullOr", var("qty"), bin_("<=", var("qty"), num("100")))))
        order["hostChecks"] = order["hostChecks"][:2]

        def fake_java(cmd, **kwargs):
            out_dir = Path(cmd[cmd.index("--out") + 1])
            (out_dir / "manifest.json").write_text(json.dumps(candidate), encoding="utf-8")
            (out_dir / "Order-p0.wgsl").write_text("// candidate shader\n", encoding="utf-8")
            return mock.Mock(returncode=0, stdout="", stderr="")

        def fake_export(app_dir, out_dir, tables):
            shutil.copytree(self.fx.data, out_dir, dirs_exist_ok=True)
            return 1.0

        with mock.patch.object(gpu_check.subprocess, "run", side_effect=fake_java), \
                mock.patch.object(gpu_check, "export_tables", side_effect=fake_export):
            result = gpu_check.plan(self.fx.app, Path("model.json"), Path("java"), Path("ai-tools.jar"),
                                    engine="cpu", concept_names=["Order"])

        self.assertEqual(result["mode"], "preflight")
        self.assertEqual(result["planDiff"], {"new": ["Order.qty.max"], "changed": ["Order.qty.min"],
                                              "removed": ["Order.customer.reference"]})
        by_id = self._by_id(result)
        self.assertEqual(by_id["Order.qty.min"]["violations"], 2, "a (qty 5) and d (qty 7) now break qty >= 10")
        self.assertEqual(by_id["Order.qty.max"]["violations"], 1, "c (qty 200) breaks the new max")
        line = gpu_check.plan_summary_line(result)
        self.assertIn("(2 of them new or changed)", line)
        saved = gpu_check.show(self.fx.app, result["runId"])
        self.assertEqual(saved["planDiff"], result["planDiff"], "the record is patched with the diff")
        # The candidate's shaders must still exist when run() reads them -- they once sat in a
        # directory plan() deleted first, so every plan silently ran on the CPU (G4).
        self.assertEqual(saved["shaders"]["Order-p0.wgsl"], "// candidate shader\n")

    def test_a_gpu_requested_pack_with_no_shader_says_why_it_ran_on_the_twin(self):
        (self.fx.app / gpu_check.MANIFEST_REL.parent / "Order-p0.wgsl").unlink()
        with mock.patch.object(gpu_check, "gpu_available", return_value=(True, "fake gpu")):
            result = gpu_check.run(self.fx.app, engine="gpu", data_dir=self.fx.data, concept_names=["Order"])
        self.assertIn("Order-p0.wgsl not found", json.dumps(result["concepts"][0]["packs"]))

    def test_plan_reports_a_java_failure_and_leaves_no_run_behind(self):
        with mock.patch.object(gpu_check.subprocess, "run",
                               return_value=mock.Mock(returncode=1, stdout="", stderr="bad model")):
            with self.assertRaisesRegex(gpu_check.GpuCheckError, "bad model"):
                gpu_check.plan(self.fx.app, Path("model.json"), Path("java"), Path("ai-tools.jar"))
        with mock.patch.object(gpu_check.subprocess, "run",
                               return_value=mock.Mock(returncode=0, stdout="", stderr="")):
            with self.assertRaisesRegex(gpu_check.GpuCheckError, "did not write"):
                gpu_check.plan(self.fx.app, Path("model.json"), Path("java"), Path("ai-tools.jar"))
        self.assertEqual(gpu_check.history(self.fx.app), [])

    def test_export_failure_is_a_setup_error(self):
        with mock.patch.object(gpu_check.subprocess, "run",
                               return_value=mock.Mock(returncode=3, stdout="", stderr="no database")):
            with self.assertRaisesRegex(gpu_check.GpuCheckError, "exit 3"):
                gpu_check.export_tables(self.fx.app, self.fx.data, ["orders"])


@unittest.skipUnless(HAVE_NUMPY, "numpy is not installed")
class CliVerbsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.fx = _AppFixture(Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def _main(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = npdev_cli.main(["gpu-check", *argv])
        return code, out.getvalue(), err.getvalue()

    def test_run_exits_1_on_violations_and_the_record_verbs_read_it_back(self):
        code, out, _ = self._main("run", "--app", str(self.fx.app), "--engine", "cpu",
                                  "--data", str(self.fx.data), "--json")
        self.assertEqual(code, 1)
        payload = json.loads(out)
        self.assertEqual(payload["exitCode"], 1)
        run_id = payload["result"]["runId"]

        code, out, _ = self._main("run", "--app", str(self.fx.app), "--engine", "cpu",
                                  "--data", str(self.fx.data), "--concepts", "Order")
        self.assertEqual(code, 1)
        self.assertIn("FAIL [1] Order.price.min", out)

        code, out, _ = self._main("list", "--app", str(self.fx.app), "--json")
        self.assertEqual(code, 0)
        self.assertTrue(any(c["id"] == "Order.code.unique" for c in json.loads(out)["checks"]))
        code, out, _ = self._main("list", "--app", str(self.fx.app))
        self.assertIn("check(s):", out)

        for extra in ((), ("--json",)):
            code, out, _ = self._main("history", "--app", str(self.fx.app), *extra)
            self.assertEqual(code, 0)
            self.assertIn(run_id, out)
            code, out, _ = self._main("show", "--app", str(self.fx.app), "--run", run_id, *extra)
            self.assertEqual(code, 0)
            self.assertIn("Order.price.min", out)

        zip_path = Path(self._tmp.name) / "bundle.zip"
        code, _, _ = self._main("bundle", "--app", str(self.fx.app), "--run", run_id, "--out", str(zip_path))
        self.assertEqual(code, 0)
        self.assertTrue(zip_path.exists())

    def test_a_missing_manifest_is_exit_1_with_a_reason_in_both_output_modes(self):
        empty = Path(self._tmp.name) / "empty"
        empty.mkdir()
        code, out, _ = self._main("list", "--app", str(empty), "--json")
        self.assertEqual(code, 1)
        self.assertIn("checks.gpuArtifacts", json.loads(out)["detail"])
        code, _, err = self._main("run", "--app", str(empty), "--engine", "cpu")
        self.assertEqual(code, 1)
        self.assertIn("npdev gpu-check run:", err)
        for verb in (("show", "--run", "x"), ("bundle", "--run", "x", "--out", str(empty / "x.zip"))):
            code, _, _ = self._main(verb[0], "--app", str(empty), *verb[1:])
            self.assertEqual(code, 1)


@unittest.skipUnless(HAVE_NUMPY and gpu_check.gpu_available()[0], "no wgpu GPU adapter on this machine")
class GpuMatchesTheTwinTest(unittest.TestCase):
    """The headline guarantee: the generated shader and the numpy twin agree bit for bit."""

    def test_a_real_dispatch_agrees_with_the_twin_on_every_row(self):
        with TemporaryDirectory() as tmp:
            fx = _AppFixture(Path(tmp))
            only_qty = manifest()
            # qty.min and code.maxLength in one pack whose scale is 2 (from price): the shader above is
            # what GpuWgslEmitter writes for these two trees -- literal 1 -> 100, and the raw str_len
            # word rescaled by 100u before it meets maxLength's literal 5 -> 500.
            qty_check, code_check = copy.deepcopy(ORDER_CHECKS[1]), copy.deepcopy(ORDER_CHECKS[4])
            qty_check["bit"], code_check["bit"] = 0, 1
            only_qty["concepts"][0]["packs"][0]["checks"] = [qty_check, code_check]
            shader_dir = Path(tmp) / "shaders"
            shader_dir.mkdir()
            (shader_dir / "Order-p0.wgsl").write_text(QTY_SHADER, encoding="utf-8")
            (fx.data / "orders.csv").write_text(
                "id,qty,price,active,status,code,customer_id,placed_at\n"
                + "".join(f"r{i},{(i % 7) - 3},1,true,OPEN,{'x' * (i % 9)},c1,d\n" for i in range(300)),
                encoding="utf-8")

            result = gpu_check.run(fx.app, engine="gpu", data_dir=fx.data, concept_names=["Order"], verify=True,
                                   manifest_override=only_qty, shader_dir=shader_dir)

            self.assertEqual(result["engine"]["used"], "gpu")
            self.assertEqual(result["verify"]["mode"], "full")
            self.assertTrue(result["verify"]["matched"], result["verify"]["mismatches"])
            qty_min = next(c for c in result["concepts"][0]["checks"] if c["id"] == "Order.qty.min")
            self.assertEqual(qty_min["violations"], sum(1 for i in range(300) if (i % 7) - 3 < 1))
            code_max = next(c for c in result["concepts"][0]["checks"] if c["id"] == "Order.code.maxLength")
            self.assertEqual(code_max["violations"], sum(1 for i in range(300) if i % 9 > 5))


if __name__ == "__main__":
    unittest.main()
