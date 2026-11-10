//! "Use a browser AI" -- the clipboard watcher (browser-AI relay, F3 T3.1).
//!
//! While the person waits for their chat AI, the Manager looks at the clipboard once a second so that
//! pressing the AI's own Copy button is the whole job: no switching back, no paste. It is the ONE place
//! that reads the clipboard -- the page never does (ui/browserai.js only ever writes the text it was asked
//! to copy) -- and it is deliberately blunt about what it keeps:
//!
//!   * a text is looked at only if it is plausibly this conversation's answer: it carries this
//!     conversation's `NPDEV-BEGIN <code>` marker, or it carries no marker at all and is a JSON object with
//!     `concepts` (an AI that ignored the format and sent the bare model);
//!   * anything else is dropped on the spot: not stored, not logged, not even its length. Only a SHA-256 of
//!     the last text seen stays in memory (never on disk) so the same text is not looked at twice;
//!   * a match goes to `npdev ai relay ingest --source clipboard-watch` -- the CLI decides what it is, this
//!     file decides nothing about an answer -- and the result is emitted as `relay-answer`;
//!   * PROMPT_ECHO and WRONG_SESSION are swallowed (relay-messages.json `watcherSilent`): right after COPY
//!     the clipboard holds our own prompt, and a person who did nothing wrong must not read an error.
//!
//! It runs only while the "waiting" step of the screen is open (the page starts and stops it, and says so
//! with a visible indicator the person can stop), and only when the Settings switch allows it.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use regex::Regex;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager};

use crate::state::AppState;

/// How the CLI logs it and how `relay-messages.json` knows it.
pub const SOURCE: &str = "clipboard-watch";
/// `relay-answer`: the ingest result plus `source` and `code`.
pub const ANSWER_EVENT: &str = "relay-answer";
/// `relay-watch-state`: `{ state: "on" | "off" | "error", code }`.
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

/// The one watcher a window may have: for the conversation `code`, until `stop` is set.
pub struct RelayWatch {
    code: String,
    stop: Arc<AtomicBool>,
}

// ------------------------------------------------------------------------------------ what is an answer

fn begin_any() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)NPDEV-BEGIN").expect("static pattern"))
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
        let own = format!(r"(?i)NPDEV-BEGIN[ \t]+{}-\d+", regex::escape(code));
        return Regex::new(&own).map(|re| re.is_match(text)).unwrap_or(false);
    }
    let trimmed = text.trim();
    trimmed.starts_with('{')
        && serde_json::from_str::<Value>(trimmed).ok().map(|v| v.get("concepts").is_some()).unwrap_or(false)
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
pub fn answer_event(mut result: Value, code: &str) -> Option<Value> {
    let outcome = result.get("outcome").and_then(|o| o.as_str()).unwrap_or("");
    if SILENT_OUTCOMES.contains(&outcome) {
        return None;
    }
    if let Some(map) = result.as_object_mut() {
        map.insert("source".to_string(), json!(SOURCE));
        map.insert("code".to_string(), json!(code));
    }
    Some(result)
}

// ------------------------------------------------------------------------------------------ the clipboard

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

// --------------------------------------------------------------------------------------------- the watcher

pub fn emit_state(app: &AppHandle, state: &str, code: &str) {
    let _ = app.emit(STATE_EVENT, json!({ "state": state, "code": code }));
}

/// The CLI's verdict on one matched text. A refusal to run it at all is an answer too (the person is waiting
/// for one), so it becomes a `relay-answer` the page words as "something went wrong".
async fn ingest(app: &AppHandle, code: &str, text: &str) -> Option<Value> {
    let state = app.state::<AppState>();
    let result = match crate::relay_cli(&state).await {
        Ok((python, cli, java_home)) => {
            crate::npdev::run_relay_ingest_text(&python, &cli, java_home.as_deref(), text, code, None, Some(SOURCE)).await
        }
        Err(e) => Err(e),
    };
    match result {
        Ok(value) => {
            // The outcome word only -- never the answer, which is the user's app.
            crate::log::info(format!("relay_watch code={code} outcome={}", crate::relay_verdict(&value)));
            answer_event(value, code)
        }
        Err(e) => {
            crate::log::info(format!("relay_watch code={code} the answer could not be checked"));
            Some(json!({ "ok": false, "error": e, "source": SOURCE, "code": code }))
        }
    }
}

async fn run(app: AppHandle, code: String, stop: Arc<AtomicBool>) {
    // What is on the clipboard NOW is the prompt the person just copied (or whatever they had): it is the
    // starting point, not an answer.
    let mut last: Option<[u8; 32]> = match read().await {
        Read::Text(text) if !text.is_empty() && text.len() <= MAX_ANSWER_BYTES => Some(digest(&text)),
        _ => None,
    };
    let mut failures = 0u32;
    let mut reported = false;
    loop {
        tokio::time::sleep(POLL).await;
        if stop.load(Ordering::SeqCst) {
            return;
        }
        match read().await {
            Read::NoText => {
                last = None;
                failures = 0;
                if reported {
                    reported = false;
                    emit_state(&app, "on", &code);
                }
            }
            Read::Busy | Read::Failed => {
                failures += 1;
                if failures >= ERROR_AFTER && !reported {
                    reported = true;
                    emit_state(&app, "error", &code);
                }
                continue;
            }
            Read::Text(text) => {
                failures = 0;
                if reported {
                    reported = false;
                    emit_state(&app, "on", &code);
                }
                if !consider(&mut last, &text, &code) {
                    continue; // dropped: nothing kept, nothing logged
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

/// Watch the clipboard for conversation `code`. A watcher for the same conversation that is already running
/// is left alone; one for another conversation is stopped first. The caller has checked the code's shape and
/// the Settings switch.
pub fn start(app: &AppHandle, code: &str) -> Value {
    let state = app.state::<AppState>();
    {
        let mut slot = state.relay_watch.lock().expect("lock poisoned");
        if let Some(current) = slot.as_ref() {
            if current.code == code && !current.stop.load(Ordering::SeqCst) {
                return json!({ "ok": true, "watching": true, "code": code });
            }
            current.stop.store(true, Ordering::SeqCst);
        }
        let stop = Arc::new(AtomicBool::new(false));
        *slot = Some(RelayWatch { code: code.to_string(), stop: stop.clone() });
        // The render check and the self-test container have no clipboard worth reading, and no answer to
        // find: the indicator is still drawn, there is just nothing behind it.
        if !crate::npdev::fake_mode() {
            tauri::async_runtime::spawn(run(app.clone(), code.to_string(), stop));
        }
    }
    emit_state(app, "on", code);
    json!({ "ok": true, "watching": true, "code": code })
}

/// Stop watching (the person pressed Stop, left the step, finished or gave up the conversation, or turned
/// the Settings switch off). Says `off` only if something was running.
pub fn stop(app: &AppHandle) -> Value {
    let state = app.state::<AppState>();
    let taken = state.relay_watch.lock().expect("lock poisoned").take();
    if let Some(watch) = taken {
        watch.stop.store(true, Ordering::SeqCst);
        emit_state(app, "off", &watch.code);
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
        assert!(answer_event(json!({"outcome": "PROMPT_ECHO"}), CODE).is_none());
        assert!(answer_event(json!({"outcome": "WRONG_SESSION"}), CODE).is_none());
        let e = answer_event(json!({"outcome": "READY", "ok": true}), CODE).expect("READY is shown");
        assert_eq!(e["source"], "clipboard-watch");
        assert_eq!(e["code"], CODE);
        assert_eq!(e["outcome"], "READY");
        assert!(answer_event(json!({"outcome": "TRUNCATED"}), CODE).is_some());
    }
}
