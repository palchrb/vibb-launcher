//! The family's Storytel login at rest (design 21 §1.4, QA #10): AES-256-GCM with this server's own
//! key, a random 96-bit nonce per save and the key's fingerprint in the associated data, so a
//! database backup holds no usable password and a login sealed with another key reads as "enter it
//! again" (the phones get a 503 until then), never as garbage.
//!
//! The key: `MUSIC_SECRET_KEY` (base64, 32 bytes) when `.env` sets it, else the key file (default
//! `data/keys/music-secret.key`, created 0600 in a 0700 directory with a random key the first time
//! the server starts without one - old `.env`s never get the variable). `data/` is the only place
//! the systemd unit lets the server write (`ProtectSystem=strict`, qa-21-step1-code #1), and no
//! backup copies `data/keys/`: the zip holds the database and the image stores, the live mirror and
//! the external drive copy the database and `data/backups/`, `update.sh` the database files. A
//! lost key means re-entering the login; nothing else depends on it.

use std::io::Write;
use std::path::Path;

use aes_gcm::Aes256Gcm;
use aes_gcm::aead::{Aead, KeyInit, Payload};
use argon2::password_hash::rand_core::{OsRng, RngCore};
use base64::Engine;
use sha2::{Digest, Sha256};

/// Where the key file goes when neither `MUSIC_SECRET_KEY` nor `MUSIC_SECRET_KEY_FILE` says
/// otherwise: inside `data/` (writable under the unit), in a directory no backup copies.
pub const DEFAULT_KEY_FILE: &str = "data/keys/music-secret.key";
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
/// (0600, a fresh random key, its directory 0700) when it doesn't exist or is empty. `Err` =
/// Storytel stays off (logged by the caller): an invalid variable, an unreadable or invalid file,
/// or one that can't be created - each says which file and why.
pub fn load(env_value: Option<&str>, file: &Path) -> Result<MusicKey, String> {
    if let Some(value) = env_value.map(str::trim).filter(|v| !v.is_empty()) {
        return decode_key(value)
            .map(|key| MusicKey::from_bytes(&key))
            .ok_or_else(|| "MUSIC_SECRET_KEY is not 32 bytes of base64".to_string());
    }
    match std::fs::read_to_string(file) {
        // An empty file is what a crash between creating and writing it used to leave: made
        // again. A non-empty invalid one may be a real key that got mangled - refused, not
        // replaced (qa-21-step1-code #9).
        Ok(text) if text.trim().is_empty() => create_key_file(file),
        Ok(text) => {
            restrict_permissions(file);
            decode_key(&text)
                .map(|key| MusicKey::from_bytes(&key))
                .ok_or_else(|| {
                    format!(
                        "{} is not 32 bytes of base64 - fix it, or remove it to make a new key \
                         (the saved Storytel login then has to be entered again)",
                        file.display()
                    )
                })
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => create_key_file(file),
        Err(err) => Err(format!("can't read {}: {err}", file.display())),
    }
}

/// Writes a new key through `<file>.tmp` (0600, synced), renames it into place and syncs the
/// directory, so a crash or a power cut leaves either no key file or a whole one.
fn create_key_file(file: &Path) -> Result<MusicKey, String> {
    let dir = file
        .parent()
        .filter(|d| !d.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    if !dir.exists() {
        std::fs::create_dir_all(dir)
            .map_err(|err| format!("can't create the directory {}: {err}", dir.display()))?;
        // Only a directory made here is made private; an existing one is the operator's.
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))
                .map_err(|err| format!("can't make {} private (0700): {err}", dir.display()))?;
        }
    }
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    let mut tmp_name = file.as_os_str().to_owned();
    tmp_name.push(".tmp");
    let tmp = std::path::PathBuf::from(tmp_name);
    let _ = std::fs::remove_file(&tmp);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let written = options
        .open(&tmp)
        .and_then(|mut out| {
            let text = format!(
                "{}\n",
                base64::engine::general_purpose::STANDARD.encode(key)
            );
            out.write_all(text.as_bytes())?;
            out.sync_all()
        })
        .and_then(|()| std::fs::rename(&tmp, file))
        .and_then(|()| std::fs::File::open(dir)?.sync_all());
    if let Err(err) = written {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("can't create {}: {err}", file.display()));
    }
    tracing::info!(
        "created the music key file {} (Storytel logins are sealed with it; no backup holds it)",
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
        let keys = dir.path().join("data").join("keys");
        let file = keys.join("music-secret.key");
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
            let dir_mode = std::fs::metadata(&keys).unwrap().permissions().mode();
            assert_eq!(dir_mode & 0o777, 0o700);
        }
        assert!(!keys.join("music-secret.key.tmp").exists());
        // Read back the next time, not replaced.
        assert_eq!(load(None, &file).unwrap().fingerprint, made.fingerprint);
        assert_eq!(
            load(Some("  "), &file).unwrap().fingerprint,
            made.fingerprint
        );

        // A mangled key is refused (it may be the real one); an empty file is made again.
        std::fs::write(&file, "not a key").unwrap();
        let err = load(None, &file).err().unwrap();
        assert!(
            err.contains("not 32 bytes") && err.contains("remove it"),
            "{err}"
        );
        std::fs::write(&file, "\n").unwrap();
        let again = load(None, &file).unwrap();
        assert_ne!(again.fingerprint, made.fingerprint);
        assert_eq!(load(None, &file).unwrap().fingerprint, again.fingerprint);
    }

    /// The shipped unit only lets the server write `data/` (qa-21-step1-code #1): a key file
    /// somewhere it can't write is a clear error naming the file, not a silent "off".
    #[test]
    #[cfg(unix)]
    fn a_key_file_in_a_read_only_directory_is_a_clear_error() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let locked = dir.path().join("locked");
        std::fs::create_dir(&locked).unwrap();
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o500)).unwrap();
        if std::fs::write(locked.join("probe"), b"x").is_ok() {
            eprintln!("running as root - the directory is writable anyway, nothing to check");
            return;
        }
        let file = locked.join("music-secret.key");
        let err = load(None, &file).err().unwrap();
        assert!(err.starts_with("can't create"), "{err}");
        assert!(err.contains("music-secret.key"), "{err}");
        assert!(!file.exists());
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o700)).unwrap();
    }
}
