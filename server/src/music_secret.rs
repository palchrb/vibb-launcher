//! The family's Storytel login at rest (design 21 §1.4, QA #10): AES-256-GCM with this server's own
//! key, a random 96-bit nonce per save and the key's fingerprint in the associated data, so a
//! database backup holds no usable password and a login sealed with another key reads as "enter it
//! again" (the phones get a 503 until then), never as garbage.
//!
//! The key: `MUSIC_SECRET_KEY` (base64, 32 bytes) when `.env` sets it, else the key file (default
//! `music-secret.key` in the working directory, outside `data/` and so outside every backup),
//! created 0600 with a random key the first time the server starts without one - old `.env`s never
//! get the variable. A lost key means re-entering the login; nothing else depends on it.

use std::io::Write;
use std::path::Path;

use aes_gcm::Aes256Gcm;
use aes_gcm::aead::{Aead, KeyInit, Payload};
use argon2::password_hash::rand_core::{OsRng, RngCore};
use base64::Engine;
use sha2::{Digest, Sha256};

/// Where the key file goes when neither `MUSIC_SECRET_KEY` nor `MUSIC_SECRET_KEY_FILE` says
/// otherwise: the working directory (`/opt/kid-phone-server`), next to `.env`, not in `data/`.
pub const DEFAULT_KEY_FILE: &str = "music-secret.key";
const NONCE_BYTES: usize = 12;

/// The server's music key and its fingerprint (16 hex characters).
pub struct MusicKey {
    cipher: Aes256Gcm,
    pub fingerprint: String,
}

impl MusicKey {
    pub fn from_bytes(key: &[u8; 32]) -> Self {
        let digest = Sha256::new()
            .chain_update(b"handy-music-key:")
            .chain_update(key)
            .finalize();
        MusicKey {
            cipher: Aes256Gcm::new_from_slice(key).expect("a 32-byte key fits AES-256"),
            fingerprint: hex::encode(&digest[..8]),
        }
    }

    fn aad(fingerprint: &str) -> Vec<u8> {
        format!("storytel-v1:{fingerprint}").into_bytes()
    }

    /// `(ciphertext, nonce)` of `plaintext`, bound to this key's fingerprint.
    pub fn seal(&self, plaintext: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let mut nonce = [0u8; NONCE_BYTES];
        OsRng.fill_bytes(&mut nonce);
        let ciphertext = self
            .cipher
            .encrypt(
                &nonce.into(),
                Payload {
                    msg: plaintext,
                    aad: &Self::aad(&self.fingerprint),
                },
            )
            .expect("AES-GCM encryption of a short message can't fail");
        (ciphertext, nonce.to_vec())
    }

    /// The plaintext, or `None` when it was sealed with another key, was changed, or isn't ours.
    pub fn open(&self, ciphertext: &[u8], nonce: &[u8], fingerprint: &str) -> Option<Vec<u8>> {
        if fingerprint != self.fingerprint {
            return None;
        }
        let nonce: [u8; NONCE_BYTES] = nonce.try_into().ok()?;
        self.cipher
            .decrypt(
                &nonce.into(),
                Payload {
                    msg: ciphertext,
                    aad: &Self::aad(fingerprint),
                },
            )
            .ok()
    }
}

fn decode_key(text: &str) -> Option<[u8; 32]> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(text.trim())
        .ok()?;
    bytes.try_into().ok()
}

/// The key from `env_value` (`MUSIC_SECRET_KEY`) when set, else from `file`, which is created
/// (0600, a fresh random key) when it doesn't exist. `Err` = Storytel stays off (logged by the
/// caller): an invalid variable, an unreadable or invalid file, or one that can't be created.
pub fn load(env_value: Option<&str>, file: &Path) -> Result<MusicKey, String> {
    if let Some(value) = env_value.map(str::trim).filter(|v| !v.is_empty()) {
        return decode_key(value)
            .map(|key| MusicKey::from_bytes(&key))
            .ok_or_else(|| "MUSIC_SECRET_KEY is not 32 bytes of base64".to_string());
    }
    match std::fs::read_to_string(file) {
        Ok(text) => {
            restrict_permissions(file);
            decode_key(&text)
                .map(|key| MusicKey::from_bytes(&key))
                .ok_or_else(|| format!("{} is not 32 bytes of base64", file.display()))
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => create_key_file(file),
        Err(err) => Err(format!("can't read {}: {err}", file.display())),
    }
}

fn create_key_file(file: &Path) -> Result<MusicKey, String> {
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut out = options
        .open(file)
        .map_err(|err| format!("can't create {}: {err}", file.display()))?;
    let text = format!(
        "{}\n",
        base64::engine::general_purpose::STANDARD.encode(key)
    );
    out.write_all(text.as_bytes())
        .and_then(|()| out.sync_all())
        .map_err(|err| format!("can't write {}: {err}", file.display()))?;
    tracing::info!(
        "created the music key file {} (Storytel logins are sealed with it; keep it out of backups)",
        file.display()
    );
    Ok(MusicKey::from_bytes(&key))
}

/// A key file anyone else can read is made 0600 (best effort, logged).
fn restrict_permissions(file: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = std::fs::metadata(file)
            && meta.permissions().mode() & 0o077 != 0
        {
            match std::fs::set_permissions(file, std::fs::Permissions::from_mode(0o600)) {
                Ok(()) => tracing::warn!("{} was readable by others - now 0600", file.display()),
                Err(err) => tracing::warn!("{} is readable by others: {err}", file.display()),
            }
        }
    }
    #[cfg(not(unix))]
    let _ = file;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sealed_logins_open_only_with_the_same_key() {
        let key = MusicKey::from_bytes(&[7; 32]);
        let other = MusicKey::from_bytes(&[8; 32]);
        assert_eq!(key.fingerprint.len(), 16);
        assert_ne!(key.fingerprint, other.fingerprint);
        let (ciphertext, nonce) = key.seal(b"secret");
        assert!(!ciphertext.windows(6).any(|w| w == b"secret"));
        assert_eq!(
            key.open(&ciphertext, &nonce, &key.fingerprint).as_deref(),
            Some(&b"secret"[..])
        );
        // Another key, a lying fingerprint, a changed byte or nonce: nothing.
        assert!(other.open(&ciphertext, &nonce, &key.fingerprint).is_none());
        assert!(
            other
                .open(&ciphertext, &nonce, &other.fingerprint)
                .is_none()
        );
        let mut changed = ciphertext.clone();
        changed[0] ^= 1;
        assert!(key.open(&changed, &nonce, &key.fingerprint).is_none());
        assert!(
            key.open(&ciphertext, &nonce[..11], &key.fingerprint)
                .is_none()
        );
        // A fresh nonce every time.
        assert_ne!(key.seal(b"secret").1, nonce);
    }

    #[test]
    fn the_key_comes_from_env_or_a_0600_file_made_once() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("music-secret.key");
        let env = base64::engine::general_purpose::STANDARD.encode([9u8; 32]);
        assert_eq!(
            load(Some(&env), &file).unwrap().fingerprint,
            MusicKey::from_bytes(&[9; 32]).fingerprint
        );
        assert!(!file.exists(), "a key from .env writes no file");
        assert!(load(Some("dG9vIHNob3J0"), &file).is_err());

        let made = load(None, &file).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&file).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
        // Read back the next time, not replaced.
        assert_eq!(load(None, &file).unwrap().fingerprint, made.fingerprint);
        assert_eq!(
            load(Some("  "), &file).unwrap().fingerprint,
            made.fingerprint
        );

        std::fs::write(&file, "not a key").unwrap();
        assert!(load(None, &file).is_err());
    }
}
