-- Security review M2: backups made before 0.24.0 hold the admin sessions of their time
-- (tower_sessions). A new epoch ends every session from before this update, so a session id taken
-- from an older backup zip, the live mirror or a USB copy signs no one in. Everyone signs in once
-- after the update; backups from now on hold no sessions at all.
UPDATE admin_users SET session_epoch = session_epoch + 1;
