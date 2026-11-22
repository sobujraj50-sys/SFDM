# SFDM — SF Download Manager

A fast, multi-connection download manager for Android (Kotlin + Jetpack Compose).

## Milestone 1 (this version)
- Multi-connection download engine (1–32 connections) with **dynamic segmentation**: a connection that finishes takes half of the largest remaining piece.
- **Resume** after pause, app kill, reboot or network loss (segment progress saved in Room).
- Automatic retry with backoff; link-expired / server errors shown clearly.
- **Network modes** (default: Auto — uses whatever network is connected):
  - Auto · Wi-Fi only · Mobile data only · Boost (Wi-Fi + mobile data at the same time)
  - Set a default in Settings, or per download under *Advanced*, or change it on a running download.
  - Per-download Wi-Fi vs mobile-data usage.
- Foreground service with progress notification, "Pause all" action, completion notifications.
- Files saved to `Download/SFDM` (MediaStore on Android 10+).
- Add links by pasting (clipboard auto-fill), **Share to SFDM** from any app, or opening http(s) file links.

## Editions (product flavors)
| Flavor | Use |
|---|---|
| `full` | Self-distributed APK — all features |
| `play` | Google Play edition — Play-restricted features disabled (`BuildConfig.PLAY_EDITION`) |

## Build
Every push to `main` builds APKs with GitHub Actions → **Actions → latest run → Artifacts** (`SFDM-full`).

Locally: `./gradlew assembleFullDebug` (JDK 17, Android SDK 35).
