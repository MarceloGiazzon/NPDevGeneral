//! THM-10: the public theme catalogue (github.com/MarceloGiazzon/npdev-themes) -- Browse themes and Share this theme.
//!
//! The catalogue is a git repository holding `themes/<id>.json` and a GENERATED `index.json`
//! (`npdev-theme-index.v1`, built by `check-themes.py --catalogue --write-index`). **Browse themes** reads the index
//! over HTTPS, then downloads one theme file and checks its SHA-256 against the index BEFORE anything is stored; a
//! file that does not match is refused and never reaches the importer. The bytes returned here are only ever handed
//! to the same `themes::import` path a hand-loaded file takes (structure re-validated, built-in ids refused, the
//! engine's contrast gate in the webview), so a catalogue theme is no more trusted than one the user pasted.
//!
//! **Share this theme** builds a GitHub "new file" URL prefilled with the theme's JSON. The Manager never handles a
//! GitHub token; the user's browser does the rest.
//!
//! Stub mode (`NPDEV_MANAGER_FAKE=1`) serves the embedded fixtures instead of the network, plus one deliberately
//! tampered entry (`tampered-demo`) so the refusal can be seen and tested without hostile network access.

use crate::themes;
use serde_json::Value;
use sha2::{Digest, Sha256};

pub const REPO: &str = "MarceloGiazzon/npdev-themes";
const RAW_BASE: &str = "https://raw.githubusercontent.com/MarceloGiazzon/npdev-themes/main/";
const INDEX_SCHEMA: &str = "npdev-theme-index.v1";
const MAX_INDEX_BYTES: usize = 200_000;
/// GitHub's new-file page takes the content in the query string; past this the browser or server may truncate it,
/// which would silently submit a broken theme.
const MAX_SHARE_URL_BYTES: usize = 7_000;
const TAMPERED_ID: &str = "tampered-demo";

const FIXTURE_INDEX: &str = include_str!("../fixtures/theme-catalogue/index.json");
const FIXTURE_FILES: &[(&str, &str)] = &[
    ("autumn", include_str!("../fixtures/theme-catalogue/autumn.json")),
    ("spring", include_str!("../fixtures/theme-catalogue/spring.json")),
    ("summer", include_str!("../fixtures/theme-catalogue/summer.json")),
    ("winter", include_str!("../fixtures/theme-catalogue/winter.json")),
];

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

fn is_hex(s: &str, len: usize) -> bool {
    s.len() == len && s.bytes().all(|c| c.is_ascii_hexdigit())
}

fn text_field(v: &Value, key: &str, max: usize) -> String {
    v[key].as_str().unwrap_or("").chars().take(max).collect()
}

/// Parse and sanitise the index. An entry that is not well-formed is dropped (one bad row must not empty the
/// browser); an index that is not the expected schema is refused whole. Only the fields the UI shows are kept.
pub fn parse_index(text: &str) -> Result<Vec<Value>, String> {
    if text.len() > MAX_INDEX_BYTES {
        return Err("the catalogue index is unreasonably large; refused".into());
    }
    let index: Value = serde_json::from_str(text).map_err(|e| format!("the catalogue index is not valid JSON: {e}"))?;
    if index["schema"] != INDEX_SCHEMA {
        return Err(format!("the catalogue index is not {INDEX_SCHEMA}"));
    }
    let mut out = Vec::new();
    for t in index["themes"].as_array().ok_or("the catalogue index has no themes list")? {
        let id = t["id"].as_str().unwrap_or("");
        let sha = t["sha256"].as_str().unwrap_or("");
        let swatches: Vec<String> =
            t["swatches"].as_array().map(|a| a.iter().filter_map(|s| s.as_str()).map(String::from).collect()).unwrap_or_default();
        let swatches_ok = swatches.len() == 5 && swatches.iter().all(|s| s.len() == 7 && s.starts_with('#') && is_hex(&s[1..], 6));
        let scheme = t["colorScheme"].as_str().unwrap_or("");
        if !themes::is_valid_id(id)
            || themes::RESERVED_IDS.contains(&id)
            || !is_hex(sha, 64)
            || t["file"].as_str() != Some(&format!("themes/{id}.json"))
            || !swatches_ok
            || !(scheme == "light" || scheme == "dark")
        {
            continue;
        }
        let mut names = serde_json::Map::new();
        if let Some(map) = t["names"].as_object() {
            for (lang, name) in map.iter().take(12) {
                if let Some(n) = name.as_str() {
                    names.insert(lang.chars().take(8).collect(), Value::String(n.chars().take(40).collect()));
                }
            }
        }
        out.push(serde_json::json!({
            "id": id,
            "name": text_field(t, "name", 40),
            "names": names,
            "version": text_field(t, "version", 20),
            "author": text_field(t, "author", 60),
            "license": text_field(t, "license", 40),
            "colorScheme": scheme,
            "swatches": swatches,
            "file": format!("themes/{id}.json"),
            "sha256": sha.to_ascii_lowercase(),
        }));
    }
    Ok(out)
}

/// The refusal at the heart of THM-10: the downloaded bytes must hash to what the index said. Returns the theme
/// text only when they do (and the file names the same id, parses, and passes the importer's structure rules).
pub fn verify_theme(entry: &Value, bytes: &[u8]) -> Result<String, String> {
    let id = entry["id"].as_str().unwrap_or("?");
    if bytes.len() > themes::MAX_THEME_BYTES {
        return Err(format!("the file for {id:?} is larger than {} bytes; refused", themes::MAX_THEME_BYTES));
    }
    let expected = entry["sha256"].as_str().unwrap_or("");
    let actual = sha256_hex(bytes);
    if !actual.eq_ignore_ascii_case(expected) {
        return Err(format!(
            "the file for {id:?} does not match the catalogue's sha256 (expected {}…, got {}…); it was NOT imported",
            &expected.chars().take(12).collect::<String>(),
            &actual[..12]
        ));
    }
    let text = String::from_utf8(bytes.to_vec()).map_err(|_| format!("the file for {id:?} is not UTF-8 text"))?;
    let theme: Value = serde_json::from_str(&text).map_err(|e| format!("the file for {id:?} is not valid JSON: {e}"))?;
    if theme["id"].as_str() != Some(id) {
        return Err(format!("the file for {id:?} names a different theme id"));
    }
    themes::validate_structure(&theme)?;
    Ok(text)
}

fn percent_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// "Share this theme": GitHub's new-file page for `themes/<id>.json`, prefilled. Refuses what the catalogue's own
/// CI would refuse, so the user hears it here and not from a red check on their contribution.
pub fn share_url(json_text: &str) -> Result<String, String> {
    if json_text.len() > themes::MAX_THEME_BYTES {
        return Err("the theme file is too large to share".into());
    }
    let theme: Value = serde_json::from_str(json_text).map_err(|e| format!("not valid JSON: {e}"))?;
    themes::validate_structure(&theme)?;
    let id = theme["id"].as_str().unwrap_or("");
    if themes::RESERVED_IDS.contains(&id) {
        return Err(format!("{id:?} is one of NPDev's own themes; give yours a different id to share it"));
    }
    if theme.get("basedOn").is_some() {
        return Err("a shared theme must be self-contained (no basedOn)".into());
    }
    for key in ["author", "license"] {
        if theme[key].as_str().unwrap_or("").is_empty() {
            return Err(format!("a shared theme needs {key:?} (the catalogue records who made it and under what licence)"));
        }
    }
    let mut shared = theme.clone();
    if shared["version"].as_str().unwrap_or("").is_empty() {
        shared["version"] = Value::String("1.0.0".into());
    }
    let pretty = serde_json::to_string_pretty(&shared).map_err(|e| e.to_string())? + "\n";
    let url = format!(
        "https://github.com/{REPO}/new/main/themes?filename={}&value={}",
        percent_encode(&format!("{id}.json")),
        percent_encode(&pretty)
    );
    if url.len() > MAX_SHARE_URL_BYTES {
        return Err("the theme is too large to prefill; copy it and paste it into the new file yourself".into());
    }
    Ok(url)
}

// -------------------------------------------------------------------------------------------- fetching

fn fake_index_text() -> String {
    FIXTURE_INDEX.to_string()
}

fn fake_entries() -> Result<Vec<Value>, String> {
    let mut entries = parse_index(&fake_index_text())?;
    // The deliberate refusal demo: this entry carries WINTER's hash, but its file (see `fake_file`) has an extra
    // byte -- exactly what a tampered or corrupted download looks like.
    if let Some(winter) = entries.iter().find(|e| e["id"] == "winter").cloned() {
        let mut t = winter;
        t["id"] = Value::String(TAMPERED_ID.into());
        t["name"] = Value::String("Tampered (stub demo)".into());
        t["names"] = serde_json::json!({});
        t["file"] = Value::String(format!("themes/{TAMPERED_ID}.json"));
        entries.push(t);
    }
    Ok(entries)
}

fn fake_file(id: &str) -> Option<Vec<u8>> {
    if id == TAMPERED_ID {
        let mut bytes = FIXTURE_FILES.iter().find(|(i, _)| *i == "winter")?.1.as_bytes().to_vec();
        bytes.push(b' ');
        return Some(bytes);
    }
    FIXTURE_FILES.iter().find(|(i, _)| *i == id).map(|(_, t)| t.as_bytes().to_vec())
}

async fn get_bytes(path: &str, limit: usize) -> Result<Vec<u8>, String> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(15))
        .user_agent("npdev-manager")
        .build()
        .map_err(|e| e.to_string())?;
    let url = format!("{RAW_BASE}{path}");
    let response = client.get(&url).send().await.map_err(|e| format!("could not reach the theme catalogue: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("the theme catalogue answered {} for {path}", response.status()));
    }
    let bytes = response.bytes().await.map_err(|e| format!("could not read {path}: {e}"))?;
    if bytes.len() > limit {
        return Err(format!("{path} is larger than {limit} bytes; refused"));
    }
    Ok(bytes.to_vec())
}

/// The catalogue's entries, sanitised. `fake` selects the embedded fixtures.
pub async fn index(fake: bool) -> Result<Vec<Value>, String> {
    if fake {
        return fake_entries();
    }
    let bytes = get_bytes("index.json", MAX_INDEX_BYTES).await?;
    parse_index(&String::from_utf8(bytes).map_err(|_| "the catalogue index is not UTF-8 text".to_string())?)
}

/// One theme's verified JSON text. The index is fetched again here (the UI's copy is never trusted for the hash).
pub async fn fetch(fake: bool, id: &str) -> Result<String, String> {
    let entries = index(fake).await?;
    let entry = entries.iter().find(|e| e["id"].as_str() == Some(id)).ok_or_else(|| format!("{id:?} is not in the catalogue"))?;
    let bytes = if fake {
        fake_file(id).ok_or_else(|| format!("no stub file for {id:?}"))?
    } else {
        get_bytes(entry["file"].as_str().unwrap_or(""), themes::MAX_THEME_BYTES + 1).await?
    };
    verify_theme(entry, &bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_captured_index_parses_and_every_fixture_file_matches_its_hash() {
        let entries = parse_index(FIXTURE_INDEX).expect("fixture index parses");
        assert_eq!(entries.len(), 4, "four seasonal themes were captured");
        for e in &entries {
            let id = e["id"].as_str().unwrap();
            let bytes = fake_file(id).unwrap_or_else(|| panic!("no fixture file for {id}"));
            verify_theme(e, &bytes).unwrap_or_else(|err| panic!("{id}: {err}"));
        }
    }

    #[test]
    fn a_tampered_file_is_refused_and_never_returned() {
        let entries = fake_entries().unwrap();
        let demo = entries.iter().find(|e| e["id"] == TAMPERED_ID).expect("the stub carries the refusal demo");
        let err = verify_theme(demo, &fake_file(TAMPERED_ID).unwrap()).unwrap_err();
        assert!(err.contains("does not match the catalogue's sha256") && err.contains("NOT imported"), "{err}");
    }

    #[test]
    fn one_changed_byte_in_a_real_theme_is_refused() {
        let entries = parse_index(FIXTURE_INDEX).unwrap();
        let spring = entries.iter().find(|e| e["id"] == "spring").unwrap();
        let mut bytes = fake_file("spring").unwrap();
        let pos = bytes.iter().position(|b| *b == b'#').unwrap();
        bytes[pos + 1] = if bytes[pos + 1] == b'0' { b'1' } else { b'0' };
        assert!(verify_theme(spring, &bytes).is_err());
    }

    #[test]
    fn a_matching_hash_does_not_excuse_a_file_for_a_different_theme() {
        let entries = parse_index(FIXTURE_INDEX).unwrap();
        let mut spring = entries.iter().find(|e| e["id"] == "spring").unwrap().clone();
        // The index says "spring" but its hash is of the SUMMER file: bytes match the hash, id does not match.
        let summer_bytes = fake_file("summer").unwrap();
        spring["sha256"] = Value::String(sha256_hex(&summer_bytes));
        assert!(verify_theme(&spring, &summer_bytes).unwrap_err().contains("different theme id"));
    }

    #[test]
    fn index_rows_that_are_malformed_or_take_a_built_in_id_are_dropped() {
        let sha = "a".repeat(64);
        let row = |id: &str, sha: &str, file: &str| {
            serde_json::json!({"id": id, "name": "X", "colorScheme": "dark", "file": file, "sha256": sha,
                "swatches": ["#000000", "#111111", "#222222", "#333333", "#444444"]})
        };
        let index = serde_json::json!({"schema": INDEX_SCHEMA, "themes": [
            row("good", &sha, "themes/good.json"),
            row("retro", &sha, "themes/retro.json"),
            row("../evil", &sha, "themes/../evil.json"),
            row("short-sha", "abc", "themes/short-sha.json"),
            row("wrong-file", &sha, "themes/other.json"),
        ]});
        let entries = parse_index(&index.to_string()).unwrap();
        assert_eq!(entries.iter().map(|e| e["id"].as_str().unwrap()).collect::<Vec<_>>(), vec!["good"]);
        assert!(parse_index(r#"{"schema":"something-else","themes":[]}"#).is_err());
    }

    #[test]
    fn share_url_prefills_the_catalogue_new_file_page_and_refuses_what_ci_would_refuse() {
        let winter = FIXTURE_FILES.iter().find(|(i, _)| *i == "winter").unwrap().1;
        let mine = winter.replace("\"id\": \"winter\"", "\"id\": \"my-winter\"");
        let url = share_url(&mine).unwrap();
        assert!(url.starts_with("https://github.com/MarceloGiazzon/npdev-themes/new/main/themes?filename=my-winter.json&value="), "{url}");
        assert!(!url.contains(' ') && !url.contains('"') && !url.contains('\n'), "the content must be percent-encoded");
        // built-in id, basedOn, missing licence
        assert!(share_url(&winter.replace("\"id\": \"winter\"", "\"id\": \"retro\"")).is_err());
        assert!(share_url(&mine.replace("\"colorScheme\"", "\"basedOn\": \"retro\", \"colorScheme\"")).is_err());
        assert!(share_url(&mine.replace("\"license\": \"MIT\",", "")).unwrap_err().contains("license"));
    }
}
