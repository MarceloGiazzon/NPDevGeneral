//! "Ask the AI to fix this" (REG-322): the text of a failed build or start that goes into a fix conversation.
//!
//! The page never reads, holds or shows a log. It names the app and WHICH run failed (`build` or `start`); this
//! module finds that run's newest log inside the app's own `logs` folder and reads a bounded tail of it. The
//! page cannot name a file, so this can only ever read `<app>/logs/<known prefix>*.log`. What it returns goes to
//! `npdev ai relay start --intent fix --errors-file`, where the CLI redacts secrets and anonymises local folders
//! (`npdev_relay_scrub.py`) before the text is placed in a prompt: this module does not scrub, it only bounds --
//! and it leaves out Java stack frames, which say where the code was and never what went wrong.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// How much of the log's end is sent. The CLI clips to about this much anyway; the error is at the end of a log.
pub const FIX_TEXT_CHARS: usize = 6000;

/// How far back from the end of a log the stack frames are looked past. A Gradle build run with `--stacktrace` ends
/// in tens of thousands of characters of frames, with the line that says what went wrong above them: the last 6000
/// characters of the raw log held nothing but `at org.gradle.internal...` (found on the real window, REG-322).
const WINDOW_BYTES: u64 = 256 * 1024;

/// The log of the run that failed: the build script's own output (`build-finalapp`, teed by `npdev monitor ops`),
/// or what the app itself printed while it tried to start. Anything else is not a failure this button handles.
pub fn log_prefix(failure: &str) -> Option<&'static str> {
    match failure {
        "build" => Some("ops-build-finalapp-"),
        "start" => Some("app-"),
        _ => None,
    }
}

/// The tail of the failed run's newest log, or `None` when there is no such log or it holds nothing to send.
pub fn fix_text(app_dir: &str, failure: &str) -> Option<String> {
    let prefix = log_prefix(failure)?;
    if app_dir.trim().is_empty() {
        return None;
    }
    newest_log_tail(Path::new(app_dir), prefix, FIX_TEXT_CHARS)
}

/// The last `max_chars` characters of the newest `<app_dir>/logs/<prefix>*.log`, stack frames left out. "Newest" is
/// by file name: the CLI stamps every log `<prefix><UTC yyyymmddThhmmssZ>.log`, so the names sort in time order (the
/// same rule `npdev monitor logs` uses). A cut that falls in the middle of a line drops that partial first line.
pub fn newest_log_tail(app_dir: &Path, prefix: &str, max_chars: usize) -> Option<String> {
    let entries = std::fs::read_dir(app_dir.join("logs")).ok()?;
    let newest = entries
        .filter_map(Result::ok)
        .filter(|e| e.file_type().map(|t| t.is_file()).unwrap_or(false))
        .filter_map(|e| e.file_name().into_string().ok())
        .filter(|name| name.starts_with(prefix) && name.ends_with(".log"))
        .max()?;
    tail_chars(&app_dir.join("logs").join(newest), max_chars)
}

/// A line of a Java stack trace -- `at some.pkg.Class.method(File.java:12)` -- or its `... 30 more` tail. It names
/// where the code was, never what went wrong; the message is on the `Exception:` / `Caused by:` line above the frames.
fn is_stack_frame(line: &str) -> bool {
    let t = line.trim();
    if let Some(rest) = t.strip_prefix("at ") {
        return match rest.find('(') {
            Some(paren) => !rest[..paren].contains(' ') && rest.ends_with(')'),
            None => false,
        };
    }
    t.strip_prefix("... ")
        .is_some_and(|rest| rest.ends_with(" more") || rest.ends_with("common frames omitted"))
}

/// The end of one text file with its stack frames left out: at most `max_chars` characters, starting at a line start
/// when the text was cut.
fn tail_chars(path: &Path, max_chars: usize) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let len = file.metadata().ok()?.len();
    let window = WINDOW_BYTES.min(len);
    file.seek(SeekFrom::Start(len - window)).ok()?;
    let mut bytes = Vec::with_capacity(window as usize);
    file.take(window).read_to_end(&mut bytes).ok()?;
    let decoded = String::from_utf8_lossy(&bytes);
    // The window may start inside a character (shown as U+FFFD) and inside a line: neither is text of the log.
    let mut text = decoded.trim_start_matches('\u{FFFD}');
    if len > window {
        if let Some(newline) = text.find('\n') {
            text = &text[newline + 1..];
        }
    }
    let kept: Vec<&str> = text.lines().filter(|line| !is_stack_frame(line)).collect();
    let joined = kept.join("\n");
    let count = joined.chars().count();
    let mut tail = joined.as_str();
    if count > max_chars {
        let skip = tail.char_indices().nth(count - max_chars).map(|(i, _)| i).unwrap_or(0);
        tail = &tail[skip..];
        // the cut fell inside a line: that partial first line is not text of the log
        if let Some(newline) = tail.find('\n') {
            tail = &tail[newline + 1..];
        }
    }
    let tail = tail.trim_end();
    if tail.trim().is_empty() {
        None
    } else {
        Some(tail.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn scratch(tag: &str) -> PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let dir = std::env::temp_dir().join(format!("npdev-relay-fix-{tag}-{}-{nanos}", std::process::id()));
        fs::create_dir_all(dir.join("logs")).expect("scratch dir");
        dir
    }

    #[test]
    fn only_the_two_known_failures_have_a_log() {
        assert_eq!(log_prefix("build"), Some("ops-build-finalapp-"));
        assert_eq!(log_prefix("start"), Some("app-"));
        assert_eq!(log_prefix("../../secrets"), None);
        assert_eq!(log_prefix(""), None);
    }

    #[test]
    fn a_build_failure_reads_the_newest_build_log_not_another_ops_script() {
        let app = scratch("newest");
        fs::write(app.join("logs/ops-build-finalapp-20261110T100000Z.log"), "old build\n").unwrap();
        fs::write(app.join("logs/ops-build-finalapp-20261110T120000Z.log"), "line 1\nBUILD FAILED\n").unwrap();
        // Sorts after "ops-build-..." as a name, and is newer: still not the build.
        fs::write(app.join("logs/ops-stop-app-20261110T130000Z.log"), "stopped\n").unwrap();
        fs::write(app.join("logs/app-20261110T130000Z.log"), "app output\n").unwrap();
        let text = fix_text(&app.to_string_lossy(), "build").expect("a build log");
        assert_eq!(text, "line 1\nBUILD FAILED");
        let text = fix_text(&app.to_string_lossy(), "start").expect("an app log");
        assert_eq!(text, "app output");
        let _ = fs::remove_dir_all(&app);
    }

    #[test]
    fn no_log_an_empty_log_or_a_blank_app_has_nothing_to_send() {
        let app = scratch("none");
        assert!(fix_text(&app.to_string_lossy(), "build").is_none());
        fs::write(app.join("logs/ops-build-finalapp-20261110T120000Z.log"), "  \n\n").unwrap();
        assert!(fix_text(&app.to_string_lossy(), "build").is_none());
        assert!(fix_text("", "build").is_none());
        assert!(fix_text(&app.to_string_lossy(), "stop").is_none());
        assert!(fix_text(&app.join("missing").to_string_lossy(), "build").is_none());
        let _ = fs::remove_dir_all(&app);
    }

    #[test]
    fn a_long_log_keeps_its_end_from_a_line_start() {
        let app = scratch("long");
        let mut body = String::new();
        for i in 0..2000 {
            body.push_str(&format!("line {i:04} ééé xxxxxxxxxxxxxxxx\n"));
        }
        body.push_str("BUILD FAILED: the end\n");
        fs::write(app.join("logs/ops-build-finalapp-20261110T120000Z.log"), &body).unwrap();
        let text = fix_text(&app.to_string_lossy(), "build").expect("a tail");
        assert!(text.chars().count() <= FIX_TEXT_CHARS, "bounded: {}", text.chars().count());
        assert!(text.ends_with("BUILD FAILED: the end"));
        assert!(text.starts_with("line "), "starts at a line start, not mid-line: {:?}", &text[..20.min(text.len())]);
        assert!(!text.contains('\u{FFFD}'));
        let _ = fs::remove_dir_all(&app);
    }

    #[test]
    fn a_short_log_is_sent_whole() {
        let app = scratch("short");
        fs::write(app.join("logs/app-20261110T120000Z.log"), "first\nsecond\nThird é\n").unwrap();
        assert_eq!(fix_text(&app.to_string_lossy(), "start").unwrap(), "first\nsecond\nThird é");
        let _ = fs::remove_dir_all(&app);
    }

    #[test]
    fn stack_frames_are_left_out_so_the_cause_above_them_is_what_gets_sent() {
        let app = scratch("frames");
        let mut body = String::from(
            "> Task :bootJar\n* What went wrong:\nMissing NPDev RuntimeHost libs manifest in <folder>\nCaused by: java.io.FileNotFoundException: x\n",
        );
        for i in 0..3000 {
            body.push_str(&format!("\tat org.gradle.internal.execution.steps.Step{i}.execute(Step{i}.java:{i})\n"));
        }
        body.push_str("\t... 30 more\n\nBUILD FAILED in 5s\nBuild finished in 21,2 s (exit 1)\n");
        fs::write(app.join("logs/ops-build-finalapp-20261110T120000Z.log"), &body).unwrap();
        let text = fix_text(&app.to_string_lossy(), "build").expect("a tail");
        assert!(text.contains("Missing NPDev RuntimeHost libs manifest"), "the cause line is kept: {text}");
        assert!(text.contains("Caused by: java.io.FileNotFoundException: x"));
        assert!(text.ends_with("Build finished in 21,2 s (exit 1)"));
        assert!(!text.contains("org.gradle.internal.execution"), "no frames: {text}");
        assert!(!text.contains("... 30 more"));
        let _ = fs::remove_dir_all(&app);
    }

    #[test]
    fn only_real_frames_are_dropped_a_sentence_that_starts_with_at_is_kept() {
        assert!(is_stack_frame("\tat org.gradle.Foo.bar(Foo.java:12)"));
        assert!(is_stack_frame("    at java.base/sun.nio.ch.Net.pollConnect(Native Method)"));
        assert!(is_stack_frame("\t... 61 more"));
        assert!(is_stack_frame("    ... 12 common frames omitted"));
        assert!(!is_stack_frame("at the end of the day (see below)"));
        assert!(!is_stack_frame("at C:\\Users\\x\\Thing.java:12"));
        assert!(!is_stack_frame("Caused by: java.net.ConnectException: Connection refused"));
        assert!(!is_stack_frame("> Task :compileJava FAILED"));
        assert!(!is_stack_frame("... and so on"));
    }

    #[test]
    fn a_log_made_only_of_frames_has_nothing_to_send() {
        let app = scratch("onlyframes");
        fs::write(app.join("logs/app-20261110T120000Z.log"), "\tat a.B.c(B.java:1)\n\t... 3 more\n").unwrap();
        assert!(fix_text(&app.to_string_lossy(), "start").is_none());
        let _ = fs::remove_dir_all(&app);
    }
}
