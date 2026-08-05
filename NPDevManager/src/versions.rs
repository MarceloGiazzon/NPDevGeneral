//! NPDev itself: tag list (cached, GitHub API is rate-limited unauthenticated), tag-zip download
//! (no git required -- SPEC.md §6: 7.0 MB vs 49 MB of git history), and the M7 Versions screen's
//! list/remove. Each installed tag is one folder under `state::versions_dir()/<tag>/` -- removal
//! is exactly `rm -rf` on that one folder, nothing else, so it is honest about what "remove" means.

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::state;

const REPO: &str = "MarceloGiazzon/NPDevGeneral";
const TAG_CACHE_TTL_SECS: u64 = 60 * 60; // 1 hour -- unauthenticated GitHub API is rate-limited.

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TagInfo {
    pub name: String,
    pub committed_at: String,
}

#[derive(Debug, Deserialize)]
struct GhTag {
    name: String,
    commit: GhTagCommit,
}

#[derive(Debug, Deserialize)]
struct GhTagCommit {
    sha: String,
}

#[derive(Debug, Deserialize)]
struct GhCommit {
    commit: GhCommitInner,
}

#[derive(Debug, Deserialize)]
struct GhCommitInner {
    committer: GhCommitter,
}

#[derive(Debug, Deserialize)]
struct GhCommitter {
    date: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct TagCache {
    fetched_at_epoch_secs: u64,
    tags: Vec<TagInfo>,
}

fn tag_cache_path() -> PathBuf {
    state::manager_home().join("tag-cache.json")
}

fn now_epoch_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or(Duration::ZERO).as_secs()
}

fn read_cache() -> Option<TagCache> {
    let text = std::fs::read_to_string(tag_cache_path()).ok()?;
    let cache: TagCache = serde_json::from_str(&text).ok()?;
    if now_epoch_secs().saturating_sub(cache.fetched_at_epoch_secs) < TAG_CACHE_TTL_SECS {
        Some(cache)
    } else {
        None
    }
}

fn write_cache(tags: &[TagInfo]) {
    let cache = TagCache {
        fetched_at_epoch_secs: now_epoch_secs(),
        tags: tags.to_vec(),
    };
    if let Ok(text) = serde_json::to_string_pretty(&cache) {
        let _ = std::fs::create_dir_all(state::manager_home());
        let _ = std::fs::write(tag_cache_path(), text);
    }
}

/// Newest tag first (by commit date -- tag names in this repo are not consistently semver, e.g.
/// `beta1.5` alongside `npdev-official-beta-20260428-062512`, so name-sorting would be wrong).
/// Cached for an hour; pass `force_refresh` to bypass the cache (e.g. a manual "Refresh" button).
pub async fn list_tags(force_refresh: bool) -> Result<Vec<TagInfo>, String> {
    if !force_refresh {
        if let Some(cache) = read_cache() {
            return Ok(cache.tags);
        }
    }

    let client = reqwest::Client::builder()
        .user_agent("npdev-manager/0.1")
        .build()
        .map_err(|e| e.to_string())?;

    let tags: Vec<GhTag> = client
        .get(format!("https://api.github.com/repos/{REPO}/tags"))
        .send()
        .await
        .map_err(|e| format!("tag list request failed: {e}"))?
        .json()
        .await
        .map_err(|e| format!("tag list response did not parse: {e}"))?;

    let mut infos = Vec::with_capacity(tags.len());
    for tag in tags {
        let commit: GhCommit = client
            .get(format!("https://api.github.com/repos/{REPO}/commits/{}", tag.commit.sha))
            .send()
            .await
            .map_err(|e| format!("commit lookup failed for {}: {e}", tag.name))?
            .json()
            .await
            .map_err(|e| format!("commit response did not parse for {}: {e}", tag.name))?;
        infos.push(TagInfo {
            name: tag.name,
            committed_at: commit.commit.committer.date,
        });
    }
    infos.sort_by(|a, b| b.committed_at.cmp(&a.committed_at));
    write_cache(&infos);
    Ok(infos)
}

pub fn tag_zip_url(tag: &str) -> String {
    format!("https://github.com/{REPO}/archive/refs/tags/{tag}.zip")
}

/// Downloads and unzips a tag into `state::versions_dir()/<tag>/`. No checksum is published for a
/// GitHub source-archive zip (unlike the Adoptium JDK or the Phase 0 I5 runtimehost-libs asset),
/// so this relies on HTTPS transport integrity -- the same trust boundary `git clone` itself has.
pub async fn install_version(
    app_state: &state::AppState,
    tag: &str,
    on_progress: impl Fn(u64, Option<u64>) + Send + 'static,
) -> Result<(), String> {
    let dest = state::versions_dir().join(tag);
    if dest.exists() {
        return Err(format!("{tag} is already installed"));
    }
    use futures_util::StreamExt;
    let client = reqwest::Client::builder()
        .user_agent("npdev-manager/0.1")
        .build()
        .map_err(|e| e.to_string())?;
    let response = client
        .get(tag_zip_url(tag))
        .send()
        .await
        .map_err(|e| format!("download failed: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("download failed: HTTP {} for tag {tag}", response.status()));
    }
    let total = response.content_length();

    let tmp_dir = std::env::temp_dir().join("npdev-manager-downloads");
    std::fs::create_dir_all(&tmp_dir).map_err(|e| e.to_string())?;
    let tmp_path = tmp_dir.join(format!("{tag}.zip"));
    let mut file = tokio::fs::File::create(&tmp_path).await.map_err(|e| e.to_string())?;

    let mut downloaded: u64 = 0;
    let mut stream = response.bytes_stream();
    use tokio::io::AsyncWriteExt;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("download interrupted: {e}"))?;
        file.write_all(&chunk).await.map_err(|e| e.to_string())?;
        downloaded += chunk.len() as u64;
        on_progress(downloaded, total);
    }
    file.flush().await.map_err(|e| e.to_string())?;
    drop(file);

    std::fs::create_dir_all(&dest).map_err(|e| e.to_string())?;
    {
        let f = std::fs::File::open(&tmp_path).map_err(|e| e.to_string())?;
        let mut zip = zip::ZipArchive::new(f).map_err(|e| format!("bad zip: {e}"))?;
        zip.extract(&dest).map_err(|e| format!("zip extraction failed: {e}"))?;
    }
    let _ = std::fs::remove_file(&tmp_path);

    // GitHub's tag zipball has one top-level dir like "NPDevGeneral-beta1.5/" -- flatten it so
    // `dest/NPDevCli/npdev_cli.py` is a stable path regardless of the archive's own naming.
    let entries: Vec<_> = std::fs::read_dir(&dest)
        .map_err(|e| e.to_string())?
        .filter_map(|e| e.ok())
        .collect();
    if entries.len() == 1 && entries[0].path().is_dir() {
        let inner = entries[0].path();
        for child in std::fs::read_dir(&inner).map_err(|e| e.to_string())? {
            let child = child.map_err(|e| e.to_string())?;
            let target = dest.join(child.file_name());
            std::fs::rename(child.path(), target).map_err(|e| e.to_string())?;
        }
        std::fs::remove_dir(&inner).map_err(|e| e.to_string())?;
    }

    let mut manager = app_state.manager.lock().expect("lock poisoned");
    manager.versions.push(state::InstalledVersion {
        tag: tag.to_string(),
        installed_at: chrono_now_iso(),
    });
    if manager.current_version.is_none() {
        manager.current_version = Some(tag.to_string());
    }
    manager.save().map_err(|e| e.to_string())?;
    Ok(())
}

pub fn chrono_now_iso() -> String {
    // No chrono dependency needed for one timestamp -- SystemTime + a manual RFC3339-ish render.
    let secs = now_epoch_secs();
    humantime_like_iso(secs)
}

fn humantime_like_iso(epoch_secs: u64) -> String {
    // Minimal, dependency-free UTC formatting (days-since-epoch civil calendar, Howard Hinnant's
    // algorithm) -- good enough for a display timestamp in manager.json, not for calendar maths.
    let days = (epoch_secs / 86400) as i64;
    let secs_of_day = epoch_secs % 86400;
    let (h, m, s) = (secs_of_day / 3600, (secs_of_day % 3600) / 60, secs_of_day % 60);
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = (z - era * 146097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m_num = if mp < 10 { mp + 3 } else { mp - 9 };
    let y_final = if m_num <= 2 { y + 1 } else { y };
    format!("{y_final:04}-{m_num:02}-{d:02}T{h:02}:{m:02}:{s:02}Z")
}

pub fn list_installed_versions(app_state: &state::AppState) -> Vec<state::InstalledVersion> {
    app_state.manager.lock().expect("lock poisoned").versions.clone()
}

pub fn remove_version(app_state: &state::AppState, tag: &str) -> Result<(), String> {
    let mut manager = app_state.manager.lock().expect("lock poisoned");
    if manager.current_version.as_deref() == Some(tag) {
        return Err(format!(
            "{tag} is the current version -- switch to another installed version first"
        ));
    }
    let dir = state::versions_dir().join(tag);
    if dir.exists() {
        std::fs::remove_dir_all(&dir).map_err(|e| e.to_string())?;
    }
    manager.versions.retain(|v| v.tag != tag);
    manager.save().map_err(|e| e.to_string())?;
    Ok(())
}

pub fn set_current_version(app_state: &state::AppState, tag: &str) -> Result<(), String> {
    let mut manager = app_state.manager.lock().expect("lock poisoned");
    if !manager.versions.iter().any(|v| v.tag == tag) {
        return Err(format!("{tag} is not installed"));
    }
    manager.current_version = Some(tag.to_string());
    manager.save().map_err(|e| e.to_string())
}
