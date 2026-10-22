//! Several equal parent accounts in one family (design 62, `docs/design/62-parent-accounts.md`).
//!
//! - **Ready** = not removed, its own password set and two-factor login on (§1). An account that
//!   isn't ready is onboarding through the forced first sign-in, with a one-time password that
//!   works until `invite_expires_at` (72 hours, written in SQL).
//! - **The actor condition** (§4): every statement here carries "the actor's session epoch is
//!   still its own and the actor is ready" in the statement itself. SQLite runs writes one at a
//!   time, so that is the race guard: a request held past a Reset, a removal or a sign-out
//!   everywhere changes nothing.
//! - [add_parent] (§4.1) and [remove_parent] (§4.2; Withdraw is its label on a not-ready row).
//!   Reset and the "Remove…" label on a ready row come with design 62 step 3.

use crate::models::AdminUser;

/// At most this many accounts that aren't removed (§4.1).
pub const MAX_PARENTS: i64 = 8;

/// `<actor ok>` (§4) with the actor's id as `?{a}` and its session epoch as `?{e}`.
macro_rules! actor_ok {
    ($a:literal, $e:literal) => {
        concat!(
            "EXISTS (SELECT 1 FROM admin_users WHERE id = ?",
            $a,
            " AND session_epoch = ?",
            $e,
            " AND removed_at IS NULL AND must_change_password = 0 AND totp_enabled = 1)"
        )
    };
}

/// What [add_parent] did.
#[derive(Debug, PartialEq, Eq)]
pub enum Added {
    /// The new account's id and when its one-time password stops working (SQLite text, UTC).
    Done { id: i64, expires_at: String },
    /// The username, or the same in other letter case, is an account's (removed ones too).
    Taken,
    /// [MAX_PARENTS] already.
    Full,
    /// The actor's session ended (or it isn't ready): nothing was written.
    SignedOut,
}

/// Adds a parent with the one-time password's hash, onboarding through the forced first sign-in,
/// valid 72 hours (§4.1). `username` is already lowercased and valid. The cap, the taken check
/// (`lower(username)`, qa-62-revision-check N2: an older install may hold "Mamma") and the actor
/// condition are in the one `INSERT`.
pub async fn add_parent(
    db: &sqlx::SqlitePool,
    actor: &AdminUser,
    username: &str,
    display_name: Option<&str>,
    otp_hash: &str,
) -> Result<Added, sqlx::Error> {
    let inserted: Result<Option<(i64, String)>, sqlx::Error> = sqlx::query_as(concat!(
        "INSERT INTO admin_users (username, display_name, password_hash, must_change_password, \
         totp_enabled, invited_by, invite_expires_at) \
         SELECT ?1, ?2, ?3, 1, 0, ?4, datetime('now', '+72 hours') \
         WHERE ",
        actor_ok!("4", "5"),
        " AND (SELECT COUNT(*) FROM admin_users WHERE removed_at IS NULL) < ?6 \
          AND NOT EXISTS (SELECT 1 FROM admin_users WHERE lower(username) = lower(?1)) \
         RETURNING id, invite_expires_at"
    ))
    .bind(username)
    .bind(display_name)
    .bind(otp_hash)
    .bind(actor.id)
    .bind(actor.session_epoch)
    .bind(MAX_PARENTS)
    .fetch_optional(db)
    .await;
    match inserted {
        Ok(Some((id, expires_at))) => return Ok(Added::Done { id, expires_at }),
        Ok(None) => {}
        Err(sqlx::Error::Database(err)) if err.is_unique_violation() => return Ok(Added::Taken),
        Err(err) => return Err(err),
    }
    // Nothing written: say why, the actor's own state first.
    if !actor_still_ok(db, actor).await? {
        return Ok(Added::SignedOut);
    }
    let taken: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM admin_users WHERE lower(username) = lower(?))",
    )
    .bind(username)
    .fetch_one(db)
    .await?;
    Ok(if taken { Added::Taken } else { Added::Full })
}

/// What [remove_parent] did.
#[derive(Debug, PartialEq, Eq)]
pub enum Removed {
    Done,
    /// No such account, or it was removed already.
    Gone,
    /// It would leave no ready account: add another parent first.
    LastReady,
    /// The actor's session ended (or it isn't ready): nothing was written.
    SignedOut,
}

/// The soft remove (§4.2), also Withdraw's: the row and its username stay, its password, 2FA,
/// sessions and push subscriptions go, in one transaction. Never the last ready account - the
/// guard counts the ready accounts other than the target, so removing a waiting one is always
/// allowed while the actor is ready. Why nothing happened is read after the statement
/// (qa-62-revision-check N3).
pub async fn remove_parent(
    db: &sqlx::SqlitePool,
    actor: &AdminUser,
    target: i64,
) -> Result<Removed, sqlx::Error> {
    let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
    let done = sqlx::query(concat!(
        "UPDATE admin_users SET removed_at = datetime('now'), password_hash = '!removed', \
         totp_secret = NULL, totp_enabled = 0, totp_last_step = NULL, reauth_wrong = 0, \
         invite_expires_at = NULL, session_epoch = session_epoch + 1 \
         WHERE id = ?1 AND removed_at IS NULL \
           AND EXISTS (SELECT 1 FROM admin_users WHERE id != ?1 AND removed_at IS NULL \
                       AND must_change_password = 0 AND totp_enabled = 1) \
           AND ",
        actor_ok!("2", "3")
    ))
    .bind(target)
    .bind(actor.id)
    .bind(actor.session_epoch)
    .execute(&mut *tx)
    .await?;
    if done.rows_affected() == 1 {
        sqlx::query("DELETE FROM push_subscriptions WHERE admin_id = ?")
            .bind(target)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        return Ok(Removed::Done);
    }
    tx.rollback().await?;
    if !actor_still_ok(db, actor).await? {
        return Ok(Removed::SignedOut);
    }
    let there: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM admin_users WHERE id = ? AND removed_at IS NULL)",
    )
    .bind(target)
    .fetch_one(db)
    .await?;
    Ok(if there {
        Removed::LastReady
    } else {
        Removed::Gone
    })
}

/// The actor condition on its own, for the answer after a statement wrote nothing.
async fn actor_still_ok(db: &sqlx::SqlitePool, actor: &AdminUser) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(concat!("SELECT ", actor_ok!("1", "2")))
        .bind(actor.id)
        .bind(actor.session_epoch)
        .fetch_one(db)
        .await
}

/// A row of Account's Parents group (§3).
pub struct ParentRow {
    pub id: i64,
    pub name: String,
    pub username: String,
    /// "pappa · Signed in 14:02 · from 10.0.0.3", "pappa · Waiting · until Fri 14:02", ...
    pub sub: String,
    /// Not ready (and not you): its action is "Withdraw…".
    pub waiting: bool,
}

/// Every account that isn't removed, oldest first, its state from "ready" (§3).
pub async fn rows(
    db: &sqlx::SqlitePool,
    me: &AdminUser,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> Result<Vec<ParentRow>, sqlx::Error> {
    let accounts: Vec<AdminUser> =
        sqlx::query_as("SELECT * FROM admin_users WHERE removed_at IS NULL ORDER BY id")
            .fetch_all(db)
            .await?;
    let mut rows = Vec::with_capacity(accounts.len());
    for account in accounts {
        let you = account.id == me.id;
        // The newest sign-in; for an account still onboarding, only one since its one-time
        // password was made (the 72 hours before its expiry), so the inviter sees it was used.
        let since = match (&account.invite_expires_at, account.ready()) {
            (Some(expires), false) => Some(expires.as_str()),
            _ => None,
        };
        let signed_in: Option<(String, Option<String>)> = if you {
            None
        } else {
            sqlx::query_as(
                "SELECT created_at, ip_address FROM security_events \
                 WHERE event_type = 'login_success' AND username = ?1 \
                   AND (?2 IS NULL OR created_at >= datetime(?2, '-72 hours')) \
                 ORDER BY id DESC LIMIT 1",
            )
            .bind(&account.username)
            .bind(since)
            .fetch_optional(db)
            .await?
        };
        // ("14:02", " from 10.0.0.3")
        let signed_in = signed_in.and_then(|(at, ip)| {
            let at = crate::display_time::parse_db_utc(&at)?;
            Some((
                zone.when(at, now),
                ip.map(|ip| format!(" from {ip}")).unwrap_or_default(),
            ))
        });
        let state = if you {
            "You".to_string()
        } else if account.ready() {
            match &signed_in {
                Some((when, from)) if from.is_empty() => format!("Signed in {when}"),
                Some((when, from)) => format!("Signed in {when} ·{from}"),
                None => "Not signed in yet".to_string(),
            }
        } else {
            match account
                .invite_expires_at
                .as_deref()
                .map(|e| (e, crate::display_time::parse_db_utc(e)))
            {
                Some((stored, Some(until))) if until > now => {
                    let mut line = format!("Waiting · until {}", zone.until_db(stored, now));
                    if let Some((when, from)) = &signed_in {
                        line.push_str(&format!(" · signed in {when}{from}"));
                    }
                    line
                }
                Some(_) => "One-time password expired".to_string(),
                None => "Waiting for sign-in".to_string(),
            }
        };
        rows.push(ParentRow {
            id: account.id,
            name: account.name().to_string(),
            sub: format!("{} · {state}", account.username),
            username: account.username.clone(),
            waiting: !you && !account.ready(),
        });
    }
    Ok(rows)
}
