# OpenMiles

A free, open source mileage tracker for Android. No ads, no account, no subscription.
Your drives stay on your phone.

**Status: beta.** Automatic tests check the mileage math, IRS rate dates, CSV export, GPS
filtering and drive detection rules on every build. Real-world driving tests are in progress,
so compare your first weeks of drives against another tracker or your odometer.

**[Download the newest version](https://github.com/sloantech/OpenMiles/releases/latest)** ·
**[Project website](https://sloantech.github.io/OpenMiles/)** ·
**[What changed in each version](CHANGELOG.md)**

## What it does now

- Start and stop a drive yourself, or turn on **automatic drive detection** (optional, off by default).
  You can pause it for an hour, a day, a week, or until you turn it back on.
- **Car Bluetooth start** (optional): a drive starts the moment your phone connects to your car
  and ends a minute after it disconnects. Motion detection still works as a backup.
- **Spoken updates** (optional): hear "Drive started" and "Drive saved, 12.4 miles", using your
  phone's own offline voice.
- **Business or personal?** (optional): a notification with one-tap Business and Personal
  buttons when a drive ends without a category.
- Records the route with GPS and adds up the miles. A drive in progress is saved every 30 seconds.
- Tap a drive to see a map of its route, drawn on your phone with no internet. "Open in Maps"
  shows it on real streets in Google Maps if you want.
- **Places with one tap:** when you keep starting or ending drives at the same spot, the app asks
  what it is. Tap Home, Work, Bank or another name. Drives then show "Home to Bank".
- Swipe a drive right for Business or left for Personal.
- Tap a drive to pick any category (Business, Personal, Medical, Charity, or your own) and add notes.
- Mileage value uses IRS rates by date (2025 and 2026 rates are built in, including the July 2026 change).
  You can add new rates in Settings.
- Reports for any date range (this month, last quarter, a custom range) with totals by category,
  a printable PDF mileage log, and a CSV spreadsheet export.
- Parking and tolls on each drive, and yearly odometer readings for IRS records.
- Auto-classify rules: work hours, and named places like Home or Office, give new drives a
  category automatically. Places can also ignore short moves, like in a parking lot.
- Edit, split or merge drives. Changed drives are marked "edited" in reports.
- Add a drive by hand when the app was off, or import past drives from Google Maps Timeline
  or a MileIQ CSV report (categories, places, purposes, parking and tolls come along).
  Every drive shows where it came from, in the app and in exports.
- Backup and restore: save one backup file wherever you like, and restore it on a new phone.
  Restoring only adds what is missing and never deletes anything.
- Light and dark looks that follow your phone, or pick one in Settings.
- A gentle weekly reminder when drives still need a category (can be turned off).
- A private detection log in Settings shows why each drive started, ended, or was not kept.
- The version number is shown in Settings, under About.

## Roadmap

Done in 0.13.0: drive maps, car Bluetooth start, spoken updates, and one-tap classify buttons.
Done in 0.14.0: one-tap place names, shown on drives and maps.

Next, one at a time:

- **Answer "Business or personal?" out loud.** Android blocks the microphone for apps running in
  the background, so a spoken answer can only work while OpenMiles is open on screen. Being
  checked before it is built.
- A check of your log before you export it (overlapping drives, business drives with no purpose,
  odometer gaps).
- One-tap return trip.
- Business names for places you've never named (like "Wells Fargo"), as an optional add-on app.
  Looking up a business needs the internet, so it would never be part of OpenMiles itself.
- More than one vehicle, each with its own log.
- Fuel log with miles per gallon.
- A Commute category, kept out of business totals.
- Route export as a GPX file.
- Kilometers and other countries' mileage rates.

## How to install it on your phone

1. On your Android phone, open the [newest release](https://github.com/sloantech/OpenMiles/releases/latest).
2. Under **Assets**, tap the file that ends in `.apk` (for example `OpenMiles-v0.11.0.apk`).
3. Open the download and tap **Install**. Android will ask you to allow installs from your
   browser or Files app. That is normal for apps outside the Play Store.

New versions install over the old one. Your drives and settings are kept. When your copy is
about two months old, the app shows a small note with a button that opens this download page.
If you would rather be told about updates automatically, the free open source app
[Obtainium](https://github.com/ImranR98/Obtainium) can watch this project's releases for you.


## Changing the name, support link, or app ID

- **App name:** `app/src/main/res/values/strings.xml` (one line).
- **Donate and source links:** `app/src/main/java/org/openmiles/app/AppConfig.kt`.
- **App ID** (`org.openmiles.app`) is set in `app/build.gradle.kts`. Pick the final one before publishing anywhere.
  It cannot be changed after you publish to Google Play.

## Privacy and safety

- The app has **no internet permission**. It cannot send your drives anywhere. Every build
  checks this and fails if it ever changes.
- Drives are stored only in the app's private storage on your phone. Android's automatic cloud
  backup is turned off on purpose. Use **Backup** in Settings to save a copy where you choose.
- Location is used only while a drive is recording, and a notification is always showing.
- **Automatic drive detection is optional and off by default.** Only when you turn it on does
  the app ask for "Allow all the time" location and Physical activity, which it needs to notice
  a drive while the app is closed. GPS still only runs while a drive is recording. Turning it
  off, or pausing it, stops new drives from starting on their own.
- Exports (PDF, spreadsheet, backup, detection log) leave the phone only when you tap them,
  and only to the app or place you pick.
- A Google Timeline file or MileIQ report you import is read on the phone. Only the drives are kept.
- The drive map is drawn on the phone. Only if you tap "Open in Maps" does the drive's start,
  end and a few points along the way go to Google Maps, which then goes online.
- **Car Bluetooth start is optional and off by default.** When you turn it on, the app asks for
  "Nearby devices" so it can show the devices already paired with your phone. It never scans
  for devices and never connects to anything. Only the car you pick counts, and only the phone's
  own Bluetooth system can send the connect signal the app listens for.
- **Spoken updates** use the phone's text-to-speech, and only a voice installed on the phone.
  If no offline voice is installed, the app stays quiet instead of using an online one.
- Every build also runs security checks: Android's security lint rules, and a scan of every
  library against a public database of known vulnerabilities.

## Known limits

- **Beta.** Automatic detection needs real-world tuning. It may start a little late, miss very
  short drives, or split a trip at a stop longer than 5 minutes (use Merge to join them).
  Some phones (Samsung especially) block background apps: set OpenMiles to Unrestricted
  battery if drives are missed.
- If GPS is lost (a tunnel or garage), the gap is counted as a straight line when the signal
  returns, so a winding road can read a little short. Compare a few drives with your car's
  trip meter.
- A drive in progress is saved every 30 seconds, so if Android closes the app, at most the last
  30 seconds are lost.
- Drives shorter than about 50 meters (a quarter mile for automatic drives) are not kept.
- The drive map shows the route line only, with no streets, and places are named by you, not
  looked up, because street maps and addresses would need the internet. "Open in Maps" hands the
  route to Google Maps when you want streets. Maps picks its own roads between a few points of
  your drive, so its miles can differ a little.
- Car Bluetooth start needs OpenMiles' battery use set to Unrestricted. Otherwise Android won't
  let a Bluetooth signal start recording while the app is closed, and motion detection takes over.
- Android only for now.

## Disclaimer

- **As is, no warranty.** OpenMiles is free, beta software, and you use it at your own risk.
  Sections 15 and 16 of the GPL-3.0 license say the same, and the license controls.
- **Not tax, legal or financial advice.** Mileage, values and reports are estimates. Check them
  against your odometer and talk to a tax professional. IRS rates can change; check
  [the IRS standard mileage rates page](https://www.irs.gov/tax-professionals/standard-mileage-rates).
- **Your records are your responsibility.** Drives are stored only on your phone. Make backups.
- **Drive safely.** Don't use your phone while driving. Set things up before you go.
- **Not affiliated** with any other mileage tracking app. Other names belong to their owners.

Full [privacy policy and disclaimer](https://sloantech.github.io/OpenMiles/privacy.html).

## Fonts

OpenMiles bundles the Inter and Space Grotesk fonts (SIL Open Font License 1.1). See
`third_party/fonts/` for details and license texts.

## License

OpenMiles is free software under the **GNU General Public License v3.0** (see `LICENSE`).
You can use it, study it, change it, and share it. If you share a changed version, you must
share its source code under the same license.

**A request:** please don't sell this app. It was built so people don't have to pay a
monthly fee for mileage tracking. If you find it useful, you can support the developer
with a donation instead: [paypal.me/joshuasloan](https://www.paypal.com/paypalme/joshuasloan). (The license does allow selling, but it requires that buyers get
the full source code too.)

Please also give your fork a different name, so people don't confuse it with this project.
