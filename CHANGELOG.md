# Changelog

All notable changes to TapOver are listed here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [1.0.1] - 2026-11-26

### Changed
- The Sponsor button on the main screen now opens https://kopiascsaba.hu/sponsor, which lists
  every way to support development, instead of GitHub Sponsors only.
- README and `FUNDING.yml` list all support options: GitHub Sponsors, Liberapay, Buy Me a Coffee
  and Stripe (one-time or monthly).

## [1.0.0] - 2026-11-12

First public release.

### Added
- Presets: each one ties a target app (optionally specific screens of it) to an overlay
  and a tap action. As many presets as you like.
- Overlay look: circle, rounded square or anything in between, with adjustable size,
  corner radius and position. Content can be a solid color, a built-in icon, or a live
  thumbnail of the newest photo or video in a folder.
- Tap actions: open the newest photo or video in a folder (in the default viewer or an
  app you pick), or launch any installed app.
- Draggable on-screen live preview in the preset editor; edits save automatically.
- Portrait and landscape: one position that stays physically fixed when the phone
  rotates, or a separate position for each orientation.
- Event-driven foreground detection through an accessibility service. The overlay shows
  only while the target app is in front and hides when the notification shade is pulled
  down.
- Per-preset "show on lock screen" option. While locked, the overlay shows a padlock
  instead of your photo, and no media is read.
- Google Camera template, also covering the camera opened from the lock screen.
- Step-by-step guide for Android 13+ "restricted settings" when TapOver is installed
  from an APK file.

[1.0.1]: ../../releases/tag/v1.0.1
[1.0.0]: ../../releases/tag/v1.0.0
