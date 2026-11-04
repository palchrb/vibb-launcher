-- Security review S-L10's fix round (launcher round L4b): the status report's `tailnet_joined` - the launcher's
-- `tsnet_node_joined` mark, i.e. the phone has been on the tailnet and starts tsnet from its stored node. Device ›
-- Advanced › Connection shows "Leave Tailnet…" only while the last report says true; NULL (an older launcher) hides it.
ALTER TABLE device_status ADD COLUMN tailnet_joined INTEGER;
