# Changelog

What changed in each version of OpenMiles. The newest version is at the top.
Each version's installable file is on the [Releases page](https://github.com/sloantech/OpenMiles/releases).

## [0.3.0] - 2026-11-17

### Added
- The version number now shows in Settings, under About.
- Each version is published on the GitHub Releases page with its version in the file name,
  for example `OpenMiles-v0.3.0.apk`. No more digging through the Actions tab.
- This changelog.

## [0.2.0] - 2026-11-16

### Added
- Optional automatic drive detection (Settings). Off by default. Uses Android's vehicle sensor
  and a 150 m circle around where you parked. Asks for "Allow all the time" location and
  Physical activity only when you turn it on.
- A private detection log in Settings (view, share, clear). Times and events only, no locations.
- A drive in progress is saved every 30 seconds, so if Android closes the app mid-drive the
  drive is recovered, without duplicates.
- A "No GPS signal" note on the recording card when GPS drops out.

### Fixed
- A lost GPS signal (tunnel, garage) no longer ends an automatic drive early.
- If only "Approximate" location was allowed, every drive was silently thrown away. The app
  now asks for Precise location instead.

### Security
- Stricter input checks for rates, dates and links.
- Old spreadsheet exports are cleared from the phone.
- Every build now scans all libraries for known vulnerabilities and fails on any finding.

## [0.1.0] - 2026-11-15

### Added
- First version: start and stop drives, swipe to classify, custom categories, notes.
- IRS mileage rates by date, with 2025 and 2026 rates built in.
- Reports by year or any date range, with CSV export.
- Optional donation and source code links in Settings.
- Signed release builds that update in place.
