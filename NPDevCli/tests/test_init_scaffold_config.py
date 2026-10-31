"""REG-318 (1) regression: the config.json `npdev init` scaffolds must be right for an app that lives
OUTSIDE the clone -- the only place init writes one.

The seed's config.json (NPDevSamples/npdev-init-seed) carries three things true only inside the clone
or only for the seed's own demo: a relative `$schema` that resolves nowhere once the app is elsewhere,
and port 8081 (config `runtime.serverPort` and `trialDefaults.runtimeUrl`) while the generated app
listens on application.yml's 8080 and the scaffolded README and init's own output say 8080.

Run with:
    python -m unittest NPDevCli.tests.test_init_scaffold_config -v
"""

from __future__ import annotations

import argparse
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _init_args(target: Path) -> argparse.Namespace:
    """The subset of `init`'s parsed Namespace `run_init` reads (same shape as
    test_init_externally_provisioned._init_args)."""
    return argparse.Namespace(
        name=str(target), from_sample=None, json=True,
        engine="h2local", db_host=None, db_port=None, db_user=None, db_password=None,
        externally_provisioned=False,
    )


class InitScaffoldConfigTest(unittest.TestCase):
    def test_schema_points_at_the_real_schema_and_the_port_is_8080(self):
        with TemporaryDirectory(prefix="npdev-init-config-") as tmp:
            target = Path(tmp) / "myapp"
            npdev_cli.run_init(_init_args(target))

            config = json.loads((target / "config.json").read_text(encoding="utf-8"))

            schema = Path(config["$schema"])
            self.assertTrue(schema.is_absolute(), config["$schema"])
            self.assertTrue(schema.exists(), "the scaffolded $schema must resolve from where the app lives: " + config["$schema"])
            self.assertEqual(8080, config["runtime"]["serverPort"])
            self.assertEqual("http://localhost:8080/", config["trialDefaults"]["runtimeUrl"])
            self.assertFalse(any("dedicated port" in note for note in config["trialDefaults"]["notes"]),
                             "the seed demo's 'dedicated port' note does not describe a scaffolded app")
            self.assertNotIn("8081", (target / "config.json").read_text(encoding="utf-8"))

    def test_scaffolded_config_still_validates_against_the_config_schema(self):
        with TemporaryDirectory(prefix="npdev-init-config-") as tmp:
            target = Path(tmp) / "myapp"
            npdev_cli.run_init(_init_args(target))

            config = json.loads((target / "config.json").read_text(encoding="utf-8"))
            schema_path = npdev_cli.repo_root() / "NPDevContract" / "schemas" / "config.schema.json"
            npdev_cli.validate_json_schema(schema_path, target / "config.json")
            self.assertIn("runtime", config)


if __name__ == "__main__":
    unittest.main()
