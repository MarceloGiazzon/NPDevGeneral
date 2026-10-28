"""BUILD-5: two apps whose output folders share a parent also share `<parent>/ArtifactNP`; the second
generate must say it is about to replace the first app's artifacts instead of doing it silently."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli


class ArtifactOwnerMarker(unittest.TestCase):
    def test_a_second_app_in_the_same_parent_is_warned(self):
        with TemporaryDirectory() as tmp:
            parent = Path(tmp)
            artifact = parent / "ArtifactNP"
            artifact.mkdir()
            alpha, beta = parent / "alpha-app", parent / "beta-app"
            self.assertIsNone(npdev_cli.artifact_owner_warning(artifact, alpha), "no marker yet: nothing to say")
            npdev_cli.claim_artifact_dir(artifact, alpha)
            self.assertIsNone(npdev_cli.artifact_owner_warning(artifact, alpha), "regenerating the same app is fine")
            warning = npdev_cli.artifact_owner_warning(artifact, beta)
            self.assertIsNotNone(warning)
            self.assertIn(str(alpha), warning)
            self.assertIn("folder of its own", warning)

    def test_claim_skips_a_missing_artifact_dir(self):
        with TemporaryDirectory() as tmp:
            artifact = Path(tmp) / "ArtifactNP"
            npdev_cli.claim_artifact_dir(artifact, Path(tmp) / "alpha-app")
            self.assertFalse(artifact.exists())


if __name__ == "__main__":
    unittest.main()
