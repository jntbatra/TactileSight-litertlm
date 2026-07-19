package com.tactilesight.core

/**
 * A capture that failed for a reason the *user* can act on, carrying the
 * sentence to say instead of the generic apology.
 *
 * Most capture failures are opaque — a socket resets, a decode throws — and
 * "Sorry, I could not see that" is the honest answer. A few are not opaque at
 * all: the band answered, it simply had no picture to send. Telling someone
 * their band is not sending pictures points at the band; telling them "I could
 * not see that" points at nothing, and the natural response is to press again
 * against a fault that repeats.
 *
 * The distinction only exists where there is a *different* action to take. This
 * is not a general error-message channel, and every use of it should be a case
 * where a blind user would do something different on hearing it.
 */
class CaptureUnavailable(
    /** Spoken verbatim. Write it to be heard, not read. */
    val spokenMessage: String,
    /** For the log — the technical detail that has no place in speech. */
    detail: String,
) : Exception(detail)
