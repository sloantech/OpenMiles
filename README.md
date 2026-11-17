# OpenMiles

A free, open source mileage tracker for Android. No ads, no account, no subscription.
Your drives stay on your phone.

**Status: beta.** Automatic tests check the mileage math, IRS rate dates, CSV export, GPS
filtering and drive detection rules on every build. Real-world driving tests are in progress,
so compare your first weeks of drives against another tracker or your odometer.

**[Download the newest version](https://github.com/sloantech/OpenMiles/releases/latest)** ·
**[What changed in each version](CHANGELOG.md)**

## What it does now

- Start and stop a drive yourself, or turn on **automatic drive detection** (optional, off by default).
- Records the route with GPS and adds up the miles. A drive in progress is saved every 30 seconds.
- Swipe a drive right for Business or left for Personal.
- Tap a drive to pick any category (Business, Personal, Medical, Charity, or your own) and add notes.
- Mileage value uses IRS rates by date (2025 and 2026 rates are built in, including the July 2026 change).
  You can add new rates in Settings.
- Reports for any date range (this month, last quarter, a custom range) with totals by category
  and a CSV spreadsheet export.
- Add a drive by hand when the app was off, or import past drives from Google Maps Timeline.
  Every drive shows where it came from, in the app and in exports.
- A private detection log in Settings shows why each drive started, ended, or was not kept.
- The version number is shown in Settings, under About.

## What comes next

- Import drives from MileIQ (waiting on a sample export file)
- Frequent places, round-trip grouping, auto-classify rules, work hours
- PDF reports, map view, backup and restore

## How to install it on your phone

1. On your Android phone, open the [newest release](https://github.com/sloantech/OpenMiles/releases/latest).
2. Under **Assets**, tap the file named like `OpenMiles-v0.3.0.apk`.
3. Open the download and tap **Install**. Android will ask you to allow installs from your
   browser or Files app. That is normal for apps outside the Play Store.

New versions install over the old one. Your drives and settings are kept. When your copy is
about two months old, the app shows a small note with a button that opens this download page.


## Changing the name, support link, or app ID

- **App name:** `app/src/main/res/values/strings.xml` (one line).
- **Donate and source links:** `app/src/main/java/org/openmiles/app/AppConfig.kt`.
- **App ID** (`org.openmiles.app`) is set in `app/build.gradle.kts`. Pick the final one before publishing anywhere.
  It cannot be changed after you publish to Google Play.

## Privacy and safety

- The app has **no internet permission**. It cannot send your drives anywhere. The build fails if that ever changes.
- Drives are stored only in the app's private storage on your phone.
- Location is used only while a drive is recording, and a notification is always showing.
- **Automatic drive detection is optional and off by default.** Turn it on in Settings. Only then
  does the app ask for "Allow all the time" location and Physical activity, which it needs to
  notice a drive while the app is closed. GPS still only runs while a drive is recording.
  Turning it off stops everything in the background.
- The spreadsheet export is only shared when you tap Export, and only to the app you pick.

## Known limits

- Automatic detection is new and needs real-world tuning. It may start a little late or miss
  very short drives. Some phones (Samsung especially) block background apps: set OpenMiles to
  Unrestricted battery if drives are missed.
- A drive in progress is saved every 30 seconds, so if Android closes the app, at most the last
  30 seconds are lost.
- No map yet.
- No backup. Auto-backup to Google is turned off on purpose so drives never leave the phone.
- GPS distance is a straight-line sum between points, so it can read a little short on winding roads. Compare a few drives with your car's trip meter.
- Drives shorter than about 50 meters are thrown away on purpose.
- The debug build is for testing. A signed release build comes later.

## Fonts

OpenMiles bundles the Inter and Space Grotesk fonts (SIL Open Font License 1.1). See
`third_party/fonts/` for details and license texts.

## License

OpenMiles is free software under the **GNU General Public License v3.0** (see `LICENSE`).
You can use it, study it, change it, and share it. If you share a changed version, you must
share its source code under the same license.

**A request:** please don't sell this app. It was built so people don't have to pay a
monthly fee for mileage tracking. If you find it useful, you can support the developer
with a donation instead: [paypal.me/joshuasloan](https://paypal.me/joshuasloan). (The license does allow selling, but it requires that buyers get
the full source code too.)

Please also give your fork a different name, so people don't confuse it with this project.
