"""Tests for `npdev db detect` (RUN-37).

What is worth pinning is not that the JSON parses but the three ways a discovery tool lies:

  1. **An exited container reported as available.** `docker ps -a` lists stopped containers too; the
     detector must decide "running" from the State field alone, and a stopped one must carry no
     adopt arguments (the selftest.rs lesson: a green dot for something that is not up).
  2. **"Docker is not here" reported as "no databases".** Both are empty lists; they are different facts.
  3. **A secret near the output.** Adopt arguments carry host and port only, never a password.

Run with:
    python -m unittest NPDevCli.tests.test_db_discovery -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_db_discovery as dd  # noqa: E402


def _row(name, image, state, ports):
    return json.dumps({"Names": name, "Image": image, "State": state, "Ports": ports,
                       "Status": "Up 2 hours" if state == "running" else "Exited (0) 3 days ago"})


def _detect(rows, docker_error="", listening=lambda host, port, timeout: False):
    return dd.detect(docker_ps=lambda: ("\n".join(rows), docker_error), listening=listening)


class ImageMatching(unittest.TestCase):
    def test_known_images(self):
        for image, engine in [("postgres:16", "postgres"), ("bitnami/postgresql:16", "postgres"),
                              ("postgis/postgis:16-3.4", "postgres"), ("mysql:8.4", "mysql"),
                              ("mariadb:11", "mysql"), ("mcr.microsoft.com/mssql/server:2022-latest", "sqlserver"),
                              ("localhost:5000/team/postgres:16", "postgres")]:
            self.assertEqual(engine, dd.engine_for_image(image), image)

    def test_unrelated_image_is_not_an_engine(self):
        self.assertIsNone(dd.engine_for_image("nginx:latest"))
        self.assertIsNone(dd.engine_for_image("redis:7"))

    def test_lookalike_images_are_not_databases(self):
        for image in ["prom/mysqld-exporter:v0.15", "postgrest/postgrest", "quay.io/prometheuscommunity/postgres-exporter",
                      "percona/percona-server-mongodb:7", "mcr.microsoft.com/mssql-tools", "confluentinc/cp-kafka:7.6",
                      "registry.example.com/postgres-mirror/nginx:1"]:
            self.assertIsNone(dd.engine_for_image(image), image)

    def test_digest_and_registry_port_forms(self):
        self.assertEqual("postgres", dd.engine_for_image("postgres@sha256:" + "a" * 64))
        self.assertEqual("sqlserver", dd.engine_for_image("docker.io/mssql/server:2022-latest"))
        self.assertEqual("mysql", dd.engine_for_image("mysql/mysql-server:8.0"))

    def test_h2server_is_not_a_container_default_port(self):
        # 9092 is Kafka's default port; h2server is a server engine but never containerized.
        self.assertNotIn(9092, dd.default_ports())
        self.assertEqual({5432, 3306, 1433}, set(dd.default_ports()))


class PortParsing(unittest.TestCase):
    def test_ipv4_ipv6_pair_is_one_mapping(self):
        parsed = dd.parse_published_ports("0.0.0.0:5433->5432/tcp, :::5433->5432/tcp")
        self.assertEqual([{"hostPort": 5433, "containerPort": 5432, "host": "localhost"}], parsed)

    def test_specific_bind_address_is_kept(self):
        parsed = dd.parse_published_ports("127.0.0.1:3307->3306/tcp, 33060/tcp")
        self.assertEqual([{"hostPort": 3307, "containerPort": 3306, "host": "127.0.0.1"}], parsed)

    def test_unpublished_and_ranges_are_skipped(self):
        self.assertEqual([], dd.parse_published_ports("5432/tcp"))
        self.assertEqual([], dd.parse_published_ports("0.0.0.0:5000-5001->5000-5001/tcp"))


class Detect(unittest.TestCase):
    def test_running_container_is_offered_with_adopt_args(self):
        result = _detect([_row("pg", "postgres:16", "running", "0.0.0.0:5433->5432/tcp")])
        (db,) = result["databases"]
        self.assertEqual(("postgres", True, "localhost", 5433), (db["engine"], db["running"], db["host"], db["port"]))
        self.assertEqual(["--engine", "postgres", "--db-host", "localhost", "--db-port", "5433",
                          "--externally-provisioned"], db["initArgs"])

    def test_exited_container_is_never_running_and_never_adoptable(self):
        result = _detect([_row("old-pg", "postgres:16", "exited", "")])
        (db,) = result["databases"]
        self.assertFalse(db["running"])
        self.assertEqual("exited", db["state"])
        self.assertIsNone(db["initArgs"])
        self.assertTrue(db["note"])

    def test_exited_container_with_published_port_still_not_adoptable(self):
        result = _detect([_row("pg", "postgres:16", "exited", "0.0.0.0:5432->5432/tcp")])
        (db,) = result["databases"]
        self.assertFalse(db["running"])
        self.assertIsNone(db["initArgs"])
        self.assertIn("exited", db["note"])

    def test_status_text_does_not_decide_running(self):
        # State says paused while Status text starts with "Up": State wins.
        row = json.dumps({"Names": "pg", "Image": "postgres:16", "State": "paused",
                          "Status": "Up 1 hour (Paused)", "Ports": "0.0.0.0:5432->5432/tcp"})
        (db,) = _detect([row])["databases"]
        self.assertFalse(db["running"])

    def test_running_container_without_published_port_is_flagged(self):
        (db,) = _detect([_row("pg", "postgres:16", "running", "5432/tcp")])["databases"]
        self.assertTrue(db["running"])
        self.assertIsNone(db["port"])
        self.assertIsNone(db["initArgs"])
        self.assertIn("no published port", db["note"])

    def test_custom_image_matched_by_default_port(self):
        (db,) = _detect([_row("mine", "acme/db:1", "running", "0.0.0.0:1434->1433/tcp")])["databases"]
        self.assertEqual(("sqlserver", "port", 1434), (db["engine"], db["matchedBy"], db["port"]))

    def test_kafka_on_9092_is_not_offered_as_a_database(self):
        result = _detect([_row("k", "confluentinc/cp-kafka:7.6", "running", "0.0.0.0:9092->9092/tcp")])
        self.assertEqual([], result["databases"])

    def test_podman_style_list_fields_do_not_crash(self):
        row = json.dumps({"Names": ["pg"], "Image": "postgres:16", "State": "running",
                          "Ports": ["0.0.0.0:5433->5432/tcp"]})
        (db,) = _detect([row])["databases"]
        self.assertEqual(("pg", 5433, True), (db["name"], db["port"], db["running"]))

    def test_malformed_row_fields_are_skipped_not_fatal(self):
        row = json.dumps({"Names": None, "Image": 7, "State": None, "Ports": {"x": 1}})
        self.assertEqual([], _detect([row])["databases"])

    def test_non_database_container_ignored(self):
        self.assertEqual([], _detect([_row("web", "nginx:latest", "running", "0.0.0.0:80->80/tcp")])["databases"])

    def test_garbage_lines_are_skipped(self):
        result = dd.detect(docker_ps=lambda: ("not json\n" + _row("pg", "postgres:16", "running",
                                                                     "0.0.0.0:5432->5432/tcp"), ""),
                           listening=lambda *a: False)
        self.assertEqual(1, len(result["databases"]))

    def test_docker_unavailable_is_not_reported_as_none_found(self):
        result = _detect([], docker_error="docker is not on PATH")
        self.assertEqual("unavailable", result["docker"]["status"])
        self.assertEqual("docker is not on PATH", result["docker"]["detail"])
        self.assertIn("docker is not on PATH", dd.render(result))

    def test_listener_reported_when_no_container_covers_the_port(self):
        result = _detect([], listening=lambda host, port, timeout: port == 3306)
        (db,) = result["databases"]
        self.assertEqual(("listener", "mysql", 3306), (db["source"], db["engine"], db["port"]))
        self.assertIn("has not confirmed", db["note"])

    def test_listener_on_a_running_containers_port_is_not_duplicated(self):
        result = _detect([_row("pg", "postgres:16", "running", "0.0.0.0:5432->5432/tcp")],
                         listening=lambda host, port, timeout: port == 5432)
        self.assertEqual(["docker"], [d["source"] for d in result["databases"]])

    def test_listener_on_an_exited_containers_port_is_still_reported(self):
        # The container is down but something else owns 5432: that is a real, different database.
        result = _detect([_row("pg", "postgres:16", "exited", "0.0.0.0:5432->5432/tcp")],
                         listening=lambda host, port, timeout: port == 5432)
        self.assertEqual(["docker", "listener"], [d["source"] for d in result["databases"]])

    def test_every_engine_gets_a_local_verdict(self):
        result = _detect([], docker_error="docker is not on PATH")
        self.assertEqual(dd.npdev_engines.engine_keys(), [e["engine"] for e in result["engines"]])

    def test_embedded_and_h2server_are_ready_without_docker(self):
        # "supported" next to "no database found" read as a contradiction; these need nothing.
        verdicts = {e["engine"]: e["status"] for e in _detect([], docker_error="docker is not on PATH")["engines"]}
        for key in ("h2local", "h2server", "inmemory"):
            self.assertEqual("ready", verdicts[key], key)

    def test_installed_but_stopped_docker_is_not_reported_as_missing(self):
        result = _detect([], docker_error="docker did not answer: pipe not found")
        self.assertTrue(result["docker"]["installed"])
        pg = next(e for e in result["engines"] if e["engine"] == "postgres")
        self.assertEqual("needs-docker", pg["status"])
        self.assertIn("not running", pg["detail"])

    def test_docker_not_on_path_says_install(self):
        result = _detect([], docker_error="docker is not on PATH")
        self.assertFalse(result["docker"]["installed"])
        pg = next(e for e in result["engines"] if e["engine"] == "postgres")
        self.assertIn("not installed", pg["detail"])

    def test_running_container_makes_its_engine_running_others_ready(self):
        result = _detect([_row("pg", "postgres:16", "running", "0.0.0.0:5433->5432/tcp")])
        verdicts = {e["engine"]: e for e in result["engines"]}
        self.assertEqual("running", verdicts["postgres"]["status"])
        self.assertIn("localhost:5433", verdicts["postgres"]["detail"])
        self.assertEqual("ready", verdicts["mysql"]["status"])

    def test_exited_container_does_not_make_its_engine_running(self):
        result = _detect([_row("pg", "postgres:16", "exited", "0.0.0.0:5432->5432/tcp")])
        pg = next(e for e in result["engines"] if e["engine"] == "postgres")
        self.assertEqual("ready", pg["status"])

    def test_no_secret_anywhere_in_the_output(self):
        row = json.dumps({"Names": "pg", "Image": "postgres:16", "State": "running",
                          "Ports": "0.0.0.0:5432->5432/tcp",
                          "Labels": "POSTGRES_PASSWORD=hunter2"})
        result = _detect([row])
        self.assertNotIn("hunter2", json.dumps(result))
        self.assertNotIn("hunter2", dd.render(result))
        self.assertFalse(any("password" in a.lower() for d in result["databases"] for a in d["initArgs"] or []))


if __name__ == "__main__":
    unittest.main()
