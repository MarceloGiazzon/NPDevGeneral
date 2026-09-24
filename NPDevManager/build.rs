fn main() {
    tauri_build::build();
    // The version chip shows this alongside the version and a hand-maintained title (main.rs's
    // CURRENT_VERSION_TITLE) so a stale build is visible at a glance instead of trusted on faith --
    // captured HERE, not hand-typed, so it can never be forgotten the way the version bump itself
    // was (twice, in one session).
    let timestamp = chrono::Local::now().format("%d-%m-%Y %H:%M:%S").to_string();
    println!("cargo:rustc-env=NPDEV_MANAGER_BUILD_TIMESTAMP={timestamp}");
}
