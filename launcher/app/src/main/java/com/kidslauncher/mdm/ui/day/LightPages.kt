package com.kidslauncher.mdm.ui.day

/*
 * The light twins of Kalender's three pages (design 78 §12.2 R2): the same page under a manifest entry of its own,
 * identical to the night class's but for the theme - `KidPageLightTheme`, the paper as the window's ground - so the
 * starting window Android draws from that entry is in the look. Empty by rule: the class only says which look the
 * page is. `PageKind` (ui/look) pairs them with the night classes and is the only place that names them.
 */

class LightDayActivity : DayActivity()

class LightEventActivity : EventActivity()

class LightEventEditActivity : EventEditActivity()
