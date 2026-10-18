//! The server's own key files in `data/keys/` (design 21 §1.4, design 35 §4.1): the music key
//! (`music_secret`) and the VAPID key (`push`). Each is made once, the first time the server
//! starts without it, through `<file>.tmp` (0600, synced) and a rename in a 0700 directory, so a
//! crash or a power cut leaves no file or a whole one. A non-empty file is read, never replaced
//! (the caller refuses one it can't parse: it may be the real key, mangled). `data/` is the only
//! place the systemd unit lets the server write, and no backup copies `data/keys/`.

use std::io::Write;
use std::path::{Path, PathBuf};

/// A key file's bytes, and whether they were made just now.
pub struct KeyFile {
    pub bytes: Vec<u8>,
    pub created: bool,
}

/// The bytes of `file`, or `fresh()`'s written to it when it doesn't exist or is empty (only
/// whitespace: what a crash between creating and writing it used to leave). `Err` names the file.
pub fn load_or_create(file: &Path, fresh: impl FnOnce() -> Vec<u8>) -> Result<KeyFile, String> {
    match std::fs::read(file) {
        Ok(bytes) if bytes.iter().all(u8::is_ascii_whitespace) => create(file, &fresh()),
        Ok(bytes) => {
            restrict_permissions(file);
            Ok(KeyFile {
                bytes,
                created: false,
            })
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => create(file, &fresh()),
        Err(err) => Err(format!("can't read {}: {err}", file.display())),
    }
}

/// Writes `bytes` through `<file>.tmp` (0600, synced), renames it into place and syncs the
/// directory. Only a directory made here is made 0700; an existing one is the operator's.
fn create(file: &Path, bytes: &[u8]) -> Result<KeyFile, String> {
    let dir = file
        .parent()
        .filter(|d| !d.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    if !dir.exists() {
        std::fs::create_dir_all(dir)
            .map_err(|err| format!("can't create the directory {}: {err}", dir.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))
                .map_err(|err| format!("can't make {} private (0700): {err}", dir.display()))?;
        }
    }
    let mut tmp_name = file.as_os_str().to_owned();
    tmp_name.push(".tmp");
    let tmp = PathBuf::from(tmp_name);
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
            out.write_all(bytes)?;
            out.sync_all()
        })
        .and_then(|()| std::fs::rename(&tmp, file))
        .and_then(|()| std::fs::File::open(dir)?.sync_all());
    if let Err(err) = written {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("can't create {}: {err}", file.display()));
    }
    Ok(KeyFile {
        bytes: bytes.to_vec(),
        created: true,
    })
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
    fn made_once_0600_read_back_and_never_replaced() {
        let dir = tempfile::tempdir().unwrap();
        let keys = dir.path().join("data").join("keys");
        let file = keys.join("some.key");
        let made = load_or_create(&file, || vec![1, 2, 3]).unwrap();
        assert!(made.created && made.bytes == [1, 2, 3]);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = |p: &Path| std::fs::metadata(p).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode(&file), 0o600);
            assert_eq!(mode(&keys), 0o700);
            // A file others can read is made private when read.
            std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o644)).unwrap();
            load_or_create(&file, || unreachable!()).unwrap();
            assert_eq!(mode(&file), 0o600);
        }
        assert!(!keys.join("some.key.tmp").exists());
        let again = load_or_create(&file, || unreachable!()).unwrap();
        assert!(!again.created && again.bytes == [1, 2, 3]);
        // An empty file is made again.
        std::fs::write(&file, b" \n").unwrap();
        assert_eq!(load_or_create(&file, || vec![9]).unwrap().bytes, [9]);
    }
}
