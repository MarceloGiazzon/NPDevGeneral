//! Manager themes (THM-3): the built-in catalogue, the user's own themes, and the appearance choice.
//!
//! A theme is DATA (`npdev-theme.v1`, NPDevContract/themes/theme.schema.json): hex colours, safe font
//! lists, a small radius and an elevation enum -- never CSS. The built-ins are embedded from the SAME
//! files the generator and `check-themes.py` read (`include_str!`), so there is no copy step and no
//! third twin. User themes live as `<id>.json` under `<manager_home>/themes/`.
//!
//! The UI validates a theme with the engine (structure AND contrast) before it ever applies one. This
//! module re-validates the STRUCTURE on import -- id pattern, hex pattern, font pattern, allowed keys,
//! ranges -- because the file name comes from the id and the webview is not the only thing that can
//! put a file in that folder. Contrast stays with the engine: it is a readability rule, not a
//! safety rule, and a hand-edited low-contrast file is refused when the UI loads it.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::PathBuf;

/// Gallery order (never file-name order): the Manager's own looks, then user themes.
pub const BUILT_IN: &[(&str, &str)] = &[
    ("npdev-dark", include_str!("../../NPDevContract/themes/npdev-dark.json")),
    ("npdev-light", include_str!("../../NPDevContract/themes/npdev-light.json")),
    ("retro", include_str!("../../NPDevContract/themes/retro.json")),
    ("synth", include_str!("../../NPDevContract/themes/synth.json")),
    ("farm", include_str!("../../NPDevContract/themes/farm.json")),
    ("warm", include_str!("../../NPDevContract/themes/warm.json")),
    ("windows", include_str!("../../NPDevContract/themes/windows.json")),
    ("high-contrast", include_str!("../../NPDevContract/themes/high-contrast.json")),
];

/// Ids a user theme may never take: every built-in, plus the generated-app default (also a built-in
/// in NPDevContract/themes, just not offered in the Manager's gallery) and the gallery's own "system".
const RESERVED_IDS: &[&str] = &[
    "npdev-dark", "npdev-light", "retro", "synth", "farm", "warm", "windows", "high-contrast", "npdev-default",
    "system",
];

pub const DEFAULT_THEME: &str = "npdev-dark";
const MAX_THEME_BYTES: usize = 20_000;
const TOKENS: [&str; 13] = [
    "bg", "surface", "surfaceRaised", "border", "borderStrong", "text", "textMuted", "accent", "onAccent",
    "positive", "negative", "warning", "info",
];
const ALLOWED_KEYS: [&str; 15] = [
    "$schema", "schema", "id", "name", "names", "version", "author", "description", "license", "colorScheme",
    "basedOn", "colors", "darkColors", "typography", "shape",
];

// ------------------------------------------------------------------------------------ appearance

/// Accessibility preferences (ACC-1), applied by the engine's `applyA11y` and `a11y.css`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct A11yPrefs {
    pub text_scale: f64,
    pub motion: String,
    pub focus: String,
    pub spacing: String,
    pub links: String,
    pub cvd: String,
}

impl Default for A11yPrefs {
    fn default() -> Self {
        A11yPrefs {
            text_scale: 1.0,
            motion: "system".into(),
            focus: "auto".into(),
            spacing: "normal".into(),
            links: "auto".into(),
            cvd: "off".into(),
        }
    }
}

/// What the user chose. `theme_id` is a built-in id, a user theme id, or "system" (follow the OS between
/// Light and Dark). Default is Dark (owner decision D1, 2026-11-03).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct Appearance {
    pub theme_id: String,
    /// "system" | "light" | "dark": only meaningful for a theme that carries `darkColors`.
    pub mode: String,
    pub a11y: A11yPrefs,
}

impl Default for Appearance {
    fn default() -> Self {
        Appearance { theme_id: DEFAULT_THEME.into(), mode: "system".into(), a11y: A11yPrefs::default() }
    }
}

const TEXT_SCALES: [f64; 5] = [0.9, 1.0, 1.15, 1.3, 1.5];

impl Appearance {
    /// Refuses a value the engine would silently replace with its default, so a typo in a CLI call or a
    /// hand-edited manager.json is reported rather than ignored.
    pub fn validate(&self) -> Result<(), String> {
        if !["system", "light", "dark"].contains(&self.mode.as_str()) {
            return Err(format!("mode must be system, light or dark, not {:?}", self.mode));
        }
        if !TEXT_SCALES.iter().any(|s| (s - self.a11y.text_scale).abs() < 1e-9) {
            return Err(format!("text size must be one of {TEXT_SCALES:?}, not {}", self.a11y.text_scale));
        }
        for (name, value, allowed) in [
            ("motion", &self.a11y.motion, &["system", "reduce"][..]),
            ("focus", &self.a11y.focus, &["auto", "always"][..]),
            ("spacing", &self.a11y.spacing, &["normal", "wide"][..]),
            ("links", &self.a11y.links, &["auto", "underline"][..]),
            ("cvd", &self.a11y.cvd, &["off", "safe"][..]),
        ] {
            if !allowed.contains(&value.as_str()) {
                return Err(format!("{name} must be one of {allowed:?}, not {value:?}"));
            }
        }
        Ok(())
    }
}

// ----------------------------------------------------------------------------------- structure

fn is_hex_colour(v: &Value) -> bool {
    v.as_str().is_some_and(|s| s.len() == 7 && s.starts_with('#') && s[1..].chars().all(|c| c.is_ascii_hexdigit()))
}

fn is_valid_id(id: &str) -> bool {
    let b = id.as_bytes();
    (2..=41).contains(&b.len())
        && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit())
        && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

fn is_safe_font(s: &str) -> bool {
    (1..=200).contains(&s.len())
        && s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, ' ' | ',' | '"' | '\'' | '-'))
}

/// Structure only (see the module comment). Returns the first problem in plain language.
pub fn validate_structure(theme: &Value) -> Result<(), String> {
    let obj = theme.as_object().ok_or("the theme is not a JSON object")?;
    if obj.get("schema").and_then(Value::as_str) != Some("npdev-theme.v1") {
        return Err("schema must be \"npdev-theme.v1\"".into());
    }
    let id = obj.get("id").and_then(Value::as_str).unwrap_or("");
    if !is_valid_id(id) {
        return Err("id must be lowercase letters, digits and hyphens (2 to 41 characters)".into());
    }
    match obj.get("name").and_then(Value::as_str) {
        Some(n) if !n.is_empty() && n.chars().count() <= 40 => {}
        _ => return Err("name is required (at most 40 characters)".into()),
    }
    let scheme = obj.get("colorScheme").and_then(Value::as_str);
    if scheme != Some("light") && scheme != Some("dark") {
        return Err("colorScheme must be light or dark".into());
    }
    for key in obj.keys() {
        if !ALLOWED_KEYS.contains(&key.as_str()) {
            return Err(format!("unknown key: {key}"));
        }
    }
    for set in ["colors", "darkColors"] {
        if let Some(colors) = obj.get(set) {
            let colors = colors.as_object().ok_or(format!("{set} must be an object"))?;
            for (k, v) in colors {
                if !TOKENS.contains(&k.as_str()) {
                    return Err(format!("{set}.{k} is not a colour token"));
                }
                if !is_hex_colour(v) {
                    return Err(format!("{set}.{k} must be #rrggbb"));
                }
            }
        }
    }
    if obj.get("basedOn").is_none() {
        let colors = obj.get("colors").and_then(Value::as_object);
        for t in TOKENS {
            if colors.and_then(|c| c.get(t)).is_none() {
                return Err(format!("colors.{t} is required (or use basedOn)"));
            }
        }
    }
    if let Some(typo) = obj.get("typography").and_then(Value::as_object) {
        for k in ["fontUi", "fontMono"] {
            if let Some(v) = typo.get(k) {
                if !v.as_str().is_some_and(is_safe_font) {
                    return Err(format!("typography.{k} may only use letters, digits, spaces, commas, quotes and hyphens"));
                }
            }
        }
    }
    if let Some(shape) = obj.get("shape").and_then(Value::as_object) {
        if let Some(r) = shape.get("radius") {
            if !r.as_u64().is_some_and(|r| r <= 24) {
                return Err("shape.radius must be a whole number from 0 to 24".into());
            }
        }
        if let Some(e) = shape.get("elevation") {
            if !e.as_str().is_some_and(|e| ["flat", "soft", "bevel", "glow"].contains(&e)) {
                return Err("shape.elevation must be flat, soft, bevel or glow".into());
            }
        }
    }
    Ok(())
}

// ----------------------------------------------------------------------------------- catalogue

fn user_themes_dir() -> PathBuf {
    crate::state::manager_home().join("themes")
}

fn built_in_themes() -> Vec<Value> {
    BUILT_IN
        .iter()
        .map(|(id, text)| {
            let mut v: Value = serde_json::from_str(text).unwrap_or_else(|e| panic!("built-in theme {id} is invalid JSON: {e}"));
            v["builtin"] = Value::Bool(true);
            v
        })
        .collect()
}

/// Built-ins in gallery order, then the user's themes (sorted by id). A user file that is not valid
/// structure is skipped, never fatal: one broken file must not empty the gallery.
pub fn list() -> Vec<Value> {
    list_in(&user_themes_dir())
}

fn list_in(dir: &std::path::Path) -> Vec<Value> {
    let mut out = built_in_themes();
    let mut user: Vec<Value> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for entry in rd.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let Ok(text) = std::fs::read_to_string(&path) else { continue };
            let Ok(mut v) = serde_json::from_str::<Value>(&text) else { continue };
            if validate_structure(&v).is_err() {
                continue;
            }
            let id = v["id"].as_str().unwrap_or("").to_string();
            if RESERVED_IDS.contains(&id.as_str()) || path.file_stem().and_then(|s| s.to_str()) != Some(&id) {
                continue;
            }
            v["builtin"] = Value::Bool(false);
            user.push(v);
        }
    }
    user.sort_by(|a, b| a["id"].as_str().cmp(&b["id"].as_str()));
    out.extend(user);
    out
}

pub fn exists(id: &str) -> bool {
    id == "system" || list().iter().any(|t| t["id"].as_str() == Some(id))
}

#[cfg(test)]
fn exists_in(dir: &std::path::Path, id: &str) -> bool {
    id == "system" || list_in(dir).iter().any(|t| t["id"].as_str() == Some(id))
}

/// Validate and store a user theme. Refuses a built-in or reserved id (a stranger's file must never
/// replace "Dark"), refuses oversize text, and writes `<id>.json` where `id` already passed the
/// pattern, so the file name can never contain a path separator.
pub fn import(json_text: &str) -> Result<Value, String> {
    import_in(&user_themes_dir(), json_text)
}

fn import_in(dir: &std::path::Path, json_text: &str) -> Result<Value, String> {
    if json_text.len() > MAX_THEME_BYTES {
        return Err(format!("the theme file is larger than {MAX_THEME_BYTES} bytes"));
    }
    let theme: Value = serde_json::from_str(json_text).map_err(|e| format!("not valid JSON: {e}"))?;
    validate_structure(&theme)?;
    let id = theme["id"].as_str().unwrap_or("").to_string();
    if RESERVED_IDS.contains(&id.as_str()) {
        return Err(format!("{id:?} is one of NPDev's own themes; give yours a different id"));
    }
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let text = serde_json::to_string_pretty(&theme).map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{id}.json")), text + "\n").map_err(|e| e.to_string())?;
    Ok(json!({ "id": id, "name": theme["name"] }))
}

/// Remove a user theme. Built-ins cannot be removed. Returns true when a file was deleted.
pub fn delete(id: &str) -> Result<bool, String> {
    delete_in(&user_themes_dir(), id)
}

fn delete_in(dir: &std::path::Path, id: &str) -> Result<bool, String> {
    if RESERVED_IDS.contains(&id) {
        return Err(format!("{id:?} is one of NPDev's own themes and cannot be removed"));
    }
    if !is_valid_id(id) {
        return Err("not a theme id".into());
    }
    let path = dir.join(format!("{id}.json"));
    match std::fs::remove_file(&path) {
        Ok(()) => Ok(true),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn light() -> Value {
        serde_json::from_str(BUILT_IN[1].1).unwrap()
    }

    #[test]
    fn every_built_in_is_valid_structure_and_listed_in_gallery_order() {
        let ids: Vec<String> = built_in_themes().iter().map(|t| t["id"].as_str().unwrap().to_string()).collect();
        assert_eq!(ids, ["npdev-dark", "npdev-light", "retro", "synth", "farm", "warm", "windows", "high-contrast"]);
        for (id, text) in BUILT_IN {
            let raw: Value = serde_json::from_str(text).unwrap();
            validate_structure(&raw).unwrap_or_else(|e| panic!("{id}: {e}"));
        }
    }

    #[test]
    fn hostile_values_are_refused() {
        let mut t = light();
        t["colors"]["bg"] = json!("red; background:url(https://evil.example/x)");
        assert!(validate_structure(&t).unwrap_err().contains("colors.bg"));
        let mut t = light();
        t["typography"] = json!({"fontUi": "x; } body { display:none"});
        assert!(validate_structure(&t).unwrap_err().contains("fontUi"));
        let mut t = light();
        t["shape"] = json!({"radius": 99});
        assert!(validate_structure(&t).is_err());
        let mut t = light();
        t["id"] = json!("../../evil");
        assert!(validate_structure(&t).unwrap_err().contains("id"));
        let mut t = light();
        t["onload"] = json!("alert(1)");
        assert!(validate_structure(&t).unwrap_err().contains("unknown key"));
    }

    #[test]
    fn a_stranger_cannot_replace_a_built_in_theme() {
        for id in ["npdev-dark", "npdev-default", "system", "high-contrast"] {
            let mut t = light();
            t["id"] = json!(id);
            let err = import_in(&std::env::temp_dir().join("npdev-thm3-never-written"), &t.to_string()).unwrap_err();
            assert!(err.contains("NPDev's own"), "{id}: {err}");
        }
        assert!(delete_in(&std::env::temp_dir(), "retro").is_err());
    }

    #[test]
    fn import_list_delete_round_trip_in_an_isolated_folder() {
        let dir = std::env::temp_dir().join(format!("npdev-thm3-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut t = light();
        t["id"] = json!("autumn-test");
        t["name"] = json!("Autumn test");
        let r = import_in(&dir, &t.to_string()).unwrap();
        assert_eq!(r["id"], "autumn-test");
        let all = list_in(&dir);
        assert_eq!(all.last().unwrap()["id"], "autumn-test");
        assert_eq!(all.last().unwrap()["builtin"], false);
        assert_eq!(all.len(), 9);
        assert!(exists_in(&dir, "autumn-test") && exists_in(&dir, "system") && !exists_in(&dir, "nope"));
        assert!(delete_in(&dir, "autumn-test").unwrap());
        assert!(!delete_in(&dir, "autumn-test").unwrap());
        assert_eq!(list_in(&dir).len(), 8);
        // a broken file next to a good one does not empty the gallery
        std::fs::write(dir.join("broken.json"), "{ not json").unwrap();
        assert_eq!(list_in(&dir).len(), 8);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn appearance_defaults_to_dark_and_a_missing_field_does_not_break_loading() {
        let a: Appearance = serde_json::from_str("{}").unwrap();
        assert_eq!(a.theme_id, "npdev-dark");
        assert_eq!(a.mode, "system");
        assert!(a.validate().is_ok());
        let half: Appearance = serde_json::from_str(r#"{"theme_id":"retro","a11y":{"cvd":"safe"}}"#).unwrap();
        assert_eq!(half.theme_id, "retro");
        assert_eq!(half.a11y.cvd, "safe");
        assert_eq!(half.a11y.text_scale, 1.0);
    }

    #[test]
    fn appearance_refuses_values_the_engine_would_silently_replace() {
        let mut a = Appearance::default();
        a.a11y.text_scale = 1.7;
        assert!(a.validate().is_err());
        let mut a = Appearance::default();
        a.a11y.cvd = "purple".into();
        assert!(a.validate().is_err());
        let mut a = Appearance::default();
        a.mode = "dim".into();
        assert!(a.validate().is_err());
    }
}
