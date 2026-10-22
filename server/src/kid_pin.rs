//! The Child PIN kept reversibly (design 59 §2.1): sealed at rest so the server can hand it to a
//! phone in android mode (`set_kid_pin`) and, behind the parent's password, show it (step 2). The
//! key is its own file, `data/keys/kid-pin.key` (a lost music key mustn't take the PINs, nor the
//! other way round), made and read by `keys::load_or_create` like the music key and used through
//! [MusicKey] (AES-256-GCM, fingerprint-checked) - no new crypto. No backup copies `data/keys/`: a
//! restore elsewhere reads every sealed PIN as "not readable here", and the parent sets the Child
//! PIN again.
//!
//! A sealed value is one text, `v1:<key fingerprint>:<nonce b64>:<ciphertext b64>`, sealed under
//! `kid-pin-v1:<fingerprint>:<device id>`, so a value copied into another phone's row doesn't open.
//! The digits travel through the code as [KidPin], whose `Debug` says nothing.

use std::path::Path;

use base64::Engine;
use serde::Serialize;

use crate::music_secret::MusicKey;

/// Where the key file goes (inside `data/`, the only place the unit lets the server write).
pub const DEFAULT_KEY_FILE: &str = "data/keys/kid-pin.key";

/// A Child PIN's digits. `Serialize` is the bare string (`pending_command.pin`); `Debug` is
/// redacted, so a `{:?}` of anything holding one never logs it.
#[derive(Clone, PartialEq, Eq, Serialize)]
#[serde(transparent)]
pub struct KidPin(String);

impl KidPin {
    pub fn new(digits: String) -> Self {
        KidPin(digits)
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for KidPin {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("KidPin(<redacted>)")
    }
}

/// The key from `file`, made (0600, a random key, its directory 0700) when it doesn't exist or is
/// empty. `Err` = the server can't keep Child PINs to send or show; the lock itself is unaffected.
pub fn load(file: &Path) -> Result<MusicKey, String> {
    let loaded = crate::keys::load_or_create(file, crate::music_secret::fresh_key_file)?;
    if loaded.created {
        tracing::info!(
            "created the Child PIN key file {} (Child PINs are sealed with it; no backup holds it)",
            file.display()
        );
    }
    // A mangled key is refused, not replaced: it may be the real one.
    std::str::from_utf8(&loaded.bytes)
        .ok()
        .and_then(crate::music_secret::decode_key)
        .map(|key| MusicKey::from_bytes(&key))
        .ok_or_else(|| {
            format!(
                "{} is not 32 bytes of base64 - fix it, or remove it to make a new key (each \
                 Child PIN then has to be set again before it can be sent or shown)",
                file.display()
            )
        })
}

fn aad(fingerprint: &str, device_id: i64) -> Vec<u8> {
    format!("kid-pin-v1:{fingerprint}:{device_id}").into_bytes()
}

/// `pin` sealed for `device_id`'s row.
pub fn seal(key: &MusicKey, device_id: i64, pin: &KidPin) -> String {
    let (ciphertext, nonce) =
        key.seal_with(&aad(&key.fingerprint, device_id), pin.as_str().as_bytes());
    let b64 = base64::engine::general_purpose::STANDARD;
    format!(
        "v1:{}:{}:{}",
        key.fingerprint,
        b64.encode(nonce),
        b64.encode(ciphertext)
    )
}

/// The PIN `sealed` holds for `device_id`, or `None`: another key, another phone's row, a changed
/// byte, another format, or anything but a valid Child PIN inside.
pub fn open(key: &MusicKey, device_id: i64, sealed: &str) -> Option<KidPin> {
    let mut parts = sealed.split(':');
    let (Some("v1"), Some(fingerprint), Some(nonce), Some(ciphertext), None) = (
        parts.next(),
        parts.next(),
        parts.next(),
        parts.next(),
        parts.next(),
    ) else {
        return None;
    };
    let b64 = base64::engine::general_purpose::STANDARD;
    let plain = key.open_with(
        &aad(fingerprint, device_id),
        &b64.decode(ciphertext).ok()?,
        &b64.decode(nonce).ok()?,
        fingerprint,
    )?;
    String::from_utf8(plain)
        .ok()
        .filter(|pin| crate::kid_lock::valid_kid_pin(pin))
        .map(KidPin)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_sealed_pin_opens_only_for_its_phone_and_key() {
        let key = MusicKey::from_bytes(&[3; 32]);
        let pin = KidPin::new("4821".to_string());
        let sealed = seal(&key, 7, &pin);
        assert!(
            sealed.starts_with(&format!("v1:{}:", key.fingerprint)),
            "{sealed}"
        );
        assert!(!sealed.contains("4821"));
        assert_eq!(open(&key, 7, &sealed), Some(pin.clone()));
        // A fresh nonce every time.
        assert_ne!(seal(&key, 7, &pin), sealed);
        // Another phone's row, another key, a changed byte, another shape: nothing.
        assert_eq!(open(&key, 8, &sealed), None);
        assert_eq!(open(&MusicKey::from_bytes(&[4; 32]), 7, &sealed), None);
        let mut changed = sealed.clone().into_bytes();
        let last = changed.len() - 3;
        changed[last] = if changed[last] == b'A' { b'B' } else { b'A' };
        assert_eq!(open(&key, 7, std::str::from_utf8(&changed).unwrap()), None);
        for bad in [
            "",
            "v1",
            &format!("v2{}", &sealed[2..]),
            &format!("{sealed}:x"),
        ] {
            assert_eq!(open(&key, 7, bad), None, "{bad}");
        }
        // Only a Child PIN comes out, whatever was sealed.
        let odd = seal(&key, 7, &KidPin::new("12ab".to_string()));
        assert_eq!(open(&key, 7, &odd), None);
    }

    #[test]
    fn debug_never_shows_the_digits_and_json_is_the_bare_string() {
        let pin = KidPin::new("4821".to_string());
        assert_eq!(format!("{pin:?}"), "KidPin(<redacted>)");
        assert_eq!(format!("{:?}", Some(&pin)), "Some(KidPin(<redacted>))");
        assert_eq!(serde_json::to_string(&pin).unwrap(), "\"4821\"");
    }

    #[test]
    fn the_key_file_is_made_once_and_a_mangled_one_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("data").join("keys").join("kid-pin.key");
        let made = load(&file).unwrap();
        assert_eq!(load(&file).unwrap().fingerprint, made.fingerprint);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&file).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
        std::fs::write(&file, "not a key").unwrap();
        let err = load(&file).err().unwrap();
        assert!(
            err.contains("kid-pin.key") && err.contains("Child PIN"),
            "{err}"
        );
        // Refused, not replaced.
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "not a key");
    }
}
