//! What happens before the server listens, for every deploy kind (design 22 §6), and the
//! container's subcommands:
//!
//! - [check_data_writable]: a clear error when `data/` isn't writable by this process (a Docker
//!   bind mount isn't seeded from the image; only a named volume is).
//! - [apply_restore_request]: a restore the PWA asked for in the container (`data/restore_request`,
//!   no root helper there) - the marker is consumed first, the same checks as `actions.sh`, a
//!   prerestore copy, and [undo_restore] when the restored database won't open.
//! - [pre_migration_backup]: `VACUUM INTO data/backups/pre-migrate-<from>-<to>.db` before pending
//!   migrations run (3 kept) - the backup a rollback needs.
//! - [initial_admin_password]: no admin and no password configured - one is generated into
//!   `data/initial-admin-password` (0600); the log names the file, never the password.
//! - [healthcheck] and [selftest_tls]: `kid_phone_server healthcheck` / `selftest-tls`.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// The file behind a `sqlite://` URL (`None` for anything else, e.g. `:memory:`).
pub fn sqlite_path(url: &str) -> Option<PathBuf> {
    let path = url
        .strip_prefix("sqlite://")
        .or_else(|| url.strip_prefix("sqlite:"))?;
    let path = path.split('?').next().unwrap_or(path);
    (!path.is_empty() && path != ":memory:").then(|| PathBuf::from(path))
}

/// Creates `dir` if needed and proves this process can write a file in it.
pub fn check_data_writable(dir: &Path) -> Result<(), String> {
    let uid = rustix::process::getuid().as_raw();
    let explain = |err: std::io::Error| {
        format!(
            "{}/ is not writable by uid {uid} ({err}). In Docker: use a named volume (it is \
             seeded from the image), or chown a bind-mounted directory to {uid}:{uid}. On the \
             Pi: the directory belongs to the service user (install.sh sets it).",
            dir.display()
        )
    };
    std::fs::create_dir_all(dir).map_err(explain)?;
    let probe = dir.join(format!(".write-check-{}", std::process::id()));
    std::fs::File::create(&probe)
        .and_then(|mut file| file.write_all(b"ok"))
        .map_err(explain)?;
    std::fs::remove_file(&probe).map_err(explain)?;
    Ok(())
}

/// The marker the PWA's restore writes in the container: one backup file name.
pub const RESTORE_REQUEST: &str = "restore_request";

/// A restore that was applied: the prerestore copy to go back to if the database won't open.
#[derive(Debug)]
pub struct AppliedRestore {
    pub filename: String,
    pub prerestore_dir: PathBuf,
}

const DB_SUFFIXES: [&str; 3] = ["", "-wal", "-shm"];

fn with_suffix(db: &Path, suffix: &str) -> PathBuf {
    let mut name = db.as_os_str().to_owned();
    name.push(suffix);
    PathBuf::from(name)
}

/// Applies `data_dir/restore_request` if it is there (the same checks as `actions.sh`'s
/// `action_restore_backup`): the marker is deleted first, so a restore that fails can't loop; the
/// name must be `backup-*.zip` without `/` or `..`, the file must be in `backups/` and hold
/// `kidphone.db`; the live database (with its `-wal`/`-shm`) is copied to
/// `backups/prerestore-<timestamp>/` first, then replaced, and its `-wal`/`-shm` removed.
/// `Ok(None)`: no marker. `Err`: refused or failed - nothing was swapped.
pub fn apply_restore_request(data_dir: &Path, db: &Path) -> Result<Option<AppliedRestore>, String> {
    let marker = data_dir.join(RESTORE_REQUEST);
    let Ok(filename) = std::fs::read_to_string(&marker) else {
        return Ok(None);
    };
    std::fs::remove_file(&marker)
        .map_err(|err| format!("couldn't remove {}: {err}", marker.display()))?;
    let filename = filename.trim().to_string();
    if !(filename.starts_with("backup-")
        && filename.ends_with(".zip")
        && !filename.contains('/')
        && !filename.contains('\\')
        && !filename.contains(".."))
    {
        return Err(format!(
            "refusing to restore {filename:?}: not a backup-*.zip name"
        ));
    }
    let backups = data_dir.join("backups");
    let zip_path = backups.join(&filename);
    let file = std::fs::File::open(&zip_path)
        .map_err(|err| format!("{} can't be opened: {err}", zip_path.display()))?;
    let mut archive =
        zip::ZipArchive::new(file).map_err(|err| format!("{filename} is not a zip: {err}"))?;
    let mut entry = archive
        .by_name("kidphone.db")
        .map_err(|_| format!("{filename} holds no kidphone.db"))?;
    let staged = with_suffix(db, ".restore-tmp");
    {
        let mut out = std::fs::File::create(&staged)
            .map_err(|err| format!("couldn't write {}: {err}", staged.display()))?;
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            let read = entry
                .read(&mut buffer)
                .map_err(|err| format!("couldn't read {filename}: {err}"))?;
            if read == 0 {
                break;
            }
            out.write_all(&buffer[..read])
                .map_err(|err| format!("couldn't write {}: {err}", staged.display()))?;
        }
        out.sync_all().ok();
    }

    let prerestore_dir = backups.join(format!(
        "prerestore-{}",
        chrono::Local::now().format("%Y%m%d-%H%M%S")
    ));
    std::fs::create_dir_all(&prerestore_dir)
        .map_err(|err| format!("couldn't make {}: {err}", prerestore_dir.display()))?;
    for suffix in DB_SUFFIXES {
        let live = with_suffix(db, suffix);
        if live.exists() {
            let copy = prerestore_dir.join(format!("kidphone.db{suffix}"));
            std::fs::copy(&live, &copy).map_err(|err| {
                let _ = std::fs::remove_file(&staged);
                format!("couldn't copy {} aside: {err}", live.display())
            })?;
        }
    }
    std::fs::rename(&staged, db)
        .map_err(|err| format!("couldn't put the backup in place: {err}"))?;
    for suffix in ["-wal", "-shm"] {
        let _ = std::fs::remove_file(with_suffix(db, suffix));
    }
    Ok(Some(AppliedRestore {
        filename,
        prerestore_dir,
    }))
}

/// Puts the prerestore copy back (the restored database didn't open): there is no shell in the
/// container to break a restart loop by hand.
pub fn undo_restore(db: &Path, restore: &AppliedRestore) -> Result<(), String> {
    for suffix in DB_SUFFIXES {
        let target = with_suffix(db, suffix);
        let copy = restore.prerestore_dir.join(format!("kidphone.db{suffix}"));
        if copy.exists() {
            std::fs::copy(&copy, &target)
                .map_err(|err| format!("couldn't put {} back: {err}", copy.display()))?;
        } else {
            let _ = std::fs::remove_file(&target);
        }
    }
    Ok(())
}

/// How many `pre-migrate-*.db` are kept.
pub const PRE_MIGRATE_KEEP: usize = 3;

/// Before pending migrations run on an existing database: `VACUUM INTO
/// <db dir>/backups/pre-migrate-<from>-<to>.db`, keeping the newest [PRE_MIGRATE_KEEP]. A fresh
/// database (no migration applied yet) needs none. An error stops the start: a migration without
/// its backup would leave no way back.
pub async fn pre_migration_backup(
    db: &sqlx::SqlitePool,
    db_path: &Path,
    latest: i64,
) -> Result<Option<PathBuf>, String> {
    let applied: Option<i64> =
        sqlx::query_scalar("SELECT MAX(version) FROM _sqlx_migrations WHERE success = 1")
            .fetch_one(db)
            .await
            .ok()
            .flatten();
    let Some(from) = applied else {
        return Ok(None);
    };
    if from >= latest {
        return Ok(None);
    }
    let dir = db_path
        .parent()
        .map(|parent| parent.join("backups"))
        .unwrap_or_else(|| PathBuf::from("backups"));
    std::fs::create_dir_all(&dir)
        .map_err(|err| format!("couldn't make {}: {err}", dir.display()))?;
    let target = dir.join(format!("pre-migrate-{from:04}-{latest:04}.db"));
    // VACUUM INTO refuses an existing file; the same name means the same upgrade again (after a
    // rollback), and the newest state is the one to keep.
    let _ = std::fs::remove_file(&target);
    sqlx::query("VACUUM INTO ?")
        .bind(target.to_string_lossy().to_string())
        .execute(db)
        .await
        .map_err(|err| format!("the pre-migration backup failed: {err}"))?;
    prune_pre_migrate(&dir);
    Ok(Some(target))
}

fn prune_pre_migrate(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let mut names: Vec<String> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.file_name().to_string_lossy().to_string())
        .filter(|name| name.starts_with("pre-migrate-") && name.ends_with(".db"))
        .collect();
    names.sort();
    let excess = names.len().saturating_sub(PRE_MIGRATE_KEEP);
    for name in &names[..excess] {
        let _ = std::fs::remove_file(dir.join(name));
    }
}

/// Where a generated first-admin password goes.
pub const INITIAL_PASSWORD_FILE: &str = "initial-admin-password";

/// The first admin's password when none is configured (`ADMIN_PASSWORD`,
/// `ADMIN_PASSWORD_FILE`): 24 random letters and digits in `data_dir/initial-admin-password`
/// (0600) - or the one already there, if an earlier start wrote it and stopped before the account
/// was created.
pub fn initial_admin_password(data_dir: &Path) -> Result<(String, PathBuf), String> {
    use argon2::password_hash::rand_core::{OsRng, RngCore};
    use std::os::unix::fs::OpenOptionsExt;

    let path = data_dir.join(INITIAL_PASSWORD_FILE);
    if let Ok(existing) = std::fs::read_to_string(&path)
        && existing.trim().len() >= crate::security::MIN_PASSWORD_LEN
    {
        return Ok((existing.trim().to_string(), path));
    }
    const CHARS: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    let password: String = (0..24)
        .map(|_| {
            let limit = u32::MAX - u32::MAX % CHARS.len() as u32;
            loop {
                let n = OsRng.next_u32();
                if n < limit {
                    break CHARS[(n % CHARS.len() as u32) as usize] as char;
                }
            }
        })
        .collect();
    let _ = std::fs::remove_file(&path);
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&path)
        .map_err(|err| format!("couldn't write {}: {err}", path.display()))?;
    file.write_all(format!("{password}\n").as_bytes())
        .map_err(|err| format!("couldn't write {}: {err}", path.display()))?;
    Ok((password, path))
}

/// `kid_phone_server healthcheck`: `GET /healthz` on `BIND_ADDR`'s port within 3 s. It dials
/// `BIND_ADDR`'s host, `0.0.0.0` as 127.0.0.1 and `::` as ::1.
pub async fn healthcheck(bind_addr: &str) -> Result<(), String> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let check = async {
        let mut addr = tokio::net::lookup_host(bind_addr)
            .await
            .map_err(|err| format!("BIND_ADDR {bind_addr:?}: {err}"))?
            .next()
            .ok_or_else(|| format!("BIND_ADDR {bind_addr:?} names no address"))?;
        match addr.ip() {
            std::net::IpAddr::V4(ip) if ip.is_unspecified() => {
                addr.set_ip(std::net::Ipv4Addr::LOCALHOST.into())
            }
            std::net::IpAddr::V6(ip) if ip.is_unspecified() => {
                addr.set_ip(std::net::Ipv6Addr::LOCALHOST.into())
            }
            _ => {}
        }
        let mut stream = tokio::net::TcpStream::connect(addr)
            .await
            .map_err(|err| format!("can't connect to {addr}: {err}"))?;
        stream
            .write_all(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            .await
            .map_err(|err| format!("can't ask {addr}: {err}"))?;
        let mut answer = Vec::new();
        stream
            .take(16 * 1024)
            .read_to_end(&mut answer)
            .await
            .map_err(|err| format!("no answer from {addr}: {err}"))?;
        let answer = String::from_utf8_lossy(&answer);
        let status_ok = answer.starts_with("HTTP/1.1 200") || answer.starts_with("HTTP/1.0 200");
        if status_ok && answer.trim_end().ends_with("ok") {
            Ok(())
        } else {
            Err(format!(
                "unhealthy: {}",
                answer.lines().next().unwrap_or("no status line")
            ))
        }
    };
    tokio::time::timeout(Duration::from_secs(3), check)
        .await
        .map_err(|_| "no answer within 3 s".to_string())?
}

/// `kid_phone_server selftest-tls`: one HTTPS request to api.github.com through the app's own
/// reqwest/rustls stack. Any HTTP status passes (shared CI runners get rate-limited); only a
/// failed handshake or certificate check fails - the silent TLS failure server/CLAUDE.md warns
/// about (v0.18.5 shipped one).
pub async fn selftest_tls() -> Result<String, String> {
    let client = reqwest::Client::builder()
        .user_agent("kid-phone-server selftest-tls (self-hosted, github.com)")
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|err| format!("couldn't build the HTTP client: {err}"))?;
    match client.get("https://api.github.com/").send().await {
        Ok(response) => Ok(format!(
            "TLS ok: api.github.com answered {}",
            response.status()
        )),
        Err(err) => Err(format!("TLS to api.github.com failed: {err:?}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sqlite_paths() {
        assert_eq!(
            sqlite_path("sqlite://data/kidphone.db"),
            Some(PathBuf::from("data/kidphone.db"))
        );
        assert_eq!(
            sqlite_path("sqlite:///tmp/x.db?mode=rwc"),
            Some(PathBuf::from("/tmp/x.db"))
        );
        assert_eq!(sqlite_path("sqlite::memory:"), None);
        assert_eq!(sqlite_path("postgres://x"), None);
    }

    #[test]
    fn a_data_dir_that_cant_be_written_says_so() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(check_data_writable(&dir.path().join("data")), Ok(()));
        // A path below a file can't be a directory: the error names the directory and the uid.
        let file = dir.path().join("plain-file");
        std::fs::write(&file, "x").unwrap();
        let err = check_data_writable(&file.join("data")).unwrap_err();
        assert!(err.contains("is not writable by uid"), "{err}");
        assert!(err.contains("named volume"), "{err}");
    }

    #[test]
    fn the_initial_password_lands_in_a_private_file_once() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let (password, path) = initial_admin_password(dir.path()).unwrap();
        assert_eq!(password.len(), 24);
        assert_eq!(std::fs::read_to_string(&path).unwrap().trim(), password);
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
        // A second start before the account exists reuses it.
        assert_eq!(initial_admin_password(dir.path()).unwrap().0, password);
    }
}
