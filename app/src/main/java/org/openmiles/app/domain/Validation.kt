package org.openmiles.app.domain

import java.net.URI
import java.time.LocalDate

/** Input checks in one place, so the screens and the data layer agree and tests can cover them. */
object Validation {
    /** A rate above $10 per mile is almost certainly a typo, like 76 instead of 0.76. */
    const val MAX_RATE = 10.0

    fun isValidRate(dollarsPerMile: Double): Boolean =
        dollarsPerMile.isFinite() && dollarsPerMile >= 0.0 && dollarsPerMile <= MAX_RATE

    /** Reads a YYYY-MM-DD date, or returns null if it is not a real date. */
    fun parseDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

    /**
     * True only for plain https web links with a real host and no embedded login.
     * Anything else (http, javascript:, intent:, file:, content:) is never opened.
     */
    fun isSafeWebLink(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }
}
