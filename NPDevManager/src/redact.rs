//! One definition of "secret" for everything that lets text leave the machine.
//!
//! The rules are DATA -- `NPDevCli/redaction-rules.json` -- embedded at compile time, so the Python
//! twin (`NPDevCli/npdev_redact.py`) and this module read the same file and cannot drift. The Manager
//! needs its own implementation because the commonest first-run failure is a missing Python, which
//! leaves the CLI unable to run at all; the report still has to be built, and built safely.
//!
//! Portability contract (see the rules file): no lookaround, no backreferences, inline flags only at
//! the start, exactly one named group `secret`. Only that group's span is replaced by the rule's mask.
//! Order: known secret values (longest first), then the rules in file order.

use regex::Regex;
use serde_json::Value;
use std::collections::BTreeMap;
use std::sync::OnceLock;

const RULES_JSON: &str = include_str!("../../NPDevCli/redaction-rules.json");

pub struct Rule {
    pub id: String,
    pub class: String,
    pub mask: String,
    pub rx: Regex,
}

pub struct Rules {
    pub rules: Vec<Rule>,
    pub known_min_len: usize,
    pub known_mask: String,
    pub key_name_rx: Option<Regex>,
}

pub fn load(json_text: &str) -> Result<Rules, String> {
    let data: Value = serde_json::from_str(json_text).map_err(|e| e.to_string())?;
    let mut rules = Vec::new();
    for r in data["rules"].as_array().ok_or("rules[] missing")? {
        let id = r["id"].as_str().unwrap_or("?").to_string();
        let pattern = r["pattern"].as_str().ok_or(format!("{id}: pattern missing"))?;
        let rx = Regex::new(pattern).map_err(|e| format!("{id}: {e}"))?;
        if !rx.capture_names().any(|n| n == Some("secret")) {
            return Err(format!("{id}: needs a named group `secret`"));
        }
        rules.push(Rule {
            id,
            class: r["class"].as_str().unwrap_or("secret").to_string(),
            mask: r["mask"].as_str().unwrap_or("<redacted>").to_string(),
            rx,
        });
    }
    let key_name_rx = data["secretKeyNamePattern"].as_str().and_then(|p| Regex::new(p).ok());
    Ok(Rules {
        rules,
        known_min_len: data["knownValueMinLength"].as_u64().unwrap_or(6) as usize,
        known_mask: data["knownValueMask"].as_str().unwrap_or("<redacted:known-secret>").to_string(),
        key_name_rx,
    })
}

/// The embedded rules, compiled once. A malformed rules file is a build-time bug caught by the unit
/// test, so a failure here panics rather than silently disabling redaction.
pub fn rules() -> &'static Rules {
    static RULES: OnceLock<Rules> = OnceLock::new();
    RULES.get_or_init(|| load(RULES_JSON).expect("NPDevCli/redaction-rules.json must load"))
}

/// Known values first (longest first), then every rule in file order. Returns the text and a
/// per-rule count, which the UI shows as "3 secrets hidden, 2 paths shortened".
pub fn redact_text(text: &str, rules: &Rules, known: &[String]) -> (String, BTreeMap<String, usize>) {
    let mut counts: BTreeMap<String, usize> = BTreeMap::new();
    let mut out = text.to_string();
    let mut values: Vec<&String> = known.iter().filter(|k| k.len() >= rules.known_min_len).collect();
    values.sort_by(|a, b| b.len().cmp(&a.len()).then(a.cmp(b)));
    values.dedup();
    for v in values {
        let n = out.matches(v.as_str()).count();
        if n > 0 {
            out = out.replace(v.as_str(), &rules.known_mask);
            *counts.entry("known-secret".into()).or_default() += n;
        }
    }
    for rule in &rules.rules {
        let mut hits = 0usize;
        out = rule
            .rx
            .replace_all(&out, |caps: &regex::Captures| {
                let whole = caps.get(0).unwrap();
                match caps.name("secret") {
                    Some(s) => {
                        hits += 1;
                        let base = whole.start();
                        let w = whole.as_str();
                        format!("{}{}{}", &w[..s.start() - base], rule.mask, &w[s.end() - base..])
                    }
                    None => whole.as_str().to_string(),
                }
            })
            .into_owned();
        if hits > 0 {
            *counts.entry(rule.id.clone()).or_default() += hits;
        }
    }
    (out, counts)
}

/// Convenience over the embedded rules.
pub fn redact(text: &str, known: &[String]) -> (String, BTreeMap<String, usize>) {
    redact_text(text, rules(), known)
}

/// Values of every environment variable whose NAME looks like a secret (the key-name pattern from
/// the rules file). The values stay in Rust: they are used to scrub text and never returned.
pub fn known_env_secrets() -> Vec<String> {
    let rx = match &rules().key_name_rx {
        Some(r) => r,
        None => return Vec::new(),
    };
    std::env::vars().filter(|(k, v)| rx.is_match(k) && !v.is_empty()).map(|(_, v)| v).collect()
}

/// How many hidden items are privacy shortening (paths, e-mail) rather than secrets, for the
/// "4 secrets hidden, 3 personal details shortened" summary.
pub fn split_counts(counts: &BTreeMap<String, usize>) -> (usize, usize) {
    let r = rules();
    let mut secrets = 0;
    let mut personal = 0;
    for (id, n) in counts {
        let class = if id == "known-secret" {
            "secret"
        } else {
            r.rules.iter().find(|x| &x.id == id).map(|x| x.class.as_str()).unwrap_or("secret")
        };
        if class == "secret" {
            secrets += n;
        } else {
            personal += n;
        }
    }
    (secrets, personal)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The SAME file the Python test reads (NPDevCli/redaction-test-vectors.json): one set of
    /// vectors, two engines, byte-identical output.
    #[test]
    fn every_shared_vector_redacts_identically() {
        let vectors: Value =
            serde_json::from_str(include_str!("../../NPDevCli/redaction-test-vectors.json")).unwrap();
        let all = vectors["vectors"].as_array().unwrap();
        assert!(all.len() >= 26, "the shared vector file shrank");
        let mut bad = Vec::new();
        for v in all {
            let known: Vec<String> =
                v["knownSecrets"].as_array().unwrap().iter().map(|s| s.as_str().unwrap().to_string()).collect();
            let (got, _) = redact(v["input"].as_str().unwrap(), &known);
            if got != v["expected"].as_str().unwrap() {
                bad.push(format!("{}: expected {:?}, got {:?}", v["name"], v["expected"], got));
            }
        }
        assert!(bad.is_empty(), "{} vector(s) differ:\n{}", bad.len(), bad.join("\n"));
    }

    #[test]
    fn rules_load_and_every_rule_has_a_secret_group() {
        let r = rules();
        assert!(r.rules.len() >= 10);
        assert!(r.key_name_rx.is_some());
    }

    #[test]
    fn counts_split_secrets_from_personal_details() {
        let (out, counts) = redact("key gsk_abcdefghijklmnopqrstuvwxyz and mail me@example.com", &[]);
        assert!(!out.contains("gsk_abcdef"));
        let (secrets, personal) = split_counts(&counts);
        assert!(secrets >= 1, "{counts:?}");
        assert!(personal >= 1, "{counts:?}");
    }
}
