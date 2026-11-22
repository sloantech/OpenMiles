# Changelog

What changed in each version of OpenMiles. The newest version is at the top.
Each version's installable file is on the [Releases page](https://github.com/sloantech/OpenMiles/releases).

## [0.14.0] - 2026-11-22

### Added
- A Notifications section in Settings, with every kind of notification in one place.
- The "Business or personal?" notification now pops up at the top of the screen when a drive
  ends, so you can classify it in one tap without opening the app. It is on by default once
  automatic drive detection is on, like other mileage apps. You can turn it off. If you already
  turned it on or off yourself, your choice is kept.
- "Show a test pop-up" in Settings, to see what it looks like and check your phone shows it.
- Weekly summary: miles, business miles and value for the past 7 days, plus how many drives still
  need a category. Pick the day and time. It stays on Sundays at 6 PM unless you change it.
- Monthly summary on the 1st at 9 AM, with last month's totals. Optional and off by default.
- "Drive classified by a rule": a quiet note with no sound or pop-up when work hours or a place
  picked the category. Optional and off by default.
- The count of drives waiting for a category rides along on notifications, so phones that show
  a number on the app icon show it for OpenMiles.
- Each kind of notification has its own entry in the phone's notification settings, so you can
  change sound or pop-ups for one without touching the others.

### Changed
- The "Ask business or personal" switch moved from Spoken updates to the new Notifications section.
- Classifying a drive in the app now clears its "Business or personal?" notification.
- The weekly summary keeps its time when clocks change for daylight saving time.

## [0.13.1] - 2026-11-20

### Fixed
- "Open in Maps" could do nothing on some phones. It now opens the Google Maps app directly. If
  Google Maps is missing or turned off, you get a list of apps to choose from instead.
- Each "Open in Maps" tap is written to the detection log, so if it ever fails, the log says why.

## [0.13.0] - 2026-11-20

### Added
- A map of each drive. Tap a drive to see the route it took, with a green dot where it started
  and a yellow dot where it ended. The map is drawn on your phone from the drive's saved GPS
  points, so OpenMiles still never goes online. There are no street names on it for the same
  reason.
- "Open in Maps" under the map shows the drive on real streets in Google Maps. It only happens
  when you tap it. Google Maps goes online to do that. OpenMiles does not.
- Drives imported from Google Timeline show a dashed straight line, because Timeline only saves
  where they started and ended. Drives added by hand or imported from MileIQ have no route,
  and the app says so.
- Start drives with your car's Bluetooth (Settings, Automatic drive detection, Car Bluetooth).
  Pick your car from the devices already paired with your phone. A drive starts the moment your
  phone connects and ends a minute after it disconnects, so a short Bluetooth drop doesn't split
  a drive. Motion detection still works as a backup, for example in a rental. Optional and off
  by default. It needs "Nearby devices" permission and Unrestricted battery use.
- Spoken updates (Settings, Spoken updates): hear "Drive started" and "Drive saved, 12.4 miles".
  It uses your phone's own voice, and only one that works offline. Music turns down while it
  talks. An automatic drive is only announced once it goes faster than walking speed.
  Optional and off by default.
- "Ask business or personal when a drive ends": a notification with Business and Personal
  buttons when a drive is saved without a category. One tap classifies it, without opening the
  app. Optional and off by default.
- The detection log shows car Bluetooth connects and disconnects, and why the voice stayed
  quiet if it did.

## [0.12.0] - 2026-11-17

### Added
- Import from MileIQ. Download your MileIQ report as CSV (from a report email or the MileIQ
  dashboard) and choose it in Settings, under Import drives. You see a preview first. Drives keep
  MileIQ's category (Business, Personal, Medical, Charity), and the start and stop places,
  purpose, vehicle, notes, parking and tolls are kept too. Drives already in OpenMiles are skipped.
  Imported drives are marked "MileIQ import" in reports.
- Icons on the swipe colors: a briefcase for Business, a house for Personal.
- A count of drives to classify on the Drives tab.
- The month card shows what your unclassified drives would be worth if they were business.

## [0.11.2] - 2026-11-17

### Added
- A plain-language privacy policy and disclaimer on the project website, linked from the app
  (Settings, About), the README and the website footer.
- A one-time "Before you start" note when the app first opens: free beta software provided as
  is, estimates not tax advice, keep backups, and don't use your phone while driving.
- A short disclaimer under Settings, About, and in the README.

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
