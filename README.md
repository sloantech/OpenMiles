# OpenMiles

A free, open source mileage tracker for Android. No ads, no account, no subscription.
Your drives stay on your phone.

**Status: Phase 1 (early test version).** It builds, and automatic tests check the mileage math,
the IRS rate dates, the CSV export, and the GPS distance filtering. It has not yet been
tested on a real phone while driving, so treat early drives as a test.

## What it does now

- Start and stop a drive. The app records the route with GPS and adds up the miles.
- Swipe a drive right for Business or left for Personal.
- Tap a drive to pick any category (Business, Personal, Medical, Charity, or your own) and add notes.
- Add your own categories under any of the four kinds.
- Mileage value uses IRS rates by date (2025 and 2026 rates are built in, including the July 2026 change).
  You can add new rates in Settings.
- Reports tab shows totals by category and exports a CSV spreadsheet for any year.

## What comes next

- Phase 3: automatic drive detection in the background
- Phase 4: frequent places, round-trip grouping, auto-classify rules, work hours
- Phase 5: PDF reports, map view, backup and restore

## How to get the app on your phone (no coding needed)

1. Create a free account at github.com and make a new repository named `OpenMiles`.
2. Upload every file and folder from this project into it (the "Add file" > "Upload files" button works).
3. Click the **Actions** tab. A build named "Build APK" starts by itself. It takes a few minutes.
4. When it shows a green check, click it and scroll to **Artifacts**. Download **OpenMiles-debug-apk**.
5. Unzip it and send `app-debug.apk` to your phone (email, Google Drive, or USB).
6. On the phone, open the file and tap Install. Android will ask you to allow installs from that app. That is normal for apps outside the Play Store.

If the build shows a red X, open it, copy the error text, and send it to Claude to fix.

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

## Known limits of Phase 1

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

## License

OpenMiles is free software under the **GNU General Public License v3.0** (see `LICENSE`).
You can use it, study it, change it, and share it. If you share a changed version, you must
share its source code under the same license.

**A request:** please don't sell this app. It was built so people don't have to pay a
monthly fee for mileage tracking. If you find it useful, you can support the developer
with a donation instead: [paypal.me/joshuasloan](https://paypal.me/joshuasloan). (The license does allow selling, but it requires that buyers get
the full source code too.)

Please also give your fork a different name, so people don't confuse it with this project.
