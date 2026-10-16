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
) {
    /**
     * Great-circle distance to a position, in km. Null for network/VRS
     * mountpoints, which publish 0,0 because they serve any location.
     */
    fun distanceKm(latDeg: Double, lonDeg: Double): Double? {
        val la = this.latDeg ?: return null
        val lo = this.lonDeg ?: return null
        if (la == 0.0 && lo == 0.0) return null
        val r = 6371.0
        val p1 = Math.toRadians(latDeg); val p2 = Math.toRadians(la)
        val dp = p2 - p1; val dl = Math.toRadians(lo - lonDeg)
        val a = Math.sin(dp / 2).let { it * it } +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}

object SourceTable {
    /** Parses sourcetable text; ignores CAS/NET/malformed lines. */
    fun parse(body: String): List<MountPoint> = body.lineSequence().mapNotNull { line ->
        val f = line.trim().split(';')
        if (f.size < 16 || f[0] != "STR" || f[1].isEmpty()) return@mapNotNull null
        MountPoint(
            mountPoint = f[1], identifier = f[2], format = f[3],
            navSystem = f[6], country = f[8],
            latDeg = f[9].toDoubleOrNull(), lonDeg = f[10].toDoubleOrNull()?.let(::normalizeLon),
            requiresGga = f[11] == "1",
            requiresAuth = f[15] != "N",
        )
    }.toList()

    /** Some casters (IGN's among them) publish west longitudes as 180..360. */
    private fun normalizeLon(lon: Double): Double = if (lon > 180.0) lon - 360.0 else lon
}
