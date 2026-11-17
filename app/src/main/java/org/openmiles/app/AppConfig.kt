package org.openmiles.app

/**
 * Settings you may want to change before sharing the app.
 */
object AppConfig {
    /**
     * Where the "Support the developer" button goes (GitHub Sponsors, Ko-fi, Buy Me a Coffee, etc.).
     * Leave it empty to hide the button.
     */
    const val DONATE_URL = "https://www.paypal.com/paypalme/joshuasloan"

    /** Link to the source code, shown in Settings. Leave empty to hide. */
    const val SOURCE_URL = "https://github.com/sloantech/openmiles"

    /** The page with the newest version to download. Opened in the browser, never by the app. */
    /** Plain-language privacy policy and disclaimer. Opened in the browser, never by the app. */
    const val PRIVACY_URL = "https://sloantech.github.io/OpenMiles/privacy.html"

    const val RELEASES_URL = "https://github.com/sloantech/OpenMiles/releases/latest"
}
