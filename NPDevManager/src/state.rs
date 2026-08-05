//! The Manager's one folder (SPEC.md §4): `manager.json` plus everything under it. Deleting the
//! folder deletes the Manager entirely -- nothing else is ever written outside it, and nothing is
//! ever installed system-wide (no PATH edits, no registry).

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

/// Tauri-managed shared state: the on-disk `ManagerState` plus handles to any currently-running
/// child process (the `dev` loop is long-running and must be stoppable from a later command).
pub struct AppState {
    pub manager: Mutex<ManagerState>,
    pub running_dev: Mutex<Option<crate::npdev::RunningProcess>>,
}

impl AppState {
    pub fn new() -> Self {
        AppState {
            manager: Mutex::new(ManagerState::load()),
            running_dev: Mutex::new(None),
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

