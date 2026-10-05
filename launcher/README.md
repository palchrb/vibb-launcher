<!-- Shields from shields.io -->
[![][shield-license]][license]

# Kids Launcher

Kids Launcher is an Android home screen for a kid's phone that doubles as a
[Device Owner](https://developer.android.com/work/dpc/build-dpc) parental-control
agent. It pairs with [`kid-phone-server`][server-repo], a self-hosted admin web app a
parent uses to manage allowlisted apps, a bedtime/screen-time schedule, kiosk (lock-task)
mode, WiFi/Bluetooth restrictions, and an offline override PIN for when a phone can't
reach the server at all.

The home screen itself is intentionally minimal: date, time, and a wallpaper. Swiping up
opens a list of allowed apps.

## Features

- **Enrollment** against a [`kid-phone-server`][server-repo] instance via a short,
  one-shot code entered in Settings - no QR scanning required.
- **App allowlist** - suspends/hides everything not explicitly allowed, set from the
  server's admin UI.
- **Kiosk (lock-task) mode**, with per-device control over which system chrome stays
  available while pinned (status bar, notifications, home button, recents, power menu,
  lock screen).
- **Bedtime / screen-time schedule**, enforced even offline against the device's own
  clock.
- **WiFi / Bluetooth restrictions** (open / restricted / disabled).
- **Offline override PIN** - a parent-set failsafe, verified fully offline (PBKDF2
  against a locally cached hash), that temporarily lifts every restriction if the phone
  ever can't reach the server at all.
- **Settings is PIN-gated** behind the same code, so a kid can't tamper with enrollment
  or sync - plus a manual "pause all restrictions" kill-switch as an emergency escape
  hatch.
- **Silent self-update** - the launcher can be updated by the server without any user
  interaction, via `PackageInstaller` (requires Device Owner).
- **App list**, showing whatever's currently allowed, with an admin-configurable
  hidden-apps list.

## License

Kids Launcher is licensed under the [GNU GPLv3 or later][license].

Its app-list screen originated in [Josia Pietsch's µLauncher][ulauncher-repo]
(itself descended from [Finn Glas's Launcher][original-repo]); that code remains under
its original [MIT terms][license-mit] as required. Everything else - the parental-control
system, the home screen, and the overall app - is original to this project.

---
  [server-repo]: https://github.com/siesta5787/kid-phone-server
  [original-repo]: https://github.com/finnmglas/Launcher
  [ulauncher-repo]: https://github.com/jrpie/launcher

<!-- Shields and Badges -->

  [shield-license]: https://img.shields.io/badge/license-GPLv3-007ec6?style=flat
  [license]: LICENSE
  [license-mit]: LICENSE-MIT-UPSTREAM
