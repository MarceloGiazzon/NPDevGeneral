//! The run recorder: a ring buffer of the last operations the Manager performed, kept so that when one
//! fails, "Get help with this error" (support.rs) has the goal, the command, the timing and the log
//! tail to put in a report.
//!
//! Two layers (SUP-1 / SUP-2):
//!   * command layer -- [`recorded`] wraps the body of a Tauri command and records timing, ok/err and
//!     the error string;
//!   * process layer -- the code that drains a child's stdout/stderr calls [`push_stdout`] /
//!     [`push_stderr`]; the line lands in the record of the operation that is running on that task.
//!
//! Nothing here redacts. Records hold the raw text and are redacted when a report is BUILT, so a rule
//! added later also covers old records. Nothing here is ever written to disk or returned to the UI.

use serde::Serialize;
use std::collections::VecDeque;
use std::sync::Mutex;

const CAPACITY: usize = 20;
const TAIL_LINES: usize = 400;

#[derive(Clone, Serialize, Debug)]
pub struct RunRecord {
    pub id: u64,
    /// An id from `ui/support-intents.json`, e.g. "npdev-setup".
    pub operation: String,
    /// RFC 3339 UTC.
    pub started_at: String,
    pub ended_at: Option<String>,
    pub started_ms: u128,
    pub ended_ms: Option<u128>,
    /// Raw; redacted only when a report is built.
    pub argv: Vec<String>,
    pub cwd: Option<String>,
    pub exit_code: Option<i32>,
    pub ok: bool,
    pub running: bool,
    /// The `Err(String)` the command returned.
    pub error: Option<String>,
    pub stdout_tail: Vec<String>,
    pub stderr_tail: Vec<String>,
}

fn store() -> &'static Mutex<VecDeque<RunRecord>> {
    static STORE: std::sync::OnceLock<Mutex<VecDeque<RunRecord>>> = std::sync::OnceLock::new();
    STORE.get_or_init(|| Mutex::new(VecDeque::new()))
}

tokio::task_local! {
    /// The record the current task is working for, so the process layer can attach lines without the
    /// recorder being threaded through every function signature.
    static CURRENT: u64;
}

fn now_ms() -> u128 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)
}

/// RFC 3339 UTC from epoch milliseconds, without a date crate (days-from-civil, Howard Hinnant).
pub fn rfc3339(ms: u128) -> String {
    let secs = (ms / 1000) as i64;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, (rem % 3600) / 60, rem % 60)
}

fn next_id() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(1);
    N.fetch_add(1, Ordering::Relaxed)
}

fn begin(operation: &str) -> u64 {
    let id = next_id();
    let ms = now_ms();
    let rec = RunRecord {
        id,
        operation: operation.to_string(),
        started_at: rfc3339(ms),
        ended_at: None,
        started_ms: ms,
        ended_ms: None,
        argv: Vec::new(),
        cwd: None,
        exit_code: None,
        ok: false,
        running: true,
        error: None,
        stdout_tail: Vec::new(),
        stderr_tail: Vec::new(),
    };
    let mut q = store().lock().expect("runs lock poisoned");
    q.push_back(rec);
    while q.len() > CAPACITY {
        q.pop_front();
    }
    id
}

fn with_record<F: FnOnce(&mut RunRecord)>(id: u64, f: F) {
    if let Ok(mut q) = store().lock() {
        if let Some(r) = q.iter_mut().find(|r| r.id == id) {
            f(r);
        }
    }
}

fn finish(id: u64, error: Option<String>) {
    let ms = now_ms();
    with_record(id, |r| {
        r.running = false;
        r.ok = error.is_none();
        r.error = error;
        r.ended_ms = Some(ms);
        r.ended_at = Some(rfc3339(ms));
    });
}

/// Wrap the body of a command: records start, end, ok/err and the error string, and scopes the task
/// so the process layer can attach output lines to this record.
pub async fn recorded<T, F>(operation: &str, fut: F) -> Result<T, String>
where
    F: std::future::Future<Output = Result<T, String>>,
{
    let id = begin(operation);
    let res = CURRENT.scope(id, fut).await;
    finish(id, res.as_ref().err().cloned());
    res
}

/// Process layer: the command line that was run (raw; redacted when a report is built).
pub fn note_command(argv: Vec<String>, cwd: Option<String>) {
    if let Ok(id) = CURRENT.try_with(|c| *c) {
        with_record(id, |r| {
            r.argv = argv;
            r.cwd = cwd;
        });
    }
}

/// Process layer for a command that ran to completion: its argv, cwd, whole output (the ring keeps
/// the last 400 lines of each stream) and exit code.
pub fn note_output(argv: Vec<String>, cwd: Option<String>, stdout: &[u8], stderr: &[u8], code: Option<i32>) {
    if let Ok(id) = CURRENT.try_with(|c| *c) {
        with_record(id, |r| {
            r.argv = argv;
            r.cwd = cwd;
            r.exit_code = code;
            for l in String::from_utf8_lossy(stdout).lines() {
                push_tail(&mut r.stdout_tail, l);
            }
            for l in String::from_utf8_lossy(stderr).lines() {
                push_tail(&mut r.stderr_tail, l);
            }
        });
    }
}

#[allow(dead_code)]
pub fn note_exit(code: Option<i32>) {
    if let Ok(id) = CURRENT.try_with(|c| *c) {
        with_record(id, |r| r.exit_code = code);
    }
}

fn push_tail(tail: &mut Vec<String>, line: &str) {
    tail.push(line.trim_end_matches(['\r', '\n']).to_string());
    if tail.len() > TAIL_LINES {
        let drop = tail.len() - TAIL_LINES;
        tail.drain(0..drop);
    }
}

/// Handle the drain loops can carry into spawned tasks (task-locals do not cross `tokio::spawn`).
#[derive(Clone, Copy)]
pub struct Sink(Option<u64>);

/// Capture the current record's handle; call this on the task that runs under [`recorded`], before
/// spawning the drain loops.
pub fn sink() -> Sink {
    Sink(CURRENT.try_with(|c| *c).ok())
}

impl Sink {
    pub fn stdout(&self, line: &str) {
        if let Some(id) = self.0 {
            with_record(id, |r| push_tail(&mut r.stdout_tail, line));
        }
    }
    pub fn stderr(&self, line: &str) {
        if let Some(id) = self.0 {
            with_record(id, |r| push_tail(&mut r.stderr_tail, line));
        }
    }
    pub fn exit(&self, code: Option<i32>) {
        if let Some(id) = self.0 {
            with_record(id, |r| r.exit_code = code);
        }
    }
}

pub fn snapshot() -> Vec<RunRecord> {
    store().lock().map(|q| q.iter().cloned().collect()).unwrap_or_default()
}

/// The newest record for `operation`, or -- when `operation` is None -- the newest failed one.
pub fn latest(operation: Option<&str>) -> Option<RunRecord> {
    let q = store().lock().ok()?;
    match operation {
        Some(op) => q.iter().rev().find(|r| r.operation == op).cloned(),
        None => q.iter().rev().find(|r| !r.ok && !r.running).cloned(),
    }
}

/// Test/selftest hook: forget everything.
#[allow(dead_code)]
pub fn clear() {
    if let Ok(mut q) = store().lock() {
        q.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc3339_known_dates() {
        assert_eq!(rfc3339(0), "1970-01-01T00:00:00Z");
        assert_eq!(rfc3339(1_000_000_000_000), "2001-09-09T01:46:40Z");
        assert_eq!(rfc3339(1_782_950_400_000), "2026-07-02T00:00:00Z");
    }

    #[tokio::test]
    async fn recorded_keeps_the_error_and_the_tail() {
        let r: Result<(), String> = recorded("unit-test-op", async {
            let s = sink();
            s.stdout("line one\r\n");
            s.stderr("boom");
            s.exit(Some(3));
            Err("it broke".to_string())
        })
        .await;
        assert!(r.is_err());
        let rec = latest(Some("unit-test-op")).expect("recorded");
        assert!(!rec.ok && !rec.running);
        assert_eq!(rec.error.as_deref(), Some("it broke"));
        assert_eq!(rec.stdout_tail, vec!["line one".to_string()]);
        assert_eq!(rec.stderr_tail, vec!["boom".to_string()]);
        assert_eq!(rec.exit_code, Some(3));
    }

    #[tokio::test]
    async fn the_ring_buffer_drops_the_oldest() {
        for i in 0..(CAPACITY + 5) {
            let _ = recorded::<(), _>(&format!("ring-{i}"), async { Ok(()) }).await;
        }
        assert!(snapshot().len() <= CAPACITY);
    }
}
