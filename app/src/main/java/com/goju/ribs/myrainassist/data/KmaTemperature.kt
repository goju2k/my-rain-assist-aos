package com.goju.ribs.myrainassist.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.goju.ribs.myrainassist.geo.DfsGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Forecast air temperature at a point, read from the KMA 날씨누리 temperature map — the same source
 * the web (ribs-home rain-assist) uses for its temperature chip, so the app and the page agree.
 *
 * The map image is a 149x253 PNG where each pixel is one DFS 5km grid cell (column = nx-1,
 * row = 253-ny, north up), coloured exactly with the legend colours in 1℃ steps. Only used to say
 * 눈 vs 비 in the wording, so any failure just returns null (callers fall back to 비).
 */
object KmaTemperature {

    private const val TAG = "KmaTemperature"
    private const val LIST_URL = "https://www.weather.go.kr/wgis-nuri/dfs/list/TMP"
    private const val IMAGE_URL = "https://www.weather.go.kr/wgis-nuri/dfs/VSRT/TMP/%s/%d"
    private const val TIMEOUT_MS = 10_000
    private const val REFRESH_MS = 20 * 60 * 1000L
    // A stale map is still a better 눈/비 hint than none, for a while.
    private const val MAX_STALE_MS = 3 * 60 * 60 * 1000L

    private class Snapshot(val fetchedAt: Long, val celsius: FloatArray)

    private val mutex = Mutex()
    @Volatile private var snapshot: Snapshot? = null

    suspend fun celsiusAt(location: LatLon): Double? {
        val cell = DfsGrid.cellFor(location.lat, location.lon) ?: return null
        val values = current() ?: return null
        val v = values[pixelIndex(cell.first, cell.second)]
        return if (v.isNaN()) null else v.toDouble()
    }

    private suspend fun current(): FloatArray? = mutex.withLock {
        val now = System.currentTimeMillis()
        val cached = snapshot
        if (cached != null && now - cached.fetchedAt < REFRESH_MS) return cached.celsius
        val fresh = runCatching { fetch() }
            .onFailure { Log.w(TAG, "fetch failed", it) }
            .getOrNull()
        if (fresh != null) {
            snapshot = Snapshot(now, fresh)
            return fresh
        }
        return if (cached != null && now - cached.fetchedAt < MAX_STALE_MS) cached.celsius else null
    }

    private suspend fun fetch(): FloatArray? = withContext(Dispatchers.IO) {
        val list = JSONObject(String(get(LIST_URL))).getJSONArray("dfsList")
        if (list.length() == 0) return@withContext null
        // First entry = the next whole hour of the very-short-range forecast.
        val item = list.getJSONObject(0)
        val fctDate = item.getString("fctDate")
        val efcDate = item.getString("efcDate")
        val png = get(IMAGE_URL.format(fctDate, item.getInt("panIndex")))
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: return@withContext null
        if (bitmap.width != DfsGrid.NX || bitmap.height != DfsGrid.NY) return@withContext null
        val pixels = IntArray(DfsGrid.NX * DfsGrid.NY)
        bitmap.getPixels(pixels, 0, DfsGrid.NX, 0, 0, DfsGrid.NX, DfsGrid.NY)
        bitmap.recycle()
        val palette = KmaTemperatureLegends.PALETTES.getValue(paletteIdFor(efcDate))
        FloatArray(pixels.size) { i -> celsiusForPixel(pixels[i], palette) }
    }

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** Row-major index into the image for a 1-based grid cell. */
    internal fun pixelIndex(nx: Int, ny: Int): Int = (DfsGrid.NY - ny) * DfsGrid.NX + (nx - 1)

    /** KMA's legend.js getPaletteId: by the month of the forecast time in KST. [efcDateUtc] like "2026-11-19T01:00:00". */
    internal fun paletteIdFor(efcDateUtc: String): String {
        val month = runCatching { LocalDateTime.parse(efcDateUtc).atOffset(ZoneOffset.UTC).plusHours(9).monthValue }
            .getOrElse { java.time.LocalDate.now(ZoneOffset.ofHours(9)).monthValue }
        return when (month) {
            3, 4 -> "E_IDFE_T_3"
            in 5..8 -> "E_IDFE_T_6"
            in 9..11 -> "E_IDFE_T_9"
            else -> "E_IDFE_T_12"
        }
    }

    /** ℃ for an ARGB pixel: exact legend colour, else the nearest one within a small distance; NaN if transparent or no match. */
    internal fun celsiusForPixel(argb: Int, palette: KmaTemperatureLegends.Palette): Float {
        if ((argb ushr 24) < 128) return Float.NaN
        val rgb = argb and 0xFFFFFF
        val exact = palette.colors.indexOf(rgb)
        if (exact >= 0) return palette.celsius[exact].toFloat()
        var best = -1
        var bestDist = 30 * 30 * 3
        palette.colors.forEachIndexed { i, c ->
            val dr = (c shr 16 and 0xFF) - (rgb shr 16 and 0xFF)
            val dg = (c shr 8 and 0xFF) - (rgb shr 8 and 0xFF)
            val db = (c and 0xFF) - (rgb and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0) palette.celsius[best].toFloat() else Float.NaN
    }
}
