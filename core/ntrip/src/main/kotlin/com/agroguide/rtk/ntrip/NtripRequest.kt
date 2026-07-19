package com.agroguide.rtk.ntrip

import java.util.Base64

enum class NtripVersion { V1, V2 }

data class NtripCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

/** Builds the exact request text for stream and sourcetable GETs. */
object NtripRequest {
    private const val AGENT = "NTRIP AgroGuideRTK/1.0"

    fun stream(c: NtripCredentials, mountPoint: String, v: NtripVersion): String =
        build(c, "/$mountPoint", v)

    fun sourceTable(c: NtripCredentials, v: NtripVersion): String = build(c, "/", v)

    private fun build(c: NtripCredentials, path: String, v: NtripVersion): String {
        val sb = StringBuilder()
        when (v) {
            NtripVersion.V1 -> {
                sb.append("GET ").append(path).append(" HTTP/1.0\r\n")
                sb.append("User-Agent: ").append(AGENT).append("\r\n")
                sb.append("Accept: */*\r\n")
            }
            NtripVersion.V2 -> {
                sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
                sb.append("Host: ").append(c.host).append(':').append(c.port).append("\r\n")
                sb.append("Ntrip-Version: Ntrip/2.0\r\n")
                sb.append("User-Agent: ").append(AGENT).append("\r\n")
                sb.append("Accept: */*\r\n")
            }
        }
        if (c.username.isNotEmpty() || c.password.isNotEmpty()) {
            val token = Base64.getEncoder().encodeToString("${c.username}:${c.password}".toByteArray())
            sb.append("Authorization: Basic ").append(token).append("\r\n")
        }
        sb.append("\r\n")
        return sb.toString()
    }
}
