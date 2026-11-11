package com.kidslauncher.mdm.ui.messages

/*
 * The light twins of Meldinger's two own-task pages (design 78 §12.2 R2, step 2): the same page under a manifest entry
 * of its own, identical to the night class's but for the theme - `KidPageLightTheme`, the paper as the window's
 * ground - so the starting window Android draws from that entry is in the look. Empty by rule: the class only says
 * which look the page is. `PageKind` (ui/look) pairs them with the night classes and is the only place that names
 * them. The picture viewer has no twin: it is night in both looks (§2), one class on the night theme.
 */

class LightMessagesActivity : MessagesActivity()

class LightThreadActivity : ThreadActivity()
