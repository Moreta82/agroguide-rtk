package com.agroguide.rtk.map

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.agroguide.rtk.geo.tiles.TileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Disk-backed tile cache. Tiles are fetched once and kept forever on disk, so
 * an area viewed (or pre-downloaded) at home works with no coverage in the
 * field — the app stays usable offline, which is the whole point of the
 * canvas-first design.
 */
object TileCache {
    private const val MEMORY_TILES = 160
    private const val USER_AGENT = "AgroGuideRTK/1.0 (Android; agricultural guidance)"

    private val memory = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
            size > MEMORY_TILES
    }
    private val inFlight = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchLimit = Semaphore(4)          // be gentle with public tile servers

    private val _version = MutableStateFlow(0)
    /** Bumped whenever a new tile becomes available, to trigger a redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _downloading = MutableStateFlow(0)
    val downloading: StateFlow<Int> = _downloading.asStateFlow()

    @Volatile private var googleSession: String? = null
    @Volatile private var googleSessionKey: String? = null

    private fun key(p: MapProvider, t: TileId) = "${p.id}/${t.z}/${t.x}/${t.y}"

    private fun file(context: Context, p: MapProvider, t: TileId) =
        File(context.filesDir, "tiles/${p.id}/${t.z}/${t.x}").apply { mkdirs() }
            .let { File(it, "${t.y}.img") }

    /**
     * Returns the tile if cached in memory; otherwise schedules a disk read or
     * network fetch and returns null. Never blocks the caller (the renderer).
     */
    fun get(context: Context, provider: MapProvider, tile: TileId, apiKey: String?): ImageBitmap? {
        if (provider == MapProvider.NONE) return null
        val k = key(provider, tile)
        synchronized(memory) { memory[k] }?.let { return it }
        synchronized(inFlight) { if (!inFlight.add(k)) return null }
        scope.launch {
            try {
                val bmp = loadFromDisk(context, provider, tile) ?: fetch(context, provider, tile, apiKey)
                if (bmp != null) {
                    synchronized(memory) { memory[k] = bmp }
                    _version.value = _version.value + 1
                }
            } finally {
                synchronized(inFlight) { inFlight.remove(k) }
            }
        }
        return null
    }

    private fun loadFromDisk(context: Context, p: MapProvider, t: TileId): ImageBitmap? {
        val f = file(context, p, t)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    private suspend fun fetch(
        context: Context, p: MapProvider, t: TileId, apiKey: String?,
    ): ImageBitmap? = fetchLimit.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                if (p == MapProvider.GOOGLE_SATELLITE) ensureGoogleSession(apiKey)
                val url = p.tileUrl(t, googleSession, apiKey) ?: return@runCatching null
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", USER_AGENT)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                if (conn.responseCode !in 200..299 || bytes.isEmpty()) return@runCatching null
                file(context, p, t).writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    /** Google's Map Tiles API requires a session token before serving tiles. */
    private fun ensureGoogleSession(apiKey: String?) {
        if (apiKey.isNullOrEmpty()) return
        if (googleSession != null && googleSessionKey == apiKey) return
        runCatching {
            val conn = (URL("https://tile.googleapis.com/v1/createSession?key=$apiKey")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            conn.outputStream.use {
                it.write("""{"mapType":"satellite","language":"es-ES","region":"ES"}""".toByteArray())
            }
            val body = conn.inputStream.use { s -> s.readBytes().toString(Charsets.UTF_8) }
            googleSession = JSONObject(body).optString("session").ifEmpty { null }
            googleSessionKey = apiKey
        }
    }

    /** Pre-fetches tiles for offline use; reports progress through [downloading]. */
    fun prefetch(context: Context, provider: MapProvider, tiles: List<TileId>, apiKey: String?) {
        if (provider == MapProvider.NONE || tiles.isEmpty()) return
        _downloading.value = _downloading.value + tiles.size
        for (t in tiles) {
            scope.launch {
                try {
                    if (loadFromDisk(context, provider, t) == null) fetch(context, provider, t, apiKey)
                } finally {
                    _downloading.value = (_downloading.value - 1).coerceAtLeast(0)
                    _version.value = _version.value + 1
                }
            }
        }
    }

    fun cacheSizeBytes(context: Context): Long =
        File(context.filesDir, "tiles").walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear(context: Context) {
        scope.launch {
            File(context.filesDir, "tiles").deleteRecursively()
            synchronized(memory) { memory.clear() }
            _version.value = _version.value + 1
        }
    }
}
