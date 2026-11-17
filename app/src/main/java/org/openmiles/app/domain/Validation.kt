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

    /** Hand-entered drives: more than zero and at most 1,000 miles. */
    const val MAX_MANUAL_MILES = 1_000.0

    fun isValidManualMiles(miles: Double?): Boolean =
        miles != null && miles.isFinite() && miles > 0.0 && miles <= MAX_MANUAL_MILES
}

/** Decides when to suggest checking for a newer version. Works fully offline. */
object UpdateReminder {
    const val DAY_MS = 86_400_000L
    /** Suggest a check once this copy is about two months old. */
    const val AFTER_DAYS = 60L
    /** "Not now" waits this long before asking again. */
    const val SNOOZE_DAYS = 30L
    /** After tapping Check, wait a week before asking again. */
    const val AFTER_CHECK_DAYS = 7L

    fun shouldShow(nowMs: Long, builtMs: Long, snoozedUntilMs: Long): Boolean =
        builtMs > 0 && nowMs - builtMs >= AFTER_DAYS * DAY_MS && nowMs >= snoozedUntilMs

    /** Whole months since this copy was built, for the reminder text. */
    fun monthsOld(nowMs: Long, builtMs: Long): Long = ((nowMs - builtMs) / (30 * DAY_MS)).coerceAtLeast(0)
}
