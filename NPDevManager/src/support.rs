//! "Get help with this error": builds ONE self-contained, redacted report a user can paste into any AI
//! chat or a GitHub issue. Nothing is ever sent from here; the UI shows the exact text first.
//!
//! The report is built from three inputs that are all data:
//!   * the run recorder (runs.rs) -- what happened, the command, the log tail;
//!   * `ui/support-intents.json`  -- what the user was TRYING to do, in plain language;
//!   * `ui/help-glossary.json`    -- the terms that may help the reader of the report.
//!
//! The whole text is redacted AFTER assembly (so the intent, the error and the log are all covered),
//! with the known secret values first (credential store + secret-named environment variables).

use crate::redact;
use crate::runs::RunRecord;
use regex::Regex;
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeMap;

const INTENTS_JSON: &str = include_str!("../ui/support-intents.json");
const GLOSSARY_JSON: &str = include_str!("../ui/help-glossary.json");

pub const REPORT_CAP: usize = 12_000;
pub const ISSUE_BODY_CAP: usize = 6_000;
const LAST_LINES: usize = 60;
const MATCH_LINES: usize = 40;
const CONTEXT: usize = 2;

pub struct ReportInputs {
    pub record: Option<RunRecord>,
    pub operation: String,
    /// What the UI caught, used when the recorder has nothing for this operation.
    pub fallback_error: Option<String>,
    pub screen: String,
    pub app_dir: Option<String>,
    pub ui_language: String,
    pub manager_version: String,
    pub source: String,
    pub os: String,
    pub admin: String,
    pub java: String,
    pub python: String,
    pub known_secrets: Vec<String>,
}

#[derive(Serialize, Debug)]
pub struct ReportOut {
    pub text: String,
    pub chars: usize,
    pub truncated: bool,
    /// How many items each rule hid, e.g. {"groq-key": 1, "email": 2}.
    pub counts: BTreeMap<String, usize>,
    pub secrets_hidden: usize,
    pub personal_shortened: usize,
    pub operation: String,
    pub issue_title: String,
    /// Capped for URL length; ends with the "paste the full report below" line.
    pub issue_body: String,
}

fn intents() -> Vec<Value> {
    serde_json::from_str::<Value>(INTENTS_JSON)
        .ok()
        .and_then(|v| v["intents"].as_array().cloned())
        .unwrap_or_default()
}

fn intent_for(operation: &str) -> (String, Vec<String>) {
    for i in intents() {
        if i["operation"].as_str() == Some(operation) {
            let glossary =
                i["glossary"].as_array().map(|a| a.iter().filter_map(|g| g.as_str().map(String::from)).collect()).unwrap_or_default();
            return (i["intent"].as_str().unwrap_or(operation).to_string(), glossary);
        }
    }
    (format!("do something in the NPDev Manager ({operation})"), Vec::new())
}

fn glossary_line(id: &str, lang: &str) -> Option<String> {
    let v: Value = serde_json::from_str(GLOSSARY_JSON).ok()?;
    let entry = v["entries"].as_array()?.iter().find(|e| e["id"].as_str() == Some(id))?;
    let term = entry["term"][lang].as_str().or_else(|| entry["term"]["en"].as_str()).unwrap_or(id);
    // The AI reading the report is told to answer in the user's language, but the glossary's
    // English short line is the safest thing for any model to read.
    let short = entry["en"]["short"].as_str()?;
    Some(format!("- {term}: {short}"))
}

/// The last 60 lines, plus up to 40 lines that look like an error with 2 lines of context either
/// side, de-duplicated and kept in their original order. Returns the selected line indices.
pub fn select_log_lines(lines: &[String]) -> Vec<usize> {
    let hint = Regex::new(r"(?i)(error|exception|failed|denied|not found|refused|timed? ?out|permission)").unwrap();
    let n = lines.len();
    let mut keep = vec![false; n];
    for i in n.saturating_sub(LAST_LINES)..n {
        keep[i] = true;
    }
    let mut hits = 0;
    for (i, l) in lines.iter().enumerate() {
        if hits >= MATCH_LINES {
            break;
        }
        if hint.is_match(l) {
            hits += 1;
            let lo = i.saturating_sub(CONTEXT);
            let hi = (i + CONTEXT).min(n.saturating_sub(1));
            for k in lo..=hi {
                keep[k] = true;
            }
        }
    }
    keep.iter().enumerate().filter(|(_, k)| **k).map(|(i, _)| i).collect()
}

fn log_section(lines: &[String], budget: usize) -> (String, bool) {
    let selected = select_log_lines(lines);
    let mut taken: Vec<usize> = Vec::new();
    let mut used = 0usize;
    // Newest first: when the budget runs out it is the oldest lines that go.
    for &i in selected.iter().rev() {
        let cost = lines[i].len() + 1;
        if used + cost > budget {
            break;
        }
        used += cost;
        taken.push(i);
    }
    taken.reverse();
    let omitted = lines.len() - taken.len();
    let mut out = String::new();
    let mut prev: Option<usize> = None;
    if let Some(&first) = taken.first() {
        if first > 0 {
            out.push_str(&format!("[... {first} earlier lines omitted ...]\n"));
        }
    }
    for &i in &taken {
        if let Some(p) = prev {
            if i > p + 1 {
                out.push_str(&format!("[... {} lines omitted ...]\n", i - p - 1));
            }
        }
        out.push_str(&lines[i]);
        out.push('\n');
        prev = Some(i);
    }
    if let Some(p) = prev {
        if p + 1 < lines.len() {
            out.push_str(&format!("[... {} later lines omitted ...]\n", lines.len() - p - 1));
        }
    }
    (out, omitted > 0)
}

fn took(rec: &RunRecord) -> String {
    match rec.ended_ms {
        Some(e) if e >= rec.started_ms => {
            let ms = e - rec.started_ms;
            if ms < 1000 {
                format!("{ms} ms")
            } else {
                format!("{:.1} s", ms as f64 / 1000.0)
            }
        }
        _ => "still running".to_string(),
    }
}

fn language_name(code: &str) -> &'static str {
    match code {
        "pt" => "Portuguese",
        _ => "English",
    }
}

pub fn build_report(inp: ReportInputs) -> ReportOut {
    let (intent, glossary_ids) = intent_for(&inp.operation);
    let lang = if inp.ui_language == "pt" { "pt" } else { "en" };

    let error = inp
        .record
        .as_ref()
        .and_then(|r| r.error.clone())
        .or_else(|| inp.fallback_error.clone())
        .unwrap_or_else(|| "(no error text was captured)".to_string());

    let mut head = String::new();
    head.push_str("This is an error report from NPDev, a free, open-source app generator.\n");
    head.push_str("Project: https://github.com/MarceloGiazzon/NPDevGeneral  \u{b7}  Docs: https://github.com/MarceloGiazzon/NPDevGeneral/tree/main/docs\n\n");
    head.push_str("WHAT I WAS TRYING TO DO\n");
    head.push_str(&format!("{intent}\n(operation id: {}; Manager screen: {})\n", inp.operation, inp.screen));
    if let Some(d) = &inp.app_dir {
        head.push_str(&format!("App folder: {d}\n"));
    }
    head.push_str("\nWHAT HAPPENED\n");
    head.push_str(&error);
    head.push_str("\n\nENVIRONMENT\n");
    head.push_str(&format!("NPDev Manager {}\n", inp.manager_version));
    head.push_str(&format!("NPDev source: {}\n", inp.source));
    head.push_str(&format!("OS: {}; running as administrator: {}\n", inp.os, inp.admin));
    head.push_str(&format!("Java: {}   Python: {}\n", inp.java, inp.python));
    head.push_str("\nCOMMAND\n");
    match &inp.record {
        Some(r) if !r.argv.is_empty() => {
            let code = r.exit_code.map(|c| format!("exit code {c}, ")).unwrap_or_default();
            head.push_str(&format!("{}   ({}took {})\n", r.argv.join(" "), code, took(r)));
        }
        Some(r) => head.push_str(&format!("(run inside the Manager, took {})\n", took(r))),
        None => head.push_str("(no command was recorded for this operation)\n"),
    }

    let mut tail = String::from("\nTERMS THAT MAY HELP\n");
    let mut any_term = false;
    for id in &glossary_ids {
        if let Some(l) = glossary_line(id, lang) {
            tail.push_str(&l);
            tail.push('\n');
            any_term = true;
        }
    }
    if !any_term {
        tail.push_str("(none)\n");
    }
    tail.push_str("\nPlease explain in plain language what went wrong and give me step-by-step instructions to fix it.\n");
    tail.push_str(&format!("I am not a developer. Reply in {}.\n", language_name(lang)));

    let mut lines: Vec<String> = Vec::new();
    if let Some(r) = &inp.record {
        lines.extend(r.stdout_tail.iter().cloned());
        lines.extend(r.stderr_tail.iter().cloned());
    }
    let log_header = "\nLOG (most relevant lines, redacted)\n";

    // Redaction can only SHORTEN text (masks are shorter than secrets in practice), so budgeting on the
    // raw length is safe; the final cap is re-checked after redaction anyway.
    let fixed = head.len() + log_header.len() + tail.len();
    let budget = REPORT_CAP.saturating_sub(fixed + 80);
    let (log_body, log_truncated) = if lines.is_empty() {
        ("(no output was captured)\n".to_string(), false)
    } else {
        log_section(&lines, budget)
    };

    let mut text = format!("{head}{log_header}{log_body}{tail}");
    let (mut redacted, counts) = redact::redact(&text, &inp.known_secrets);
    let mut truncated = log_truncated;
    if redacted.chars().count() > REPORT_CAP {
        let cut: String = redacted.chars().take(REPORT_CAP - 40).collect();
        redacted = format!("{cut}\n[... report cut at {REPORT_CAP} characters ...]\n");
        truncated = true;
    }
    text = redacted;

    let (secrets, personal) = redact::split_counts(&counts);
    let issue_title = format!("[Manager] {} failed", inp.operation);
    let note = "\n\n[... truncated for the link: the full report is on your clipboard -- paste it below this line ...]";
    let issue_body = if text.chars().count() > ISSUE_BODY_CAP {
        let cut: String = text.chars().take(ISSUE_BODY_CAP - note.chars().count()).collect();
        format!("{cut}{note}")
    } else {
        format!("{text}\n\nThe full report is on your clipboard: paste it below this line.")
    };

    ReportOut {
        chars: text.chars().count(),
        text,
        truncated,
        counts,
        secrets_hidden: secrets,
        personal_shortened: personal,
        operation: inp.operation,
        issue_title,
        issue_body,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rec(op: &str, err: &str, lines: Vec<&str>) -> RunRecord {
        RunRecord {
            id: 1,
            operation: op.into(),
            started_at: "2026-11-03T10:00:00Z".into(),
            ended_at: Some("2026-11-03T10:00:05Z".into()),
            started_ms: 0,
            ended_ms: Some(5000),
            argv: vec!["python".into(), "npdev_cli.py".into(), "setup".into()],
            cwd: None,
            exit_code: Some(1),
            ok: false,
            running: false,
            error: Some(err.into()),
            stdout_tail: lines.into_iter().map(String::from).collect(),
            stderr_tail: vec![],
        }
    }

    fn inputs(r: Option<RunRecord>, known: Vec<String>) -> ReportInputs {
        ReportInputs {
            record: r,
            operation: "npdev-setup".into(),
            fallback_error: None,
            screen: "install".into(),
            app_dir: None,
            ui_language: "en".into(),
            manager_version: "0.7.0: test".into(),
            source: "downloaded v1".into(),
            os: "windows 11 (x86_64)".into(),
            admin: "no".into(),
            java: "17".into(),
            python: "3.12".into(),
            known_secrets: known,
        }
    }

    #[test]
    fn every_intent_references_real_glossary_entries() {
        let g: Value = serde_json::from_str(GLOSSARY_JSON).unwrap();
        let ids: Vec<&str> = g["entries"].as_array().unwrap().iter().filter_map(|e| e["id"].as_str()).collect();
        for i in intents() {
            for gid in i["glossary"].as_array().unwrap() {
                assert!(ids.contains(&gid.as_str().unwrap()), "intent {} cites unknown glossary id {}", i["operation"], gid);
            }
        }
    }

    #[test]
    fn the_report_shows_neither_a_vendor_key_nor_a_known_secret() {
        let r = rec(
            "npdev-setup",
            "setup failed: GROQ_API_KEY=gsk_abcdefghijklmnopqrstuvwx rejected",
            vec!["> Task :compileJava FAILED", "the vault said hunter-two-secret-value", "mail me@example.com C:\\Users\\Marcelo\\x"],
        );
        let out = build_report(inputs(Some(r), vec!["hunter-two-secret-value".into()]));
        assert!(!out.text.contains("gsk_abcdefghijklmnopqrstuvwx"), "{}", out.text);
        assert!(!out.text.contains("hunter-two-secret-value"), "{}", out.text);
        assert!(!out.text.contains("me@example.com"));
        assert!(!out.text.contains("Users\\Marcelo"));
        assert!(out.text.contains("<redacted"), "{}", out.text);
        assert!(out.text.contains("prepare NPDev's building blocks"), "intent missing");
        assert!(out.text.contains("compileJava FAILED"), "the failing line must reach the log section");
        assert!(out.secrets_hidden >= 2 && out.personal_shortened >= 1, "{:?}", out.counts);
    }

    #[test]
    fn a_long_log_is_capped_and_says_how_much_was_omitted() {
        let many: Vec<String> = (0..5000).map(|i| format!("noise line number {i} with some padding text to take room")).collect();
        let mut r = rec("npdev-setup", "x", vec![]);
        r.stdout_tail = many;
        r.stdout_tail.insert(10, "FAILURE: build failed with an exception".into());
        let out = build_report(inputs(Some(r), vec![]));
        assert!(out.chars <= REPORT_CAP, "{}", out.chars);
        assert!(out.truncated);
        assert!(out.text.contains("lines omitted"));
        assert!(out.issue_body.chars().count() <= ISSUE_BODY_CAP + 200);
    }

    #[test]
    fn falls_back_to_the_ui_error_when_nothing_was_recorded() {
        let mut inp = inputs(None, vec![]);
        inp.fallback_error = Some("could not reach the network".into());
        let out = build_report(inp);
        assert!(out.text.contains("could not reach the network"));
        assert!(out.text.contains("no command was recorded"));
    }

    /// The UI render check (scripts/quality/check-manager-ui-render.mjs) answers `support_report` with
    /// this file, so the dialog it draws is REAL builder output -- planted key and known secret
    /// included -- not text someone invented. Regenerate with NPDEV_UPDATE_FIXTURES=1.
    #[test]
    fn the_golden_fixture_matches_what_the_report_builder_produces() {
        let r = rec(
            "install-java",
            "download failed: GROQ_API_KEY=gsk_abcdefghijklmnopqrstuvwx was rejected by the proxy",
            vec![
                "downloading jdk-17.0.11 (190 MB)",
                "proxy replied 407 and the vault value hunter-two-secret-value was echoed",
                "error: could not verify the archive",
            ],
        );
        let mut inp = inputs(Some(r), vec!["hunter-two-secret-value".into()]);
        inp.operation = "install-java".into();
        inp.screen = "install".into();
        let out = build_report(inp);
        assert!(!out.text.contains("gsk_abcdefghijklmnopqrstuvwx") && !out.text.contains("hunter-two-secret-value"));
        let mut json = serde_json::to_string_pretty(&out).unwrap();
        json.push('\n');
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/fixtures/support-install-failed.json");
        if std::env::var("NPDEV_UPDATE_FIXTURES").is_ok() {
            std::fs::write(path, &json).unwrap();
        }
        let on_disk = std::fs::read_to_string(path).unwrap_or_default().replace("\r\n", "\n");
        assert_eq!(on_disk, json, "fixtures/support-install-failed.json is stale; rerun with NPDEV_UPDATE_FIXTURES=1");
    }

    #[test]
    fn selection_keeps_error_lines_with_context() {
        let lines: Vec<String> = (0..300).map(|i| if i == 50 { "boom: failed".into() } else { format!("ok {i}") }).collect();
        let sel = select_log_lines(&lines);
        assert!(sel.contains(&50) && sel.contains(&48) && sel.contains(&52));
        assert!(sel.contains(&299) && !sel.contains(&100));
    }
}
