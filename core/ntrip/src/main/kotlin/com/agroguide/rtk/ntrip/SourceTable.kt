package com.agroguide.rtk.ntrip

/** One STR record from an NTRIP sourcetable. */
data class MountPoint(
    val mountPoint: String,
    val identifier: String,
    val format: String,
    val navSystem: String,
    val country: String,
    val latDeg: Double?,
    val lonDeg: Double?,
    val requiresGga: Boolean,
    val requiresAuth: Boolean,
)

object SourceTable {
    /** Parses sourcetable text; ignores CAS/NET/malformed lines. */
    fun parse(body: String): List<MountPoint> = body.lineSequence().mapNotNull { line ->
        val f = line.trim().split(';')
        if (f.size < 16 || f[0] != "STR" || f[1].isEmpty()) return@mapNotNull null
        MountPoint(
            mountPoint = f[1], identifier = f[2], format = f[3],
            navSystem = f[6], country = f[8],
            latDeg = f[9].toDoubleOrNull(), lonDeg = f[10].toDoubleOrNull(),
            requiresGga = f[11] == "1",
            requiresAuth = f[15] != "N",
        )
    }.toList()
}
