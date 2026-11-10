//! "Use a browser AI" -- what the Downloads watcher looks at (browser-AI relay, F3 T3.2).
//!
//! Some chat AIs hand the answer over as a file to download instead of text to copy (a `model.json`, a zip of
//! files). While the person waits, the Manager looks at their Downloads folder every two seconds so that
//! saving the file is the whole job. This module is the PURE half -- which files are worth a look and what
//! was already looked at; `relay_watch.rs` owns the task, the CLI call and the notice. It is deliberately
//! blunt about what it touches:
//!
//!   * only files that appeared (or changed) AFTER the watcher started: whatever was in Downloads before the
//!     person pressed Copy is never read, and neither is a file saved while they had pressed Stop;
//!   * only the top level of the folder, only `.json` `.zip` `.txt` `.md`, only regular files (no folders, no
//!     links), only up to the size the CLI itself would take;
//!   * a file is read only once it has stopped changing (two passes in a row with the same size and time), so
//!     a download still being written is never read half-way;
//!   * the text of a file is shown to the caller's `accept` filter and, unless it says "plausibly this
//!     conversation's answer", forgotten: nothing is stored or logged -- only the file's path, size and time
//!     stay in memory so the same version is not read twice. A zip is read in memory, never extracted, and
//!     only its protocol members (`model.json`, `npdev-elements.json`, `npdev-submission.json`) are looked at;
//!   * it never moves, renames or deletes anything. The CLI reads the file where it is.

use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use sha2::{Digest, Sha256};

/// How the CLI logs it (`ai-relay-session.schema.json` `answerSource`).
pub const SOURCE: &str = "downloads-watch";
/// How often the folder is looked at.
pub const POLL: Duration = Duration::from_secs(2);

const EXTENSIONS: [&str; 4] = ["json", "zip", "txt", "md"];
/// The members of a zip that can be an answer, whatever folder they sit in.
const PROTOCOL_MEMBERS: [&str; 3] = ["model.json", "npdev-elements.json", "npdev-submission.json"];
/// `relay-ingest-policy.json` `limits.maxAnswerChars`: a bigger text file is never an answer the CLI would take.
const MAX_TEXT_BYTES: u64 = 2_000_000;
/// The CLI's own default archive ceiling (`npdev_relay_zip`: maxAnswerChars + 65536 of headers).
const MAX_ZIP_BYTES: u64 = 2_000_000 + 65_536;
/// `limits.maxFileChars`.
const MAX_MEMBER_BYTES: u64 = 1_000_000;
/// A zip with more members than this is only looked at in its first members.
const MAX_MEMBERS_LOOKED_AT: usize = 1000;
/// `ingest_file` takes at most sixteen files in one call.
pub const MAX_FILES_PER_ANSWER: usize = 16;
/// A file that cannot be read (another program holds it) is tried this many times, then left alone.
const READ_TRIES: u8 = 3;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct Stamp {
    len: u64,
    modified: Option<SystemTime>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Text,
    Zip,
}

/// A file whose content passed the caller's filter.
#[derive(Debug)]
pub struct Found {
    pub path: PathBuf,
    /// SHA-256 of the text that passed (for a zip: the protocol member's), so the same answer arriving by
    /// another road is recognised.
    pub digest: [u8; 32],
}

/// One watcher's memory of a Downloads folder.
pub struct Scanner {
    dir: PathBuf,
    since: SystemTime,
    /// Versions already looked at (taken or not): the same path with the same size and time is never read twice.
    seen: HashMap<PathBuf, Stamp>,
    /// Versions seen once and waiting to prove they have stopped changing, with the failed reads so far.
    waiting: HashMap<PathBuf, (Stamp, u8)>,
}

fn kind_of(path: &Path) -> Option<Kind> {
    let ext = path.extension()?.to_str()?.to_ascii_lowercase();
    if !EXTENSIONS.contains(&ext.as_str()) {
        return None;
    }
    Some(if ext == "zip" { Kind::Zip } else { Kind::Text })
}

/// New since the watcher started: created OR changed after it. Both, because a browser may stamp a download with
/// the server's `Last-Modified` (long ago) while the file itself is brand new, and a tool may rewrite a file in
/// place (old creation time, new content).
fn is_fresh(meta: &fs::Metadata, since: SystemTime) -> bool {
    [meta.created().ok(), meta.modified().ok()].into_iter().flatten().max().map(|t| t > since).unwrap_or(false)
}

fn digest(text: &str) -> [u8; 32] {
    Sha256::digest(text.as_bytes()).into()
}

fn member_is_protocol(name: &str) -> bool {
    let base = name.rsplit(['/', '\\']).next().unwrap_or(name);
    PROTOCOL_MEMBERS.iter().any(|m| base.eq_ignore_ascii_case(m))
}

/// The texts of a file that could be an answer: a text file's own text (nothing if it is not UTF-8), a zip's
/// protocol members (nothing if it is not a readable zip). Reads at most the limits above.
fn candidate_texts(path: &Path, kind: Kind) -> std::io::Result<Vec<String>> {
    match kind {
        Kind::Text => Ok(String::from_utf8(fs::read(path)?).ok().into_iter().collect()),
        Kind::Zip => {
            let mut archive = match zip::ZipArchive::new(fs::File::open(path)?) {
                Ok(a) => a,
                Err(_) => return Ok(Vec::new()), // not a zip (yet): not an answer
            };
            let mut texts = Vec::new();
            for i in 0..archive.len().min(MAX_MEMBERS_LOOKED_AT) {
                let Ok(mut member) = archive.by_index(i) else { continue };
                if !member.is_file() || !member_is_protocol(member.name()) || member.size() > MAX_MEMBER_BYTES {
                    continue;
                }
                let mut bytes = Vec::new();
                if member.by_ref().take(MAX_MEMBER_BYTES + 1).read_to_end(&mut bytes).is_err() || bytes.len() as u64 > MAX_MEMBER_BYTES {
                    continue;
                }
                if let Ok(text) = String::from_utf8(bytes) {
                    texts.push(text);
                }
                if texts.len() >= 8 {
                    break;
                }
            }
            Ok(texts)
        }
    }
}

impl Scanner {
    /// Watch `dir` for files that appear after `since`.
    pub fn new(dir: PathBuf, since: SystemTime) -> Self {
        Scanner { dir, since, seen: HashMap::new(), waiting: HashMap::new() }
    }

    /// One pass over the folder. Returns the files that are new, finished, and whose text `accept` takes -- at most
    /// [`MAX_FILES_PER_ANSWER`], each version once. Fails only when the folder itself cannot be listed.
    pub fn scan(&mut self, accept: &dyn Fn(&str) -> bool) -> std::io::Result<Vec<Found>> {
        let mut found = Vec::new();
        for entry in fs::read_dir(&self.dir)? {
            if found.len() >= MAX_FILES_PER_ANSWER {
                break; // the rest is for the next pass: nothing past this point has been marked as seen
            }
            let Ok(entry) = entry else { continue };
            // `DirEntry::metadata` does not follow links: a link, a folder or anything odd is not a download.
            let Ok(meta) = entry.metadata() else { continue };
            if !meta.is_file() {
                continue;
            }
            let path = entry.path();
            let Some(kind) = kind_of(&path) else { continue };
            let limit = if kind == Kind::Zip { MAX_ZIP_BYTES } else { MAX_TEXT_BYTES };
            if meta.len() == 0 || meta.len() > limit || !is_fresh(&meta, self.since) {
                continue;
            }
            let stamp = Stamp { len: meta.len(), modified: meta.modified().ok() };
            if self.seen.get(&path) == Some(&stamp) {
                continue;
            }
            let finished = matches!(self.waiting.get(&path), Some((s, _)) if *s == stamp);
            if !finished {
                // First time this version is seen (or it changed): give it one more poll to prove it is finished.
                self.waiting.insert(path, (stamp, 0));
                continue;
            }
            match candidate_texts(&path, kind) {
                Ok(texts) => {
                    let taken = texts.iter().find(|t| accept(t)).map(|t| digest(t));
                    self.waiting.remove(&path);
                    self.seen.insert(path.clone(), stamp);
                    if let Some(digest) = taken {
                        found.push(Found { path, digest });
                    }
                }
                Err(_) => {
                    let tries = self.waiting.get(&path).map(|(_, n)| n + 1).unwrap_or(1);
                    if tries >= READ_TRIES {
                        self.waiting.remove(&path);
                        self.seen.insert(path, stamp);
                    } else {
                        self.waiting.insert(path, (stamp, tries));
                    }
                }
            }
        }
        Ok(found)
    }
}

/// One key for a batch of files that arrived together, so a second road carrying the same answer is recognised.
pub fn batch_key(found: &[Found]) -> [u8; 32] {
    if let [one] = found {
        return one.digest; // the same bytes as the clipboard's hash of that text
    }
    let mut sorted: Vec<&[u8; 32]> = found.iter().map(|f| &f.digest).collect();
    sorted.sort();
    let mut hasher = Sha256::new();
    for d in sorted {
        hasher.update(d);
    }
    hasher.finalize().into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static NEXT: AtomicUsize = AtomicUsize::new(0);

    /// A folder of its own under the temp dir, removed at the end of the test.
    struct Dir(PathBuf);
    impl Dir {
        fn new() -> Self {
            let p = std::env::temp_dir().join(format!("npdev-relay-downloads-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::SeqCst)));
            fs::create_dir_all(&p).unwrap();
            Dir(p)
        }
        fn put(&self, name: &str, bytes: &[u8]) -> PathBuf {
            let p = self.0.join(name);
            fs::write(&p, bytes).unwrap();
            p
        }
    }
    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    const HOUR: Duration = Duration::from_secs(3600);
    fn long_ago() -> SystemTime {
        SystemTime::now() - HOUR
    }
    fn next_hour() -> SystemTime {
        SystemTime::now() + HOUR
    }
    fn model_filter(t: &str) -> bool {
        t.contains("\"concepts\"")
    }
    const MODEL: &str = "{\"concepts\": [{\"name\": \"Customer\"}]}";

    fn names(found: &[Found]) -> Vec<String> {
        let mut n: Vec<String> = found.iter().map(|f| f.path.file_name().unwrap().to_string_lossy().to_string()).collect();
        n.sort();
        n
    }

    fn zip_with(entries: &[(&str, &str)]) -> Vec<u8> {
        let mut cursor = std::io::Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut cursor);
            let options = zip::write::SimpleFileOptions::default();
            for (name, body) in entries {
                zip.start_file(*name, options).unwrap();
                zip.write_all(body.as_bytes()).unwrap();
            }
            zip.finish().unwrap();
        }
        cursor.into_inner()
    }

    #[test]
    fn a_new_answer_file_is_taken_once_after_it_has_settled() {
        let dir = Dir::new();
        dir.put("model.json", MODEL.as_bytes());
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        assert!(s.scan(&model_filter).unwrap().is_empty(), "first sight: wait to see that it stopped changing");
        let got = s.scan(&model_filter).unwrap();
        assert_eq!(names(&got), ["model.json"]);
        assert_eq!(got[0].digest, digest(MODEL));
        assert!(s.scan(&model_filter).unwrap().is_empty(), "the same version is never taken twice");
        assert!(s.scan(&model_filter).unwrap().is_empty());
    }

    #[test]
    fn a_file_older_than_the_watcher_is_never_looked_at() {
        let dir = Dir::new();
        dir.put("model.json", MODEL.as_bytes());
        let calls = AtomicUsize::new(0);
        let accept = |t: &str| {
            calls.fetch_add(1, Ordering::SeqCst);
            model_filter(t)
        };
        let mut s = Scanner::new(dir.0.clone(), next_hour()); // the watcher "started" after the file
        for _ in 0..4 {
            assert!(s.scan(&accept).unwrap().is_empty());
        }
        assert_eq!(calls.load(Ordering::SeqCst), 0, "an old file is not even read");
    }

    #[test]
    fn a_file_that_is_not_an_answer_is_read_once_and_forgotten() {
        let dir = Dir::new();
        dir.put("notes.txt", b"hunter2 the shopping list");
        let calls = AtomicUsize::new(0);
        let accept = |t: &str| {
            calls.fetch_add(1, Ordering::SeqCst);
            model_filter(t)
        };
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        for _ in 0..5 {
            assert!(s.scan(&accept).unwrap().is_empty());
        }
        assert_eq!(calls.load(Ordering::SeqCst), 1, "read once, then left alone");
    }

    #[test]
    fn a_file_still_being_written_is_not_read_until_it_stops_growing() {
        let dir = Dir::new();
        let path = dir.put("model.json", b"{\"concepts\": [");
        let calls = AtomicUsize::new(0);
        let accept = |t: &str| {
            calls.fetch_add(1, Ordering::SeqCst);
            model_filter(t)
        };
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        assert!(s.scan(&accept).unwrap().is_empty());
        fs::write(&path, MODEL).unwrap(); // the download grew between two polls
        assert!(s.scan(&accept).unwrap().is_empty(), "its size changed: back to waiting");
        assert_eq!(calls.load(Ordering::SeqCst), 0, "nothing half-written was read");
        assert_eq!(names(&s.scan(&accept).unwrap()), ["model.json"]);
    }

    #[test]
    fn only_the_four_answer_extensions_in_the_top_folder() {
        let dir = Dir::new();
        dir.put("a.JSON", MODEL.as_bytes());
        dir.put("b.md", MODEL.as_bytes());
        dir.put("c.txt", MODEL.as_bytes());
        dir.put("d.crdownload", MODEL.as_bytes());
        dir.put("e.png", MODEL.as_bytes());
        dir.put("noextension", MODEL.as_bytes());
        fs::create_dir_all(dir.0.join("sub")).unwrap();
        fs::write(dir.0.join("sub").join("deep.json"), MODEL).unwrap();
        fs::create_dir_all(dir.0.join("folder.json")).unwrap(); // a folder that looks like a file
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        s.scan(&model_filter).unwrap();
        assert_eq!(names(&s.scan(&model_filter).unwrap()), ["a.JSON", "b.md", "c.txt"]);
    }

    #[test]
    fn an_empty_or_oversize_file_is_skipped_and_a_non_utf8_one_is_not_an_answer() {
        let dir = Dir::new();
        dir.put("empty.json", b"");
        dir.put("huge.txt", &vec![b'x'; (MAX_TEXT_BYTES + 1) as usize]);
        dir.put("binary.json", &[0xff, 0xfe, 0x00, 0x01, 0xc3, 0x28]);
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        for _ in 0..3 {
            assert!(s.scan(&|_| true).unwrap().is_empty());
        }
    }

    #[test]
    fn a_zip_is_looked_into_for_its_protocol_member_only_and_never_extracted() {
        let dir = Dir::new();
        dir.put("answer.zip", &zip_with(&[("readme.md", "hi"), ("app/Model.JSON", MODEL)]));
        dir.put("holiday.zip", &zip_with(&[("photos/model-railway.json", MODEL), ("notes.txt", MODEL)]));
        dir.put("broken.zip", b"PK this is not really a zip");
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        s.scan(&model_filter).unwrap();
        let got = s.scan(&model_filter).unwrap();
        assert_eq!(names(&got), ["answer.zip"], "an unrelated zip, even one with json inside, is left alone");
        assert_eq!(got[0].digest, digest(MODEL));
        let before: Vec<_> = fs::read_dir(&dir.0).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(before.len(), 3, "nothing was extracted, moved or deleted");
    }

    #[test]
    fn a_hostile_zip_costs_nothing() {
        let dir = Dir::new();
        // A path-escaping name is only ever a label here: nothing is written anywhere.
        dir.put("evil.zip", &zip_with(&[("../../../escape/model.json", MODEL)]));
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        s.scan(&model_filter).unwrap();
        assert_eq!(names(&s.scan(&model_filter).unwrap()), ["evil.zip"]);
        assert!(!dir.0.join("..").join("escape").exists());
        // A member bigger than the member limit is skipped without being read.
        let big = "x".repeat((MAX_MEMBER_BYTES + 10) as usize);
        let dir2 = Dir::new();
        dir2.put("big.zip", &zip_with(&[("model.json", &big)]));
        let mut s2 = Scanner::new(dir2.0.clone(), long_ago());
        s2.scan(&|_| true).unwrap();
        assert!(s2.scan(&|_| true).unwrap().is_empty());
    }

    #[test]
    fn at_most_sixteen_files_in_one_pass_and_the_rest_on_the_next() {
        let dir = Dir::new();
        for i in 0..20 {
            dir.put(&format!("m{i:02}.json"), format!("{{\"concepts\": [{i}]}}").as_bytes());
        }
        let mut s = Scanner::new(dir.0.clone(), long_ago());
        s.scan(&model_filter).unwrap();
        let first = s.scan(&model_filter).unwrap();
        assert_eq!(first.len(), MAX_FILES_PER_ANSWER);
        let mut all = names(&first);
        for _ in 0..3 {
            all.extend(names(&s.scan(&model_filter).unwrap()));
        }
        all.sort();
        let total = all.len();
        all.dedup();
        assert_eq!((total, all.len()), (20, 20), "every file is taken exactly once across the passes");
    }

    #[test]
    fn a_missing_folder_is_an_error_not_a_panic() {
        let mut s = Scanner::new(std::env::temp_dir().join("npdev-relay-downloads-does-not-exist"), long_ago());
        assert!(s.scan(&|_| true).is_err());
    }

    #[test]
    fn a_batch_of_one_has_the_hash_of_its_text_and_order_does_not_matter() {
        let a = Found { path: PathBuf::from("a"), digest: digest("one") };
        let b = Found { path: PathBuf::from("b"), digest: digest("two") };
        assert_eq!(batch_key(std::slice::from_ref(&a)), digest("one"));
        let ab = batch_key(&[Found { path: PathBuf::from("a"), digest: a.digest }, Found { path: PathBuf::from("b"), digest: b.digest }]);
        let ba = batch_key(&[b, a]);
        assert_eq!(ab, ba);
    }
}
