#!/usr/bin/env python3

"""ModelHolder reload-wiring gate: a bean that seeds derived state from `modelHolder.get()` inside
its own constructor or `@Bean` factory method must also register a `ModelReloadListener` in that
SAME method, or the state it captured freezes at whatever the model was when Spring built that bean
(once, at startup) and never observes a later `/model-reload`.

WHY THIS EXISTS
---------------
B28 (ledger/boundaries/B28.yml) was reopened twice by an "exhaustive sweep" finding residuals this
exact shape (REG-235/236/237/239/240/241) that a plain code-reading pass had missed, and its own
text warns explicitly against restating the boundary as fixed without a mechanical check behind it
("an unenforced 'no bean caches model-derived state' restatement is exactly the sentence that was
wrong before"). `check-model-provider-injection.py` already guards ONE narrow shape (a direct
`NPDevModelProvider` injection); this gate guards the broader, actually-recurring shape: a
`ModelHolder`-holding bean whose constructor or `@Bean` method reads `.get()` and stores something
derived from it (a snapshot, an index, a policy object) with no `addReloadListener` call to keep
that derived state in sync.

DETECTION (regex/brace-matching over the AST-free source text, same tradeoff every checker in this
package already makes -- see check-model-provider-injection.py's own header):
1. Find every constructor and every `@Bean`-annotated method in a file.
2. Within that method/constructor's body, find every `modelHolder.get()` call NOT deferred inside a
   lambda (a lambda passed as a `Supplier`/callback defers evaluation to whenever it is actually
   invoked, which is not bean-construction time -- e.g. `NpdevPluginConfig`'s own lazy
   `RuntimePluginRealizationProvider` suppliers; see its class javadoc).
3. If at least one non-deferred `.get()` call exists and the body contains no
   `.addReloadListener(` call anywhere, the method/constructor is flagged.
4. `ALLOWLIST` exempts a (file, context) pair -- never a whole file, since most flagged files have
   other, correctly-wired `@Bean` methods alongside the one exempted -- whose only offending shape
   is a deliberate, already-documented "boot-time snapshot by design" site: a Spring
   `ApplicationRunner`/`InitializingBean` one-shot boot task (runs exactly once, strictly before any
   reload endpoint could ever be reached), or a diagnostics/summary bean explicitly meant to describe
   what the app booted with rather than its live state. Every entry cites the exact comment already
   in the source that makes this determination, not a fresh judgment call by this script.

"Deferred" (step 2) also covers a nested method body other than a lambda -- e.g. an anonymous
class's `@Override`-ed method, which (like a lambda) only runs when something later calls it, not
when the enclosing constructor/@Bean method itself executes (`RuntimeInvariantEngineFactory`'s
per-request `create(...)` is exactly this shape; see its own class javadoc).

USAGE
    python scripts/quality/check-model-holder-reload-wiring.py
    python scripts/quality/check-model-holder-reload-wiring.py --json
    python scripts/quality/check-model-holder-reload-wiring.py --repo <path>
    python scripts/quality/check-model-holder-reload-wiring.py --calibrate  # self-test, exit 1 on failure
Exit 0 = every modelHolder.get() read that seeds constructor/@Bean-time state is reload-wired.
Exit 1 = at least one is not. Exit 2 = usage.
"""

from __future__ import annotations
import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

# Same two roots check-model-provider-injection.py scans -- NPDevRuntimeHost is the Spring Boot
# template copied into every generated FinalApp; runtimehost-core is the pre-built jar staged into
# it. Together they are every source file this bug class can occur in.
SCAN_ROOTS = [
    "NPDevRuntimeHost/src/main/java",
    "NPDevRuntimeHost/runtimehost-core/src/main/java",
]

SKIP_DIR_PARTS = {"build", "target", ".gradle", "generated"}

# (file, context) -- context is the exact label scan_file() reports (e.g. "constructor Foo" or
# "@Bean method bar") -- -> why this one specific site's offending shape is not a residual. Never
# key by file alone: several of these files have other, correctly reload-wired @Bean methods.
ALLOWLIST: dict[tuple[str, str], str] = {
    (
        "NPDevRuntimeHost/runtimehost-core/src/main/java/com/finalexec/controlpanel/"
        "TenantAutoRegistrationRunner.java",
        "constructor TenantAutoRegistrationRunner",
    ):
        "implements ApplicationRunner; run() fires exactly once at boot, strictly before any "
        "/model-reload could occur (see the class's own REG-208 comment) -- there is no staleness "
        "for a listener to fix.",
    (
        "NPDevRuntimeHost/src/main/java/com/finalexec/config/NpdevCapabilityBindingConfig.java",
        "@Bean method httpMessagingCapabilityAdapter",
    ):
        "REG-208 (B28 lift), per the method's own javadoc: whether this bean exists AT ALL is "
        "decided by a construction-time read of the model (Spring beans cannot be added after "
        "context startup) -- the OTHER named residual category from B28's own title ('a change "
        "requiring newly generated code needs a restart'), not the 'cached derived state goes "
        "stale' shape this gate targets. A reload that newly binds messaging-http still needs a "
        "restart for this bean to come into existence, by design.",
    (
        "NPDevRuntimeHost/src/main/java/com/finalexec/controlpanel/ControlPanelTenantUsersController.java",
        "constructor ControlPanelTenantUsersController",
    ):
        "Deliberate, per the constructor's own comment: userTable resolves the IDENTITY PACK's "
        "table name only as a config DEFAULT when no @Value override is set; re-deriving a config "
        "default on every reload would be surprising to an operator who already configured it "
        "explicitly. Every per-request call site re-resolves identity tables fresh via "
        "requireIdentityTables() regardless.",
    (
        "NPDevRuntimeHost/runtimehost-core/src/main/java/com/finalexec/config/NpdevObservabilityConfig.java",
        "@Bean method startupValidator",
    ):
        "StartupValidator (NPDevKernel adapters/runtime-validation) implements Spring's "
        "InitializingBean -- afterPropertiesSet() runs exactly once at boot, strictly before any "
        "reload endpoint could ever be reached, same posture as an ApplicationRunner.",
    (
        "NPDevRuntimeHost/runtimehost-core/src/main/java/com/finalexec/config/NpdevPluginConfig.java",
        "@Bean method runtimePluginProfileDiagnostics",
    ):
        "Deliberate, per the method's own javadoc: 'a boot-time diagnostic snapshot... not "
        "reload-aware, and not expected to be (a diagnostics report of the profile this instance "
        "BOOTED with)' -- same posture as NpdevObservabilityConfig#startupValidator.",
}

CLASS_DECL_RE = re.compile(r"\bclass\s+(\w+)")
BEAN_RE = re.compile(r"@Bean\b")
GET_CALL_RE = re.compile(r"\bmodelHolder\.get\(\)")
LISTENER_RE = re.compile(r"\.addReloadListener\(")
ARROW_RE = re.compile(r"->")


def strip_comments_and_strings(text: str) -> str:
    """Blanks out comment/string CONTENTS (preserving length and newlines, so line numbers and
    brace positions computed against the result still line up with the original file) so brace
    matching and the regexes above never fire on a `{`/`}`/`modelHolder.get()` mentioned inside a
    comment or a string literal. Handles `//`, `/* */`, Java text blocks, `"..."`, and `'...'`."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j == -1 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j == -1 else j + 3
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def find_matching(text: str, open_idx: int, open_ch: str, close_ch: str) -> int | None:
    depth = 0
    i, n = open_idx, len(text)
    while i < n:
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def method_name_before_paren(text: str, paren_idx: int) -> str:
    i = paren_idx - 1
    while i >= 0 and text[i].isspace():
        i -= 1
    end = i + 1
    while i >= 0 and (text[i].isalnum() or text[i] == "_"):
        i -= 1
    return text[i + 1:end] or "<unnamed>"


def skip_annotations(text: str, pos: int) -> int:
    """Advances past any run of `@Annotation` / `@Annotation(...)` markers (and the whitespace
    between them) starting at `pos`, returning the index of the first non-annotation, non-blank
    character -- the start of the actual modifiers/return-type/method-name text."""
    n = len(text)
    while True:
        j = pos
        while j < n and text[j].isspace():
            j += 1
        if j < n and text[j] == "(":
            close = find_matching(text, j, "(", ")")
            if close is None:
                return j
            pos = close + 1
            continue
        if j < n and text[j] == "@":
            k = j + 1
            while k < n and (text[k].isalnum() or text[k] in "._"):
                k += 1
            pos = k
            continue
        return j


def find_blocks(stripped: str) -> list[tuple[str, int, int]]:
    """Returns (label, body_start, body_end) for every constructor and every `@Bean` method --
    body_start/body_end are indices of the enclosing `{`/`}` in `stripped` (inclusive)."""
    blocks: list[tuple[str, int, int]] = []

    class_match = CLASS_DECL_RE.search(stripped)
    if class_match:
        class_name = class_match.group(1)
        ctor_re = re.compile(r"(?:public|private|protected)\s+" + re.escape(class_name) + r"\s*\(")
        for m in ctor_re.finditer(stripped):
            paren_open = m.end() - 1
            paren_close = find_matching(stripped, paren_open, "(", ")")
            if paren_close is None:
                continue
            semi = stripped.find(";", paren_close)
            brace_open = stripped.find("{", paren_close)
            if brace_open == -1 or (0 <= semi < brace_open):
                continue  # a declaration with no body (shouldn't happen for a constructor)
            brace_close = find_matching(stripped, brace_open, "{", "}")
            if brace_close is not None:
                blocks.append((f"constructor {class_name}", brace_open, brace_close))

    for m in BEAN_RE.finditer(stripped):
        # @Bean itself may carry args ("@Bean(destroyMethod = \"close\")"), and further annotations
        # ("@ConditionalOnProperty(...)") may sit between it and the method signature -- skip past
        # all of them, or the first "(" found would be an annotation's own parens, not the method's,
        # mislabeling the method (and mis-scoping its body).
        pos = skip_annotations(stripped, m.end())
        paren_open = stripped.find("(", pos)
        if paren_open == -1:
            continue
        name = method_name_before_paren(stripped, paren_open)
        paren_close = find_matching(stripped, paren_open, "(", ")")
        if paren_close is None:
            continue
        brace_open = stripped.find("{", paren_close)
        if brace_open == -1:
            continue
        brace_close = find_matching(stripped, brace_open, "{", "}")
        if brace_close is not None:
            blocks.append((f"@Bean method {name}", brace_open, brace_close))

    return blocks


# Java keywords that can precede a "(...) {" without that being a method/lambda declaration.
_CONTROL_KEYWORDS = {"if", "for", "while", "try", "catch", "else", "synchronized", "switch", "finally", "do"}

# A "(...)  {" (or "-> {") immediately preceded by an identifier that isn't a control-flow keyword:
# a method declaration (including an anonymous class's @Override-ed method) or a block lambda.
_METHOD_OR_LAMBDA_OPENER_RE = re.compile(r"(?:->|(\w+)\s*\([^()]*\)\s*(?:throws\s+[\w.,\s]+)?)\s*$")


def classify_brace_opener(preceding_text: str) -> bool:
    """True if `preceding_text` (trimmed to a bounded window ending right before a '{') looks like
    it opens a lambda body or a method body (a nested method declaration, most commonly an anonymous
    class's @Override-ed method) rather than a plain control-flow or statement block."""
    m = _METHOD_OR_LAMBDA_OPENER_RE.search(preceding_text)
    if not m:
        return False
    identifier = m.group(1)
    return identifier is None or identifier not in _CONTROL_KEYWORDS


def deferred_spans(body: str) -> list[tuple[int, int]]:
    """Returns every (start, end) index range (inclusive) of a '{'...matching '}' block that is a
    lambda or nested method body anywhere in `body` -- these all run later, on invocation, not when
    the enclosing constructor/@Bean method itself executes, so anything inside is not a
    construction-time read. Content already inside one such span is not descended into again (it is
    transitively deferred); content inside a PLAIN block (if/for/try/a bare statement block) IS
    descended into, since that block itself still runs eagerly as part of the enclosing method."""
    spans = []
    i, n = 0, len(body)
    while i < n:
        if body[i] == "{":
            lookback = body[max(0, i - 300):i]
            close = find_matching(body, i, "{", "}")
            if close is None:
                break
            if classify_brace_opener(lookback):
                spans.append((i, close))
                i = close + 1
            else:
                i += 1
        else:
            i += 1
    return spans


def is_deferred(body: str, get_start: int, spans: list[tuple[int, int]]) -> bool:
    if any(start < get_start < end for start, end in spans):
        return True
    # Expression-form lambda ("-> expr", no braces) -- deferred_spans only finds BRACE-delimited
    # regions, so a bare "() -> new Thing(modelHolder.get())" (no '{') needs its own check: the
    # nearest '->' before this occurrence, with no statement-ending ';' between it and the match.
    # If a '{' sits in between, that arrow actually opened a BLOCK lambda already covered by
    # deferred_spans above, so this occurrence must be genuinely outside it -- don't double-count.
    preceding = body[:get_start]
    arrow_idx = preceding.rfind("->")
    if arrow_idx == -1:
        return False
    between = preceding[arrow_idx:]
    if "{" in between:
        return False
    return ";" not in between


def scan_file(path: Path, rel: str) -> list[dict]:
    try:
        original = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return []
    stripped = strip_comments_and_strings(original)
    hits = []
    for label, body_start, body_end in find_blocks(stripped):
        if (rel, label) in ALLOWLIST:
            continue
        body = stripped[body_start:body_end + 1]
        spans = deferred_spans(body)
        eager_gets = [g for g in GET_CALL_RE.finditer(body) if not is_deferred(body, g.start(), spans)]
        if not eager_gets:
            continue
        if LISTENER_RE.search(body):
            continue
        first = eager_gets[0]
        line_no = stripped.count("\n", 0, body_start + first.start()) + 1
        hits.append({"file": rel, "line": line_no, "context": label})
    return hits


def iter_java(repo: Path):
    for root in SCAN_ROOTS:
        base = repo / root
        if not base.is_dir():
            continue
        for path in base.rglob("*.java"):
            if any(part in SKIP_DIR_PARTS for part in path.parts):
                continue
            rel = path.relative_to(repo).as_posix()
            yield path, rel


def scan(repo: Path) -> list[dict]:
    hits: list[dict] = []
    for path, rel in iter_java(repo):
        hits.extend(scan_file(path, rel))
    return hits


def resolve_repo(explicit: str | None) -> Path | None:
    if explicit:
        return Path(explicit).resolve()
    candidate = Path(__file__).resolve().parents[2]
    modules = ("NPDevContract", "NPDevGenerator", "NPDevKernel")
    return candidate if all((candidate / m).is_dir() for m in modules) else None


def calibrate() -> int:
    ok = True

    def report(label: str, fired: bool, expect_fire: bool) -> None:
        nonlocal ok
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} (fired: {fired}, expected: {expect_fire})")

    print("Calibration -- constructor/@Bean caching without a listener must fire; with one, must not:")
    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        base = repo_root / "NPDevRuntimeHost/src/main/java/com/finalexec/config"
        base.mkdir(parents=True, exist_ok=True)

        bad_ctor = base / "BadCtor.java"
        bad_ctor.write_text(
            "package com.finalexec.config;\n"
            "public final class BadCtor {\n"
            "    private final Object snapshot;\n"
            "    public BadCtor(ModelHolder modelHolder) {\n"
            "        this.snapshot = build(modelHolder.get());\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        good_ctor = base / "GoodCtor.java"
        good_ctor.write_text(
            "package com.finalexec.config;\n"
            "public final class GoodCtor {\n"
            "    private volatile Object snapshot;\n"
            "    public GoodCtor(ModelHolder modelHolder) {\n"
            "        this.snapshot = build(modelHolder.get());\n"
            "        modelHolder.addReloadListener((before, after) -> this.snapshot = build(after));\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        bad_bean = base / "BadBeanConfig.java"
        bad_bean.write_text(
            "package com.finalexec.config;\n"
            "public class BadBeanConfig {\n"
            "    @Bean\n"
            "    public ReloadableInvariantEngine invariantEngine(ModelHolder modelHolder) {\n"
            "        ReloadableInvariantEngine engine = new ReloadableInvariantEngine(modelHolder.get());\n"
            "        return engine;\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        good_bean = base / "GoodBeanConfig.java"
        good_bean.write_text(
            "package com.finalexec.config;\n"
            "public class GoodBeanConfig {\n"
            "    @Bean\n"
            "    public ReloadableInvariantEngine invariantEngine(ModelHolder modelHolder) {\n"
            "        ReloadableInvariantEngine engine = new ReloadableInvariantEngine(modelHolder.get());\n"
            "        modelHolder.addReloadListener((before, after) -> engine.rebuild(after));\n"
            "        return engine;\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        lazy_bean = base / "LazySupplierConfig.java"
        lazy_bean.write_text(
            "package com.finalexec.config;\n"
            "public class LazySupplierConfig {\n"
            "    @Bean\n"
            "    public RuntimePluginRealizationProvider persistenceProvider(ModelHolder modelHolder) {\n"
            "        return named(\"x\", () -> new InMemoryPersistenceCapabilityAdapter(modelHolder.get()));\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        per_call_method = base / "PerCallController.java"
        per_call_method.write_text(
            "package com.finalexec.config;\n"
            "public class PerCallController {\n"
            "    private final ModelHolder modelHolder;\n"
            "    public PerCallController(ModelHolder modelHolder) {\n"
            "        this.modelHolder = modelHolder;\n"
            "    }\n"
            "    public Object list() {\n"
            "        return modelHolder.get().getConcepts();\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        # @Bean carrying its own args, PLUS a second annotation before the method signature -- both
        # must be skipped to find the real method name/parameter list, not just the first "(".
        annotated_bad_bean = base / "AnnotatedBadBeanConfig.java"
        annotated_bad_bean.write_text(
            "package com.finalexec.config;\n"
            "public class AnnotatedBadBeanConfig {\n"
            "    @Bean(destroyMethod = \"close\")\n"
            "    @ConditionalOnProperty(name = \"x\", havingValue = \"y\")\n"
            "    public Thing thing(ModelHolder modelHolder) {\n"
            "        return new Thing(modelHolder.get());\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        # Per-request work done through an anonymous class's @Override-ed method (not a lambda) --
        # RuntimeInvariantEngineFactory's own real shape.
        anon_class_bean = base / "AnonClassFactoryConfig.java"
        anon_class_bean.write_text(
            "package com.finalexec.config;\n"
            "public class AnonClassFactoryConfig {\n"
            "    @Bean\n"
            "    public Factory factory(ModelHolder modelHolder) {\n"
            "        return new Factory() {\n"
            "            @Override\n"
            "            public Engine create(Lookup lookup) {\n"
            "                return Engine.fromModel(modelHolder.get(), lookup);\n"
            "            }\n"
            "        };\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        # A BLOCK lambda (braces, internal semicolons) deferring modelHolder.get() -- the simple
        # "no semicolon before the match" check alone would wrongly call this eager.
        block_lambda_bean = base / "BlockLambdaConfig.java"
        block_lambda_bean.write_text(
            "package com.finalexec.config;\n"
            "public class BlockLambdaConfig {\n"
            "    @Bean\n"
            "    public RuntimePluginRealizationProvider provider(ModelHolder modelHolder) {\n"
            "        return named(\"x\", () -> {\n"
            "            DataSource dataSource = resolve();\n"
            "            if (dataSource == null) {\n"
            "                return new InMemoryPersistenceCapabilityAdapter(modelHolder.get());\n"
            "            }\n"
            "            return new PostgresPersistenceCapabilityAdapter(dataSource, modelHolder.get());\n"
            "        });\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        allowlisted = repo_root / (
            "NPDevRuntimeHost/runtimehost-core/src/main/java/com/finalexec/controlpanel/"
            "TenantAutoRegistrationRunner.java"
        )
        allowlisted.parent.mkdir(parents=True, exist_ok=True)
        allowlisted.write_text(
            "package com.finalexec.controlpanel;\n"
            "public final class TenantAutoRegistrationRunner {\n"
            "    private final Object identityTables;\n"
            "    public TenantAutoRegistrationRunner(ModelHolder modelHolder) {\n"
            "        this.identityTables = resolve(modelHolder.get());\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )

        all_hits = scan(repo_root)
        hit_files = {hit["file"] for hit in all_hits}
        hit_contexts = {(hit["file"], hit["context"]) for hit in all_hits}
        report("constructor caching with no listener is caught",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/BadCtor.java" in hit_files,
               expect_fire=True)
        report("constructor caching WITH a listener is not caught",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/GoodCtor.java" in hit_files,
               expect_fire=False)
        report("@Bean method caching with no listener is caught",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/BadBeanConfig.java" in hit_files,
               expect_fire=True)
        report("@Bean method caching WITH a listener is not caught",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/GoodBeanConfig.java" in hit_files,
               expect_fire=False)
        report("a modelHolder.get() deferred inside a lazy Supplier lambda is not caught",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/LazySupplierConfig.java" in hit_files,
               expect_fire=False)
        report("a plain per-request method reading modelHolder.get() fresh is not caught",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/PerCallController.java" in hit_files,
               expect_fire=False)
        report("the allowlisted ApplicationRunner shape is not caught despite matching the bad pattern",
               "NPDevRuntimeHost/runtimehost-core/src/main/java/com/finalexec/controlpanel/"
               "TenantAutoRegistrationRunner.java" in hit_files,
               expect_fire=False)
        report("@Bean(args) + a second annotation is still parsed to the real method name 'thing'",
               ("NPDevRuntimeHost/src/main/java/com/finalexec/config/AnnotatedBadBeanConfig.java",
                "@Bean method thing") in hit_contexts,
               expect_fire=True)
        report("modelHolder.get() inside an anonymous class's @Override-ed method (not a lambda) "
               "is treated as deferred, not a bean-construction-time read",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/AnonClassFactoryConfig.java" in hit_files,
               expect_fire=False)
        report("modelHolder.get() inside a BLOCK lambda (braces, internal semicolons) is still "
               "recognized as deferred",
               "NPDevRuntimeHost/src/main/java/com/finalexec/config/BlockLambdaConfig.java" in hit_files,
               expect_fire=False)

        bad_ctor.unlink()
        bad_bean.unlink()
        annotated_bad_bean.unlink()
        clean_hits = {hit["file"] for hit in scan(repo_root)}
        report("gate is silent once both bad files are removed",
               len(clean_hits) > 0,
               expect_fire=False)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=None)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--calibrate", action="store_true", help="run the required controls and exit")
    args = parser.parse_args()
    if args.calibrate:
        return calibrate()
    repo = resolve_repo(args.repo)
    if repo is None:
        print("error: could not resolve the repo root (the directory holding NPDevContract + "
              "NPDevGenerator + NPDevKernel) from this script's location; pass --repo",
              file=sys.stderr)
        return 2
    hits = scan(repo)
    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-model-holder-reload-wiring.v1",
            "repo": str(repo),
            "allowlist": [{"file": f, "context": c, "reason": r} for (f, c), r in ALLOWLIST.items()],
            "siteCount": len(hits),
            "sites": hits,
        }, indent=2))
        return 1 if hits else 0
    print("ModelHolder reload-wiring gate")
    print("=" * 78)
    if not hits:
        print("  PASS -- every modelHolder.get() read that seeds constructor/@Bean-time state "
              "is paired with an addReloadListener call in the same method")
        return 0
    print(f"  FAIL -- {len(hits)} unwired site(s)\n")
    for hit in hits:
        print(f"  {hit['file']}:{hit['line']} ({hit['context']})")
    print("\n  modelHolder.get() was called here to seed state kept beyond this method call, but no")
    print("  modelHolder.addReloadListener(...) call exists in the same constructor/@Bean method --")
    print("  a later /model-reload will not be observed. Either read modelHolder.get() fresh on every")
    print("  use instead of caching it, or register a ModelReloadListener that rebuilds the cached")
    print("  state in place (see JdbcBusinessConceptStore or NpdevCapabilityBindingConfig#invariantEngine")
    print("  for the two established idioms). If this site is a boot-time-only ApplicationRunner that")
    print("  provably runs before any reload could occur, add it to ALLOWLIST with a one-line reason.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
