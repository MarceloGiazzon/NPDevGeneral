"""Tests for `npdev probe` (VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27 P1).

Unit + fixture-only: the XML parsing, the 2x2 intersection math, the human table's leading row, and
the "execution reach" naming rule are all testable with no JVM, no app and no download. The live
behaviours (agent really attaches, jacococli really reports) belong to the P1 acceptance walk, not
here -- these protect the decision code so it does not "ship wrong" silently.

SYNTAX RULE: these run from the repo; nothing here is a check-*.py script.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_probe  # type: ignore

REPO_ROOT = Path(__file__).resolve().parents[2]

SAMPLE_JACOCO_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="probe">
  <package name="com/finalexec/db">
    <class name="com/finalexec/db/SchemaVerifyMain">
      <method name="main" desc="()V">
        <counter type="INSTRUCTION" missed="8" covered="6"/>
        <counter type="LINE" missed="4" covered="3"/>
        <counter type="BRANCH" missed="2" covered="0"/>
      </method>
      <counter type="INSTRUCTION" missed="8" covered="6"/>
      <counter type="LINE" missed="4" covered="3"/>
      <counter type="BRANCH" missed="2" covered="0"/>
    </class>
    <class name="com/finalexec/db/JdbcSchemaStore">
      <method name="boot" desc="()V">
        <counter type="INSTRUCTION" missed="0" covered="30"/>
        <counter type="LINE" missed="0" covered="12"/>
      </method>
      <method name="probe" desc="()V">
        <counter type="INSTRUCTION" missed="14" covered="0"/>
        <counter type="LINE" missed="5" covered="0"/>
      </method>
      <counter type="INSTRUCTION" missed="14" covered="30"/>
      <counter type="LINE" missed="5" covered="12"/>
    </class>
  </package>
</report>
"""

SAMPLE_TEST_BASELINE_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="tests">
  <package name="com/finalexec/db">
    <class name="com/finalexec/db/JdbcSchemaStore">
      <counter type="INSTRUCTION" missed="20" covered="20"/>
      <counter type="LINE" missed="8" covered="9"/>
    </class>
  </package>
</report>
"""


def _parse_classes_from_sample() -> list[dict]:
    tree = ET.ElementTree(ET.fromstring(SAMPLE_JACOCO_XML))
    with mock.patch.object(ET, "parse", return_value=tree):
        return npdev_probe._parse_jacoco_xml("ignored.xml")


class ParseJacocoXmlTest(unittest.TestCase):
    def test_classes_and_counters(self):
        classes = _parse_classes_from_sample()
        self.assertEqual([c["name"] for c in classes],
                         ["com.finalexec.db.JdbcSchemaStore", "com.finalexec.db.SchemaVerifyMain"])
        by_name = {c["name"]: c for c in classes}
        verify = by_name["com.finalexec.db.SchemaVerifyMain"]
        store = by_name["com.finalexec.db.JdbcSchemaStore"]
        # Class-level counters, not per-method: SchemaVerifyMain 3 covered / 4 missed lines.
        self.assertEqual(verify["reachedLines"], 3)
        self.assertEqual(verify["unreachedLines"], 4)
        # Two methods' LINE counters are NOT added separately -- the class counter wins.
        self.assertEqual(store["reachedLines"], 12)
        self.assertEqual(store["unreachedLines"], 5)
        self.assertEqual(store["reachedInstructions"], 30)


class IntersectionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.classes = _parse_classes_from_sample()
        baseline_tree = ET.ElementTree(ET.fromstring(SAMPLE_TEST_BASELINE_XML))
        with mock.patch.object(ET, "parse", return_value=baseline_tree):
            self.baseline = npdev_probe._parse_test_baseline("tests.xml")

    def test_two_by_two_math(self):
        result = npdev_probe._intersect_reach_and_tests(self.classes, self.baseline)
        cells = result["overlapping"]
        # JdbcSchemaStore: reached=12, tested=9 -> reached&tested 9, reached&UNTESTED 3;
        # unreached=5, tested covers only 9 of 12 reached so testedUnreached 0.
        self.assertEqual(cells["reachedTested"], 9)
        self.assertEqual(cells["reachedUntested"], 3)
        self.assertEqual(cells["testedUnreached"], 0)
        self.assertEqual(cells["neither"], 5)
        # A5: SchemaVerifyMain is in the reach report but NOT in the test baseline -> reported as
        # UNKNOWN, never silently folded into 'unreached'.
        self.assertEqual(result["unknownReachOnly"]["count"], 1)
        self.assertIn("com.finalexec.db.SchemaVerifyMain", result["unknownReachOnly"]["classes"])
        self.assertEqual(result["unknownBaselineOnly"]["count"], 0)


class BaselineDirectoryAggregationTest(unittest.TestCase):
    """A5: `--baseline <dir>` aggregates every jacocoTestReport.xml under it (the kernel gate's
    '42 reports aggregated' shape), with MAX -- never sum -- when a class appears in several."""

    def test_directory_aggregation_uses_max_per_class(self):
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = root / "m1" / "jacocoTestReport.xml"
            second = root / "m2" / "jacocoTestReport.xml"
            first.parent.mkdir(parents=True)
            second.parent.mkdir(parents=True)
            first.write_text(
                '<report name="m1"><package name="com/x"><class name="com/x/A"><counter type="LINE" missed="2" covered="6"/></class></package></report>',
                encoding="utf-8")
            second.write_text(
                '<report name="m2"><package name="com/x"><class name="com/x/A"><counter type="LINE" missed="1" covered="5"/></class>'
                '<class name="com/x/B"><counter type="LINE" missed="0" covered="8"/></class></package></report>',
                encoding="utf-8")
            baseline = npdev_probe._parse_test_baseline(root)
        self.assertEqual(baseline["classes"]["com.x.A"]["testedLines"], 6)  # max, not 11
        self.assertEqual(baseline["classes"]["com.x.B"]["testedLines"], 8)

    def test_directory_without_reports_raises(self):
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as tmp:
            with self.assertRaises(npdev_probe.ProbeError):
                npdev_probe._parse_test_baseline(Path(tmp))


class ReachNamingAndTableTest(unittest.TestCase):
    """S6.4's naming rule is a safety rail, not cosmetics: the metric is execution reach, never
    coverage, and the human table LEADS with reached & UNTESTED when the intersection exists."""

    def _sample_report(self, with_intersection: bool) -> dict:
        classes = _parse_classes_from_sample()
        report = {
            "schemaVersion": npdev_probe.REPORT_SCHEMA,
            "probeRunId": "run-1",
            "metric": "execution-reach",
            "totals": {
                "reachedLines": sum(c["reachedLines"] for c in classes),
                "unreachedLines": sum(c["unreachedLines"] for c in classes),
            },
            "classes": classes,
        }
        if with_intersection:
            report["intersection"] = {
                "overlapping": {"reachedTested": 9, "reachedUntested": 3,
                                "testedUnreached": 0, "neither": 5},
                "unknownReachOnly": {"count": 1, "classes": ["com.finalexec.db.SchemaVerifyMain"]},
                "unknownBaselineOnly": {"count": 0, "classes": []},
            }
        return report

    def test_metric_key_is_never_coverage(self):
        blob = str(self._sample_report(with_intersection=False))
        self.assertNotIn("coverage", blob.lower())

    def test_table_leads_with_reached_untested_when_present(self):
        table = npdev_probe._reach_human_table(self._sample_report(with_intersection=True))
        lines = table.splitlines()
        self.assertTrue(lines[1].startswith("  reached & tested"))
        self.assertTrue(lines[2].startswith("  reached & UNTESTED"))
        self.assertIn("THE HEADLINE", lines[2])
        self.assertNotIn("coverage", table.lower())  # never in the metric name

    def test_table_without_baseline_points_to_p2(self):
        table = npdev_probe._reach_human_table(self._sample_report(with_intersection=False))
        self.assertIn("executed at runtime", table)
        self.assertIn("--baseline", table)


class EnvironmentDiscoveryTest(unittest.TestCase):
    def test_outside_repo_root_is_a_sibling(self):
        expected = REPO_ROOT.parent / (REPO_ROOT.name + "__OutsideRepo")
        self.assertEqual(npdev_probe.outside_repo_root(), expected)

    def test_agent_discovery_returns_none_without_caches(self):
        with mock.patch.object(npdev_probe, "_candidate_cache_roots", return_value=[]):
            self.assertIsNone(npdev_probe.find_agent_dist_jar())


if __name__ == "__main__":
    unittest.main()