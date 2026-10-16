package com.agroguide.rtk.ntrip

/**
 * Why an NTRIP session is not delivering corrections. Surfaced to the user so
 * a wrong password can be told apart from a wrong mountpoint or no coverage.
 */
enum class NtripFailure {
    /** 401: missing or wrong username/password. */
    UNAUTHORIZED,
    /** 403: credentials valid but not allowed on this stream. */
    FORBIDDEN,
    /** 404, or a SOURCETABLE answer to a stream request: mountpoint does not exist. */
    MOUNTPOINT_NOT_FOUND,
    /** DNS could not resolve the caster address. */
    HOST_NOT_FOUND,
    /** TCP connect failed or timed out: no data connection, wrong port, caster down. */
    UNREACHABLE,
    /** Connected and accepted, but the caster stopped (or never started) sending. */
    NO_DATA,
    /** Any other refusal; the raw status line is kept for display. */
    REJECTED,
}

class NtripHead(
    val ok: Boolean,
    val failure: NtripFailure?,
    val statusLine: String,
    val chunked: Boolean,
)

object NtripResponse {
    /**
     * Classifies a caster's response head.
     *
     * Note: "SOURCETABLE 200 OK" contains 200 but is how casters answer a
     * request for a mountpoint they do not have — it must never be treated as
     * a stream, or the table text gets written to the receiver as RTCM.
     */
    fun classify(head: String): NtripHead {
        val status = head.lineSequence().firstOrNull()?.trim().orEmpty()
        val chunked = head.lowercase().contains("transfer-encoding: chunked")
        val upper = status.uppercase()
        val code = Regex("""^(?:ICY|HTTP/\d\.\d|RTSP/\d\.\d)\s+(\d{3})""").find(upper)?.groupValues?.get(1)
        val failure = when {
            upper.startsWith("SOURCETABLE") -> NtripFailure.MOUNTPOINT_NOT_FOUND
            code == "200" -> null
            code == "401" -> NtripFailure.UNAUTHORIZED
            code == "403" -> NtripFailure.FORBIDDEN
            code == "404" -> NtripFailure.MOUNTPOINT_NOT_FOUND
            else -> NtripFailure.REJECTED
        }
        return NtripHead(failure == null, failure, status, chunked)
    }
}

/** Caster address as typed by a user, cleaned up. */
data class NtripEndpoint(val host: String, val port: Int) {
    companion object {
        /**
         * Accepts what people actually paste: "http://host/", "host:2102",
         * stray spaces, upper case. A port inside [hostInput] wins over
         * [defaultPort].
         */
        fun parse(hostInput: String, defaultPort: Int): NtripEndpoint {
            var h = hostInput.trim()
            h = h.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
            h = h.substringBefore('/').trim()
            var port = defaultPort
            val colon = h.lastIndexOf(':')
            if (colon > 0) {
                h.substring(colon + 1).toIntOrNull()?.let { port = it }
                h = h.substring(0, colon)
            }
            return NtripEndpoint(h.lowercase(), port)
        }

        fun normalizeMount(input: String): String = input.trim().trimStart('/').trim()
    }
}
