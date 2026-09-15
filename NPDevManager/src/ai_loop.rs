//! Session 2, NPDEV_MEGA_ROADMAP.md: closes the AI authoring loop. Until this module existed, the
//! Prompter tab (`main.rs`'s `prompter_generate` / `validate_prompter_model` / `apply_prompter_model`,
//! `ui/prompter.js`) chained prompt -> AI call -> validate -> apply as separate manual button
//! presses, and never built, started, or checked whether the result actually ran -- a failure never
//! went back to the AI. This module is the closed cycle:
//!
//!   prompt -> AI call -> extract JSON -> validate -> apply -> generate+build -> start -> wait-healthy
//!      ^                                                          |               |          |
//!      +---------------- re-prompt with the classified failure ---+---------------+----------+
//!                           (bounded retries, full transcript persisted)
//!
//! Deliberately free of Tauri types except `Sink::Tauri` (an owned, `'static`, `Clone` `AppHandle` --
//! the same shape `npdev::run_setup_streaming` already carries) so `run()` is callable from three
//! places without duplication: the `run_ai_loop` Tauri command (UI), the `--ai-loop` CLI flag on this
//! same binary (headless/scriptable, mirroring the `--selftest` precedent in `main.rs`/`selftest.rs`),
//! and the `#[cfg(test)]` tests at the bottom of this file.
//!
//! Every stage below reuses an existing primitive rather than inventing a second definition of the
//! same question: `npdev::run_validate_model` is the real DSL validator (the same
//! `npdev-validation-report.v2` the Prompter's manual Validate button reads, whose `layer` field --
//! `structural` vs `semantic` -- is exactly the "schema violation" vs "semantic-validator rejection"
//! split the roadmap asks for); `npdev::run_monitor_probe`'s `health` field is the platform's one
//! existing answer to "is this app up".

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tauri::{AppHandle, Emitter};

use crate::npdev;
use crate::state::{AppState, PrompterProfile};
use crate::versions;

const CONTEXT_CHAR_CAP: usize = 60_000;
const HEALTH_POLL_INTERVAL: Duration = Duration::from_secs(3);
const APPLY_CONFIRM_TOKEN: &str = "I_UNDERSTAND_THIS_OVERWRITES_MODEL_JSON";

// -------------------------------------------------------------------------------------------
// Where events go: a real window (streamed as `ai-loop-event`) or stdout (the `--ai-loop` CLI
// flag). Both also always land in the persisted transcript -- see `emit_event`.
// -------------------------------------------------------------------------------------------

#[derive(Clone)]
pub enum Sink {
    Tauri(AppHandle),
    Stdout,
}

impl Sink {
    fn emit(&self, value: &Value) {
        match self {
            Sink::Tauri(app) => {
                let _ = app.emit("ai-loop-event", value);
            }
            Sink::Stdout => println!("{value}"),
        }
    }
}

fn emit_event(sink: &Sink, transcript_path: &Path, mut value: Value) {
    if let Some(obj) = value.as_object_mut() {
        obj.entry("ts").or_insert_with(|| Value::String(versions::chrono_now_iso()));
    }
    sink.emit(&value);
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(transcript_path) {
        use std::io::Write;
        let _ = writeln!(f, "{value}");
    }
}

// -------------------------------------------------------------------------------------------
// Failure classification. All four are retryable BY CONSTRUCTION -- an `EngineFailure` (a
// transport/auth error from the AI call, `generate`/`apply` erroring, or health == "port-conflict")
// never becomes one of these; the loop breaks on those directly, without spending retry budget,
// because re-prompting the AI about an expired key or a port already in use cannot fix it.
// -------------------------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
pub enum FailureClass {
    /// No balanced JSON object anywhere in the AI's answer text.
    Extraction,
    /// `validate_prompter_model`'s report had `status: "failed"`. `layer` is the first
    /// error-severity diagnostic's layer (`structural` | `semantic` | ...) -- the roadmap's "schema
    /// violation" vs "semantic-validator rejection" split, read directly off the report rather than
    /// re-derived.
    Validation { layer: String, diagnostics: Vec<Value> },
    /// `build-finalapp` exited non-zero. `log_tail` is the tail of its own captured build log.
    Compile { log_tail: String },
    /// The app never reached `health == "running"` after starting (crashed, or timed out).
    /// `health` is the last observed value ("stopped" / "error" / "starting" on timeout).
    Boot { health: String, log_tail: String },
}

impl FailureClass {
    fn label(&self) -> &'static str {
        match self {
            FailureClass::Extraction => "extraction",
            FailureClass::Validation { .. } => "validation",
            FailureClass::Compile { .. } => "compile",
            FailureClass::Boot { .. } => "boot",
        }
    }
}

enum Decision {
    Retry,
    ExhaustedBudget,
}

/// The whole retry-termination rule: keep going while there is budget left. Split out because it is
/// the one piece of this module's control flow worth a unit test on its own boundary.
fn decide(iteration: u32, max_iterations: u32) -> Decision {
    if iteration < max_iterations {
        Decision::Retry
    } else {
        Decision::ExhaustedBudget
    }
}

/// `validate_prompter_model`'s report, classified. `None` means the candidate passed (status
/// `passed` or `warning` -- a warning-only report does not block the loop, same as the manual
/// Prompter's Apply button, which the roadmap's own discipline note says must survive unchanged).
fn classify_validation(report: &Value) -> Option<FailureClass> {
    let status = report.get("status").and_then(|v| v.as_str()).unwrap_or("");
    if status != "failed" {
        return None;
    }
    let diagnostics: Vec<Value> = report
        .get("diagnostics")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let layer = diagnostics
        .iter()
        .find(|d| d.get("severity").and_then(|s| s.as_str()) == Some("error"))
        .and_then(|d| d.get("layer").and_then(|l| l.as_str()))
        .unwrap_or("structural")
        .to_string();
    Some(FailureClass::Validation { layer, diagnostics })
}

/// `run_ops_script_capture`'s final `{kind: "done", exitCode, logFile}` value, classified. The
/// exit code is cross-checked against the captured ops lines: the wrapper reports `exitCode: 0`
/// even when gradle failed (RUN-35 -- its `done` event is not a trustworthy success signal), so a
/// zero code alone is not proof of a clean build.
fn classify_build(done: &Value, captured_lines: &[String]) -> Option<FailureClass> {
    let exit_code = done.get("exitCode").and_then(|v| v.as_i64()).unwrap_or(0);
    let log_tail = captured_tail(captured_lines, 200)
        .or_else(|| {
            done.get("logFile")
                .and_then(|v| v.as_str())
                .and_then(|p| read_log_tail(Path::new(p), 200))
        })
        .unwrap_or_else(|| "(could not read the build log)".to_string());
    let gradle_reported_failure = captured_lines.iter().any(|line| {
        line.contains("BUILD FAILED") || line.contains("FAILURE: Build failed with an exception.")
    });
    if exit_code != 0 || gradle_reported_failure {
        Some(FailureClass::Compile { log_tail })
    } else {
        None
    }
}

fn captured_tail(lines: &[String], max_lines: usize) -> Option<String> {
    if lines.is_empty() {
        return None;
    }
    let start = lines.len().saturating_sub(max_lines);
    Some(lines[start..].join("\n"))
}

fn read_log_tail(path: &Path, max_lines: usize) -> Option<String> {
    let text = std::fs::read_to_string(path).ok()?;
    let lines: Vec<&str> = text.lines().collect();
    let start = lines.len().saturating_sub(max_lines);
    Some(lines[start..].join("\n"))
}

/// `monitor_logs`'s `{sources: [{source, tail: [...]}]}` shape (fixtures/monitor-logs.json), joined
/// into one string. Filtered to one source ("app") when calling `run_monitor_logs`, so the first
/// entry is the one that matters.
fn tail_from_monitor_logs(report: &Value) -> String {
    report
        .get("sources")
        .and_then(|v| v.as_array())
        .and_then(|arr| arr.first())
        .and_then(|s| s.get("tail"))
        .and_then(|t| t.as_array())
        .map(|lines| {
            lines
                .iter()
                .filter_map(|l| l.as_str())
                .collect::<Vec<_>>()
                .join("\n")
        })
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "(no app log lines captured)".to_string())
}

// -------------------------------------------------------------------------------------------
// Candidate extraction -- a Rust port of `prompter.js`'s `extractCandidateModel` /
// `balancedObjectAt`, kept in lockstep deliberately: a provider that wraps its JSON in prose or a
// fenced code block is the common case for the manual flow too, and the loop needs to survive the
// exact same shapes.
// -------------------------------------------------------------------------------------------

/// Scans forward from a byte offset that must index a `{` and returns the substring covering the
/// first BALANCED object, or `None` if the braces never close. Operates on bytes rather than chars:
/// safe because `{`, `}`, `"` and `\` are single-byte ASCII and never appear as a UTF-8 continuation
/// byte, so every offset this function touches or returns is a valid `str` char boundary.
fn balanced_object_at(text: &str, start: usize) -> Option<&str> {
    let bytes = text.as_bytes();
    let mut depth = 0i32;
    let mut in_string = false;
    let mut escaped = false;
    let mut i = start;
    while i < bytes.len() {
        let ch = bytes[i];
        if in_string {
            if escaped {
                escaped = false;
            } else if ch == b'\\' {
                escaped = true;
            } else if ch == b'"' {
                in_string = false;
            }
            i += 1;
            continue;
        }
        match ch {
            b'"' => in_string = true,
            b'{' => depth += 1,
            b'}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(&text[start..=i]);
                }
            }
            _ => {}
        }
        i += 1;
    }
    None
}

fn extract_fenced(text: &str) -> Option<Value> {
    let start_marker = text.find("```")?;
    let after = &text[start_marker + 3..];
    let after = after.strip_prefix("json").unwrap_or(after);
    let after = after.trim_start_matches(['\n', '\r']);
    let end_marker = after.find("```")?;
    serde_json::from_str(after[..end_marker].trim()).ok()
}

pub fn extract_candidate(text: &str) -> Option<Value> {
    let trimmed = text.trim();
    if let Ok(v) = serde_json::from_str::<Value>(trimmed) {
        return Some(v);
    }
    if let Some(v) = extract_fenced(trimmed) {
        return Some(v);
    }
    let start = trimmed.find('{')?;
    if let Some(end) = trimmed.rfind('}') {
        if end > start {
            if let Ok(v) = serde_json::from_str::<Value>(&trimmed[start..=end]) {
                return Some(v);
            }
        }
    }
    // The outermost-brace span above assumes nothing brace-bearing follows the model; the prompt
    // asks for a "deliberateRemovals" note after the JSON, and a note with a brace in it would push
    // `rfind('}')` past the model. Fall back to the FIRST balanced object instead of the widest span
    // (mirrors prompter.js's MON-10 fix).
    let obj = balanced_object_at(trimmed, start)?;
    serde_json::from_str(obj).ok()
}

// -------------------------------------------------------------------------------------------
// Prompt composition
// -------------------------------------------------------------------------------------------

const RENAME_REMOVAL_DISCIPLINE: &str =
    "If your change RENAMES an existing field or concept, you MUST declare it rather than deleting \
     the old name and adding a new one: put \"renamedFrom\": \"<oldName>\" on the renamed field \
     object (or on the concept object for a table rename). A rename that is not declared reads as an \
     unrelated drop-plus-add and will either be refused or lose that column's data. If your change \
     REMOVES a field on purpose, say so in a plain-sentence \"deliberateRemovals\" note after the \
     JSON -- prose only, with no braces in it.";

/// The first prompt of a run. Mirrors `prompter.js`'s `buildPrompterPrompt` wording (same context
/// cap, same rename/removal discipline) so the CLI's composed prompt and the UI's manually-composed
/// one read the same way -- but is only actually exercised by the `--ai-loop` CLI path today: the UI
/// path (`run_ai_loop`) is handed the string the frontend already built with "Generate Prompt", so
/// the two never drift on the interactive path by construction.
pub fn compose_initial_prompt(app_name: &str, ask: &str, context: Option<&Value>) -> String {
    let mut context_block = String::new();
    if let Some(context) = context {
        let mut json = serde_json::to_string_pretty(context).unwrap_or_default();
        let mut truncated = false;
        if json.len() > CONTEXT_CHAR_CAP {
            json.truncate(CONTEXT_CHAR_CAP);
            truncated = true;
        }
        context_block = format!(
            "\n=== Current model (\"{app_name}\", from model.json) ===\n{json}{}\n",
            if truncated { "\n... (truncated)" } else { "" }
        );
    }
    format!(
        "You are helping extend an app built on NPDev, a JSON-model-driven app platform. An NPDev \
         app is described by a JSON \"model\": concepts (entities with typed fields), flows \
         (multi-step server-side operations), pages and a menu. Below is the current model for an \
         app called \"{app_name}\", followed by what I want changed.\n\n\
         Respond with ONLY the complete, updated model.json -- the entire file, valid and ready to \
         save as-is, with the requested change already applied. Do not output a diff, a patch, an \
         explanation, or any text outside the JSON, except the one note described below. Leave every \
         concept, field and flow not mentioned below byte-for-byte unchanged.\n\n\
         {RENAME_REMOVAL_DISCIPLINE}\n{context_block}\n=== What I want ===\n{ask}\n"
    )
}

/// A retry prompt: a fresh single-shot message (no chat history, matching the existing single-shot
/// design of `prompter_generate`) carrying the failing candidate and its classified error inline.
fn build_reprompt(app_name: &str, original_ask: &str, previous_candidate: Option<&Value>, failure: &FailureClass) -> String {
    let candidate_block = match previous_candidate {
        Some(c) => format!(
            "\n=== Your previous answer (the candidate that failed) ===\n{}\n",
            serde_json::to_string_pretty(c).unwrap_or_default()
        ),
        None => String::new(),
    };
    let failure_detail = match failure {
        FailureClass::Extraction => "Your previous answer did not contain a single complete, valid \
            JSON object. Respond with ONLY the JSON model.json -- no prose, no markdown fences, \
            nothing before or after the braces."
            .to_string(),
        FailureClass::Validation { layer, diagnostics } => {
            let lines: Vec<String> = diagnostics
                .iter()
                .filter(|d| d.get("severity").and_then(|s| s.as_str()) == Some("error"))
                .map(|d| {
                    let msg = d.get("message").and_then(|v| v.as_str()).unwrap_or("");
                    let path = d.get("path").and_then(|v| v.as_str()).unwrap_or("(no path)");
                    match d.get("suggestedFix").and_then(|v| v.as_str()) {
                        Some(fix) => format!("- [{path}] {msg} (suggested fix: {fix})"),
                        None => format!("- [{path}] {msg}"),
                    }
                })
                .collect();
            format!(
                "Your previous model.json failed {layer} validation with these error(s):\n{}",
                lines.join("\n")
            )
        }
        FailureClass::Compile { log_tail } => format!(
            "Your previous model.json passed validation but the generated app FAILED TO COMPILE. \
             Here is the tail of the build log:\n{log_tail}\n\
             Find the specific error (often a field type, a missing reference, or an expression the \
             validator accepts but the generator cannot turn into valid Java) and fix it."
        ),
        FailureClass::Boot { health, log_tail } => format!(
            "Your previous model.json built successfully but the app FAILED TO START (health: \
             {health}). Here is the tail of its log:\n{log_tail}\n\
             Find whatever in the model is causing the startup failure and fix it."
        ),
    };
    format!(
        "You are helping extend an app built on NPDev, a JSON-model-driven app platform. This is a \
         retry: your previous answer for the app \"{app_name}\" did not work. Respond again with \
         ONLY the complete, corrected model.json -- the entire file, valid and ready to save as-is. \
         Do not output a diff, a patch, an explanation, or any text outside the JSON, except the one \
         note described below.\n\n\
         === What was originally asked ===\n{original_ask}\n{candidate_block}\n\
         === Why it failed ===\n{failure_detail}\n\n{RENAME_REMOVAL_DISCIPLINE}\n"
    )
}

// -------------------------------------------------------------------------------------------
// The engine
// -------------------------------------------------------------------------------------------

pub struct LoopConfig {
    pub app_dir: String,
    pub app_name: String,
    pub profile: PrompterProfile,
    pub model: String,
    pub effort: Option<String>,
    pub initial_prompt: String,
    pub plain_ask: String,
    pub max_iterations: u32,
    pub python_exe: PathBuf,
    pub npdev_cli: PathBuf,
    pub java_home: Option<String>,
}

enum HealthOutcome {
    Running,
    PortConflict,
    /// The last observed `health` value, or "timeout" if it never left "starting"/"unknown".
    Failed(String),
}

/// One probe sample's decision, pure so it can be unit-tested.
///
/// The Monitor's probe cannot tell "launched moments ago, still booting" from "down": while nothing
/// is bound to the app's port yet it reports `stopped`, and a generated app takes ~15-25s to come
/// up (RUN-36 -- this false `stopped` also aborted iterations and left the booted spawn tree alive,
/// which then held `_ops` and aborted the next iteration's regenerate with a file-in-use error).
/// `stopped` is therefore only terminal once the spawn tree the loop started is actually gone --
/// the only state where "nothing is listening" cannot mean "not yet". `port-conflict` and `error`
/// stay terminal: a wrong process on the port is not going to become this app, and a port that is
/// open while /actuator/health answers non-UP is a started-but-unhealthy app waiting would not fix.
#[derive(Debug)]
enum HealthVerdict {
    Running,
    PortConflict,
    Failed(String),
    KeepPolling,
}

fn health_verdict(health: &str, proc_alive: bool, elapsed: Duration, timeout: Duration) -> HealthVerdict {
    match health {
        "running" => HealthVerdict::Running,
        "port-conflict" => HealthVerdict::PortConflict,
        "stopped" if proc_alive && elapsed < timeout => HealthVerdict::KeepPolling,
        "stopped" => HealthVerdict::Failed("stopped".to_string()),
        "error" => HealthVerdict::Failed("error".to_string()),
        _ => HealthVerdict::KeepPolling, // "starting" / "unknown"
    }
}

async fn wait_for_healthy(
    python_exe: &Path,
    npdev_cli: &Path,
    java_home: Option<&str>,
    app_dir: &str,
    timeout: Duration,
    app_state: &AppState,
) -> HealthOutcome {
    let start = Instant::now();
    loop {
        if let Ok(probe) = npdev::run_monitor_probe(python_exe, npdev_cli, java_home, app_dir, false).await {
            let health = probe.get("health").and_then(|v| v.as_str()).unwrap_or("unknown");
            // The tracked `monitor ops run-finalapp` wrapper stays up exactly as long as the app's
            // own JVM does, so it is the right liveness signal for the "booting" reading of
            // "stopped". `try_wait` needs `&mut Child`, so the process is moved out of the registry
            // for the (fast, non-blocking) check and put straight back. A transport error reading
            // it is treated as alive so a probe hiccup cannot manufacture a false boot failure.
            let proc_alive = {
                let mut guard = app_state.running.lock().expect("lock poisoned");
                match guard.remove(app_dir) {
                    Some(mut proc) => {
                        let alive = proc.child.try_wait().ok().flatten().is_none();
                        guard.insert(app_dir.to_string(), proc);
                        alive
                    }
                    None => false, // not in the registry at all: nothing of ours is running
                }
            };
            match health_verdict(health, proc_alive, start.elapsed(), timeout) {
                HealthVerdict::Running => return HealthOutcome::Running,
                HealthVerdict::PortConflict => return HealthOutcome::PortConflict,
                HealthVerdict::Failed(h) => return HealthOutcome::Failed(h),
                HealthVerdict::KeepPolling => {} // keep polling
            }
        }
        if start.elapsed() >= timeout {
            return HealthOutcome::Failed("timeout".to_string());
        }
        tokio::time::sleep(HEALTH_POLL_INTERVAL).await;
    }
}

/// Runs the closed loop to completion (booted, exhausted, or aborted) or until `cancel` is set.
/// Takes `&AppState` directly rather than a Tauri `State<'_, AppState>` -- the same shape
/// `resolve_python_exe`/`resolve_npdev_cli`/`resolve_java_home` in `main.rs` already use -- so it
/// works identically whether `app_state` came from a real `AppHandle::state()` (the UI path) or a
/// plain `AppState::new()` the `--ai-loop` CLI branch constructs itself, the same way
/// `selftest.rs` does for the other headless path this binary has.
pub async fn run(app_state: &AppState, config: LoopConfig, sink: Sink, cancel: Arc<AtomicBool>) {
    let LoopConfig {
        app_dir,
        app_name,
        profile,
        model,
        effort,
        initial_prompt,
        plain_ask,
        max_iterations,
        python_exe,
        npdev_cli,
        java_home,
    } = config;

    let run_id = versions::chrono_now_iso().replace(':', "-");
    let transcript_dir = PathBuf::from(&app_dir).join("logs").join("ai-loop");
    if let Err(e) = std::fs::create_dir_all(&transcript_dir) {
        sink.emit(&json!({
            "kind": "loop-done", "outcome": "aborted",
            "reason": format!("could not create the transcript directory: {e}"),
        }));
        return;
    }
    let transcript_path = transcript_dir.join(format!("run-{run_id}.jsonl"));
    let emit = |v: Value| emit_event(&sink, &transcript_path, v);

    emit(json!({
        "kind": "loop-start", "appDir": app_dir, "appName": app_name, "profileId": profile.id,
        "model": model, "maxIterations": max_iterations, "ask": plain_ask, "transcript": transcript_path,
    }));

    let mut prompt = initial_prompt;
    let mut last_candidate: Option<Value> = None;
    let mut iteration: u32 = 0;

    let final_outcome = 'iterations: loop {
        iteration += 1;
        emit(json!({"kind": "iteration-start", "iteration": iteration}));

        if cancel.load(Ordering::Relaxed) {
            break json!({"outcome": "cancelled", "iteration": iteration});
        }

        emit(json!({"kind": "prompt-composed", "iteration": iteration, "prompt": prompt}));
        let response_text = match crate::generate_via_profile(&profile, &model, effort.as_deref(), &prompt).await {
            Ok(v) => v.get("text").and_then(|t| t.as_str()).unwrap_or_default().to_string(),
            Err(e) => break json!({"outcome": "aborted", "reason": e, "iteration": iteration}),
        };
        emit(json!({"kind": "ai-call-done", "iteration": iteration, "response": response_text}));

        let candidate = match extract_candidate(&response_text) {
            Some(c) => c,
            None => {
                let failure = FailureClass::Extraction;
                emit(json!({"kind": "iteration-failed", "iteration": iteration, "class": failure.label()}));
                match decide(iteration, max_iterations) {
                    Decision::Retry => {
                        prompt = build_reprompt(&app_name, &plain_ask, last_candidate.as_ref(), &failure);
                        emit(json!({"kind": "reprompt", "iteration": iteration, "prompt": prompt}));
                        continue 'iterations;
                    }
                    Decision::ExhaustedBudget => break json!({
                        "outcome": "exhausted", "lastFailure": failure.label(), "iteration": iteration,
                    }),
                }
            }
        };
        last_candidate = Some(candidate.clone());

        // --- validate ---
        let candidate_path = std::env::temp_dir()
            .join(format!("npdev-ai-loop-candidate-{}-{iteration}.json", std::process::id()));
        if let Err(e) = std::fs::write(&candidate_path, serde_json::to_string_pretty(&candidate).unwrap_or_default()) {
            break json!({"outcome": "aborted", "reason": format!("could not write candidate: {e}"), "iteration": iteration});
        }
        let report = npdev::run_validate_model(&python_exe, &npdev_cli, java_home.as_deref(), &candidate_path.to_string_lossy()).await;
        let _ = std::fs::remove_file(&candidate_path);
        let report = match report {
            Ok(r) => r,
            Err(e) => break json!({"outcome": "aborted", "reason": e, "iteration": iteration}),
        };
        emit(json!({"kind": "validate-result", "iteration": iteration, "report": report}));

        if let Some(failure) = classify_validation(&report) {
            emit(json!({"kind": "iteration-failed", "iteration": iteration, "class": failure.label()}));
            match decide(iteration, max_iterations) {
                Decision::Retry => {
                    prompt = build_reprompt(&app_name, &plain_ask, Some(&candidate), &failure);
                    emit(json!({"kind": "reprompt", "iteration": iteration, "prompt": prompt}));
                    continue 'iterations;
                }
                Decision::ExhaustedBudget => break json!({
                    "outcome": "exhausted", "lastFailure": failure.label(), "iteration": iteration,
                }),
            }
        }

        // --- apply (never a re-parse of the answer text -- exactly the validated candidate) ---
        if let Err(e) = crate::apply_validated_model(app_dir.clone(), candidate.clone(), APPLY_CONFIRM_TOKEN.to_string()) {
            break json!({"outcome": "aborted", "reason": e, "iteration": iteration});
        }
        emit(json!({"kind": "apply-done", "iteration": iteration}));

        // --- generate ---
        let model_dir = match crate::model_dir_of_app(&app_dir) {
            Some(d) => d,
            None => break json!({
                "outcome": "aborted",
                "reason": "not an npdev-init app (no '<name>-app' suffix on its directory)",
                "iteration": iteration,
            }),
        };
        let model_path = format!("{model_dir}/model.json");
        let config_path = format!("{model_dir}/config.json");
        if let Err(e) = npdev::run_generate_app(&python_exe, &npdev_cli, java_home.as_deref(), &model_path, &config_path, &app_dir).await {
            break json!({"outcome": "aborted", "reason": e, "iteration": iteration});
        }
        emit(json!({"kind": "generate-done", "iteration": iteration}));

        // --- build ---
        // The wrapper's `done` event reports exitCode 0 even when gradle failed (RUN-35), so the
        // captured ops lines are the loop's only trustworthy record of whether the build actually
        // succeeded -- collect them here and cross-check in `classify_build`.
        // `run_ops_script_capture`'s callback is `Fn` and the future must be `Send`, so the buffer
        // goes in a Mutex (the calls are synchronous on this task, the lock is never contended).
        let build_lines = std::sync::Mutex::new(Vec::<String>::new());
        let build_done = npdev::run_ops_script_capture(
            python_exe.clone(),
            npdev_cli.clone(),
            java_home.clone(),
            app_dir.clone(),
            "build-finalapp".to_string(),
            |line| {
                emit_event(&sink, &transcript_path, json!({"kind": "ops-line", "iteration": iteration, "raw": line}));
                if line.get("kind").and_then(|k| k.as_str()) == Some("line") {
                    if let Some(text) = line.get("text").and_then(|t| t.as_str()) {
                        build_lines.lock().expect("lock poisoned").push(text.to_string());
                    }
                }
            },
        )
        .await;
        let build_done = match build_done {
            Ok(v) => v,
            Err(e) => break json!({"outcome": "aborted", "reason": e, "iteration": iteration}),
        };
        if let Some(failure) = classify_build(&build_done, build_lines.lock().expect("lock poisoned").as_slice()) {
            emit(json!({"kind": "iteration-failed", "iteration": iteration, "class": failure.label()}));
            match decide(iteration, max_iterations) {
                Decision::Retry => {
                    prompt = build_reprompt(&app_name, &plain_ask, Some(&candidate), &failure);
                    emit(json!({"kind": "reprompt", "iteration": iteration, "prompt": prompt}));
                    continue 'iterations;
                }
                Decision::ExhaustedBudget => break json!({
                    "outcome": "exhausted", "lastFailure": failure.label(), "iteration": iteration,
                }),
            }
        }
        emit(json!({"kind": "build-done", "iteration": iteration}));

        // --- start (stop whatever THIS run left behind from an earlier, failed iteration first --
        // otherwise the second iteration's boot check would read the FIRST iteration's stale process). ---
        {
            let owned = app_state.running.lock().expect("lock poisoned").remove(&app_dir);
            if let Some(proc) = owned {
                let _ = npdev::stop_running_process(proc).await;
            }
        }
        let proc = match npdev::spawn_ops_script(python_exe.clone(), npdev_cli.clone(), java_home.clone(), app_dir.clone(), "run-finalapp".to_string()).await {
            Ok(p) => p,
            Err(e) => break json!({"outcome": "aborted", "reason": e, "iteration": iteration}),
        };
        app_state.running.lock().expect("lock poisoned").insert(app_dir.clone(), proc);
        emit(json!({"kind": "start-done", "iteration": iteration}));

        // --- wait-healthy (the smoke-check) ---
        match wait_for_healthy(&python_exe, &npdev_cli, java_home.as_deref(), &app_dir, Duration::from_secs(90), app_state).await {
            HealthOutcome::Running => {
                emit(json!({"kind": "health-result", "iteration": iteration, "health": "running"}));
                break json!({"outcome": "booted", "iteration": iteration});
            }
            HealthOutcome::PortConflict => {
                // The loop spawned this app, and it is NOT the one serving -- stop it so it cannot
                // linger holding `_ops` or a half-bound port after the loop aborts.
                {
                    let owned = app_state.running.lock().expect("lock poisoned").remove(&app_dir);
                    if let Some(proc) = owned {
                        let _ = npdev::stop_running_process(proc).await;
                    }
                }
                emit(json!({"kind": "iteration-failed", "iteration": iteration, "class": "engine", "detail": "port-conflict"}));
                break json!({
                    "outcome": "aborted",
                    "reason": "another instance is already using this app's port -- stop it and re-run",
                    "iteration": iteration,
                });
            }
            HealthOutcome::Failed(health) => {
                // RUN-36: tear the spawn tree down BEFORE going back to the model -- a booted-but-
                // unfelt app left running holds `_ops` (its monitor wrapper's cwd) and the next
                // iteration's regenerate dies with a Windows file-in-use error. The app already
                // failed this iteration's smoke check; nothing this loop can do wants it up.
                {
                    let owned = app_state.running.lock().expect("lock poisoned").remove(&app_dir);
                    if let Some(proc) = owned {
                        let _ = npdev::stop_running_process(proc).await;
                    }
                }
                let log_tail = npdev::run_monitor_logs(&python_exe, &npdev_cli, java_home.as_deref(), &app_dir, "app", 200)
                    .await
                    .map(|r| tail_from_monitor_logs(&r))
                    .unwrap_or_else(|_| "(could not read the app log)".to_string());
                let failure = FailureClass::Boot { health: health.clone(), log_tail };
                emit(json!({"kind": "iteration-failed", "iteration": iteration, "class": failure.label(), "health": health}));
                match decide(iteration, max_iterations) {
                    Decision::Retry => {
                        prompt = build_reprompt(&app_name, &plain_ask, Some(&candidate), &failure);
                        emit(json!({"kind": "reprompt", "iteration": iteration, "prompt": prompt}));
                        continue 'iterations;
                    }
                    Decision::ExhaustedBudget => break json!({
                        "outcome": "exhausted", "lastFailure": failure.label(), "iteration": iteration,
                    }),
                }
            }
        }
    };

    let mut done_event = serde_json::Map::new();
    done_event.insert("kind".to_string(), json!("loop-done"));
    if let Some(fields) = final_outcome.as_object() {
        for (k, v) in fields {
            done_event.insert(k.clone(), v.clone());
        }
    }
    emit(Value::Object(done_event));
}

// -------------------------------------------------------------------------------------------
// Tests -- the pure, no-I/O parts: classification, the retry boundary, prompt construction and
// candidate extraction. The stage sequence itself (`run`) is proven live, the same way every other
// Tauri command in this codebase is (no unit harness mocks a child process here either) -- see the
// plan's Verification section for the live proof this module's Done-when condition actually needs.
// -------------------------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_candidate_parses_plain_json() {
        let v = extract_candidate("  { \"a\": 1 }  ").unwrap();
        assert_eq!(v["a"], 1);
    }

    #[test]
    fn extract_candidate_parses_fenced_json() {
        let text = "Sure, here you go:\n```json\n{\"a\": 1}\n```\nLet me know if you need changes.";
        let v = extract_candidate(text).unwrap();
        assert_eq!(v["a"], 1);
    }

    #[test]
    fn extract_candidate_falls_back_to_first_balanced_object_past_trailing_braces() {
        // MON-10 shape: prose after the JSON that itself contains braces (a deliberateRemovals note
        // referencing "{fieldName}") must not push the widest-span match past the real object.
        let text = "{\"a\": 1}\ndeliberateRemovals: dropped the legacy {oldField} column on purpose.";
        let v = extract_candidate(text).unwrap();
        assert_eq!(v["a"], 1);
    }

    #[test]
    fn extract_candidate_returns_none_for_prose_with_no_json() {
        assert!(extract_candidate("I could not make that change.").is_none());
    }

    #[test]
    fn classify_validation_passes_through_warning_only_reports() {
        let report = json!({"status": "warning", "diagnostics": []});
        assert!(classify_validation(&report).is_none());
    }

    #[test]
    fn classify_validation_reads_the_first_error_layer() {
        let report = json!({
            "status": "failed",
            "diagnostics": [
                {"layer": "ux-metadata", "severity": "warning", "message": "cosmetic"},
                {"layer": "semantic", "severity": "error", "message": "unknown reference"},
            ],
        });
        match classify_validation(&report) {
            Some(FailureClass::Validation { layer, diagnostics }) => {
                assert_eq!(layer, "semantic");
                assert_eq!(diagnostics.len(), 2);
            }
            other => panic!("expected a Validation failure, got {other:?}"),
        }
    }

    #[test]
    fn classify_build_ignores_a_clean_exit() {
        assert!(classify_build(&json!({"exitCode": 0}), &[]).is_none());
    }

    #[test]
    fn classify_build_ignores_clean_gradle_output_even_with_lines() {
        let lines = vec![
            "> Task :compileJava".to_string(),
            "BUILD SUCCESSFUL in 42s".to_string(),
        ];
        assert!(classify_build(&json!({"exitCode": 0, "logFile": null}), &lines).is_none());
    }

    #[test]
    fn classify_build_flags_a_failed_build_even_when_the_wrapper_reports_exit_code_zero() {
        // RUN-35 shape: gradle failed but `npdev monitor ops`'s done event said exitCode 0 with no
        // logFile -- the class must come from the captured lines, and the tail must be theirs.
        let lines = vec![
            "> Task :compileJava FAILED".to_string(),
            "FAILURE: Build failed with an exception.".to_string(),
            "BUILD FAILED in 25s".to_string(),
        ];
        match classify_build(&json!({"exitCode": 0, "logFile": null}), &lines) {
            Some(FailureClass::Compile { log_tail }) => {
                assert!(log_tail.contains("compileJava FAILED"));
                assert!(log_tail.contains("BUILD FAILED in 25s"));
            }
            other => panic!("expected a Compile failure, got {other:?}"),
        }
    }

    #[test]
    fn classify_build_uses_captured_lines_over_a_missing_log_file() {
        let lines = vec!["BUILD FAILED in 9s".to_string()];
        match classify_build(&json!({"exitCode": 1, "logFile": null}), &lines) {
            Some(FailureClass::Compile { log_tail }) => assert_eq!(log_tail, "BUILD FAILED in 9s"),
            other => panic!("expected a Compile failure, got {other:?}"),
        }
    }

    #[test]
    fn decide_retries_while_under_budget_and_exhausts_at_the_limit() {
        assert!(matches!(decide(1, 5), Decision::Retry));
        assert!(matches!(decide(4, 5), Decision::Retry));
        assert!(matches!(decide(5, 5), Decision::ExhaustedBudget));
    }

    #[test]
    fn health_verdict_treats_started_but_booting_apps_as_non_terminal() {
        // RUN-36: while the app is still booting, the Monitor's probe reports "stopped" (nothing is
        // on the port yet) even though the app's JVM is alive and coming up. While the spawn tree
        // the loop started is alive, "stopped" must mean "keep polling", not "failed".
        match health_verdict("stopped", true, Duration::from_secs(5), Duration::from_secs(90)) {
            HealthVerdict::KeepPolling => {}
            other => panic!("boot-window stopped must keep polling, got {other:?}"),
        }
    }

    #[test]
    fn health_verdict_is_terminal_once_the_spawn_tree_is_gone() {
        // The spawn tree died: "stopped" really means down.
        match health_verdict("stopped", false, Duration::from_secs(5), Duration::from_secs(90)) {
            HealthVerdict::Failed(h) => assert_eq!(h, "stopped"),
            other => panic!("dead spawn tree + stopped must fail, got {other:?}"),
        }
    }

    #[test]
    fn health_verdict_stops_polling_stopped_after_the_timeout() {
        match health_verdict("stopped", true, Duration::from_secs(90), Duration::from_secs(90)) {
            HealthVerdict::Failed(h) => assert_eq!(h, "stopped"),
            other => panic!("stopped past the timeout must fail, got {other:?}"),
        }
    }

    #[test]
    fn health_verdict_keeps_running_and_port_conflict_terminal_regardless_of_liveness() {
        assert!(matches!(health_verdict("running", true, Duration::ZERO, Duration::from_secs(90)), HealthVerdict::Running));
        assert!(matches!(health_verdict("running", false, Duration::ZERO, Duration::from_secs(90)), HealthVerdict::Running));
        assert!(matches!(health_verdict("port-conflict", true, Duration::ZERO, Duration::from_secs(90)), HealthVerdict::PortConflict));
        assert!(matches!(health_verdict("error", true, Duration::ZERO, Duration::from_secs(90)), HealthVerdict::Failed(_)));
    }

    #[test]
    fn health_verdict_keeps_polling_starting_and_unknown() {
        for health in ["starting", "unknown"] {
            match health_verdict(health, false, Duration::ZERO, Duration::from_secs(90)) {
                HealthVerdict::KeepPolling => {}
                other => panic!("{health} must keep polling, got {other:?}"),
            }
        }
    }

    #[test]
    fn build_reprompt_carries_the_diagnostics_and_the_rename_discipline() {
        let failure = FailureClass::Validation {
            layer: "semantic".to_string(),
            diagnostics: vec![json!({
                "severity": "error", "message": "unknown reference target", "path": "concepts[Ticket]",
                "suggestedFix": "add the Priority field",
            })],
        };
        let prompt = build_reprompt("wmsoffice", "add a Priority field", Some(&json!({"x": 1})), &failure);
        assert!(prompt.contains("unknown reference target"));
        assert!(prompt.contains("add the Priority field"));
        assert!(prompt.contains("renamedFrom"));
        assert!(prompt.contains("deliberateRemovals"));
    }

    #[test]
    fn compose_initial_prompt_includes_context_when_given() {
        let prompt = compose_initial_prompt("wmsoffice", "add a field", Some(&json!({"concepts": []})));
        assert!(prompt.contains("wmsoffice"));
        assert!(prompt.contains("add a field"));
        assert!(prompt.contains("concepts"));
    }

    #[test]
    fn compose_initial_prompt_omits_the_context_block_when_none() {
        let prompt = compose_initial_prompt("wmsoffice", "add a field", None);
        assert!(!prompt.contains("Current model"));
    }
}
