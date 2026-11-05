//! Docker helpers behind the New-app database card.
//!
//! Two small, explicit actions the user asks for by pressing a button, both visible while they run:
//!
//!   * [`start_container`] -- `docker start <name>` for a stopped database the card listed. Only a
//!     NAME the card showed can reach it (see [`valid_container_name`]); nothing is built from free text.
//!   * [`install_docker_desktop`] -- opens an ELEVATED, VISIBLE PowerShell window that runs
//!     `winget install Docker.DockerDesktop`. Visible on purpose: installing Docker needs administrator
//!     rights (Windows' own consent prompt appears, and can be declined), can take minutes, and often ends
//!     with "restart your computer". A silent background install that sat there for ten minutes with no
//!     sign of life, or sprang a reboot on someone, is exactly what the guided card exists to avoid. The
//!     card polls `npdev db detect` and moves on by itself when Docker answers.
//!
//!   * [`wsl_status`] / [`install_wsl`] -- Docker Desktop on Windows runs on WSL 2 ("Windows Subsystem for
//!     Linux"). On a fresh Windows it is often missing, and Docker then fails with a message no
//!     non-specialist can act on. The assistant checks it first and, when it is missing, offers the same
//!     kind of visible, elevated window running `wsl --install --no-distribution` (Microsoft's own
//!     command; no Linux distribution is installed, Docker brings its own).
//!
//! Creating a database container is NOT here: that is `npdev db provision` in the CLI, so a terminal
//! user gets the same recipe the Manager does.

use std::path::PathBuf;
use std::process::Command;

/// Docker's own rule for a container name, minus the length it never needs: a letter or digit first,
/// then letters, digits, `_`, `.` or `-`. Anything else is refused before a process is spawned.
pub fn valid_container_name(name: &str) -> bool {
    let mut chars = name.chars();
    match chars.next() {
        Some(c) if c.is_ascii_alphanumeric() => {}
        _ => return false,
    }
    name.len() <= 128 && chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-'))
}

#[cfg(windows)]
fn quiet(command: &mut Command) -> &mut Command {
    use std::os::windows::process::CommandExt;
    // Without this a console window flashes for every short-lived child of a GUI process.
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW)
}

#[cfg(not(windows))]
fn quiet(command: &mut Command) -> &mut Command {
    command
}

/// `docker start <name>`. Returns Docker's own message on failure so the card can show it verbatim.
pub fn start_container(name: &str) -> Result<String, String> {
    if !valid_container_name(name) {
        return Err(format!("'{name}' is not a container name Docker accepts"));
    }
    let output = quiet(Command::new("docker").args(["start", name]))
        .output()
        .map_err(|e| format!("could not run docker: {e}"))?;
    if output.status.success() {
        return Ok(name.to_string());
    }
    let stderr = String::from_utf8_lossy(&output.stderr);
    let text = stderr.trim();
    Err(if text.is_empty() { format!("docker start {name} exited with {:?}", output.status.code()) } else { text.to_string() })
}

/// `winget.exe` on PATH (it is an app-execution alias under WindowsApps, which is on the user's PATH).
#[cfg_attr(not(windows), allow(dead_code))]
pub fn find_winget() -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).map(|dir| dir.join("winget.exe")).find(|p| p.exists())
}

/// The PowerShell that opens the elevated install window. A function so a test can read exactly what
/// the user will be asked to approve.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn winget_launch_script() -> String {
    "Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList \
     '-NoProfile','-NoExit','-ExecutionPolicy','Bypass','-Command',\
     'winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements'"
        .to_string()
}

#[cfg(windows)]
pub fn install_docker_desktop() -> Result<String, String> {
    if find_winget().is_none() {
        return Err("winget was not found on this computer".to_string());
    }
    let output = quiet(Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", &winget_launch_script()]))
        .output()
        .map_err(|e| format!("could not open the installer window: {e}"))?;
    if output.status.success() {
        return Ok("Docker Desktop installer opened".to_string());
    }
    // `Start-Process -Verb RunAs` throws when the person answers "No" to Windows' consent prompt.
    let stderr = String::from_utf8_lossy(&output.stderr);
    if stderr.contains("canceled") || stderr.contains("cancelled") {
        return Err("the administrator prompt was declined".to_string());
    }
    Err(format!("could not open the installer window: {}", stderr.trim()))
}

#[cfg(not(windows))]
pub fn install_docker_desktop() -> Result<String, String> {
    Err("installing Docker Desktop from here is only supported on Windows".to_string())
}

/// The PowerShell that opens the elevated WSL install window (same shape as [`winget_launch_script`]).
#[cfg_attr(not(windows), allow(dead_code))]
pub fn wsl_launch_script() -> String {
    "Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList      '-NoProfile','-NoExit','-ExecutionPolicy','Bypass','-Command',     'wsl --install --no-distribution'"
        .to_string()
}

/// `{applicable, installed}`: whether this machine needs WSL for Docker, and whether `wsl --status`
/// answers. Only the exit code is read -- wsl.exe prints UTF-16 and words that change between builds.
#[cfg(windows)]
pub fn wsl_status() -> serde_json::Value {
    let installed = quiet(Command::new("wsl.exe").arg("--status"))
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);
    serde_json::json!({ "applicable": true, "installed": installed })
}

#[cfg(not(windows))]
pub fn wsl_status() -> serde_json::Value {
    serde_json::json!({ "applicable": false, "installed": true })
}

#[cfg(windows)]
pub fn install_wsl() -> Result<String, String> {
    let output = quiet(Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", &wsl_launch_script()]))
        .output()
        .map_err(|e| format!("could not open the WSL installer window: {e}"))?;
    if output.status.success() {
        return Ok("WSL installer opened".to_string());
    }
    let stderr = String::from_utf8_lossy(&output.stderr);
    if stderr.contains("canceled") || stderr.contains("cancelled") {
        return Err("the administrator prompt was declined".to_string());
    }
    Err(format!("could not open the WSL installer window: {}", stderr.trim()))
}

#[cfg(not(windows))]
pub fn install_wsl() -> Result<String, String> {
    Err("WSL is only needed on Windows".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn container_names_follow_dockers_rule_and_nothing_else_gets_through() {
        for ok in ["npdev-mysql", "npdev-mysql-2", "a", "My_DB.1"] {
            assert!(valid_container_name(ok), "{ok}");
        }
        for bad in ["", "-rm", ".hidden", "_x", "a b", "a;b", "a&b", "$(x)", "a/b", "a\"b", "ação", &"x".repeat(129)] {
            assert!(!valid_container_name(bad), "{bad}");
        }
    }

    #[test]
    fn the_install_window_asks_for_administrator_and_runs_only_the_docker_package() {
        let script = winget_launch_script();
        assert!(script.contains("-Verb RunAs"), "must elevate through Windows' own prompt");
        assert!(script.contains("-NoExit"), "the window must stay open so the result can be read");
        assert!(script.contains("winget install -e --id Docker.DockerDesktop"));
        assert_eq!(1, script.matches("winget").count(), "exactly one winget invocation");
    }

    #[test]
    fn the_wsl_window_asks_for_administrator_and_installs_no_distribution() {
        let script = wsl_launch_script();
        assert!(script.contains("-Verb RunAs"));
        assert!(script.contains("-NoExit"));
        assert!(script.contains("wsl --install --no-distribution"));
        assert_eq!(1, script.matches("wsl ").count(), "exactly one wsl invocation");
    }
}
