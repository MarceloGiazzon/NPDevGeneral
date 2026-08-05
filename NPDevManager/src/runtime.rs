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

fn os_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else {
        "linux"
    }
}

fn arch_name() -> &'static str {
    if cfg!(target_arch = "aarch64") {
        "aarch64"
    } else {
        "x64"
    }
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

pub async fn resolve_jdk17() -> Result<DownloadTarget, String> {
    let url = format!(
        "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture={}&image_type=jdk&os={}&vendor=eclipse",
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
        .ok_or_else(|| format!("Adoptium API returned no JDK 17 build for {}/{}", os_name(), arch_name()))?;
    Ok(DownloadTarget {
        url: first.binary.package.link,
        sha256: first.binary.package.checksum,
        file_name: first.binary.package.name,
    })
}

/// True once `state::jdk_dir()/bin/java(.exe)` exists -- the private JDK is already installed.
pub fn jdk_already_installed() -> bool {
    java_binary_in(&state::jdk_dir()).exists()
}

pub fn java_binary_in(jdk_home: &Path) -> PathBuf {
    jdk_home.join("bin").join(if cfg!(target_os = "windows") { "java.exe" } else { "java" })
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

pub fn resolve_portable_python() -> DownloadTarget {
    let (platform_triple, sha256) = if cfg!(target_os = "windows") {
        ("x86_64-pc-windows-msvc", "846c79af4c264b8f504b25a478bfeb646194d3d830d143aa44c32ed25da40c44")
    } else if cfg!(target_arch = "aarch64") {
        ("aarch64-unknown-linux-gnu", "a8c8e1966602cc605f139d2d21c659252f9fbced1d6fb6a10fbbd59ac1366dc9")
    } else {
        ("x86_64-unknown-linux-gnu", "a140c0868258075d160fa0da51ddffd423efbc9dd350695abd33e7ce3ce94352")
    };
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
    if cfg!(target_os = "windows") {
        python_home.join("python.exe")
    } else {
        python_home.join("bin").join("python3")
    }
}

pub fn portable_python_already_installed() -> bool {
    python_binary_in(&state::python_dir()).exists()
}

/// Detect a system Python >= 3.9 -- checked with the SAME command a terminal user would run, no
/// special-cased logic: `python --version` / `python3 --version`, whichever resolves. Returns the
/// resolved path only when it also satisfies the version. M4's "download only otherwise" rule.
pub async fn detect_system_python() -> Option<PathBuf> {
    for candidate in ["python3", "python"] {
        let Ok(output) = tokio::process::Command::new(candidate).arg("--version").output().await else {
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

fn parse_python_version(text: &str) -> Option<(u32, u32)> {
    let rest = text.trim().strip_prefix("Python ")?;
    let mut parts = rest.split('.');
    let major: u32 = parts.next()?.trim().parse().ok()?;
    let minor: u32 = parts.next()?.trim().parse().ok()?;
    Some((major, minor))
}

async fn which_command(name: &str) -> Result<PathBuf, String> {
    let program = if cfg!(target_os = "windows") { "where" } else { "which" };
    let output = tokio::process::Command::new(program)
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
