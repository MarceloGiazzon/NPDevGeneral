//! "Use a browser AI" -- the watchers (browser-AI relay, F3 T3.1 clipboard + T3.2 Downloads).
//!
//! While the person waits for their chat AI, the Manager looks at two places so that pressing the AI's own
//! Copy button -- or saving the file the AI offers -- is the whole job: no switching back, no paste, no picking
//! a file. They are the ONLY places that read the clipboard or the Downloads folder -- the page never does
//! (ui/browserai.js only ever writes the text it was asked to copy) -- and they are deliberately blunt about
//! what they keep:
//!
//!   * the CLIPBOARD, once a second: a text is looked at only if it is plausibly this conversation's answer --
//!     it carries this conversation's `NPDEV-BEGIN <code>` marker, or it carries no marker at all and is a
//!     JSON object with `concepts` (an AI that ignored the format and sent the bare model);
//!   * the DOWNLOADS folder, every two seconds (`relay_downloads.rs`): a file is looked at only if it appeared
//!     after the watcher started, is one of `.json` `.zip` `.txt` `.md`, has stopped changing, and its text
//!     passes the same kind of test (the marker, or a JSON object with one of the protocol's keys; a zip needs
//!     a protocol member that does). The file is never moved, renamed or deleted;
//!   * anything else is dropped on the spot: not stored, not logged, not even its length. Only a SHA-256 of
//!     the last clipboard text and the path/size/time of files already looked at stay in memory (never on disk);
//!   * a match goes to `npdev ai relay ingest --source clipboard-watch|downloads-watch` -- the CLI decides what
//!     it is, this file decides nothing about an answer -- and the result is emitted as `relay-answer`;
//!   * PROMPT_ECHO and WRONG_SESSION are swallowed (relay-messages.json `watcherSilent`): right after COPY
//!     the clipboard holds our own prompt, and a person who did nothing wrong must not read an error.
//!
//! It runs only while the "waiting" step of the screen is open (the page starts and stops it, and says so
//! with a visible notice the person can stop), and only when the Settings switch allows it. One notice, one
//! stop, one switch cover both places; `relay-watch-state` says which of them are actually being read.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime};

use regex::Regex;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager};

use crate::relay_downloads;
use crate::state::AppState;

/// How the CLI logs it and how `relay-messages.json` knows it.
pub const CLIPBOARD_SOURCE: &str = "clipboard-watch";
/// `relay-answer`: the ingest result plus `source` and `code`.
pub const ANSWER_EVENT: &str = "relay-answer";
/// `relay-watch-state`: `{ state: "on" | "off" | "error", code, sources: ["clipboard", "downloads"] }` -- `sources`
/// are the places being read right now, so the notice never claims one that is not.
pub const STATE_EVENT: &str = "relay-watch-state";

const POLL: Duration = Duration::from_secs(1);
/// `relay-ingest-policy.json` `limits.maxAnswerChars`. A bigger clipboard is never an answer the CLI would
/// take, so it is not even hashed; the person can still paste it and get the CLI's own sentence.
const MAX_ANSWER_BYTES: usize = 2_000_000;
/// Outcomes the watcher never shows (relay-messages.json `watcherSilent`).
const SILENT_OUTCOMES: [&str; 2] = ["PROMPT_ECHO", "WRONG_SESSION"];
/// This many reads in a row that could not reach the clipboard (no display, a locked-down desktop, another
/// program holding it open) and the person is told once that watching does not work here; pasting still does.
/// "Busy" counts too: a program that has the clipboard open for a moment is normal, but on Windows a clipboard
/// that is refused outright is reported as exactly that, and a notice saying "watching" over a clipboard that can
/// never be read would be a lie. Ten polls is long enough that a moment is never mistaken for a refusal.
const ERROR_AFTER: u32 = 10;
/// The same for the Downloads folder (two seconds a poll, so ten seconds): a folder that cannot be listed.
const DOWNLOADS_ERROR_AFTER: u32 = 5;
/// The same answer arriving by both roads (Copy AND Download) within this long is taken once.
const DUPLICATE_WINDOW: Duration = Duration::from_secs(10);
/// The top-level keys of the protocol's files: `model.json`, `npdev-elements.json`, `npdev-submission.json`
/// (`npdev_relay_ingest.sniff_kind`). A downloaded `.json` without any of them is not the AI's answer.
const PROTOCOL_KEYS: [&str; 6] = ["concepts", "upsert", "remove", "renames", "deliberateRemovals", "couldNotExpress"];

// ------------------------------------------------------------------------------------ what is an answer

fn begin_any() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)NPDEV-BEGIN").expect("static pattern"))
}

/// Does the text carry THIS conversation's marker (`NPDEV-BEGIN <code>-<round>`)?
fn own_marker(text: &str, code: &str) -> bool {
    let own = format!(r"(?i)NPDEV-BEGIN[ \t]+{}-\d+", regex::escape(code));
    Regex::new(&own).map(|re| re.is_match(text)).unwrap_or(false)
}

/// Is this text worth sending to the CLI for the conversation `code`? Pure and cheap: it never leaves this
/// function, and what it says "no" to is forgotten.
pub fn looks_like_answer(text: &str, code: &str) -> bool {
    if text.is_empty() || text.len() > MAX_ANSWER_BYTES {
        return false;
    }
    if begin_any().is_match(text) {
        // A marker is there: it must be THIS conversation's. (Another one's is WRONG_SESSION, which the
        // watcher would not show anyway.)
        return own_marker(text, code);
    }
    let trimmed = text.trim();
    trimmed.starts_with('{')
        && serde_json::from_str::<Value>(trimmed).ok().map(|v| v.get("concepts").is_some()).unwrap_or(false)
}

/// The same question for the text of a downloaded file (or of a zip's protocol member). A file is how the AI
/// hands over the protocol's own files, so a bare JSON object counts when it has ANY of their keys -- a
/// change's `npdev-elements.json` has `upsert`/`remove`, not `concepts`. A byte-order mark in front (Notepad
/// and some exporters add one) is not part of the text.
pub fn looks_like_file_answer(text: &str, code: &str) -> bool {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    if text.is_empty() || text.len() > MAX_ANSWER_BYTES {
        return false;
    }
    if begin_any().is_match(text) {
        return own_marker(text, code);
    }
    let trimmed = text.trim();
    trimmed.starts_with('{')
        && serde_json::from_str::<Value>(trimmed)
            .ok()
            .and_then(|v| v.as_object().map(|o| PROTOCOL_KEYS.iter().any(|k| o.contains_key(*k))))
            .unwrap_or(false)
}

fn digest(text: &str) -> [u8; 32] {
    Sha256::digest(text.as_bytes()).into()
}

/// One poll that read some text. True when the text is new AND worth sending to the CLI; `last` always
/// becomes this text's hash, so the same text is not looked at twice, and a text that is dropped leaves
/// nothing behind but that hash.
pub fn consider(last: &mut Option<[u8; 32]>, text: &str, code: &str) -> bool {
    if text.is_empty() || text.len() > MAX_ANSWER_BYTES {
        *last = None;
        return false;
    }
    let now = digest(text);
    if *last == Some(now) {
        return false;
    }
    *last = Some(now);
    looks_like_answer(text, code)
}

/// The event for an ingest result, or nothing when the outcome is one the watcher stays silent about.
pub fn answer_event(mut result: Value, code: &str, source: &str) -> Option<Value> {
    let outcome = result.get("outcome").and_then(|o| o.as_str()).unwrap_or("");
    if SILENT_OUTCOMES.contains(&outcome) {
        return None;
    }
    if let Some(map) = result.as_object_mut() {
        map.insert("source".to_string(), json!(source));
        map.insert("code".to_string(), json!(code));
    }
    Some(result)
}

/// The same answer arriving by two roads (the person pressed Copy AND Download) is checked once: true when `key`
/// is not what was last taken within [`DUPLICATE_WINDOW`] (and it becomes what was last taken). A person who copies
/// the same text again later -- to try again -- is not mistaken for the second road.
pub fn claim(last: &mut Option<([u8; 32], Instant)>, key: [u8; 32], now: Instant) -> bool {
    if let Some((taken, at)) = last {
        if *taken == key && now.saturating_duration_since(*at) < DUPLICATE_WINDOW {
            return false;
        }
    }
    *last = Some((key, now));
    true
}

// -------------------------------------------------------------------------------------------- what is read

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Source {
    Clipboard,
    Downloads,
}

/// What the person is told: `on` with the places being read, or `error` when none can be.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Notice {
    pub state: &'static str,
    pub sources: Vec<&'static str>,
}

/// Which places can be read right now. Each watcher reports its own place; a change in what the notice would say
/// comes back as `Some`, so the page is told once per change and never twice.
pub struct Health {
    ok: [bool; 2],
    last: Notice,
}

impl Health {
    /// `clipboard` / `downloads`: whether that place exists on this computer at all (a computer with no Downloads
    /// folder simply has one place less; that is not an error).
    pub fn new(clipboard: bool, downloads: bool) -> Self {
        let mut health = Health { ok: [clipboard, downloads], last: Notice { state: "on", sources: Vec::new() } };
        health.last = health.notice();
        health
    }

    pub fn notice(&self) -> Notice {
        let mut sources = Vec::new();
        if self.ok[Source::Clipboard as usize] {
            sources.push("clipboard");
        }
        if self.ok[Source::Downloads as usize] {
            sources.push("downloads");
        }
        Notice { state: if sources.is_empty() { "error" } else { "on" }, sources }
    }

    pub fn set(&mut self, source: Source, ok: bool) -> Option<Notice> {
        self.ok[source as usize] = ok;
        let now = self.notice();
        if now == self.last {
            return None;
        }
        self.last = now.clone();
        Some(now)
    }
}

// ---- runtime (needs tauri): begin ----

/// The one watcher a window may have: for the conversation `code`, until `stop` is set. It covers both places.
pub struct RelayWatch {
    code: String,
    stop: Arc<AtomicBool>,
    shared: Arc<Shared>,
}

/// What the clipboard task and the Downloads task share.
struct Shared {
    health: Mutex<Health>,
    last_ingested: Mutex<Option<([u8; 32], Instant)>>,
}

enum Read {
    Text(String),
    /// Something else is on the clipboard (an image, a file list) or it is empty.
    NoText,
    /// Another program holds the clipboard open (for a moment, normally): try again next second. Ten in a row
    /// count as the clipboard being unreachable.
    Busy,
    /// The clipboard cannot be reached at all.
    Failed,
}

fn read_blocking() -> Read {
    let mut clipboard = match arboard::Clipboard::new() {
        Ok(c) => c,
        Err(arboard::Error::ClipboardOccupied) => return Read::Busy,
        Err(_) => return Read::Failed,
    };
    match clipboard.get_text() {
        Ok(text) => Read::Text(text),
        Err(arboard::Error::ContentNotAvailable) => Read::NoText,
        Err(arboard::Error::ClipboardOccupied) => Read::Busy,
        Err(_) => Read::Failed,
    }
}

/// `arboard::Clipboard` is not `Send` on every platform, so each read makes its own and lives and dies on one
/// blocking thread.
async fn read() -> Read {
    tokio::task::spawn_blocking(read_blocking).await.unwrap_or(Read::Failed)
}

pub fn emit_state(app: &AppHandle, state: &str, code: &str, sources: &[&str]) {
    let _ = app.emit(STATE_EVENT, json!({ "state": state, "code": code, "sources": sources }));
}

fn report(app: &AppHandle, code: &str, notice: Option<Notice>) {
    if let Some(n) = notice {
        emit_state(app, n.state, code, &n.sources);
    }
}

/// The CLI's verdict on one matched text or file. A refusal to run it at all is an answer too (the person is
/// waiting for one), so it becomes a `relay-answer` the page words as "something went wrong".
fn finish(code: &str, source: &str, result: Result<Value, String>) -> Option<Value> {
    match result {
        Ok(value) => {
            // The outcome word only -- never the answer, which is the user's app.
            crate::log::info(format!("relay_watch code={code} source={source} outcome={}", crate::relay_verdict(&value)));
            answer_event(value, code, source)
        }
        Err(e) => {
            crate::log::info(format!("relay_watch code={code} source={source} the answer could not be checked"));
            Some(json!({ "ok": false, "error": e, "source": source, "code": code }))
        }
    }
}

async fn ingest(app: &AppHandle, code: &str, text: &str) -> Option<Value> {
    let state = app.state::<AppState>();
    let result = match crate::relay_cli(&state).await {
        Ok((python, cli, java_home)) => {
            crate::npdev::run_relay_ingest_text(&python, &cli, java_home.as_deref(), text, code, None, Some(CLIPBOARD_SOURCE)).await
        }
        Err(e) => Err(e),
    };
    finish(code, CLIPBOARD_SOURCE, result)
}

async fn ingest_files(app: &AppHandle, code: &str, files: &[String]) -> Option<Value> {
    let state = app.state::<AppState>();
    let result = match crate::relay_cli(&state).await {
        Ok((python, cli, java_home)) => {
            crate::npdev::run_relay_ingest_file(&python, &cli, java_home.as_deref(), files, code, None, Some(relay_downloads::SOURCE)).await
        }
        Err(e) => Err(e),
    };
    finish(code, relay_downloads::SOURCE, result)
}

async fn run(app: AppHandle, code: String, stop: Arc<AtomicBool>, shared: Arc<Shared>) {
    // What is on the clipboard NOW is the prompt the person just copied (or whatever they had): it is the
    // starting point, not an answer.
    let mut last: Option<[u8; 32]> = match read().await {
        Read::Text(text) if !text.is_empty() && text.len() <= MAX_ANSWER_BYTES => Some(digest(&text)),
        _ => None,
    };
    let mut failures = 0u32;
    loop {
        tokio::time::sleep(POLL).await;
        if stop.load(Ordering::SeqCst) {
            return;
        }
        match read().await {
            Read::NoText => {
                last = None;
                failures = 0;
                report(&app, &code, shared.health.lock().expect("lock poisoned").set(Source::Clipboard, true));
            }
            Read::Busy | Read::Failed => {
                failures += 1;
                if failures >= ERROR_AFTER {
                    report(&app, &code, shared.health.lock().expect("lock poisoned").set(Source::Clipboard, false));
                }
                continue;
            }
            Read::Text(text) => {
                failures = 0;
                report(&app, &code, shared.health.lock().expect("lock poisoned").set(Source::Clipboard, true));
                if !consider(&mut last, &text, &code) {
                    continue; // dropped: nothing kept, nothing logged
                }
                if !claim(&mut shared.last_ingested.lock().expect("lock poisoned"), digest(&text), Instant::now()) {
                    continue; // the same answer just arrived as a file
                }
                let event = ingest(&app, &code, &text).await;
                drop(text);
                if stop.load(Ordering::SeqCst) {
                    return;
                }
                if let Some(event) = event {
                    let _ = app.emit(ANSWER_EVENT, event);
                }
            }
        }
    }
}

async fn run_downloads(app: AppHandle, code: String, stop: Arc<AtomicBool>, shared: Arc<Shared>, dir: PathBuf, since: SystemTime) {
    let mut scanner = relay_downloads::Scanner::new(dir, since);
    let mut failures = 0u32;
    loop {
        tokio::time::sleep(relay_downloads::POLL).await;
        if stop.load(Ordering::SeqCst) {
            return;
        }
        let own = code.clone();
        let joined = tokio::task::spawn_blocking(move || {
            let scanned = scanner.scan(&|text| looks_like_file_answer(text, &own));
            (scanner, scanned)
        })
        .await;
        let Ok((back, scanned)) = joined else {
            // The scan itself crashed: this place is out, the clipboard (if any) carries on.
            report(&app, &code, shared.health.lock().expect("lock poisoned").set(Source::Downloads, false));
            return;
        };
        scanner = back;
        let found = match scanned {
            Ok(found) => {
                failures = 0;
                report(&app, &code, shared.health.lock().expect("lock poisoned").set(Source::Downloads, true));
                found
            }
            Err(_) => {
                failures += 1;
                if failures >= DOWNLOADS_ERROR_AFTER {
                    report(&app, &code, shared.health.lock().expect("lock poisoned").set(Source::Downloads, false));
                }
                continue;
            }
        };
        if found.is_empty() {
            continue;
        }
        if !claim(&mut shared.last_ingested.lock().expect("lock poisoned"), relay_downloads::batch_key(&found), Instant::now()) {
            continue; // the same answer just arrived on the clipboard
        }
        let files: Vec<String> = found.iter().map(|f| f.path.to_string_lossy().to_string()).collect();
        let event = ingest_files(&app, &code, &files).await;
        if stop.load(Ordering::SeqCst) {
            return;
        }
        if let Some(event) = event {
            let _ = app.emit(ANSWER_EVENT, event);
        }
    }
}

/// Watch for conversation `code`. A watcher for the same conversation that is already running is left alone;
/// one for another conversation is stopped first. The caller has checked the code's shape and the Settings
/// switch. Says which places are being read (`sources`), the same words the `relay-watch-state` event uses.
pub fn start(app: &AppHandle, code: &str) -> Value {
    let state = app.state::<AppState>();
    // Files that appear from this moment on are the answer's; whatever is in Downloads now is not.
    let since = SystemTime::now();
    let dir = dirs::download_dir().filter(|d| d.is_dir());
    // The render check and the self-test container have no clipboard worth reading, and no answer to
    // find: the notice is still drawn, there is just nothing behind it.
    let fake = crate::npdev::fake_mode();
    let notice;
    {
        let mut slot = state.relay_watch.lock().expect("lock poisoned");
        if let Some(current) = slot.as_ref() {
            if current.code == code && !current.stop.load(Ordering::SeqCst) {
                let sources = current.shared.health.lock().expect("lock poisoned").notice().sources;
                return json!({ "ok": true, "watching": true, "code": code, "sources": sources });
            }
            current.stop.store(true, Ordering::SeqCst);
        }
        let stop = Arc::new(AtomicBool::new(false));
        let shared = Arc::new(Shared { health: Mutex::new(Health::new(true, fake || dir.is_some())), last_ingested: Mutex::new(None) });
        notice = shared.health.lock().expect("lock poisoned").notice();
        *slot = Some(RelayWatch { code: code.to_string(), stop: stop.clone(), shared: shared.clone() });
        if !fake {
            tauri::async_runtime::spawn(run(app.clone(), code.to_string(), stop.clone(), shared.clone()));
            if let Some(dir) = dir {
                tauri::async_runtime::spawn(run_downloads(app.clone(), code.to_string(), stop, shared, dir, since));
            }
        }
    }
    emit_state(app, notice.state, code, &notice.sources);
    json!({ "ok": true, "watching": true, "code": code, "sources": notice.sources })
}

/// Stop watching (the person pressed Stop, left the step, finished or gave up the conversation, or turned
/// the Settings switch off). Says `off` only if something was running.
pub fn stop(app: &AppHandle) -> Value {
    let state = app.state::<AppState>();
    let taken = state.relay_watch.lock().expect("lock poisoned").take();
    if let Some(watch) = taken {
        watch.stop.store(true, Ordering::SeqCst);
        emit_state(app, "off", &watch.code, &[]);
    }
    json!({ "ok": true, "watching": false })
}

/// `stop`, but only if the watcher is for this conversation.
pub fn stop_for(app: &AppHandle, code: &str) {
    let state = app.state::<AppState>();
    let mine = state.relay_watch.lock().expect("lock poisoned").as_ref().map(|w| w.code == code).unwrap_or(false);
    if mine {
        stop(app);
    }
}

// ---- runtime: end ----

#[cfg(test)]
mod tests {
    use super::*;

    const CODE: &str = "AB12";

    #[test]
    fn this_conversations_marker_is_an_answer() {
        assert!(looks_like_answer("NPDEV-BEGIN AB12-1\n{\"concepts\":[]}\nNPDEV-END AB12-1", CODE));
        // Decoration before the marker, lower case, a second round: still this conversation's.
        assert!(looks_like_answer("Here you go!\n```\nnpdev-begin ab12-3\nfile\n", CODE));
    }

    #[test]
    fn another_conversations_marker_is_not() {
        assert!(!looks_like_answer("NPDEV-BEGIN ZZ99-1\n{\"concepts\":[]}", CODE));
        // A bare model next to someone else's marker is not claimed either: a marker settles it.
        assert!(!looks_like_answer("NPDEV-BEGIN ZZ99-1 {\"concepts\":[]}", CODE));
    }

    #[test]
    fn a_bare_model_without_any_marker_is_an_answer() {
        assert!(looks_like_answer("  {\"concepts\": [{\"name\": \"Customer\"}]}\n", CODE));
        assert!(!looks_like_answer("{\"name\": \"not a model\"}", CODE));
        assert!(!looks_like_answer("{\"concepts\": [", CODE)); // not JSON at all
        assert!(!looks_like_answer("[{\"concepts\": 1}]", CODE)); // an array is not an object
    }

    #[test]
    fn everything_else_is_dropped() {
        assert!(!looks_like_answer("", CODE));
        assert!(!looks_like_answer("hunter2-correct-horse", CODE));
        assert!(!looks_like_answer("please paste this into the AI", CODE));
    }

    #[test]
    fn a_huge_clipboard_is_dropped_before_it_is_even_hashed() {
        let mut last = None;
        let big = format!("NPDEV-BEGIN {CODE}-1 {}", "x".repeat(MAX_ANSWER_BYTES));
        assert!(!consider(&mut last, &big, CODE));
        assert_eq!(last, None, "an oversize text leaves nothing behind, not even a hash");
    }

    #[test]
    fn the_same_text_is_looked_at_once() {
        let mut last = None;
        let answer = "NPDEV-BEGIN AB12-1\n{}\n";
        assert!(consider(&mut last, answer, CODE));
        assert!(!consider(&mut last, answer, CODE), "copied again unchanged: not a new answer");
        assert!(!consider(&mut last, "something else", CODE));
        assert!(consider(&mut last, answer, CODE), "after other text, the same answer copied again counts");
    }

    #[test]
    fn the_text_on_the_clipboard_when_watching_starts_is_not_an_answer() {
        // run() seeds `last` with the hash of what is on the clipboard at that moment.
        let prompt = "NPDEV-BEGIN AB12-1 is how your answer must start";
        let mut last = Some(digest(prompt));
        assert!(!consider(&mut last, prompt, CODE));
    }

    #[test]
    fn a_dropped_text_keeps_only_its_hash() {
        let mut last = None;
        assert!(!consider(&mut last, "hunter2-correct-horse", CODE));
        assert_eq!(last, Some(digest("hunter2-correct-horse")));
    }

    #[test]
    fn the_prompt_echo_and_wrong_session_are_silent_everything_else_is_tagged() {
        assert!(answer_event(json!({"outcome": "PROMPT_ECHO"}), CODE, "clipboard-watch").is_none());
        assert!(answer_event(json!({"outcome": "WRONG_SESSION"}), CODE, "downloads-watch").is_none());
        let e = answer_event(json!({"outcome": "READY", "ok": true}), CODE, "clipboard-watch").expect("READY is shown");
        assert_eq!(e["source"], "clipboard-watch");
        assert_eq!(e["code"], CODE);
        assert_eq!(e["outcome"], "READY");
        let f = answer_event(json!({"outcome": "TRUNCATED"}), CODE, "downloads-watch").expect("TRUNCATED is shown");
        assert_eq!(f["source"], "downloads-watch");
    }

    // ---------------------------------------------------------------- the Downloads watcher's filter

    #[test]
    fn a_downloaded_model_or_change_file_is_an_answer_whatever_protocol_file_it_is() {
        assert!(looks_like_file_answer("{\"namespace\": \"x\", \"concepts\": []}", CODE));
        assert!(looks_like_file_answer("{\"upsert\": {\"concepts\": []}, \"remove\": []}", CODE), "a change has no concepts at the top");
        assert!(looks_like_file_answer("{\"renames\": [], \"deliberateRemovals\": []}", CODE), "the manifest that goes with it");
        assert!(looks_like_file_answer("\u{feff}{\"concepts\": []}", CODE), "a byte-order mark is not part of the text");
    }

    #[test]
    fn a_downloaded_file_that_is_not_the_protocol_is_dropped() {
        assert!(!looks_like_file_answer("{\"name\": \"package.json\", \"version\": \"1.0.0\"}", CODE));
        assert!(!looks_like_file_answer("[{\"concepts\": 1}]", CODE));
        assert!(!looks_like_file_answer("{\"concepts\": [", CODE));
        assert!(!looks_like_file_answer("just some notes\nbuy milk", CODE));
        assert!(!looks_like_file_answer("", CODE));
        assert!(!looks_like_file_answer(&"x".repeat(MAX_ANSWER_BYTES + 1), CODE));
    }

    #[test]
    fn a_downloaded_text_or_markdown_file_with_this_conversations_marker_is_an_answer() {
        assert!(looks_like_file_answer("# answer\nNPDEV-BEGIN AB12-2\n{}\nNPDEV-END AB12-2\n", CODE));
        assert!(!looks_like_file_answer("NPDEV-BEGIN ZZ99-1\n{\"concepts\": []}", CODE), "another conversation's marker settles it");
    }

    // ----------------------------------------------------------------------- one answer, two roads

    #[test]
    fn the_same_answer_by_a_second_road_is_taken_once_but_a_later_retry_is_not_swallowed() {
        let key = digest("the answer");
        let t0 = Instant::now();
        let mut last = None;
        assert!(claim(&mut last, key, t0));
        assert!(!claim(&mut last, key, t0 + Duration::from_secs(2)), "arrived again as a file two seconds later");
        assert!(claim(&mut last, digest("another"), t0 + Duration::from_secs(3)));
        assert!(claim(&mut last, key, t0 + Duration::from_secs(4)), "a different answer in between: the first counts again");
        assert!(claim(&mut last, digest("x"), t0 + Duration::from_secs(5)));
        let mut later = None;
        assert!(claim(&mut later, key, t0));
        assert!(claim(&mut later, key, t0 + DUPLICATE_WINDOW + Duration::from_secs(1)), "the person tries the same text again after a while");
    }

    // ------------------------------------------------------------------- what the notice says it reads

    #[test]
    fn the_notice_names_exactly_the_places_that_can_be_read() {
        let mut h = Health::new(true, true);
        assert_eq!(h.notice(), Notice { state: "on", sources: vec!["clipboard", "downloads"] });
        assert_eq!(h.set(Source::Clipboard, true), None, "nothing changed: nothing to say");
        assert_eq!(h.set(Source::Clipboard, false), Some(Notice { state: "on", sources: vec!["downloads"] }));
        assert_eq!(h.set(Source::Clipboard, false), None, "said once");
        assert_eq!(h.set(Source::Downloads, false), Some(Notice { state: "error", sources: vec![] }));
        assert_eq!(h.set(Source::Clipboard, true), Some(Notice { state: "on", sources: vec!["clipboard"] }));
        assert_eq!(h.set(Source::Downloads, true), Some(Notice { state: "on", sources: vec!["clipboard", "downloads"] }));
    }

    #[test]
    fn a_computer_without_a_downloads_folder_just_has_one_place_less() {
        let mut h = Health::new(true, false);
        assert_eq!(h.notice(), Notice { state: "on", sources: vec!["clipboard"] });
        assert_eq!(h.set(Source::Clipboard, false), Some(Notice { state: "error", sources: vec![] }));
    }
}
