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
//! Nothing here logs a value, and nothing returns one to the UI: `prompter_profiles` reports only
//! whether an entry exists. A window that can read a key back is a window that can leak it into a
//! screenshot.

use keyring::Entry;

/// What this shows as in Windows Credential Manager (`cmdkey /list`), the macOS Keychain, and the
/// Linux keyutils session keyring. Chosen to be recognisable to a user auditing their own
/// credentials, who did not read this file.
const SERVICE: &str = "NPDev Manager";

fn account(profile_id: &str) -> String {
    format!("prompter/{profile_id}")
}

fn entry(profile_id: &str) -> Result<Entry, String> {
    Entry::new(SERVICE, &account(profile_id))
        .map_err(|e| format!("could not open the OS credential store: {e}"))
}

pub fn set_secret(profile_id: &str, value: &str) -> Result<(), String> {
    entry(profile_id)?
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
    match entry(profile_id)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(format!("could not read the credential: {e}")),
    }
}

/// Deleting an entry that is not there is success, not an error -- callers are deleting a profile,
/// and a profile that never had a key stored is not a failed deletion.
pub fn delete_secret(profile_id: &str) -> Result<(), String> {
    match entry(profile_id)?.delete_credential() {
        Ok(()) => Ok(()),
        Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(format!("could not delete the credential: {e}")),
    }
}

pub fn has_secret(profile_id: &str) -> bool {
    matches!(get_secret(profile_id), Ok(Some(value)) if !value.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

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
