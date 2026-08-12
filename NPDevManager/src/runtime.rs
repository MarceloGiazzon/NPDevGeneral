//! Private JDK / Python download + extract (Phase 2 M3/M4). **Never touches PATH, the registry,
//! or any system setting** -- everything lands under the Manager's own home
//! (`state::manager_home()`), and `JAVA_HOME`/the resolved Python path are only ever passed in the
//! environment of processes the Manager itself spawns (`npdev.rs`).
//!
//! URLs and checksums here were verified live against the real APIs while this file was written
//! (2026-08-05), not copied from the spec docs (§11 prohibits hardcoding from the doc without
//! confirming first) -- see `NPDev_General__OutsideRepo/manager/PHASE2_EVIDENCE.md`.

use std::path::{Path, PathBuf};

use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::state;

#[derive(Debug, Clone)]
pub struct DownloadTarget {
    pub url: String,
    pub sha256: String,
    pub file_name: String,
}

/// Pure mapping from `std::env::consts::OS`-shaped strings to the Adoptium API's `os` query param.
/// Split out from `os_name()` so I1's tests can exercise every platform string the real APIs use
/// without needing a separate compile per target (`cfg!` is compile-time-only).
pub fn os_name_for(target_os: &str) -> &'static str {
    if target_os == "windows" {
        "windows"
    } else {
        "linux"
    }
}

/// Same idea as `os_name_for`, for the `architecture` query param.
pub fn arch_name_for(target_arch: &str) -> &'static str {
    if target_arch == "aarch64" {
        "aarch64"
    } else {
        "x64"
    }
}

fn os_name() -> &'static str {
    os_name_for(std::env::consts::OS)
}

fn arch_name() -> &'static str {
    arch_name_for(std::env::consts::ARCH)
}

// ---------------------------------------------------------------------------------------------
// M3: private JDK 17 -- resolved live from the Adoptium v3 API (confirmed shape 2026-08-05):
// GET https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse
// -> [ { "binary": { "package": { "link": "...zip", "checksum": "<sha256 hex>", "name": "..." } } } ]
// The checksum comes back IN the same response -- no separate .sha256 fetch needed, unlike the
// tag-zip / runtimehost-libs downloads elsewhere in this project.
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct AdoptiumAsset {
    binary: AdoptiumBinary,
}

#[derive(Debug, Deserialize)]
struct AdoptiumBinary {
    package: AdoptiumPackage,
}

#[derive(Debug, Deserialize)]
struct AdoptiumPackage {
    link: String,
    checksum: String,
    name: String,
}

/// deps-and-java/PLAN.md W1.8, widened by ROUND2_PLAN.md R1c: parameterized so a future caller can
/// resolve a JDK matching an app's own `build.javaVersion` (any integer >= 17, no upper enum), not
/// just the Manager's own private-JDK level. The
/// Adoptium URL already had `17` as a literal path segment -- `major` just replaces it; the rest
/// of the request/response shape is identical for any hotspot major version Adoptium serves.
pub async fn resolve_jdk(major: u32) -> Result<DownloadTarget, String> {
    let url = format!(
        "https://api.adoptium.net/v3/assets/latest/{}/hotspot?architecture={}&image_type=jdk&os={}&vendor=eclipse",
        major,
        arch_name(),
        os_name()
    );
    let client = reqwest::Client::builder()
        .user_agent("npdev-manager/0.1")
        .build()
        .map_err(|e| e.to_string())?;
    let assets: Vec<AdoptiumAsset> = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("Adoptium API request failed: {e}"))?
        .json()
        .await
        .map_err(|e| format!("Adoptium API response did not parse: {e}"))?;
    let first = assets
        .into_iter()
        .next()
        .ok_or_else(|| format!("Adoptium API returned no JDK {} build for {}/{}", major, os_name(), arch_name()))?;
    Ok(DownloadTarget {
        url: first.binary.package.link,
        sha256: first.binary.package.checksum,
        file_name: first.binary.package.name,
    })
}

/// 17 stays the default install -- the Manager's OWN private JDK, used to run the CLI/Gradle for
/// platform work, which is pinned at 17 regardless of what any generated app's build.javaVersion
/// requests (deps-and-java/PLAN.md D1, Risk 3 -- wiring the Run screen to notice a per-app level
/// and provision a SECOND private JDK is deferred, tracked but out of scope here).
pub async fn resolve_jdk17() -> Result<DownloadTarget, String> {
    resolve_jdk(17).await
}

/// True once `state::jdk_dir()/bin/java(.exe)` exists -- the private JDK is already installed.
pub fn jdk_already_installed() -> bool {
    java_binary_in(&state::jdk_dir()).exists()
}

pub fn java_binary_in(jdk_home: &Path) -> PathBuf {
    java_binary_in_for(jdk_home, std::env::consts::OS)
}

/// Pure form of `java_binary_in` -- takes the target OS as a parameter so tests can check both
/// platforms' executable name from a single compile.
pub fn java_binary_in_for(jdk_home: &Path, target_os: &str) -> PathBuf {
    jdk_home.join("bin").join(if target_os == "windows" { "java.exe" } else { "java" })
}

// ---------------------------------------------------------------------------------------------
// M4: private Python -- ONLY the exact release Phase 0 I4a already proved works (the CLI is
// stdlib-only; this specific build was verified running doctor/doctor --json/init/validate on a
// real machine -- see PHASE0_EVIDENCE.md). Deliberately pinned, not "latest": a new
// python-build-standalone release is real information NPDev has not tested against.
// python-build-standalone does not publish a checksum per asset, so these SHA-256 hashes were
// computed directly from the downloaded files during this implementation (2026-08-05) and are
// pinned here alongside the URL, giving real integrity verification despite the upstream gap.
// ---------------------------------------------------------------------------------------------

const PYTHON_RELEASE_TAG: &str = "20260804";
const PYTHON_VERSION: &str = "3.12.13";

/// Pure mapping from (target_os, target_arch) to the python-build-standalone platform triple and
/// its pinned SHA-256 -- split out from `resolve_portable_python` so I1's tests can exercise every
/// platform this Manager supports from one compile.
pub fn python_platform_triple_and_sha256(target_os: &str, target_arch: &str) -> (&'static str, &'static str) {
    if target_os == "windows" {
        ("x86_64-pc-windows-msvc", "846c79af4c264b8f504b25a478bfeb646194d3d830d143aa44c32ed25da40c44")
    } else if target_arch == "aarch64" {
        ("aarch64-unknown-linux-gnu", "a8c8e1966602cc605f139d2d21c659252f9fbced1d6fb6a10fbbd59ac1366dc9")
    } else {
        ("x86_64-unknown-linux-gnu", "a140c0868258075d160fa0da51ddffd423efbc9dd350695abd33e7ce3ce94352")
    }
}

pub fn resolve_portable_python() -> DownloadTarget {
    let (platform_triple, sha256) =
        python_platform_triple_and_sha256(std::env::consts::OS, std::env::consts::ARCH);
    let file_name = format!("cpython-{PYTHON_VERSION}+{PYTHON_RELEASE_TAG}-{platform_triple}-install_only.tar.gz");
    let url = format!(
        "https://github.com/astral-sh/python-build-standalone/releases/download/{PYTHON_RELEASE_TAG}/{file_name}"
    );
    DownloadTarget {
        url,
        sha256: sha256.to_string(),
        file_name,
    }
}

pub fn python_binary_in(python_home: &Path) -> PathBuf {
    python_binary_in_for(python_home, std::env::consts::OS)
}

/// Pure form of `python_binary_in` -- see `java_binary_in_for`.
pub fn python_binary_in_for(python_home: &Path, target_os: &str) -> PathBuf {
    if target_os == "windows" {
        python_home.join("python.exe")
    } else {
        python_home.join("bin").join("python3")
    }
}

pub fn portable_python_already_installed() -> bool {
    python_binary_in(&state::python_dir()).exists()
}

/// `tokio::process::Command::new`, but never flashes a console window on Windows -- these probes
/// (`python --version`, `where`) run on every Ready/Install screen load, and a console-subsystem
/// child spawned from this GUI's no-console parent gets its own window unless told not to.
fn no_window_command(program: &str) -> tokio::process::Command {
    let mut cmd = tokio::process::Command::new(program);
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

/// Detect a system Python >= 3.9 -- checked with the SAME command a terminal user would run, no
/// special-cased logic: `python --version` / `python3 --version`, whichever resolves. Returns the
/// resolved path only when it also satisfies the version. M4's "download only otherwise" rule.
pub async fn detect_system_python() -> Option<PathBuf> {
    for candidate in ["python3", "python"] {
        let Ok(output) = no_window_command(candidate).arg("--version").output().await else {
            continue;
        };
        if !output.status.success() {
            continue;
        }
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        if let Some(version) = parse_python_version(&text) {
            if version >= (3, 9) {
                if let Ok(resolved) = which_command(candidate).await {
                    return Some(resolved);
                }
            }
        }
    }
    None
}

/// A Java the machine already has, good enough to run NPDev with.
#[derive(Debug, Clone)]
pub struct SystemJava {
    /// The JDK home (not the binary) -- the shape `JAVA_HOME` and the CLI both want.
    pub java_home: PathBuf,
    /// Exactly what `java -version` reported, e.g. "17.0.11".
    pub version: String,
}

/// Detect a system Java >= 17, in the SAME order the CLI itself resolves one (`npdev_cli.py`'s
/// `java_launcher`: JAVA_HOME's binary first, PATH second) -- so what the Install tab reports and
/// what a spawned child actually gets cannot disagree.
///
/// There was no system-Java detection anywhere in the Manager before this: `jdk_status` asked only
/// whether the PRIVATE JDK existed under the Manager's home, so a machine with a perfect JDK 17
/// was told "not installed" and offered a download as the only way forward. End to end the system
/// Java already worked -- with `manager.jdk_home` unset the CLI resolves JAVA_HOME/PATH itself --
/// the Install tab simply never said so.
pub async fn detect_system_java() -> Option<SystemJava> {
    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        let home = PathBuf::from(java_home.trim());
        if !java_home.trim().is_empty() {
            let binary = java_binary_in(&home);
            if binary.exists() {
                if let Some(version) = java_version_of(&binary).await {
                    if parse_java_major(&version).map(|m| m >= 17).unwrap_or(false) {
                        return Some(SystemJava { java_home: home, version });
                    }
                }
            }
        }
    }
    let resolved = which_command("java").await.ok()?;
    let version = java_version_of(&resolved).await?;
    if !parse_java_major(&version).map(|m| m >= 17).unwrap_or(false) {
        return None;
    }
    // `<home>/bin/java` -> `<home>`. A `java` that is not inside a `bin` directory is something
    // this function has no home to report for, so it reports none rather than inventing one.
    let java_home = resolved.parent()?.parent()?.to_path_buf();
    Some(SystemJava { java_home, version })
}

async fn java_version_of(binary: &Path) -> Option<String> {
    let output = no_window_command(&binary.to_string_lossy())
        .arg("-version")
        .output()
        .await
        .ok()?;
    if !output.status.success() {
        return None;
    }
    // `java -version` prints to STDERR, not stdout -- has done since 1.0, and reading only stdout
    // is the classic way to conclude a working JDK is absent. Both are joined so a future JDK that
    // moves it to stdout still parses.
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stderr),
        String::from_utf8_lossy(&output.stdout)
    );
    parse_java_version_string(&text)
}

/// Pulls `17.0.11` out of the first quoted version in `java -version` output, whatever vendor
/// banner surrounds it: `openjdk version "17.0.11" 2024-04-16`, `java version "1.8.0_401"`, ...
pub fn parse_java_version_string(text: &str) -> Option<String> {
    let line = text.lines().find(|l| l.contains('"'))?;
    let start = line.find('"')? + 1;
    let rest = &line[start..];
    let end = rest.find('"')?;
    let version = rest[..end].trim();
    if version.is_empty() {
        None
    } else {
        Some(version.to_string())
    }
}

/// The major version of a Java version string, both modern (`17.0.11` -> 17) and legacy
/// (`1.8.0_401` -> 8). The legacy form matters: a machine with Java 8 on PATH must be reported as
/// NOT usable, and reading its leading `1` as the major would say "1 >= 17 is false" by luck rather
/// than by understanding -- which stops being luck the day someone runs it against `1.9`.
pub fn parse_java_major(version: &str) -> Option<u32> {
    let mut parts = version.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.trim().parse().ok()?;
    if first == 1 {
        parts.next()?.trim().parse().ok()
    } else {
        Some(first)
    }
}

fn parse_python_version(text: &str) -> Option<(u32, u32)> {
    let rest = text.trim().strip_prefix("Python ")?;
    let mut parts = rest.split('.');
    let major: u32 = parts.next()?.trim().parse().ok()?;
    let minor: u32 = parts.next()?.trim().parse().ok()?;
    Some((major, minor))
}

async fn which_command(name: &str) -> Result<PathBuf, String> {
    let program = if cfg!(target_os = "windows") { "where" } else { "which" };
    let output = no_window_command(program)
        .arg(name)
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(format!("{program} could not resolve {name}"));
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let first_line = text.lines().next().unwrap_or("").trim();
    if first_line.is_empty() {
        Err(format!("{program} {name} produced no output"))
    } else {
        Ok(PathBuf::from(first_line))
    }
}

// ---------------------------------------------------------------------------------------------
// Shared download + verify + extract machinery
// ---------------------------------------------------------------------------------------------

/// Downloads to a temp file, verifies its SHA-256 against `target.sha256` BEFORE extracting
/// anything, then unpacks into `dest_dir` (zip on Windows, tar.gz on Linux -- matching the asset
/// each `resolve_*` function actually returns for that platform). On any failure, `dest_dir` is
/// left untouched -- caller decides what to do next (M3/M4 have no "build fallback" the way
/// Phase 0 I5's jar download does; a failed runtime download is reported to the user directly).
pub async fn download_verify_extract(
    target: &DownloadTarget,
    dest_dir: &Path,
    on_progress: impl Fn(u64, Option<u64>) + Send + 'static,
) -> Result<(), String> {
    use futures_util::StreamExt;

    let client = reqwest::Client::builder()
        .user_agent("npdev-manager/0.1")
        .build()
        .map_err(|e| e.to_string())?;
    let response = client
        .get(&target.url)
        .send()
        .await
        .map_err(|e| format!("download failed: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("download failed: HTTP {}", response.status()));
    }
    let total = response.content_length();

    let tmp_dir = std::env::temp_dir().join("npdev-manager-downloads");
    std::fs::create_dir_all(&tmp_dir).map_err(|e| e.to_string())?;
    let tmp_path = tmp_dir.join(&target.file_name);
    let mut file = tokio::fs::File::create(&tmp_path).await.map_err(|e| e.to_string())?;

    let mut hasher = Sha256::new();
    let mut downloaded: u64 = 0;
    let mut stream = response.bytes_stream();
    use tokio::io::AsyncWriteExt;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("download interrupted: {e}"))?;
        hasher.update(&chunk);
        file.write_all(&chunk).await.map_err(|e| e.to_string())?;
        downloaded += chunk.len() as u64;
        on_progress(downloaded, total);
    }
    file.flush().await.map_err(|e| e.to_string())?;
    drop(file);

    let actual: String = hasher.finalize().iter().map(|b| format!("{b:02x}")).collect();
    if !actual.eq_ignore_ascii_case(&target.sha256) {
        let _ = std::fs::remove_file(&tmp_path);
        return Err(format!(
            "checksum mismatch for {} -- expected {}, got {} (refusing to extract a corrupted/tampered download)",
            target.file_name, target.sha256, actual
        ));
    }

    std::fs::create_dir_all(dest_dir).map_err(|e| e.to_string())?;
    extract_archive(&tmp_path, dest_dir)?;
    let _ = std::fs::remove_file(&tmp_path);
    Ok(())
}

fn extract_archive(archive_path: &Path, dest_dir: &Path) -> Result<(), String> {
    let name = archive_path.to_string_lossy();
    if name.ends_with(".zip") {
        let file = std::fs::File::open(archive_path).map_err(|e| e.to_string())?;
        let mut zip = zip::ZipArchive::new(file).map_err(|e| format!("bad zip: {e}"))?;
        zip.extract(dest_dir).map_err(|e| format!("zip extraction failed: {e}"))?;
    } else if name.ends_with(".tar.gz") || name.ends_with(".tgz") {
        let file = std::fs::File::open(archive_path).map_err(|e| e.to_string())?;
        let decoder = flate2::read::GzDecoder::new(file);
        let mut archive = tar::Archive::new(decoder);
        archive.unpack(dest_dir).map_err(|e| format!("tar extraction failed: {e}"))?;
    } else {
        return Err(format!("unrecognized archive type: {name}"));
    }
    // Both Adoptium's JDK archives and python-build-standalone's tarballs contain exactly one
    // top-level directory (jdk-17.x.y+z/, python/) -- flatten it into dest_dir so callers get a
    // stable `dest_dir/bin/java` / `dest_dir/python.exe` regardless of the archive's own version
    // string in its top-level folder name.
    flatten_single_top_level_dir(dest_dir)
}

fn flatten_single_top_level_dir(dest_dir: &Path) -> Result<(), String> {
    let entries: Vec<_> = std::fs::read_dir(dest_dir)
        .map_err(|e| e.to_string())?
        .filter_map(|e| e.ok())
        .collect();
    if entries.len() != 1 || !entries[0].path().is_dir() {
        return Ok(());
    }
    let inner = entries[0].path();
    for child in std::fs::read_dir(&inner).map_err(|e| e.to_string())? {
        let child = child.map_err(|e| e.to_string())?;
        let target = dest_dir.join(child.file_name());
        std::fs::rename(child.path(), target).map_err(|e| e.to_string())?;
    }
    std::fs::remove_dir(&inner).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------------------------
// I1: unit tests for everything above that does not touch the network or a window. Every temp
// dir is uniquely named (pid + nanosecond timestamp) and removed at the end of its own test so
// tests can run concurrently without colliding, matching M0's "never write outside Build/temp"
// rule -- these never touch `state::manager_home()`.
// ---------------------------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;

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

    // --- parse_python_version -----------------------------------------------------------------

    #[test]
    fn parse_python_version_accepts_real_python_output() {
        assert_eq!(parse_python_version("Python 3.12.13"), Some((3, 12)));
        assert_eq!(parse_python_version("Python 3.9.0\n"), Some((3, 9)));
        assert_eq!(parse_python_version("Python 3.13.0\r\n"), Some((3, 13)));
        assert_eq!(parse_python_version("  Python 3.10.4  "), Some((3, 10)));
    }

    #[test]
    fn parse_python_version_rejects_malformed_or_unrelated_output() {
        assert_eq!(parse_python_version(""), None);
        assert_eq!(parse_python_version("Python"), None);
        assert_eq!(parse_python_version("Python3.12"), None);
        assert_eq!(parse_python_version("Python abc.def"), None);
        assert_eq!(parse_python_version("not python at all"), None);
        assert_eq!(parse_python_version("command not found: python"), None);
    }

    // --- java -version parsing -------------------------------------------------------------------

    #[test]
    fn parse_java_version_string_reads_the_quoted_version_from_real_banners() {
        // Real `java -version` first lines, one per vendor shape this has to survive.
        assert_eq!(
            parse_java_version_string("openjdk version \"17.0.11\" 2024-04-16\nOpenJDK Runtime Environment ...")
                .as_deref(),
            Some("17.0.11")
        );
        assert_eq!(
            parse_java_version_string("java version \"21.0.3\" 2024-04-16 LTS").as_deref(),
            Some("21.0.3")
        );
        assert_eq!(
            parse_java_version_string("java version \"1.8.0_401\"").as_deref(),
            Some("1.8.0_401")
        );
        // The version is on the SECOND line for some wrappers -- the first quoted line wins, not
        // the first line.
        assert_eq!(
            parse_java_version_string("Picked up JAVA_TOOL_OPTIONS: -Xmx1g\nopenjdk version \"17.0.9\" 2023-10-17")
                .as_deref(),
            Some("17.0.9")
        );
    }

    #[test]
    fn parse_java_version_string_rejects_output_with_no_version_in_it() {
        assert_eq!(parse_java_version_string(""), None);
        assert_eq!(parse_java_version_string("'java' is not recognized as an internal command"), None);
        assert_eq!(parse_java_version_string("openjdk version 17.0.11"), None); // unquoted
        assert_eq!(parse_java_version_string("openjdk version \"\""), None);
    }

    #[test]
    fn parse_java_major_understands_both_the_modern_and_the_legacy_scheme() {
        assert_eq!(parse_java_major("17.0.11"), Some(17));
        assert_eq!(parse_java_major("21.0.3"), Some(21));
        assert_eq!(parse_java_major("24"), Some(24));
        assert_eq!(parse_java_major("17.0.11+9"), Some(17));
        // Legacy `1.x` naming: the major is the SECOND component. A Java 8 must be reported as
        // unusable for a platform that needs 17.
        assert_eq!(parse_java_major("1.8.0_401"), Some(8));
        assert_eq!(parse_java_major("1.7.0_80"), Some(7));
        assert_eq!(parse_java_major(""), None);
        assert_eq!(parse_java_major("not-a-version"), None);
        assert_eq!(parse_java_major("1"), None);
    }

    // --- OS/arch resolution --------------------------------------------------------------------

    #[test]
    fn os_name_for_covers_every_target_the_apis_use() {
        assert_eq!(os_name_for("windows"), "windows");
        assert_eq!(os_name_for("linux"), "linux");
        // Neither Adoptium nor python-build-standalone resolution has a macOS branch wired up yet
        // -- this pins the current (fallback-to-linux) behaviour so a future macOS build target
        // fails a test instead of silently downloading the wrong asset.
        assert_eq!(os_name_for("macos"), "linux");
    }

    #[test]
    fn arch_name_for_covers_every_target_the_apis_use() {
        assert_eq!(arch_name_for("aarch64"), "aarch64");
        assert_eq!(arch_name_for("x86_64"), "x64");
        assert_eq!(arch_name_for("x86"), "x64");
    }

    #[test]
    fn python_platform_triple_and_sha256_covers_windows_and_both_linux_arches() {
        let (triple, sha) = python_platform_triple_and_sha256("windows", "x86_64");
        assert_eq!(triple, "x86_64-pc-windows-msvc");
        assert_eq!(sha.len(), 64, "sha256 hex digest must be 64 chars");

        let (triple, sha) = python_platform_triple_and_sha256("linux", "aarch64");
        assert_eq!(triple, "aarch64-unknown-linux-gnu");
        assert_eq!(sha.len(), 64);

        let (triple, sha) = python_platform_triple_and_sha256("linux", "x86_64");
        assert_eq!(triple, "x86_64-unknown-linux-gnu");
        assert_eq!(sha.len(), 64);

        // Windows always wins the branch regardless of arch -- only one Windows build is pinned.
        let (windows_triple, _) = python_platform_triple_and_sha256("windows", "aarch64");
        assert_eq!(windows_triple, "x86_64-pc-windows-msvc");
    }

    #[test]
    fn java_binary_in_for_picks_the_platform_executable_name() {
        let home = PathBuf::from("/opt/jdk-17");
        assert_eq!(java_binary_in_for(&home, "windows"), home.join("bin").join("java.exe"));
        assert_eq!(java_binary_in_for(&home, "linux"), home.join("bin").join("java"));
    }

    #[test]
    fn python_binary_in_for_picks_the_platform_executable_name() {
        let home = PathBuf::from("/opt/python");
        assert_eq!(python_binary_in_for(&home, "windows"), home.join("python.exe"));
        assert_eq!(python_binary_in_for(&home, "linux"), home.join("bin").join("python3"));
    }

    // --- flatten_single_top_level_dir -----------------------------------------------------------

    #[test]
    fn flatten_moves_a_single_wrapper_dirs_contents_up_one_level() {
        let dest = unique_temp_dir("flatten-wrapper");
        let wrapper = dest.join("jdk-17.0.9+9");
        std::fs::create_dir_all(wrapper.join("bin")).unwrap();
        std::fs::write(wrapper.join("bin").join("java"), b"fake").unwrap();
        std::fs::write(wrapper.join("release"), b"fake").unwrap();

        flatten_single_top_level_dir(&dest).unwrap();

        assert!(dest.join("bin").join("java").exists());
        assert!(dest.join("release").exists());
        assert!(!wrapper.exists());

        std::fs::remove_dir_all(&dest).unwrap();
    }

    #[test]
    fn flatten_is_a_no_op_with_multiple_top_level_entries() {
        let dest = unique_temp_dir("flatten-multi");
        std::fs::write(dest.join("a.txt"), b"a").unwrap();
        std::fs::write(dest.join("b.txt"), b"b").unwrap();

        flatten_single_top_level_dir(&dest).unwrap();

        assert!(dest.join("a.txt").exists());
        assert!(dest.join("b.txt").exists());

        std::fs::remove_dir_all(&dest).unwrap();
    }

    #[test]
    fn flatten_is_a_no_op_when_the_single_entry_is_a_file() {
        let dest = unique_temp_dir("flatten-single-file");
        std::fs::write(dest.join("only.txt"), b"only").unwrap();

        flatten_single_top_level_dir(&dest).unwrap();

        assert!(dest.join("only.txt").exists());

        std::fs::remove_dir_all(&dest).unwrap();
    }

    // --- extract_archive ------------------------------------------------------------------------

    fn build_test_zip(archive_path: &Path, wrapper_dir_name: &str) {
        let file = std::fs::File::create(archive_path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file(format!("{wrapper_dir_name}/bin/java"), options).unwrap();
        zip.write_all(b"fake-java-binary").unwrap();
        zip.start_file(format!("{wrapper_dir_name}/release"), options).unwrap();
        zip.write_all(b"JAVA_VERSION=\"17\"").unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn extract_archive_unpacks_a_zip_and_flattens_its_wrapper_dir() {
        // The archive file itself must live OUTSIDE dest -- extract_archive unpacks INTO dest, and
        // if the .zip were sitting in dest too, flatten_single_top_level_dir would see 2 top-level
        // entries (the wrapper dir AND the leftover archive file) and correctly no-op, which is
        // exactly the real download_verify_extract avoids by extracting to a fresh dest dir while
        // the temp archive lives under std::env::temp_dir()'s own download-cache subfolder.
        let source_dir = unique_temp_dir("extract-zip-source");
        let dest = unique_temp_dir("extract-zip-dest");
        let archive_path = source_dir.join("jdk.zip");
        build_test_zip(&archive_path, "jdk-17.0.9+9");

        extract_archive(&archive_path, &dest).unwrap();

        assert!(dest.join("bin").join("java").exists());
        assert_eq!(std::fs::read_to_string(dest.join("bin").join("java")).unwrap(), "fake-java-binary");
        assert!(dest.join("release").exists());

        std::fs::remove_dir_all(&source_dir).unwrap();
        std::fs::remove_dir_all(&dest).unwrap();
    }

    fn build_test_tar_gz(archive_path: &Path, wrapper_dir_name: &str) {
        let file = std::fs::File::create(archive_path).unwrap();
        let encoder = flate2::write::GzEncoder::new(file, flate2::Compression::default());
        let mut builder = tar::Builder::new(encoder);

        for (rel_path, contents) in [("bin/python3", &b"fake-python-binary"[..]), ("PYTHON.json", &b"{}"[..])] {
            let mut header = tar::Header::new_gnu();
            header.set_size(contents.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder
                .append_data(&mut header, format!("{wrapper_dir_name}/{rel_path}"), contents)
                .unwrap();
        }

        builder.into_inner().unwrap().finish().unwrap();
    }

    #[test]
    fn extract_archive_unpacks_a_tar_gz_and_flattens_its_wrapper_dir() {
        let source_dir = unique_temp_dir("extract-targz-source");
        let dest = unique_temp_dir("extract-targz-dest");
        let archive_path = source_dir.join("python.tar.gz");
        build_test_tar_gz(&archive_path, "python");

        extract_archive(&archive_path, &dest).unwrap();

        assert!(dest.join("bin").join("python3").exists());
        assert_eq!(
            std::fs::read_to_string(dest.join("bin").join("python3")).unwrap(),
            "fake-python-binary"
        );
        assert!(dest.join("PYTHON.json").exists());

        std::fs::remove_dir_all(&source_dir).unwrap();
        std::fs::remove_dir_all(&dest).unwrap();
    }

    #[test]
    fn extract_archive_rejects_an_unrecognized_extension() {
        let dest = unique_temp_dir("extract-bad-ext");
        let archive_path = dest.join("weird.rar");
        std::fs::write(&archive_path, b"not an archive").unwrap();

        let result = extract_archive(&archive_path, &dest);
        assert!(result.is_err());

        std::fs::remove_dir_all(&dest).unwrap();
    }
}
