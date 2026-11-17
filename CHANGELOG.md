# Changelog

What changed in each version of OpenMiles. The newest version is at the top.
Each version's installable file is on the [Releases page](https://github.com/sloantech/OpenMiles/releases).

## [0.11.1] - 2026-11-17

### Changed
- The Donate button now opens PayPal directly instead of going through a redirect.
- The project website is live at https://sloantech.github.io/OpenMiles/. Every build now checks
  that the website describes the current version, so it is updated with each release.

## [0.11.0] - 2026-11-17

### Added
- A weekly reminder on Sunday evenings when drives still need a category. It only appears when
  there is something to classify, never sends anything off the phone, and can be turned off in
  Settings, under Weekly reminder.

## [0.10.0] - 2026-11-17

### Added
- PDF mileage log (Reports, "Export these dates as a PDF mileage log"): a summary with miles and
  value by type, business parking and tolls, and odometer totals for a whole year, then every
  drive with date, times, miles, category, purpose, value and where it came from.
- Parking and tolls on each drive (tap a drive). Business totals show in Reports, the PDF and
  two new spreadsheet columns.
- Odometer readings for January 1 and December 31 of each year (Reports). The PDF shows total
  miles driven and the business share.
- Optional name and vehicle printed at the top of the PDF.
- Backups now include parking, tolls and odometer readings.

### Changed
- The spreadsheet export has new Parking and Tolls columns before Notes.
- The database has new fields for parking and tolls and a table for odometer readings.
  Existing drives are kept unchanged.

## [0.9.0] - 2026-11-17

### Added
- Pause automatic detection for 1 hour, the rest of today, 24 hours, 1 week, or until you turn
  it back on (Settings, Automatic drive detection). Start drive still works while paused, and
  a drive already recording still ends normally.
- The header shows "Auto-detect paused" while paused, and the detection log records pauses
  and any signals ignored because of one.

## [0.8.0] - 2026-11-17

### Added
- Auto-classify rules (Settings, Auto-classify rules), so you swipe less:
  - Work hours: drives that start during your work hours on your work days get a category,
    such as Business. Overnight shifts work too.
  - Places: name places like Home, Office or a client, either where you are now or from the
    start or end of any drive. Drives that start or end at a place get its category.
    Where a drive ends wins over where it started, and place rules win over work hours.
  - Ignore short moves: mark a place so automatic drives that start and end inside it, like
    moving the car in a parking lot, are not recorded.
- "Apply rules to unclassified drives" runs your rules over drives you have not classified yet,
  including Google Timeline imports. Rules never change a category you picked yourself.
- New drives, recovered drives, Timeline imports and drives added by hand all use the rules.
- Backups now include places and the work-hours rule.

### Changed
- The database has a new table for places. Existing drives are kept unchanged.

## [0.7.0] - 2026-11-17

### Added
- Edit a drive's date, times or miles (tap a drive, then Edit).
- Split a drive in two at a time you pick. Miles are divided along the GPS route when there is
  one, otherwise by time.
- Merge a drive with the one after it, for when a stop split one trip in two.
- Drives changed by hand are marked "edited" on the drive card and in the spreadsheet's Source
  column, so your log stays honest.
- A drive can now end after midnight when added or edited by hand.

### Changed
- The database records whether a drive was edited. Existing drives are kept unchanged.

## [0.6.0] - 2026-11-17

### Added
- Backup and restore (Settings, Backup). A backup is one file you save where you like, such as
  Google Drive, email or your computer. Each backup is read back and checked right after saving.
- Restoring only adds what is missing (drives, categories and rates). Nothing on the phone is
  deleted or changed, and a restore either finishes completely or changes nothing.
- A gentle reminder on the Drives tab when you have not made a backup in 30 days.

## [0.5.0] - 2026-11-17

### Changed
- New "Highway" look: navy and amber colors, the Inter and Space Grotesk fonts, and a new app icon.
- The Drives tab now opens with a summary of this month (miles, drives, business value, and
  how many drives still need a category), a larger Start drive button, and drives grouped by day.
- Drive cards show big mileage numbers and a colored tag for each category.
- A live recording card shows the miles counting up in large type.

### Added
- Dark mode. It follows your phone's setting, or pick Light or Dark in Settings, under Appearance.
- Themed icon support on Android 13 and newer.

## [0.4.0] - 2026-11-17

### Added
- Add a drive by hand, for times the app was off. Marked "Added by hand".
- Import past drives from Google Maps Timeline (Settings, Import drives). The file is read on
  the phone only. You pick the years, short drives under a quarter mile can be skipped, and
  drives you already have are skipped automatically. Marked "Google Timeline".
- Every drive now shows where it came from (GPS, Added by hand, Google Timeline), including a
  new Source column in the spreadsheet export.
- A gentle note when this copy is about two months old, with a Check button that opens the
  download page in your browser. The app itself still never goes online.

### Changed
- The database now records each drive's source. Existing drives are kept and marked as GPS.
  A test on every build checks this upgrade.

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
