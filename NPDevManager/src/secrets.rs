//! Provider credentials for the Prompter, stored in the OS credential store.
//!
//! `manager.json` is a plain file in the user's profile that the Manager rewrites on every settings
//! change and that a support request could plausibly ask someone to paste. An API key does not
//! belong in it. Everything here is a thin wrapper over the `keyring` crate so the rest of the
//! Manager never touches a backend type: one service name, one account naming rule, and three
//! operations.
//!
//! The account key is `prompter/<profile id>`, so a machine can hold credentials for several
//! providers side by side and deleting a profile deletes exactly its own entry.
//!
//! S17b (NPDEV_MEGA_ROADMAP.md): scoped account keys widen the same store to DB and deploy
//! credentials with per-environment isolation (`db/dev/<profile>`, `db/staging/<profile>`,
//! `db/prod/<profile>`, same for `deploy/`). The `prompter` scope keeps its legacy single-key
//! shape on purpose (no env segment), so every credential recorded before this change keeps
//! resolving under the exact account key it was stored with.
//!
//! Nothing here logs a value, and nothing returns one to the UI: the profile-listing entry points
//! (`secret_profiles` in main.rs) report only whether an entry exists. A window that can read a key
//! back is a window that can leak it into a screenshot.

use keyring::Entry;

/// What this shows as in Windows Credential Manager (`cmdkey /list`), the macOS Keychain, and the
/// Linux keyutils session keyring. Chosen to be recognisable to a user auditing their own
/// credentials, who did not read this file.
const SERVICE: &str = "NPDev Manager";

/// Scopes backed by NON-prompter credential kinds. Prompter keys predate the env split and are
/// exempt from it (see `account_key`).
pub const SCOPES: [&str; 2] = ["db", "deploy"];

/// Env names the UI/CLI offer; not an enum because an operator may type a custom one.
pub const ENVIRONMENTS: [&str; 3] = ["dev", "staging", "prod"];

/// The account key for a credential. The `prompter` scope (and any scope with no env) keeps the
/// legacy single-segment shape so pre-S17b credentials never move; every other scope isolates per
/// environment, so dev/staging/prod can hold different credentials for the same profile without
/// overwriting each other.
pub fn account_key(scope: &str, env: Option<&str>, profile_id: &str) -> String {
    if scope == "prompter" || env.is_none() || env.is_some_and(|e| e.is_empty()) {
        format!("{scope}/{profile_id}")
    } else {
        let env = env.unwrap_or("");
        format!("{scope}/{env}/{profile_id}")
    }
}

fn entry_for(scope: &str, env: Option<&str>, profile_id: &str) -> Result<Entry, String> {
    Entry::new(SERVICE, &account_key(scope, env, profile_id))
        .map_err(|e| format!("could not open the OS credential store: {e}"))
}

pub fn set_secret(profile_id: &str, value: &str) -> Result<(), String> {
    set_secret_scoped("prompter", None, profile_id, value)
}

/// S17b: scoped twin of [`set_secret`] -- `db/prod/warehouse`, `deploy/staging/gateway`, etc.
pub fn set_secret_scoped(scope: &str, env: Option<&str>, profile_id: &str, value: &str) -> Result<(), String> {
    entry_for(scope, env, profile_id)?
        .set_password(value)
        .map_err(|e| format!("could not store the credential: {e}"))
}

/// `Ok(None)` for "no entry", distinct from `Err` for "the store itself failed".
///
/// The distinction is load-bearing: a missing entry is an ordinary state the UI renders as "no key
/// stored", while a broken store is something the user has to act on. Collapsing them would make a
/// locked or unavailable keychain look like an empty one, and the next save would silently write
/// over a credential the Manager had merely failed to read.
pub fn get_secret(profile_id: &str) -> Result<Option<String>, String> {
    get_secret_scoped("prompter", None, profile_id)
}

/// S17b: scoped twin of [`get_secret`].
pub fn get_secret_scoped(scope: &str, env: Option<&str>, profile_id: &str) -> Result<Option<String>, String> {
    match entry_for(scope, env, profile_id)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(format!("could not read the credential: {e}")),
    }
}

/// Deleting an entry that is not there is success, not an error -- callers are deleting a profile,
/// and a profile that never had a key stored is not a failed deletion.
pub fn delete_secret(profile_id: &str) -> Result<(), String> {
    delete_secret_scoped("prompter", None, profile_id)
}

/// S17b: scoped twin of [`delete_secret`].
pub fn delete_secret_scoped(scope: &str, env: Option<&str>, profile_id: &str) -> Result<(), String> {
    match entry_for(scope, env, profile_id)?.delete_credential() {
        Ok(()) => Ok(()),
        Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(format!("could not delete the credential: {e}")),
    }
}

pub fn has_secret(profile_id: &str) -> bool {
    has_secret_scoped("prompter", None, profile_id)
}

/// S17b: scoped twin of [`has_secret`].
pub fn has_secret_scoped(scope: &str, env: Option<&str>, profile_id: &str) -> bool {
    matches!(get_secret_scoped(scope, env, profile_id), Ok(Some(value)) if !value.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// S17b: the account-key naming rule is pure string building -- test it on every platform,
    /// since a regression here would silently collide dev/staging/prod credentials (or, worse,
    /// overwrite a legacy prompter key with a scoped one).
    #[test]
    fn account_key_isolates_scope_and_environment() {
        assert_eq!("db/dev/warehouse", account_key("db", Some("dev"), "warehouse"));
        assert_eq!("db/staging/warehouse", account_key("db", Some("staging"), "warehouse"));
        assert_eq!("db/prod/warehouse", account_key("db", Some("prod"), "warehouse"));
        // Same profile id, different env -> different accounts.
        assert_ne!(account_key("db", Some("dev"), "warehouse"),
                   account_key("db", Some("prod"), "warehouse"));
        // Different scopes never collide even with the same profile id.
        assert_ne!(account_key("db", Some("prod"), "gateway"),
                   account_key("deploy", Some("prod"), "gateway"));
    }

    #[test]
    fn prompter_scope_keeps_the_legacy_account_shape() {
        // Pre-S17b credentials live at prompter/<id> with NO env segment; the scoped path must not
        // move them, or every existing prompter key would stop resolving.
        assert_eq!("prompter/anthropic-work", account_key("prompter", None, "anthropic-work"));
        assert_eq!("prompter/anthropic-work", account_key("prompter", Some("dev"), "anthropic-work"));
        assert_eq!("prompter/anthropic-work", account_key("prompter", Some(""), "anthropic-work"));
    }

    #[test]
    fn scopes_and_environments_are_offered_to_the_ui() {
        assert!(SCOPES.contains(&"db") && SCOPES.contains(&"deploy"));
        assert_eq!(3, ENVIRONMENTS.len());
    }

    /// Windows only, deliberately.
    ///
    /// This is a round trip against the REAL credential store -- there is no in-memory backend to
    /// substitute, and a mock would prove nothing about the thing that can actually fail (the OS
    /// store being locked, absent, or refusing a write). On the headless Linux CI container there is
    /// no keyring daemon and no kernel session keyring to speak of, so the honest options are to run
    /// it where a store exists or to skip it with a stated reason -- the same explicit-SKIP
    /// discipline `--selftest` uses. Silently passing on a platform where it did not run is the one
    /// option ruled out.
    #[test]
    #[cfg(windows)]
    fn round_trips_a_secret_through_the_os_credential_store() {
        let id = "npdev-manager-selftest-profile";
        let _ = delete_secret(id);

        assert_eq!(get_secret(id).expect("store is readable"), None);
        assert!(!has_secret(id));

        set_secret(id, "sk-test-value").expect("store is writable");
        assert_eq!(get_secret(id).expect("store is readable"), Some("sk-test-value".to_string()));
        assert!(has_secret(id));

        delete_secret(id).expect("store is deletable");
        assert_eq!(get_secret(id).expect("store is readable"), None);
        // Deleting twice is not an error -- callers delete a profile, not a credential.
        delete_secret(id).expect("deleting a missing entry succeeds");
    }
}
