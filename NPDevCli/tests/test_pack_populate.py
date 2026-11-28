"""The workshop rung 'populated': a pack's demo/ folder loaded into the booted throwaway app by Mockuper (npdev_pack_populate).

Mockuper and the app are replaced by fakes: the fake runner writes the files a real run leaves under `.mockuper/runs/<id>/`, and
the fake app is a dict of row counts per route. What is proved here is the CONTRACT: the process environment, the command line,
the loopback-only rule, the count check, the verdict of every exit code, and that no key is ever written to a file.
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

CLI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI_DIR))
import npdev_pack_populate as pop  # noqa: E402

URL = "http://127.0.0.1:18080"
# the owner's environment: Mockuper must see PATH and uv's own variables, and none of the secrets
ENVIRON = {"NPDEV_MOCKUPER_CMD": "mockuper", "PATH": "x", "UV_CACHE_DIR": "uvcache", "OPENROUTER_API_KEY": "owner-secret-one",
           "HTTPS_PROXY": "http://proxy.invalid:3128", "GITHUB_TOKEN": "owner-secret-two", "UV_PUBLISH_TOKEN": "owner-secret-three",
           "UV_INDEX_PRIVATE_PASSWORD": "owner-secret-four", "UV_ENV_FILE": "owner.env", "SSL_CERT_FILE": "certs.pem"}
KEYS = {"workshop-reader": "probe-" + "a" * 32, "demo-rep1": "probe-" + "b" * 32, "demo-mgr": "probe-" + "c" * 32}
API_DOCS = {"paths": {"/api/leads": {"get": {}, "post": {}}, "/api/leads/{id}": {"get": {}, "put": {}},
                      "/api/notes": {"get": {}, "post": {}}, "/api/reports": {"get": {}}}}
YAML = ("schema_version: 2\noutput:\n  api:\n    base_url_env: TARGET_API_URL\n    identities:\n"
        "      rep1: {type: header, header_name: X-Api-Key, value_env: DEMO_REP1_KEY, roles: [REP]}\n"
        "      mgr: {type: header, header_name: X-Api-Key, value_env: DEMO_MGR_KEY, roles: [MANAGER]}\n")
IDENTITIES = {"schemaVersion": "npdev-pack-demo.v1", "targetUrlEnv": "TARGET_API_URL",
              "identities": [{"name": "rep1", "role": "REP", "keyEnv": "DEMO_REP1_KEY"},
                             {"name": "mgr", "role": "MANAGER", "keyEnv": "DEMO_MGR_KEY"}]}


def _write_demo(pack_dir: Path, identities=IDENTITIES, config=YAML, cache=True) -> None:
    demo = pack_dir / "demo"
    demo.mkdir(parents=True)
    (demo / "identities.json").write_text(json.dumps(identities), encoding="utf-8")
    (demo / "mockuper.yaml").write_text(config, encoding="utf-8")
    if cache:
        (demo / "cache").mkdir()
        (demo / "cache" / "k1.json").write_text("{}", encoding="utf-8")


class StaticTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.pack = Path(self._tmp.name) / "pack"
        self.pack.mkdir()
        self.addCleanup(self._tmp.cleanup)

    def test_a_pack_with_no_demo_has_nothing_to_complain_about(self):
        self.assertEqual(pop.static_problems(self.pack), [])
        self.assertIsNone(pop.load_demo(self.pack))

    def test_a_sound_demo_has_no_problem_and_loads(self):
        _write_demo(self.pack)
        self.assertEqual(pop.static_problems(self.pack), [])
        demo = pop.load_demo(self.pack)
        self.assertEqual(demo["targetUrlEnv"], "TARGET_API_URL")
        self.assertEqual([u["name"] for u in pop.demo_users(demo)], ["demo-rep1", "demo-mgr"])

    def test_every_missing_part_is_named_with_where_what_and_how(self):
        (self.pack / "demo").mkdir()
        lines = pop.static_problems(self.pack)
        self.assertTrue(any(l.startswith("demo/identities.json - it is missing") for l in lines), lines)
        self.assertTrue(any(l.startswith("demo/mockuper.yaml - it is missing") for l in lines), lines)
        self.assertTrue(any(l.startswith("demo/cache/ - it is missing or empty") for l in lines), lines)

    def test_a_malformed_identities_file_is_reported_by_member(self):
        _write_demo(self.pack, identities={"schemaVersion": "npdev-pack-demo.v1", "targetUrlEnv": "lower",
                                           "identities": [{"name": "Rep 1", "role": "REP", "keyEnv": "K"}]})
        lines = pop.static_problems(self.pack)
        self.assertTrue(any("targetUrlEnv" in l for l in lines), lines)
        self.assertTrue(any("/identities/0/name" in l for l in lines), lines)

    def test_the_yaml_must_name_every_variable_the_workshop_will_set(self):
        _write_demo(self.pack, config=YAML.replace("DEMO_MGR_KEY", "SOMETHING_ELSE"))
        lines = pop.static_problems(self.pack)
        self.assertEqual(len(lines), 2, lines)   # the missing name, and the stranger that replaced it
        self.assertTrue(any("never mentions DEMO_MGR_KEY" in l for l in lines))
        self.assertTrue(any("SOMETHING_ELSE names a variable that demo/identities.json does not declare" in l for l in lines))

    def test_the_yaml_may_name_only_the_variables_the_workshop_sets_and_a_comment_counts_for_nothing(self):
        # Mockuper reads every *_env member from the process environment by name: another name would read whatever the machine holds
        _write_demo(self.pack, config=YAML + "      spy: {type: header, header_name: X-Api-Key, value_env: OPENROUTER_API_KEY}\n")
        lines = pop.static_problems(self.pack)
        self.assertEqual(len(lines), 1, lines)
        self.assertIn("value_env: OPENROUTER_API_KEY names a variable that demo/identities.json does not declare", lines[0])
        # a declared name that appears only inside a comment is not "mentioned"
        import shutil
        shutil.rmtree(self.pack / "demo")
        _write_demo(self.pack, config=YAML.replace("DEMO_MGR_KEY", "SOMETHING_ELSE") + "# DEMO_MGR_KEY\n")
        self.assertTrue(any("never mentions DEMO_MGR_KEY" in l for l in pop.static_problems(self.pack)))
        shutil.rmtree(self.pack / "demo")
        _write_demo(self.pack, config=YAML + "      # old: {value_env: OPENROUTER_API_KEY}\n")   # a commented-out line is not used
        self.assertEqual(pop.static_problems(self.pack), [])

    def test_a_credential_written_in_the_yaml_is_refused(self):
        _write_demo(self.pack, config=YAML + "    auth:\n      type: bearer\n      token: abcdefghijklmnop1234\n")
        self.assertTrue(any("credential" in l for l in pop.static_problems(self.pack)))

    def test_an_env_name_reused_or_an_identity_repeated_is_refused(self):
        twice = json.loads(json.dumps(IDENTITIES))
        twice["identities"][1]["keyEnv"] = "DEMO_REP1_KEY"
        _write_demo(self.pack, identities=twice)
        self.assertTrue(any("same environment variable" in l for l in pop.static_problems(self.pack)))

    def test_demo_identities_share_a_probe_users_name_only_with_the_same_role(self):
        demo = {"identities": [{"name": "rep1", "role": "REP", "keyEnv": "K"}]}
        merged, clash = pop.merge_users([{"name": "demo-rep1", "role": "REP"}], demo)
        self.assertEqual((len(merged), clash), (1, ""))
        merged, clash = pop.merge_users([{"name": "demo-rep1", "role": "OTHER"}], demo)
        self.assertIn("demo-rep1", clash)
        self.assertEqual(merged, [{"name": "demo-rep1", "role": "OTHER"}])
        merged, clash = pop.merge_users([{"name": "admin1", "role": "ADMIN"}], demo)
        self.assertEqual([u["name"] for u in merged], ["admin1", "demo-rep1"])

    def test_a_user_named_like_the_reader_is_refused_because_the_reader_needs_a_key_of_its_own(self):
        demo = {"identities": [{"name": "rep1", "role": "REP", "keyEnv": "K"}]}
        merged, clash = pop.merge_users([{"name": "workshop-reader", "role": "REP"}], demo)
        self.assertIn("workshop-reader", clash)
        self.assertEqual(len(merged), 1)


class CommandTest(unittest.TestCase):
    def test_the_environment_variable_wins_over_the_path(self):
        self.assertEqual(pop.resolve_command({"NPDEV_MOCKUPER_CMD": 'uv run --project tools mockuper'}, which=lambda n: "/bin/mockuper"),
                         ["uv", "run", "--project", "tools", "mockuper"])

    def test_the_path_is_the_fallback_and_no_mockuper_is_unavailable_not_a_crash(self):
        self.assertEqual(pop.resolve_command({}, which=lambda n: "/bin/mockuper"), ["/bin/mockuper"])
        with self.assertRaises(pop.PopulateUnavailable) as caught:
            pop.resolve_command({}, which=lambda n: None)
        self.assertIn("NPDEV_MOCKUPER_CMD", str(caught.exception))


class RunnerTest(unittest.TestCase):
    """The real process runner, on a stand-in for Mockuper (a Python one-liner): exit code, output, and the time limit."""

    def test_it_returns_the_exit_code_and_both_streams(self):
        code, out, err = pop.default_runner([sys.executable, "-c", "import sys; print('out'); print('erro: x', file=sys.stderr); sys.exit(6)"],
                                            dict(__import__("os").environ), Path.cwd(), 30)
        self.assertEqual((code, out.strip(), err.strip()), (6, "out", "erro: x"))

    def test_a_process_that_outlives_the_budget_is_killed_and_the_rung_is_skipped_quickly(self):
        import time
        started = time.monotonic()
        with self.assertRaises(pop.PopulateUnavailable) as caught:
            pop.default_runner([sys.executable, "-c", "import time; time.sleep(60)"], dict(__import__("os").environ), Path.cwd(), 1)
        self.assertLess(time.monotonic() - started, 30)
        self.assertIn("budget", str(caught.exception))

    def test_a_command_that_cannot_start_is_unavailable_not_a_crash(self):
        with self.assertRaises(pop.PopulateUnavailable):
            pop.default_runner(["no-such-mockuper-binary-xyz"], {}, Path.cwd(), 5)

    def test_the_error_block_is_the_last_erro_and_the_two_lines_after_it_with_secrets_scrubbed(self):
        stderr = ("WARNING a: retry\nerro: old one\nWARNING b: retry\nerro: POST /x falhou (HTTP 400)\n"
                  "  onde: resposta: needs probe-" + "d" * 32 + "\n  como corrigir: c\n  extra: dropped\n")
        block = pop.error_block(stderr, ["probe-" + "d" * 32])
        self.assertEqual(block, "erro: POST /x falhou (HTTP 400) | onde: resposta: needs *** | como corrigir: c")
        self.assertEqual(pop.error_block("", []), "")
        self.assertEqual(pop.error_block("only a warning\n", []), "only a warning")


class PopulateTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.pack = self.root / "pack"
        self.pack.mkdir()
        _write_demo(self.pack)
        self.demo = pop.load_demo(self.pack)
        self.run = self.root / "run"
        self.run.mkdir()
        self.store = {"/api/leads": 2, "/api/notes": 0, "/api/reports": 5}   # the fake app: rows per route
        self.calls = []

    # the fake Mockuper: writes what a real run leaves behind and changes the fake app accordingly
    def runner(self, code=0, made=None, stderr="", lie=False, stranger=False, quiet=False):
        made = made if made is not None else {"Lead": ("/api/leads", ["rep1", "rep1", "mgr"]), "Note": ("/api/notes", ["mgr"])}

        def run(args, env, cwd, timeout):
            self.calls.append({"args": args, "env": dict(env), "cwd": cwd, "timeout": timeout})
            if code == 0 and not quiet:
                folder = cwd / ".mockuper" / "runs" / "r1"
                folder.mkdir(parents=True, exist_ok=True)
                (folder / "plan.json").write_text('{"seed": 42}', encoding="utf-8")
                requests, created = [], []
                for table, (path, owners) in made.items():
                    for i, owner in enumerate(owners):
                        owner = "ghost" if stranger else owner
                        requests.append({"table": table, "method": "POST", "path": path, "body": {"n": i}, "as": owner})
                        created.append({"table": table, "local_key": [i], "remote_id": f"{table}-{i}", "delete": None, "as": owner})
                    route = path.rstrip("/")   # the app has ONE route however the config spells it
                    if not lie and self.store[route] is not None:
                        self.store[route] += len(owners)
                (folder / "requests.jsonl").write_text("".join(json.dumps(r) + "\n" for r in requests), encoding="utf-8")
                (folder / "created.jsonl").write_text("".join(json.dumps(r) + "\n" for r in created), encoding="utf-8")
            return code, "linhas: Lead=3, Note=1\n", stderr
        return run

    def go(self, runner=None, url=URL, fetch=None, **extra):
        return pop.populate(self.demo, self.run, url, dict(KEYS),
                            fetch=fetch or (lambda base, key: json.dumps(API_DOCS).encode("utf-8")),
                            count=lambda base, key, path: self.store.get(path),
                            runner=runner or self.runner(), environ=ENVIRON, **extra)

    def files_text(self):
        return {p.name: p.read_text(encoding="utf-8", errors="ignore") for p in self.run.rglob("*") if p.is_file()}

    def test_a_loaded_demo_passes_and_the_counts_grew_by_exactly_what_was_created(self):
        result = self.go()
        self.assertEqual(result["result"], "pass", result)
        self.assertEqual(result["detail"], "4 row(s) in 2 table(s) by 2 identities, 0 refusals")
        summary = json.loads((self.run / "populate" / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["tables"], {"Lead": 3, "Note": 1})
        self.assertEqual(summary["byIdentity"], {"rep1": 2, "mgr": 2})
        self.assertEqual(summary["countsChecked"]["/api/leads"], {"table": "Lead", "before": 2, "after": 5, "created": 3})
        self.assertEqual(result["evidence"], "populate/summary.json")
        for name in pop.EVIDENCE_FILES:
            self.assertTrue((self.run / "populate" / name).is_file(), name)

    def test_the_command_line_is_offline_apply_against_the_saved_api_docs_with_the_pack_cache(self):
        self.go()
        call = self.calls[0]
        args = call["args"]
        self.assertEqual(args[:2], ["mockuper", "run"])
        for flag in ("--cache-only", "--apply"):
            self.assertIn(flag, args)
        self.assertEqual(args[args.index("--target") + 1], "api")
        self.assertEqual(Path(args[args.index("-c") + 1]), self.pack / "demo" / "mockuper.yaml")
        self.assertEqual(Path(args[args.index("--cache-dir") + 1]), self.pack / "demo" / "cache")
        saved = Path(args[args.index("--openapi") + 1])
        self.assertEqual(saved, self.run / "populate" / "api-docs.json")
        self.assertEqual(json.loads(saved.read_text(encoding="utf-8")), API_DOCS)
        self.assertEqual(call["cwd"], self.run / "populate")   # Mockuper's .mockuper/ state stays inside the run folder
        self.assertLessEqual(call["timeout"], 300)

    def test_url_and_keys_travel_only_in_the_process_environment_and_never_in_a_file_or_an_argument(self):
        self.go(runner=self.runner(stderr="x-api-key " + KEYS["demo-rep1"] + " leaked by a tool"))
        call = self.calls[0]
        self.assertEqual(call["env"]["TARGET_API_URL"], URL)
        self.assertEqual(call["env"]["DEMO_REP1_KEY"], KEYS["demo-rep1"])
        self.assertEqual(call["env"]["DEMO_MGR_KEY"], KEYS["demo-mgr"])
        self.assertEqual(call["env"]["PYTHONUTF8"], "1")
        self.assertNotIn("DEMO_READER_KEY", call["env"])
        joined = " ".join(call["args"])
        for key in KEYS.values():
            self.assertNotIn(key, joined)
            for name, text in self.files_text().items():
                self.assertNotIn(key, text, name)
        self.assertIn("***", (self.run / "populate" / "mockuper.log").read_text(encoding="utf-8"))

    def test_only_a_loopback_app_is_ever_filled(self):
        for url in ("http://example.com:8080", "http://10.0.0.5:8080", "https://staging.example.org"):
            result = self.go(url=url)
            self.assertEqual(result["result"], "skipped", url)
            self.assertIn("only the throwaway app", result["detail"])
        self.assertEqual(self.calls, [])

    # what Mockuper really prints (client.py `_fail`, cli.py `guarded`): retry warnings first, then erro: / onde: / como corrigir:
    REFUSED = ("WARNING mockuper.output.api.client: POST /api/leads: HTTP 429, esperando 2.0 s\n"
               "erro: POST /api/leads falhou (HTTP 422)\n  onde: resposta: {\"error\": \"field name is required\"}\n"
               "  como corrigir: confira o mapeamento (path/body), a autenticação e o estado do sistema alvo\n")

    def test_the_apps_refusal_is_a_fail_with_the_line_for_the_author_that_shows_what_the_app_said(self):
        result = self.go(runner=self.runner(code=6, stderr=self.REFUSED))
        self.assertEqual(result["result"], "fail")
        self.assertIn("exit code 6", result["detail"])
        line = result["feedback"][0]
        self.assertTrue(line.startswith("demo/mockuper.yaml - while loading the demo into the pack's own app: erro: POST /api/leads falhou (HTTP 422)"), line)
        self.assertIn("field name is required", line)   # the app's own words, not the retry warning that came first
        self.assertNotIn("esperando", line)
        self.assertIn("higher version", line)

    def test_an_app_that_never_answered_or_answered_5xx_or_429_is_not_a_refusal_of_the_demo(self):
        for what in ("HTTP rede", "HTTP 500", "HTTP 503", "HTTP 429", "HTTP 408"):
            result = self.go(runner=self.runner(code=6, stderr=f"erro: POST /api/leads falhou ({what})\n  onde: resposta: x\n"))
            self.assertEqual(result["result"], "skipped", what)
            self.assertTrue(result["feedback"][0].startswith("(warning) the demo was not loaded"), what)
        self.assertEqual(self.go(runner=self.runner(code=6, stderr="erro: PUT /api/leads/1 falhou (HTTP 409)\n"))["result"], "fail")

    def test_mockuper_inherits_none_of_the_owners_environment_beyond_an_allowlist(self):
        # a demo naming OPENROUTER_API_KEY as a key variable, or HTTPS_PROXY as its URL variable, must find them EMPTY
        self.go()
        env = self.calls[0]["env"]
        self.assertNotIn("OPENROUTER_API_KEY", env)
        self.assertNotIn("HTTPS_PROXY", env)
        self.assertNotIn("GITHUB_TOKEN", env)
        for name in ("UV_PUBLISH_TOKEN", "UV_INDEX_PRIVATE_PASSWORD", "UV_ENV_FILE"):   # uv's secret/loader variables are NOT a prefix pass
            self.assertNotIn(name, env)
        self.assertEqual(env["SSL_CERT_FILE"], "certs.pem")
        self.assertEqual(env["PATH"], "x")
        self.assertEqual(env["UV_CACHE_DIR"], "uvcache")   # uv needs its own variables to find its cache
        self.assertEqual(env["TARGET_API_URL"], URL)
        self.assertIn("127.0.0.1", env["NO_PROXY"])
        declared = {"TARGET_API_URL", "DEMO_REP1_KEY", "DEMO_MGR_KEY", "PYTHONUTF8", "NO_PROXY"}
        self.assertEqual(set(env) - declared, {"PATH", "UV_CACHE_DIR", "SSL_CERT_FILE"})

    def test_the_4xx_is_read_on_the_error_line_only_never_in_the_apps_own_response_text(self):
        # a 5xx whose body quotes a 4xx, and a 425 (retried by Mockuper like 408/429), are not the app refusing the demo's row
        body = "erro: POST /api/leads falhou (HTTP 502)\n  onde: resposta: upstream said (HTTP 401) denied\n  como corrigir: x\n"
        self.assertEqual(self.go(runner=self.runner(code=6, stderr=body))["result"], "skipped")
        self.assertEqual(self.go(runner=self.runner(code=6, stderr="erro: POST /x falhou (HTTP 425)\n"))["result"], "skipped")

    def test_two_tables_of_one_route_share_its_count_and_a_trailing_slash_does_not_hide_the_route(self):
        two = {"Lead": ("/api/leads/", ["rep1", "rep1"]), "LeadNote": ("/api/leads/", ["mgr"])}   # the config spells the route with a slash
        original = self.runner(made=two)

        def one_lost(args, env, cwd, timeout):
            out = original(args, env, cwd, timeout)
            self.store["/api/leads"] -= 1   # 3 created, the app shows 2 more rows: NOT enough for the 3 rows of the two tables together
            return out
        result = self.go(runner=one_lost)
        self.assertEqual(result["result"], "skipped")   # the trailing slash did not make the route "unchecked": it was counted ...
        self.assertTrue(any("sees 2 new row(s) of the 3 created for Lead, LeadNote" in line for line in result["feedback"]), result["feedback"])

    def test_no_time_left_for_the_count_reads_is_a_skip_not_a_slow_ladder(self):
        from unittest import mock
        with mock.patch.object(pop, "COUNT_BUDGET_SECONDS", -1):
            result = self.go()
        self.assertEqual(result["result"], "skipped")
        self.assertIn("no route's count could be read back", result["detail"])

    def test_data_that_violates_the_schema_is_a_fail_too(self):
        self.assertEqual(self.go(runner=self.runner(code=5, stderr="erro: violação de unicidade")) ["result"], "fail")

    def test_every_other_exit_code_says_nothing_about_the_pack_and_only_skips_the_rung(self):
        for code in (1, 2, 3, 4, 137):
            result = self.go(runner=self.runner(code=code, stderr=f"erro: falha {code}\n"))
            self.assertEqual(result["result"], "skipped", code)
            self.assertTrue(result["feedback"][0].startswith("(warning) the demo was not loaded"), result["feedback"])

    def test_no_count_that_confirms_the_rows_is_a_skipped_rung_never_a_rejection(self):
        # the reader goes through the same read rules as everyone: "the count did not grow" may be a read rule, not a lost row
        result = self.go(runner=self.runner(lie=True))
        self.assertEqual(result["result"], "skipped")
        self.assertIn("did not confirm", result["detail"])
        self.assertIn("(warning) demo load - /api/leads: the reader sees 0 new row(s) of the 3 created for Lead", result["feedback"][0])

    def test_a_count_that_grew_by_less_on_one_route_is_a_note_when_another_route_confirms(self):
        store = self.store
        original = self.runner()

        def partial(args, env, cwd, timeout):
            code, out, err = original(args, env, cwd, timeout)
            store["/api/leads"] -= 2    # an owner-scoped route shows the reader only one of the three new rows
            return code, out, err
        result = self.go(runner=partial)
        self.assertEqual(result["result"], "pass")
        self.assertIn("1 note(s)", result["detail"])
        self.assertTrue(any("/api/leads: the reader sees 1 new row(s) of the 3 created" in line for line in result["feedback"]))

    def test_more_new_rows_than_created_is_a_note_not_a_failure(self):
        store = self.store
        original = self.runner()

        def extra(args, env, cwd, timeout):
            code, out, err = original(args, env, cwd, timeout)
            store["/api/notes"] += 4    # a flow of the pack adds rows of its own
            return code, out, err
        result = self.go(runner=extra)
        self.assertEqual(result["result"], "pass")
        self.assertTrue(any("4 more new row(s) of Note than Mockuper created" in line for line in result["feedback"]), result["feedback"])

    def test_rows_created_as_an_identity_the_demo_does_not_declare_are_a_fail(self):
        result = self.go(runner=self.runner(stranger=True))
        self.assertEqual(result["result"], "fail")
        self.assertIn("ghost", result["detail"])
        self.assertTrue(result["feedback"][0].startswith("demo/identities.json - Mockuper created rows as ['ghost']"), result["feedback"])
        self.assertIn("name the same identities", result["feedback"][0])

    def test_a_run_that_created_nothing_is_not_a_pass(self):
        result = self.go(runner=self.runner(made={}))
        self.assertEqual(result["result"], "fail")
        self.assertIn("created nothing", result["detail"])

    def test_a_route_the_app_cannot_count_is_a_note_when_another_route_confirms_and_a_skip_when_none_does(self):
        self.store["/api/notes"] = None   # GET answers something the counter cannot read (the reader may lack the grant)
        result = self.go()
        self.assertEqual(result["result"], "pass")
        self.assertIn("the count of Note could not be read back", result["detail"])
        self.store.update({"/api/leads": None})
        result = self.go(runner=self.runner(made={"Note": ("/api/notes", ["mgr"])}))
        self.assertEqual(result["result"], "skipped")
        self.assertIn("no route's count could be read back", result["detail"])

    def test_no_mockuper_a_dead_app_and_a_timeout_are_skipped_never_failed(self):
        def no_command(*a, **k):
            raise pop.PopulateUnavailable("Mockuper did not finish inside the 180 s budget")
        self.assertEqual(self.go(runner=no_command)["result"], "skipped")

        def dead(base, key):
            raise ConnectionRefusedError("down")
        self.assertEqual(self.go(fetch=dead)["result"], "skipped")
        self.assertEqual(self.go(fetch=lambda base, key: b"not json")["result"], "skipped")
        result = pop.populate(self.demo, self.run, URL, dict(KEYS), fetch=lambda b, k: b"{}", count=lambda *a: None,
                              runner=no_command, environ={}, which=lambda n: None)
        self.assertEqual(result["result"], "skipped")
        self.assertIn("NPDEV_MOCKUPER_CMD", result["detail"])

    def test_only_routes_that_can_be_created_and_listed_are_counted(self):
        self.assertEqual(pop.creatable_paths(API_DOCS), ["/api/leads", "/api/notes"])
        self.assertEqual(pop.creatable_paths({}), [])


if __name__ == "__main__":
    unittest.main()
