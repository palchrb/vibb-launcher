//! What the server keeps about a phone, and for how long (cleanup round 2026-10-06). Principle:
//! collect only what's needed to manage the phone, delete on a schedule, never store
//! notification or message content.

use std::path::Path;

/// Where the removed conversation journal kept its media (photos, video, voice notes).
pub const JOURNAL_MEDIA_DIR: &str = "data/journal_media";

/// Deletes the removed conversation journal's media directory, if it is still there (the
/// tables went with migration 0038). Returns whether something was deleted; a failure is logged
/// and retried at the next start.
pub async fn remove_journal_media(dir: &Path) -> bool {
    match tokio::fs::remove_dir_all(dir).await {
        Ok(()) => {
            tracing::info!(
                "Deleted the removed conversation journal's media ({})",
                dir.display()
            );
            true
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
        Err(e) => {
            tracing::error!(%e, "Couldn't delete the old journal media in {}", dir.display());
            false
        }
    }
}
