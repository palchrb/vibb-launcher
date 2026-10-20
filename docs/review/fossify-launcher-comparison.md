# Fossify Launcher vs Vibb One - what is worth borrowing

Read-only comparison, 2026-10-20. Fossify = `Launcher-main/app/src/main/kotlin/org/fossify/home/` (GPL-3.0, so its
code may be adapted into our GPL directories with its notice, never into the MIT `ui/list/` or `apps/`).
Ours = `launcher/app/src/main/java/com/kidslauncher/mdm/`.

Short verdict: Fossify is a general-purpose launcher with a simpler engine than ours. It has pages, a dock,
folders, free placement and widgets, and it has less care for icons, ink, threading and accessibility. It is
better in a few small places: it re-renders on app updates, keeps a placeholder colour across restarts, handles
a Home press while already on Home, uses a DiffUtil adapter, and has a widget host view that swallows touches.
Most of its features (shortcuts, App info, uninstall, the notification-shade fling, widget binding and
configuration) are exactly what a locked kid phone must not have.

## 1. Loading and refreshing the app list

- **Fossify**: no `LauncherApps.Callback` and no package broadcasts. Every `onResume` runs
  `getAllAppLaunchers()` on a background thread (`MainActivity.kt:269-289, 596-619, 1082-1131`). That is
  `queryIntentActivities` for the own user only, plus `loadIcon` and `toBitmap` at the intrinsic size for every
  app, plus an average colour. It stores `AppLauncher` (label, activity, `thumbnailColor`) in Room (`AppsDatabase`)
  and holds the list in a `@Volatile` singleton (`helpers/IconCache.kt`). Hidden apps live in a Room table.
  There are no work profiles (`Process.myUserHandle()`).
- **Ours**: `Application.kt:56-150` registers a `LauncherApps.Callback`, including suspend and unsuspend,
  plus profile broadcasts. `loadApps()` (`:435`) runs `getApps()` (`Functions.kt:135`) on `Dispatchers.Default`
  over every profile. It posts to LiveData, and Home's `refreshApps` (`HomeActivity.kt:682`) filters and renders
  off the main thread. Nothing is persisted across restarts.
- **Verdict**: ours is better (event-driven, no work on every resume). Fossify's one useful habit is keeping a
  per-app colour across restarts (item 3 below).
- **Side findings, ours** (not from Fossify):
  - `loadApps()` starts a new uncoordinated coroutine per callback. A burst such as `onPackagesSuspended` from
    `AppEnforcer` and then `onPackagesUnsuspended` can finish out of order, and `postValue` lets the older list
    win. Fix: a conflated single-flight job (S).
  - `DetailedAppInfo(LauncherActivityInfo)` (`apps/DetailedAppInfo.kt:21-28`, MIT) calls `getBadgedIcon(0)`
    eagerly for every app on every reload. The drawer never shows icons. Making it lazy is a small MIT-side edit
    with the existing palchrb-modified precedent (S, low value).

## 2. Icon rendering and caching

- **Fossify**: `info.loadIcon(pm)` (or `getBadgedIcon(0)`, `extensions/Context.kt:41-61`) is turned into a
  bitmap and drawn as is. There is no shape normalisation, no monochrome or themed icons and no icon packs.
  The drawer uses Glide with a disk-cache strategy on an in-memory drawable (pointless) and a `thumbnailColor`
  placeholder (`adapters/LaunchersAdapter.kt:87-108`). Because every resume rebuilds everything, an updated
  app's icon shows at once.
- **Ours**: `KidAvatars.renderAppIcon` (`ui/home/KidAvatars.kt:171-190`) has three states (own logo, parent
  glyph, Vibb monochrome on one of six tones). It renders on `Dispatchers.Default` into an 8 MB `LruCache` keyed
  by `appIconKey` (`appdisplay/AppDisplay.kt:104`: app|size|density|parent choice).
- **Verdict**: ours is far better, with one gap. **The cache key has no app-version component and nothing evicts
  on `onPackageChanged`/`onPackageAdded`.** An app update that changes its icon (or adds a monochrome layer) keeps
  the old bitmap until LRU eviction or process death. Our device-owner process lives for days. Fossify gets this
  right by accident, because it has no cache.
- **Adopt**: `KidAvatars.evictPackage(pkg)`, using `iconCache.snapshot().keys` and the existing `packageOf(key)`,
  called from `Application.launcherAppsCallback.onPackageAdded/Changed/Removed` before `loadApps()`. **S.**

## 3. The home grid

- **Fossify**: one `HomeScreenGrid` (2,473 lines) draws everything on a Canvas
  (`HomeScreenGridDrawingArea.onDraw -> drawInto`). It has pages with an animated pager, a dock, folders, drag and
  drop, a widget resize frame and Room-persisted positions. Labels are a `StaticLayout` rebuilt **on every draw**
  (`HomeScreenGrid.kt:1900-1916`, maxLines 2, end ellipsis, always white with a 2 px black shadow), and
  `newDrawable().mutate()` runs per item per frame. It needs an `ExploreByTouchHelper` (`:1682-1793`) to be
  accessible at all. Cells are `(height / rows) x (width / cols)` (`fillCellSizes`, `:1510`). The long-press
  menu is a `PopupMenu` with App info, Uninstall, Remove, Rename and app shortcuts (`extensions/Activity.kt:83-176`).
- **Ours**: a `RecyclerView` with real views (`HomeGridAdapter`, `FadingRecyclerView`, `WholeWordTextView`
  labels, `PressFeedback`). The order is server and filter driven, with no kid reordering. Long-press offers
  rename and hide only.
- **Verdict**: ours is better for a kid phone, with real accessibility nodes, whole-word labels and no per-frame
  allocation. Pages, folders and kid drag-and-drop would add exactly the fiddly, attention-taking surface we
  avoid. The one thing Fossify's drawer does better is updates: it uses a `ListAdapter` with DiffUtil, stable
  ids and `itemAnimator = null` (`LaunchersAdapter.kt:30-44, 166-181`). Ours calls `notifyDataSetChanged()` on
  every `submit` (`HomeGridAdapter.kt:48-61`), including each debounced badge change. That rebinds every tile
  and re-runs every label fit.
- **Adopt**: DiffUtil (or stable ids plus a badge payload) in `HomeGridAdapter.submit`, with `itemAnimator = null`
  so nothing moves. **S-M.**

## 4. Wallpaper, ink and scrims

- **Fossify**: always draws on the system wallpaper. It reads `WallpaperColors.HINT_SUPPORTS_DARK_TEXT`
  (`extensions/WallpaperColors.kt`) and listens with `addOnColorsChangedListener` (`MainActivity.kt:187-217`),
  but only to flip the status and navigation bar icons. It also does a binder `getWallpaperColors` on every
  resume. Labels are always white with a shadow, and there is no scrim.
- **Ours**: we own the wallpaper (`DISALLOW_SET_WALLPAPER`) and compute the ink from the known fill or photo in
  linear light, with a scrim up to 0.45 to reach 4.5:1 and a shadow as fallback (`ui/wallpaper/WallpaperInk.kt`).
  The result drives labels, badge rings and bar icons. `WallpaperGround` draws our fill when the system
  wallpaper isn't ours.
- **Verdict**: ours is clearly better. `WallpaperColors` is a 3-colour hint with a boolean, too coarse for 4.5:1
  on small labels. A colours listener is not needed, because the wallpaper only changes through us. **Nothing to
  adopt.**

## 5. Gestures and back

- **Fossify**: one `GestureDetectorCompat` in `MainActivity.onTouchEvent` (`:386-492, 985-1080`):
  - The drawer is a view in the same window whose `y` **follows the finger** and snaps open or closed at 50 %
    of the screen. Fling up opens it.
  - Fling down expands the notification shade by reflection (`StatusBarManager.expandNotificationsPanel`,
    `EXPAND_STATUS_BAR`).
  - Fling left or right changes page, and a double tap on empty space calls `dpm.lockNow()` through a device
    admin.
  - Flings within 500 ms of a drag release are ignored.
  - Back closes search, then the drawer or widgets, then the resize frame, and otherwise does nothing.
  - `onNewIntent` closes the drawer, and **when Home is pressed while already on Home it skips to page 0**
    (`:219-243`, `FLAG_ACTIVITY_BROUGHT_TO_FRONT` check).
- **Ours**: one detector fed from `HomeActivity.dispatchTouchEvent` (`:361-378`). The pure `homeSwipe` uses
  DOWN-recorded `canScroll` values: up opens the drawer activity, left opens kid Settings. A swipe sends CANCEL
  to the views. Back on Home is a no-op callback (`:238`) with `enableOnBackInvokedCallback="true"`. **There is
  no `onNewIntent`**, so pressing Home while on a scrolled Home does nothing.
- **Verdict**: our detector is more robust. Fossify's finger-following drawer feels more physical, but it needs
  the drawer inside Home's window. That means rewriting the MIT `AppListActivity` path (L), so it is not worth it.
  The Home-press reset is cheap and calm.
- **Adopt**: `HomeActivity.onNewIntent`. When it was already resumed (Fossify's `wasJustPaused` idea, so the
  typed HOME starts from `HomeFront` and lock leave don't count) and the content is shown, smooth-scroll
  `homeGrid` to 0 and `homeContactsScroll` to its start. Close an open long-press menu (`closeOverlays`). **S.**
- **Don't copy**: the shade fling and double-tap lock.

## 6. Drawer / all apps

- **Fossify**: a grid with a search bar. Search uses `normalizeString` (accent-insensitive contains), and the
  IME action launches the first hit (`fragments/AllAppsFragment.kt:166-180, 252-270`). It has a
  `RecyclerViewFastScroller` with a title bubble, a pull-down to close when scrolled to the top
  (`onInterceptTouchEvent`), keyboard auto-hide on scroll, and a "no results" placeholder.
- **Ours**: the MIT µLauncher text list, coloured through `res/` only, with no search and no index. The kid has
  about 10-30 apps.
- **Verdict**: different needs. Search and a fast scroller are for 100+ apps, and a keyboard in the drawer is
  noise for a kid. If it is ever wanted, it must be done from outside `ui/list/` (MIT), which is awkward.
  **Nothing to adopt now.**

## 7. Widget host (for the parked display-only glance widget, PLAN.md:909)

- **Fossify**:
  - `MyAppWidgetHost(context, WIDGET_HOST_ID)` overrides `onCreateView` to return `MyAppWidgetHostView`.
  - `startListening()` runs in `onStart` and `stopListening()` in `onStop` inside a try (`MainActivity.kt:245-307`).
  - Binding: `allocateAppWidgetId`, then `bindAppWidgetIdIfAllowed`, and otherwise the system
    `ACTION_APPWIDGET_BIND` dialog (`MainActivity.kt:1287-1306`). It then calls
    `startAppWidgetConfigureActivityForResult` when the provider has a configure activity.
  - Initial size comes from `targetCellWidth/Height` on S+ with a dp formula fallback (`Context.kt:63-81`).
  - `deleteAppWidgetId` runs on remove or resize-out (`HomeScreenGrid.kt:273, 341`), and there is a drag
    resize frame (`MyAppWidgetResizeFrame`).
  - `MyAppWidgetHostView.onInterceptTouchEvent` returns `true` whenever `ignoreTouches` is set, so the widget's
    own views never see the touch (`views/MyAppWidgetHostView.kt`).
- **Reusable** (as patterns, written fresh or adapted with attribution in a GPL dir such as `lock/`):
  - The host subclass returning a host view that **always** intercepts touches. That is the "touch-consuming
    layer" our parked note wants, done in the view itself and not as an overlay.
  - `startListening`/`stopListening` tied to the activity's start and stop.
  - `deleteAppWidgetId` hygiene, so ids don't leak.
  - `targetCellWidth/Height` for sizing into `glanceFit`.
- **Risky, never copy**:
  - The `ACTION_APPWIDGET_BIND` dialog. It is Settings UI on the kid's screen; use `appwidget grantbind` at
    enrolment as planned.
  - `startAppWidgetConfigureActivityForResult`. It launches the provider app's activity, which is an escape
    from the lock and kiosk.
  - The resize frame and long-press menu (the kid edits the surface).
  - Letting `AppWidgetHostView` handle clicks at all. RemoteViews PendingIntents launch anything.
  - Fossify's `ExploreByTouchHelper` exposes widgets as clickable nodes. Ours must set
    `importantForAccessibility="noHideDescendants"` on the host view, or accessibility can click through the
    widget.

## 8. Other

- **App shortcuts** (`Activity.kt:122-160`, `getShortcuts` + `startShortcut`) and **pinned shortcuts**
  (`ACTION_CONFIRM_PIN_SHORTCUT`, `MainActivity.kt:506-554`, with a `Thread.sleep(2000)` hack). On a kid phone a
  shortcut is a deep link past our app-level rules (for example "New incognito tab", or Settings sub-pages).
  We don't handle `CONFIRM_PIN_SHORTCUT`, so `requestPinShortcut` simply fails. Keep it that way. **Don't copy.**
- **App info and Uninstall in the long-press menu** (`Activity.kt:57-81`). These open system Settings and are
  banned for us (`KidScreensEscapeTest`). **Side finding, ours**: `actions/AppAction.kt:52-66` (inherited) still
  shows "can't open" with OK, which calls `AppInfo.openSettings` (`ui/list/apps/ContextMenuActions.kt:29`,
  `startAppDetailsActivity`). It is only reachable on the fallback path (an app known with `INVALID_USER` and no
  launch intent), and lock task blocks Settings in the kiosk. Even so, it is a system-Settings door that
  `KidScreensEscapeTest` doesn't cover (it scans `ui/home`, `HomeActivity`, ... but not `actions/`). Worth a
  look: drop the OK action or show a plain message (S).
- **Lock via device admin** (`receivers/LockDeviceAdminReceiver.kt`, double tap calls `lockNow`). We are device
  owner and have our own PIN lock with careful dark lock-up paths (design 32). Don't add a gesture that turns the
  screen off.
- **Notification dots**: Fossify has none. Ours (`BadgeListenerService` with counts) is ahead.
- **Splash / launcher-icon colour aliases**: 18 `activity-alias` entries for icon colours. Irrelevant.
- **Accessibility**: Fossify's canvas needs a hand-written node tree (which also throws
  `IllegalArgumentException` for unknown ids). Our view-based grid and the cell's content descriptions are better.
- **Insets**: Fossify pads the grid bottom by `max(systemBars, systemGestures, navigationBars-ignoring-visibility)`
  (`HomeScreenGrid.kt:194-219`), so it stays out of the gesture zone even when the bar is hidden. Our
  `KidInsets.apply` and the grid's bottom padding could be checked against the gesture inset on the Jelly Star.
  This is an emulator check, not code.

## Ranked: adoptable ideas (max 6)

1. **Evict icons on package change** (S). Add `KidAvatars.evictPackage(pkg)` (`ui/home/KidAvatars.kt`, over
   `iconCache.snapshot()` with `packageOf`) and call it from `Application.launcherAppsCallback`
   `onPackageAdded/Changed/Removed` (`Application.kt:56-110`) before `loadApps()`. Today an updated app keeps
   its old icon on Home, the glance and the time-rule screen until the process dies. Fossify avoids this by
   re-rendering on each resume.
2. **Home press while on Home goes back to the top** (S). Add `HomeActivity.onNewIntent`: only when Home was
   already resumed (Fossify's `wasJustPaused`, `MainActivity.kt:219-243`) and the content is shown, smooth-scroll
   `homeGrid` to 0 and `homeContactsScroll` to its start, and `closeOverlays()`. Exclude `HomeFront`'s
   lock-leave and boot starts (they come while not resumed).
3. **DiffUtil in `HomeGridAdapter`** (S-M). Replace `notifyDataSetChanged()` (`HomeGridAdapter.kt:48-61`) with
   `ListAdapter`/DiffUtil, stable ids from the tile key, a badge-only payload and `itemAnimator = null`, as
   Fossify's `LaunchersAdapter.kt:30-44, 166-181` does. A badge change then rebinds one tile, not every tile
   with its `WholeWordTextView` fit.
4. **Coalesce `loadApps()`** (S). This is ours, noticed in the comparison: a conflated single-flight job in
   `Application.kt:435`, so a burst of suspend and unsuspend callbacks from `AppEnforcer` can't post an older
   list last.
5. **Persist each app's tone for the placeholder** (S). Store each app's tone in CE prefs (Fossify keeps
   `thumbnailColor` in Room, `models/AppLauncher.kt`, `LaunchersAdapter.kt:95-99`). After a process restart or
   the first unlock, Home's placeholder (`HomeGridAdapter` slate) is then the app's own tone, and the icon
   fades in on a matching disc. A disk bitmap cache of rendered icons would be the M variant.
6. **Display-only widget host view, for when the parked glance widget is revisited** (M). Adapt Fossify's
   `MyAppWidgetHost`/`MyAppWidgetHostView` pattern into `lock/`, with `onInterceptTouchEvent` always true,
   `importantForAccessibility = noHideDescendants` and start/stop listening on the glance's start and stop. Add
   `deleteAppWidgetId` hygiene and `targetCellWidth/Height` sizing. Never the bind dialog, configure activity or
   resize frame.

## Explicitly do not copy

- Fling down to expand the notification shade (`StatusBarManager` reflection, `EXPAND_STATUS_BAR`).
- App shortcuts in the long-press menu and `ACTION_CONFIRM_PIN_SHORTCUT` pinned shortcuts: deep links past our
  rules.
- App info and Uninstall menu items (system Settings, `ACTION_DELETE`).
- The widget bind dialog (`ACTION_APPWIDGET_BIND`), widget configure activities, widget click-through and the
  resize frame.
- The Canvas-drawn grid with `ExploreByTouchHelper`, pages, a dock, folders and kid drag-and-drop: more surface,
  worse accessibility, a `StaticLayout` per frame.
- Double tap to `lockNow()` via device admin.
- Drawer search with the keyboard and the IME launching the first hit, and the fast-scroll bubble: noise at
  10-30 apps, and it would have to touch the MIT `ui/list/`.
- `WallpaperColors` as the ink source: too coarse for 4.5:1 labels. Our computed ink is better.
- A Room DB for the app list. Our list is the server's and the filter's; nothing needs persisting except
  perhaps the tone (item 5).
