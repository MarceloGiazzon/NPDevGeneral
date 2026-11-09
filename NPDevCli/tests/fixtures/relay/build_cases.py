"""Builds relay-ingest-cases.json: synthetic browser-AI answers + the result ingest must give.

Why a builder and not hand-written JSON: answers contain fences, quotes and escapes; writing them as
Python strings and letting json.dump escape them is the only way to keep 30+ cases correct.

Add a case: append to CASES, run `python build_cases.py`, run the relay tests. When a REAL answer
from a browser AI is mis-classified in the field, anonymise it and add it here as a case named
`field-<target>-<what>` -- that is how the corpus grows (plan section 6).

Fields of a case:
  name            unique id
  answer          the raw text the user brought back
  session/round   what the session expects (default K7Q2 / 1)
  phase           mvp | f4 (default mvp)
  expect.outcome  the outcome code
  expect.files    optional list of [path, status] that must match exactly (order-insensitive)
  expect.findings optional list of finding codes that must ALL be present
  expect.notFindings optional list of finding codes that must be absent
NEVER put a drive-letter-looking path in a case (portable-tooling scan): build it at run time in the
test instead, as test_relay_ingest.py does for the absolute-path case.
"""
from __future__ import annotations

import json
from pathlib import Path

FENCE = "`" * 3

MODEL = {
    "namespace": "trial.workshop",
    "version": "1.0",
    "concepts": [
        {"name": "WorkOrder", "fields": [
            {"name": "id", "type": "uuid", "id": True, "required": True},
            {"name": "title", "type": "string", "required": True, "ui": {"label": "Título"}},
            {"name": "status", "type": "string", "enum": ["Open", "Done"]},
        ]},
        {"name": "Customer", "fields": [
            {"name": "id", "type": "uuid", "id": True, "required": True},
            {"name": "name", "type": "string", "required": True},
        ]},
    ],
}
MANIFEST = {"request": "Workshop app with work orders and customers", "renames": [], "deliberateRemovals": [],
            "securityChanges": [], "couldNotExpress": [], "assumptions": ["Status has two values"]}


def js(value) -> str:
    return json.dumps(value, indent=2, ensure_ascii=False)


def block(body: str, info: str = "json") -> str:
    return f"{FENCE}{info}\n{body}\n{FENCE}"


def full_answer(session="K7Q2", rnd=1, model_text=None, extra_files=(), files_count=None, end=True,
                intro="I created a small workshop app.") -> str:
    parts = [f"NPDEV-BEGIN {session}-{rnd}", intro, "", "NPDEV-FILE model.json",
             block(model_text if model_text is not None else js(MODEL)), "",
             "NPDEV-FILE npdev-submission.json", block(js(MANIFEST))]
    for path, body, info in extra_files:
        parts += ["", f"NPDEV-FILE {path}", block(body, info)]
    count = files_count if files_count is not None else 2 + len(extra_files)
    if end:
        parts += ["", f"NPDEV-END {session}-{rnd} files={count}"]
    return "\n".join(parts) + "\n"


MODEL_TEXT = js(MODEL)
lines = MODEL_TEXT.split("\n")

CASES = [
    # ---- PARSED ----------------------------------------------------------------------------
    {"name": "ready-full-protocol", "answer": full_answer(),
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"]],
                "notFindings": ["markers-missing", "end-marker-missing"]}},
    {"name": "ready-markdown-decorated-markers",
     "answer": full_answer().replace("NPDEV-BEGIN K7Q2-1", "**NPDEV-BEGIN K7Q2-1**")
                            .replace("NPDEV-FILE model.json", "`NPDEV-FILE model.json`"),
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"]]}},
    {"name": "ready-single-block-copy-button", "answer": MODEL_TEXT + "\n",
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"]], "findings": ["markers-missing", "fences-missing"]}},
    {"name": "ready-single-fenced-block-no-markers", "answer": "Here you go:\n\n" + block(MODEL_TEXT) + "\n",
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"]], "findings": ["markers-missing"]}},
    {"name": "ready-manual-selection-fences-lost",
     "answer": "NPDEV-BEGIN K7Q2-1\nI created the app.\nNPDEV-FILE model.json\n" + MODEL_TEXT +
               "\nNPDEV-FILE npdev-submission.json\n" + js(MANIFEST) + "\nNPDEV-END K7Q2-1 files=2\n",
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"]],
                "findings": ["fences-missing"]}},
    {"name": "ready-label-in-info-string",
     "answer": "NPDEV-BEGIN K7Q2-1\n" + block(MODEL_TEXT, "json file=model.json") + "\nNPDEV-END K7Q2-1 files=1\n",
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"]]}},
    {"name": "ready-label-from-heading",
     "answer": "### model.json\n\n" + block(MODEL_TEXT) + "\n\n**npdev-submission.json**\n" + block(js(MANIFEST)) + "\n",
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"]]}},
    {"name": "ready-unlabeled-blocks-sniffed",
     "answer": "Model:\n" + block(MODEL_TEXT) + "\nAnd the manifest:\n" + block(js(MANIFEST)) + "\n",
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"]]}},
    {"name": "ready-repairs-comment-and-trailing-comma",
     "answer": full_answer(model_text=MODEL_TEXT.replace('"version": "1.0",', '"version": "1.0", // first version')
                                                .replace('"Done"\n', '"Done",\n')),
     "expect": {"outcome": "PARSED", "findings": ["json-repaired"]}},
    {"name": "ready-repairs-smart-quote-delimiters",
     "answer": full_answer(model_text=MODEL_TEXT.replace('"namespace"', "“namespace”")),
     "expect": {"outcome": "PARSED", "findings": ["json-repaired"]}},
    {"name": "ready-bom-crlf-nbsp-zero-width",
     "answer": "﻿" + full_answer().replace("\n", "\r\n").replace("I created", "I created")
                                       .replace("NPDEV-BEGIN", "​NPDEV-BEGIN"),
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"]]}},
    {"name": "ready-duplicate-model-last-wins",
     "answer": "First try:\n" + block(MODEL_TEXT) + "\nCorrected:\n" + block(MODEL_TEXT.replace("Done", "Closed")) + "\n",
     "expect": {"outcome": "PARSED", "findings": ["duplicate-file-last-wins"]}},
    {"name": "ready-no-false-elision-on-legit-text",
     "answer": full_answer(model_text=MODEL_TEXT.replace('"Título"', '"Carregando… https://example.org/a"')),
     "expect": {"outcome": "PARSED", "notFindings": ["elision"]}},
    {"name": "ready-end-marker-forgotten",
     "answer": full_answer(end=False),
     "expect": {"outcome": "PARSED", "findings": ["end-marker-missing"]}},
    {"name": "ready-path-with-definition-prefix",
     "answer": full_answer().replace("NPDEV-FILE model.json", "NPDEV-FILE definition/model.json"),
     "expect": {"outcome": "PARSED", "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"]]}},

    # ---- TRUNCATED -------------------------------------------------------------------------
    {"name": "truncated-unclosed-fence",
     "answer": "NPDEV-BEGIN K7Q2-1\nNPDEV-FILE model.json\n" + FENCE + "json\n" + "\n".join(lines[:12]) + "\n",
     "expect": {"outcome": "TRUNCATED", "findings": ["unclosed-block"]}},
    {"name": "truncated-unbalanced-no-fence", "answer": "\n".join(lines[:14]) + "\n",
     "expect": {"outcome": "TRUNCATED"}},
    {"name": "truncated-closed-fence-unbalanced-json",
     "answer": block("\n".join(lines[:14])) + "\n",
     "expect": {"outcome": "TRUNCATED", "findings": ["unbalanced-json"]}},
    {"name": "truncated-file-count-short",
     "answer": "NPDEV-BEGIN K7Q2-1\nNPDEV-FILE model.json\n" + block(MODEL_TEXT) + "\nNPDEV-END K7Q2-1 files=2\n",
     "expect": {"outcome": "TRUNCATED", "findings": ["file-count-short"]}},

    # ---- ELIDED ----------------------------------------------------------------------------
    {"name": "elided-comment-en",
     "answer": full_answer(model_text=MODEL_TEXT.replace('    {\n      "name": "Customer"',
                                                         '    // ... other concepts unchanged\n    {\n      "name": "Customer"')),
     "expect": {"outcome": "ELIDED", "findings": ["elision"]}},
    {"name": "elided-comment-pt",
     "answer": full_answer(model_text=MODEL_TEXT.replace('"concepts": [', '"concepts": [\n    // resto igual ao anterior')),
     "expect": {"outcome": "ELIDED"}},
    {"name": "elided-ellipsis-line",
     "answer": full_answer(model_text=MODEL_TEXT.replace('    {\n      "name": "Customer"', '    ...\n    {\n      "name": "Customer"')),
     "expect": {"outcome": "ELIDED"}},
    {"name": "elided-ellipsis-container",
     "answer": full_answer(model_text=MODEL_TEXT.replace('"enum": [\n                "Open",\n                "Done"\n              ]', '"enum": [...]')
                                                .replace('"enum": [', '"enum": [...], "x": [')),
     "expect": {"outcome": "ELIDED"}},

    # ---- no usable files ---------------------------------------------------------------------
    {"name": "questions-only",
     "answer": "Before I build it, a few questions:\n1. Who uses the app?\n2. Should customers log in?\n",
     "expect": {"outcome": "QUESTIONS"}},
    {"name": "file-link-sandbox",
     "answer": "Done! [Download model.json](sandbox:/mnt/data/model.json)\n",
     "expect": {"outcome": "FILE_LINK_ONLY", "findings": ["file-link"]}},
    {"name": "file-link-download-wording-pt",
     "answer": "Pronto. Clique para baixar o arquivo npdev-K7Q2-1.zip acima.\n",
     "expect": {"outcome": "FILE_LINK_ONLY"}},
    {"name": "limit-reached-en",
     "answer": "You've reached your free usage limit. Try again in 3 hours or upgrade to Plus.\n",
     "expect": {"outcome": "LIMIT_REACHED"}},
    {"name": "limit-reached-pt",
     "answer": "Você atingiu o limite de mensagens do plano gratuito. Tente novamente mais tarde.\n",
     "expect": {"outcome": "LIMIT_REACHED"}},
    {"name": "no-files-prose-refusal",
     "answer": "I can't help with generating that application.\n",
     "expect": {"outcome": "NO_FILES"}},
    {"name": "no-files-mentions-canvas",
     "answer": "I put the model in the canvas on the right.\n",
     "expect": {"outcome": "NO_FILES", "findings": ["mentions-artifact"]}},

    # ---- session / echo ----------------------------------------------------------------------
    {"name": "wrong-session", "answer": full_answer(session="ZZ99"),
     "expect": {"outcome": "WRONG_SESSION"}},
    {"name": "stale-round", "round": 2, "answer": full_answer(rnd=1),
     "expect": {"outcome": "STALE_ROUND"}},
    {"name": "round-ahead-is-only-a-warning", "answer": full_answer(rnd=2),
     "expect": {"outcome": "PARSED", "findings": ["round-ahead"]}},
    {"name": "prompt-echo",
     "answer": "NPDEV-PROMPT K7Q2-1\nYou are authoring ... reply in this format:\nNPDEV-BEGIN K7Q2-1\n...\n"
               "What I want: a workshop app\nNPDEV-PROMPT-END K7Q2-1\n",
     "expect": {"outcome": "PROMPT_ECHO"}},

    # ---- BAD_JSON / PARTIAL ------------------------------------------------------------------
    {"name": "bad-json-unquoted-keys",
     "answer": block('{ namespace: "trial.x", version: "1.0", concepts: [] }') + "\n",
     "expect": {"outcome": "BAD_JSON", "findings": ["json-parse-error"]}},
    {"name": "partial-path-traversal",
     "answer": full_answer(extra_files=[("../../evil.json", '{"a": 1}', "json")]),
     "expect": {"outcome": "PARTIAL", "findings": ["path-denied"],
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"], ["../../evil.json", "denied"]]}},
    {"name": "partial-config-file-denied-d9",
     "answer": full_answer(extra_files=[("config.json", '{"scenario": {"name": "x"}}', "json")]),
     "expect": {"outcome": "PARTIAL", "findings": ["path-denied"],
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"], ["config.json", "denied"]]}},
    {"name": "partial-web-page-in-mvp-is-later-phase",
     "answer": full_answer(extra_files=[("web/painel.html", "<h1>Painel</h1>", "html")]),
     "expect": {"outcome": "PARTIAL",
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"], ["web/painel.html", "denied"]]}},
    {"name": "partial-only-manifest",
     "answer": "NPDEV-BEGIN K7Q2-1\nNPDEV-FILE npdev-submission.json\n" + block(js(MANIFEST)) + "\nNPDEV-END K7Q2-1 files=1\n",
     "expect": {"outcome": "PARTIAL", "findings": ["missing-model"]}},

    # ---- F4: pages and code quarantine (D-4) -------------------------------------------------
    {"name": "f4-java-capability-quarantined", "phase": "f4",
     "answer": full_answer(extra_files=[("capabilities/freight/src/main/java/demo/Freight.java",
                                         "package demo;\npublic class Freight { }", "java")]),
     "expect": {"outcome": "PARSED", "findings": ["code-quarantined"],
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"],
                          ["capabilities/freight/src/main/java/demo/Freight.java", "quarantine"]]}},
    {"name": "f4-html-with-script-quarantined", "phase": "f4",
     "answer": full_answer(extra_files=[("web/painel.html", "<h1>Painel</h1>\n<script>fetch('/api')</script>", "html")]),
     "expect": {"outcome": "PARSED", "findings": ["code-quarantined"],
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"], ["web/painel.html", "quarantine"]]}},
    {"name": "f4-html-onclick-quarantined", "phase": "f4",
     "answer": full_answer(extra_files=[("web/painel.html", '<button onclick="go()">Ir</button>', "html")]),
     "expect": {"outcome": "PARSED",
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"], ["web/painel.html", "quarantine"]]}},
    {"name": "f4-plain-html-allowed", "phase": "f4",
     "answer": full_answer(extra_files=[("web/sobre.html", "<h1>Sobre</h1>\n<p>Oficina do bairro.</p>", "html"),
                                        ("web/sobre.css", "h1 { color: teal; }", "css")]),
     "expect": {"outcome": "PARSED", "notFindings": ["code-quarantined"],
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"],
                          ["web/sobre.html", "allowed"], ["web/sobre.css", "allowed"]]}},
    {"name": "f4-js-file-quarantined", "phase": "f4",
     "answer": full_answer(extra_files=[("web/chart.js", "console.log('chart')", "js")]),
     "expect": {"outcome": "PARSED",
                "files": [["model.json", "allowed"], ["npdev-submission.json", "allowed"], ["web/chart.js", "quarantine"]]}},
    {"name": "f4-executable-denied", "phase": "f4",
     "answer": full_answer(extra_files=[("web/run.ps1", "Remove-Item x", "powershell")]),
     "expect": {"outcome": "PARTIAL", "findings": ["path-denied"]}},
]


def main() -> None:
    names = [c["name"] for c in CASES]
    assert len(names) == len(set(names)), "duplicate case names"
    out = Path(__file__).with_name("relay-ingest-cases.json")
    doc = {"_comment": "GENERATED by build_cases.py -- edit that file, not this one.",
           "schemaVersion": "npdev-relay-ingest-cases.v1", "cases": CASES}
    out.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(CASES)} cases to {out.name}")


if __name__ == "__main__":
    main()
