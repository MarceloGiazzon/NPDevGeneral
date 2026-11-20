use std::process::Command;

fn main() {
    tauri_build::build();

    // The version chip's tooltip shows this alongside the version and a hand-maintained title
    // (main.rs's CURRENT_VERSION_TITLE) so a stale build is visible at a glance instead of trusted
    // on faith -- captured HERE, not hand-typed, so it can never be forgotten.
    //
    // WHY THE rerun-if-changed LINES (2026-11-20): `tauri_build::build()` prints its own
    // `cargo:rerun-if-changed` lines (tauri.conf.json, icons, capabilities). The moment ANY such
    // line exists, cargo stops re-running this script on other file changes -- so the timestamp was
    // only refreshed when tauri.conf.json changed (in practice: on a version bump), and every
    // rebuild in between showed the old date. Naming the source directories here makes the stamp
    // move on every rebuild that changes code or UI.
    for path in ["src", "ui", "build.rs", "Cargo.toml", "tauri.conf.json", "capabilities"] {
        println!("cargo:rerun-if-changed={path}");
    }

    let timestamp = chrono::Local::now().format("%d-%m-%Y %H:%M:%S").to_string();
    println!("cargo:rustc-env=NPDEV_MANAGER_BUILD_TIMESTAMP={timestamp}");

    // Which commit the binary was built from, "+changes" when NPDevManager had uncommitted edits.
    // No git (the manager-harness container copies sources without .git) => "unknown commit".
    let commit = git(&["rev-parse", "--short", "HEAD"]).unwrap_or_else(|| "unknown commit".into());
    let dirty = git(&["status", "--porcelain", "--", "."]).is_some_and(|s| !s.is_empty());
    let source = if dirty { format!("{commit}+changes") } else { commit };
    println!("cargo:rustc-env=NPDEV_MANAGER_BUILD_SOURCE={source}");
    // A commit without a source change must still refresh the commit id.
    if let Some(git_dir) = git(&["rev-parse", "--absolute-git-dir"]) {
        println!("cargo:rerun-if-changed={git_dir}/logs/HEAD");
    }
}

fn git(args: &[&str]) -> Option<String> {
    let out = Command::new("git").args(args).output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}
