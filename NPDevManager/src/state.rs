//! The Manager's one folder (SPEC.md §4): `manager.json` plus everything under it. Deleting the
//! folder deletes the Manager entirely -- nothing else is ever written outside it, and nothing is
//! ever installed system-wide (no PATH edits, no registry).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

/// `%LOCALAPPDATA%\NPDev` on Windows, `~/.local/share/npdev` on Linux (SPEC.md §4). Overridable via
/// `NPDEV_MANAGER_HOME` so stub-mode/dev runs and tests never touch the real one.
pub fn manager_home() -> PathBuf {
    if let Ok(dir) = std::env::var("NPDEV_MANAGER_HOME") {
        if !dir.trim().is_empty() {
            return PathBuf::from(dir);
        }
    }
    let base = dirs::data_local_dir().expect("no local data dir resolvable on this platform");
    if cfg!(target_os = "linux") {
        // dirs::data_local_dir() already returns ~/.local/share on Linux; SPEC.md names the leaf
        // "npdev" (lowercase), matching XDG convention, vs Windows's "NPDev".
        base.join("npdev")
    } else {
        base.join("NPDev")
    }
}

pub fn jdk_dir() -> PathBuf {
    manager_home().join("jdk-17")
}

pub fn python_dir() -> PathBuf {
    manager_home().join("python")
}

pub fn versions_dir() -> PathBuf {
    manager_home().join("versions")
}

pub fn apps_dir() -> PathBuf {
    manager_home().join("apps")
}

pub fn logs_dir() -> PathBuf {
    manager_home().join("logs")
}

/// Where `npdev setup` stages the runtime jars, for THIS Manager: the CLI resolves it as
/// `NPDEV_BUILD_ROOT/runtimehost-libs` (`npdev_cli.py::_default_runtimehost_libs_dir`) and
/// `npdev::build_command` pins `NPDEV_BUILD_ROOT` to `manager_home()`. Deliberately never created
/// here -- see `ensure_dirs`.
pub fn runtimehost_libs_dir() -> PathBuf {
    manager_home().join("runtimehost-libs")
}

/// The file doctor's `ai-knowledge-index` check tests, resolved the same way the CLI resolves it
/// (`_ai_build_root() / "npdev-ai" / "rag-index.json"`, with `NPDEV_BUILD_ROOT` = `manager_home()`).
pub fn ai_knowledge_index_path() -> PathBuf {
    manager_home().join("npdev-ai").join("rag-index.json")
}

/// How many `*.jar` files sit DIRECTLY in `dir` (never recursive, never creates anything). Returns
/// 0 for a directory that does not exist, which is the same answer a caller wants for "nothing
/// staged" -- `jarsStaged` is reported separately from the count so the two cannot be conflated.
pub fn count_jars_in(dir: &std::path::Path) -> usize {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return 0;
    };
    entries
        .filter_map(|e| e.ok())
        .filter(|e| {
            e.path()
                .extension()
                .map(|ext| ext.eq_ignore_ascii_case("jar"))
                .unwrap_or(false)
        })
        .count()
}

fn manager_json_path() -> PathBuf {
    manager_home().join("manager.json")
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct InstalledVersion {
    pub tag: String,
    pub installed_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AppEntry {
    pub name: String,
    pub directory: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ManagerState {
    pub schema_version: String,
    pub current_version: Option<String>,
    pub versions: Vec<InstalledVersion>,
    pub apps: Vec<AppEntry>,
    /// Recorded once M3/M4 succeed, so later runs don't re-probe: exact resolved binary paths.
    pub jdk_home: Option<String>,
    pub python_exe: Option<String>,
    /// MONITOR_PLAN D7: the Monitor shows the UNION of apps the Manager created and apps found by
    /// scanning these paths. Persisted so a user adds "where my apps live" once, not every launch.
    /// Empty by default -- the Monitor then shows only registered apps, which is correct rather
    /// than a guess about this machine's layout.
    #[serde(default)]
    pub inspect_paths: Vec<String>,
    /// D9 detection order #2: the user's explicit answer, which always beats a derived guess. Left
    /// None until detection finds an engine somewhere derived and the user says "remember it" --
    /// the Manager never ASKS for a path before trying to find one itself.
    #[serde(default)]
    pub scrapforai_root: Option<String>,
    /// E2: the assistant provider, if the user configured one. The Manager never bundles an API key.
    #[serde(default)]
    pub assistant: Option<AssistantConfig>,
    /// Prompter provider profiles. NON-SECRET fields only: the API key lives in the OS credential
    /// store under service "NPDev Manager", account "prompter/<id>" -- never here. See `secrets.rs`.
    #[serde(default)]
    pub prompter_profiles: Vec<PrompterProfile>,
}

/// One configured way to reach a model, for the Prompter tab.
///
/// Deliberately the same two shapes as `AssistantConfig` (an argv template or an HTTP endpoint),
/// because they cover the same ground: a user's own CLI, or a provider API. The difference is where
/// the credential lives -- here it is not a field at all.
///
/// `endpoint` being user-supplied is fine in the Manager and would NOT be fine in the generated
/// app's proxy. The Manager is the user configuring their own machine, so there is no confused
/// deputy: whoever types the endpoint is whoever owns the key. The in-app proxy takes its endpoint
/// from a server-side profile precisely because there the caller and the key owner differ.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PrompterProfile {
    /// Slug, unique within the list, e.g. "anthropic-work". Also the credential-store account key.
    pub id: String,
    pub label: String,
    /// "command" or "http".
    pub kind: String,
    /// command kind: the argv template. `{prompt_file}` is substituted with a path to a temp file --
    /// never with the prompt itself, which on an argv lands in shell history and every process
    /// listing on the machine.
    #[serde(default)]
    pub command: Vec<String>,
    /// http kind: the full messages/completions URL.
    #[serde(default)]
    pub endpoint: Option<String>,
    /// "bearer" | "x-api-key" | "x-goog-api-key".
    #[serde(default)]
    pub auth_style: Option<String>,
    #[serde(default)]
    pub models: Vec<String>,
    #[serde(default)]
    pub default_model: Option<String>,
    /// "low" | "medium" | "high", mapped per provider (or ignored where there is no equivalent).
    #[serde(default)]
    pub default_effort: Option<String>,
}

/// E2. Two shapes, both supplied entirely by the user: an external command template (their own
/// Claude CLI, say) or an HTTP endpoint plus a key they typed. Nothing here ships with the Manager.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AssistantConfig {
    /// "command" or "http".
    pub kind: String,
    /// command kind: the argv template. `{prompt_file}` and `{context_file}` are substituted with
    /// paths to temp files -- never with the content itself, which would put a user's DOM excerpt
    /// on a command line where it lands in shell history and process listings.
    #[serde(default)]
    pub command: Vec<String>,
    #[serde(default)]
    pub endpoint: Option<String>,
    #[serde(default)]
    pub api_key: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
}

impl ManagerState {
    fn new() -> Self {
        ManagerState {
            schema_version: "npdev-manager-state.v1".to_string(),
            ..Default::default()
        }
    }

    pub fn load() -> Self {
        let path = manager_json_path();
        match std::fs::read_to_string(&path) {
            Ok(text) => serde_json::from_str(&text).unwrap_or_else(|_| ManagerState::new()),
            Err(_) => ManagerState::new(),
        }
    }

    pub fn save(&self) -> std::io::Result<()> {
        std::fs::create_dir_all(manager_home())?;
        let text = serde_json::to_string_pretty(self).expect("ManagerState always serializes");
        std::fs::write(manager_json_path(), text)
    }
}

/// Tauri-managed shared state: the on-disk `ManagerState` plus handles to every currently-running
/// child process.
///
/// MONITOR_PLAN B2: this used to be `Mutex<Option<RunningProcess>>` -- one app at a time, enforced
/// by the TYPE. The Monitor's whole premise is a wall of apps, several of them running, so the
/// registry is now keyed by app directory. The Run screen keeps its one-at-a-time feel BY POLICY,
/// which is a UX choice it can change, rather than by a data structure that cannot.
///
/// **Nothing is ever trusted from memory.** Kill the Manager mid-run and the registry is gone while
/// the apps keep serving; a Manager that then reported them stopped would be confidently wrong. The
/// Monitor re-derives every card from `npdev monitor probe` and uses this map only to know which
/// processes IT can stop directly. That is the M0-M8 AppState lesson, restated.
pub struct AppState {
    pub manager: Mutex<ManagerState>,
    /// key = app directory, canonicalised by the caller.
    pub running: Mutex<HashMap<String, crate::npdev::RunningProcess>>,
    /// R2: the exploration engine outlives individual requests by design, so it gets an EXPLICIT
    /// lifecycle -- a status chip and a stop button -- rather than being left to leak.
    pub engine: Mutex<Option<crate::npdev::RunningProcess>>,
    /// The MODEL directory `start_dev` is currently watching, if any. `npdev dev` rebuilds and runs
    /// the SAME `<dir>-app` FinalApp that `start_app` can launch directly, and both hold the same
    /// embedded (non-server-mode) H2 file open -- a second opener does not queue, it crashes the
    /// boot with "The file is locked". Tracked here so `start_app`/`start_dev` can refuse the second
    /// launch with a clear reason instead of letting the collision happen and reporting a bare
    /// "stopped" the user has no way to explain.
    pub dev_app_dir: Mutex<Option<String>>,
}

impl AppState {
    pub fn new() -> Self {
        AppState {
            manager: Mutex::new(ManagerState::load()),
            running: Mutex::new(HashMap::new()),
            engine: Mutex::new(None),
            dev_app_dir: Mutex::new(None),
        }
    }
}

pub fn ensure_dirs() -> std::io::Result<()> {
    // Deliberately NOT runtimehost_libs_dir(): `doctor`'s own runtimehost-jars check only tests
    // directory existence, not a manifest inside it (true of the plain CLI too, not a Manager
    // gap) -- `npdev setup` creates that directory itself exactly when it actually stages jars
    // into it, so creating it eagerly here would make a healthy-looking Ready screen lie.
    for d in [manager_home(), versions_dir(), apps_dir(), logs_dir()] {
        std::fs::create_dir_all(&d)?;
    }
    Ok(())
}

pub fn current_version_dir(state: &ManagerState) -> Option<PathBuf> {
    state
        .current_version
        .as_ref()
        .map(|tag| versions_dir().join(tag))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unique_temp_dir(label: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "npdev-manager-test-{label}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn count_jars_in_counts_only_jars_directly_inside() {
        let dir = unique_temp_dir("count-jars");
        std::fs::write(dir.join("kernel-0.1.0.jar"), b"x").unwrap();
        std::fs::write(dir.join("core-0.1.0.jar"), b"x").unwrap();
        // Case-insensitively a jar too -- Windows filesystems hand back whatever case was written.
        std::fs::write(dir.join("dsl-0.1.0.JAR"), b"x").unwrap();
        std::fs::write(dir.join("runtimehost-libs-manifest.json"), b"{}").unwrap();
        std::fs::create_dir(dir.join("nested")).unwrap();
        std::fs::write(dir.join("nested").join("buried.jar"), b"x").unwrap();

        assert_eq!(count_jars_in(&dir), 3);

        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// The whole point of reporting `jarsStaged` separately: an absent directory answers 0 rather
    /// than exploding, and nothing is created on the way to that answer (`ensure_dirs`'s rule --
    /// a directory conjured by a status check would make the Ready screen lie).
    #[test]
    fn count_jars_in_answers_zero_for_a_missing_directory_without_creating_it() {
        let dir = unique_temp_dir("count-jars-missing");
        let absent = dir.join("runtimehost-libs");

        assert_eq!(count_jars_in(&absent), 0);
        assert!(!absent.exists());

        std::fs::remove_dir_all(&dir).unwrap();
    }
}

