package io.github.taehunkim.nunsori.sdk

import java.io.IOException

/** Base class of SDK failures. */
open class NunsoriException(message: String?, cause: Throwable? = null) : IOException(message, cause)

/** Nunsori is not installed, refused the connection, did not answer, or the versions do not fit. */
class NunsoriUnavailableException(val reason: Reason, message: String? = null, cause: Throwable? = null) :
    NunsoriException(message ?: reason.name, cause) {
    enum class Reason {
        NOT_INSTALLED,
        /** Refused for another reason than approval (e.g. a Nunsori without this service). */
        NOT_ALLOWED,
        /** The user has not allowed this app in Nunsori yet (the request is waiting there) or denied it. Show [Nunsori.approvalIntent]. */
        NOT_APPROVED,
        NO_ANSWER,
        /** Nunsori's API level is lower than this call needs: update Nunsori. */
        HOST_TOO_OLD,
        /** Nunsori no longer serves this client's API level: update the client app. */
        CLIENT_TOO_OLD,
    }
}

/** Nunsori answered with an error (see [Nunsori.Errors]). */
class NunsoriCallException(val code: Int, message: String?) : NunsoriException(message)

/** Binder only carries a few exception types, so errors travel as `IllegalStateException("nunsori:<code>:<message>")`. */
object NunsoriErrors {
    private const val PREFIX = "nunsori:"

    /** Service side: the exception to throw from a Binder method. */
    fun encode(code: Int, message: String?): IllegalStateException = IllegalStateException("$PREFIX$code:${message ?: ""}")

    /** Client side: null when [e] is not a Nunsori error. */
    fun decode(e: IllegalStateException): NunsoriCallException? {
        val m = e.message ?: return null
        if (!m.startsWith(PREFIX)) return null
        val rest = m.removePrefix(PREFIX)
        val code = rest.substringBefore(':').toIntOrNull() ?: return null
        return NunsoriCallException(code, rest.substringAfter(':').ifEmpty { null })
    }

    /** Client side: why a call was refused. */
    fun refusal(e: SecurityException): NunsoriUnavailableException {
        val err = e.message?.let { decode(IllegalStateException(it)) }
        return if (err?.code == Nunsori.Errors.NOT_APPROVED) NunsoriUnavailableException(NunsoriUnavailableException.Reason.NOT_APPROVED, err.message, e)
        else NunsoriUnavailableException(NunsoriUnavailableException.Reason.NOT_ALLOWED, e.message, e)
    }
}
