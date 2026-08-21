"""MON-9: the Monitor reports whether an app keeps its data.

Measured 2026-08-17: `_ops/resolved-db-plan.json` is the single file `probe_app` reads per app, and
it carried no schema-lifecycle information at all -- the posture was consumed at generation time and
never surfaced to an operator. That was tolerable while every posture preserved data. STOR-16's
`Ephemeral` makes it a safety question: an app that empties itself on every start must not look
identical to one that does not.

The three-state answer is the substance here. "Unknown" is not a placeholder for "we will fill this
in later" -- it is the honest report for an app generated before the field existed, and the test
below pins that it is never rounded up to "preserved".

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_monitor_data_policy -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_monitor  # noqa: E402


class DataPolicyProbeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-mon9-")
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def _app(self, plan: dict) -> Path:
        app = self.root / "DemoApp"
        ops = app / "_ops"
        ops.mkdir(parents=True)
        (ops / "resolved-db-plan.json").write_text(json.dumps(plan), encoding="utf-8")
        return app

    def test_an_ephemeral_app_reports_ephemeral(self) -> None:
        app = self._app({
            "appId": "demo",
            "engine": "H2Local",
            "schemaLifecycle": {
                "strategy": "Ephemeral",
                "dataPolicy": "ephemeral",
                "allowDestructiveRecreate": True,
                "scope": "NpdevOwnedTablesOnly",
                "ownership": "NpdevManaged",
            },
        })

        record = npdev_monitor.probe_app(app)

        self.assertEqual("ephemeral", record["dataPolicy"])
        self.assertEqual("Ephemeral", record["schemaLifecycle"]["strategy"])

    def test_a_keep_existing_app_reports_preserved(self) -> None:
        app = self._app({
            "appId": "demo",
            "engine": "H2Local",
            "schemaLifecycle": {
                "strategy": "KeepExistingIfCompatible",
                "dataPolicy": "preserved",
                "allowDestructiveRecreate": False,
                "scope": "NpdevOwnedTablesOnly",
                "ownership": "NpdevManaged",
            },
        })

        record = npdev_monitor.probe_app(app)

        self.assertEqual("preserved", record["dataPolicy"])

    def test_an_app_generated_before_this_field_reports_unknown_not_preserved(self) -> None:
        """The backward-compatibility case, and the one that matters. Every app on this machine was
        generated before MON-9; guessing "preserved" for them would be a reassurance the plan file
        cannot support, and would be wrong for exactly the apps most worth warning about."""
        app = self._app({"appId": "demo", "engine": "H2Local", "schemaFingerprint": "sha256:x"})

        record = npdev_monitor.probe_app(app)

        self.assertEqual("unknown", record["dataPolicy"])
        self.assertIsNone(record["schemaLifecycle"])

    def test_the_posture_survives_redaction(self) -> None:
        """`npdev monitor logs export` redacts the plan before it leaves the machine, and the point
        of the export is that someone else reads it. A posture that redaction ate would be missing
        from exactly the artefact a support conversation is built on -- while the password it exists
        to catch must still go."""
        payload = {
            "schemaLifecycle": {"strategy": "Ephemeral", "dataPolicy": "ephemeral"},
            "dataPolicy": "ephemeral",
            "password": "hunter2",
            "jdbcUrl": "jdbc:h2:./data/db;password=hunter2",
        }

        redacted = npdev_monitor.redact(payload)

        self.assertEqual("Ephemeral", redacted["schemaLifecycle"]["strategy"])
        self.assertEqual("ephemeral", redacted["dataPolicy"])
        self.assertEqual("<redacted>", redacted["password"])
        self.assertIn("password=<redacted>", redacted["jdbcUrl"])

    def test_redaction_does_not_eat_the_word_passed(self) -> None:
        """MON-14. `_SECRET_KEYS` is used with `search()`, so the original `pass(word)?` matched the
        word **passed** and replaced the value of any `passed`/`summary.passed` field. Measured
        before the fix: `{'summary': {'passed': 12}}` -> `{'summary': {'passed': '<redacted>'}}`.

        That is the worst shape a redactor can fail in -- the result is still well-formed JSON, so an
        operator reads quietly wrong numbers rather than getting an error. And redaction is mandatory
        on the export path, so any test-shaped payload joining a bundle was silently corrupted.

        Both directions are asserted here: the innocent keys must survive AND the credentials must
        still go, because a "fix" that simply stopped redacting would also make this pass.
        """
        payload = {
            "summary": {"passed": 12, "failed": 0},
            "passCount": 7,
            "passRate": 0.9,
            "keyCount": 3,
            # ... while every genuine credential shape still goes.
            "password": "hunter2",
            "dbPassword": "hunter2",
            "passwd": "hunter2",
            "passphrase": "correct horse",
            "pass": "hunter2",
            "apiKey": "k",
            "api_key": "k",
            "token": "t",
            "secret": "s",
            "privateKey": "pk",
        }

        redacted = npdev_monitor.redact(payload)

        self.assertEqual({"passed": 12, "failed": 0}, redacted["summary"])
        self.assertEqual(7, redacted["passCount"])
        self.assertEqual(0.9, redacted["passRate"])
        self.assertEqual(3, redacted["keyCount"])
        for credential_key in (
            "password", "dbPassword", "passwd", "passphrase", "pass",
            "apiKey", "api_key", "token", "secret", "privateKey",
        ):
            self.assertEqual("<redacted>", redacted[credential_key], credential_key)


if __name__ == "__main__":
    unittest.main()
