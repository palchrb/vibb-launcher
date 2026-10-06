-- The launcher's applicationId changed from com.kidslauncher.mdm to me.vibb.launcher (2026-10-06;
-- the debug build from com.kidslauncher.mdm.debug to me.vibb.launcher.debug). The launcher only
-- downloads its own catalog row when the row's package name is blank or equals its own package
-- (MdmSyncWorker), so rename the launcher row; any other row is left alone. A phone still running
-- the old package can't update into the renamed app anyway - Android treats it as a different app,
-- and the old launcher refuses an APK of another package (pendingApkCheck NOT_OURS) - it has to be
-- provisioned again (DEPLOY.md).
UPDATE tracked_apps SET package_name = 'me.vibb.launcher'
WHERE is_launcher = 1 AND package_name = 'com.kidslauncher.mdm';
UPDATE tracked_apps SET package_name = 'me.vibb.launcher.debug'
WHERE is_launcher = 1 AND package_name = 'com.kidslauncher.mdm.debug';
