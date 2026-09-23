# Cube Time Tracker

A physical cube (or any object with one NFC tag per face) that starts and
stops project timers when you tap it on your phone. No account, no server,
no data ever leaves your device.

## How it works

1. Each face of the cube has an NFC tag (recycled ones work too: filament
   spool chips, old cards, decommissioned badges — as long as the phone
   can read its UID).
2. The first time an unknown tag is scanned, the app asks which project to
   associate it with. Nothing is ever written to the tag: only its
   factory-set identifier (UID) is read.
3. On later scans, tapping the cube starts the timer for the project
   linked to that face, automatically closing any timer already running
   on a different project. Tapping the same face again stops the timer.
4. All data (projects, sessions, tag mappings) stays in a local SQLite
   database (via Room) inside the app. CSV export is manual and always
   shared at the user's explicit choice (email, personal cloud drive, etc.).

## Project status

Working initial skeleton:
- Data model (Project, TagMapping, TimeSession) with Room
- NFC reading via foreground dispatch (UID only, no writing)
- Start/stop/switch toggle logic between projects
- Local CSV export
- Automatic build via GitHub Actions (see `.github/workflows/build.yml`)

Still to do (see Roadmap):
- Project management and tag-assignment UI (setup screen)
- Reports/statistics screen
- 3D-printable cube STL file
- iOS guide via Apple Shortcuts

## Where to get the APK

Every push to `main` automatically builds a debug APK via GitHub Actions.
Go to the repository's "Actions" tab, open the latest run of the
"Build APK" workflow, scroll to the "Artifacts" section at the bottom of
the run summary page, and download `cube-time-tracker-debug-apk`. It's a
zip file containing `app-debug.apk` — unzip it, transfer the APK to your
Android phone, and install it (you'll need to allow "install from unknown
sources" for the app you use to open it).

Note: GitHub Actions artifacts require being signed in to GitHub to
download, and expire after 90 days by default. Once the app is stable, a
tagged GitHub Release will be added so anyone can download a permanent APK
without needing a GitHub account.

## Tech stack

- Kotlin + Jetpack Compose
- Room (local persistence)
- Android NFC API (`NfcAdapter`, foreground dispatch)
- No network dependency: `usesCleartextTraffic="false"`, no Internet
  permission in the manifest

## Suggested hardware

- 3D-printed cube (STL files in `/hardware`, coming soon)
- 6 NFC tags of any kind (new NTAG213/215 tags are the simplest choice,
  but any 13.56MHz tag the phone can read will work)

## License

Code: MIT. Hardware design (once published): CC-BY-SA.

## Privacy by design

This project has no backend, collects no telemetry, and requires no
account. All data stays on the user's device.
