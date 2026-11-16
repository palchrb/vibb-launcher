package com.kidslauncher.mdm.calls

/*
 * The light twin of the phone book (design 78 §12.2 R2; its own task since 2026-11-16): the same page under a manifest
 * entry of its own, identical to the night class's but for the theme - `KidPageLightTheme`, the paper as the window's
 * ground - so the starting window Android draws from that entry is in the look. Empty by rule: the class only says
 * which look the page is. `PageKind` (ui/look) pairs it with the night class and is the only place that names it.
 */

class LightPhoneBookActivity : PhoneBookActivity()
