package com.goju.ribs.myrainassist.data

import android.graphics.BitmapFactory
import android.util.Log
import com.goju.ribs.myrainassist.analysis.ForecastEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Radar frames for the area around the user, fetched as cheaply as possible.
 *
 * The server's v2 feed (`rain-assist/v2/index.json`) lists the last few frames and, per frame,
 * which 256px tiles of the mosaic contain any rain. This only downloads the rainy tiles covering
 * [ForecastEngine.roiFor] around the user, keeps them across cycles (a tile for a given tm never
 * changes), and revalidates the index with its ETag — so a typical cycle costs one tiny index
 * request plus the new frame's few nearby tiles, instead of the whole 4-frame `current.json`
 * (~400 KB) every time. Tiles not listed are known rain-free. Falls back to [RadarApi]
 * (`current.json`) whenever the v2 feed is unavailable.
 */
class RadarRepository {

    /** [bytes] is what this fetch actually downloaded. */
    data class Fetch(val response: RadarResponse, val source: String, val bytes: Long)

    private class Index(
        val corners: List<LatLon>,
        val imageWidth: Int,
        val imageHeight: Int,
        val tileSize: Int,
        val tilePath: String,
        val frames: List<Pair<String, Set<Pair<Int, Int>>>>,
    )

    private class Tile(val width: Int, val height: Int, val grid: ByteArray)

    private var index: Index? = null
    private var indexEtag: String? = null
    private val tileCache = HashMap<String, Tile>()

    suspend fun fetch(userLocation: LatLon): Fetch = withContext(Dispatchers.IO) {
        try {
            fetchV2(userLocation)
        } catch (e: Exception) {
            Log.w(TAG, "v2 feed unavailable, falling back to current.json", e)
            Fetch(RadarApi.fetchFrames(), SOURCE_LEGACY, -1)
        }
    }

    private fun fetchV2(userLocation: LatLon): Fetch {
        var bytes = 0L
        val (status, body) = get(BASE_URL + "index.json", indexEtag)
        val idx = when (status) {
            HttpURLConnection.HTTP_NOT_MODIFIED -> index ?: throw IOException("304 without a cached index")
            else -> {
                bytes += body!!.bytes.size
                indexEtag = body.etag
                parseIndex(String(body.bytes)).also { index = it }
            }
        }
        if (idx.frames.size < 2) throw IOException("v2 index has ${idx.frames.size} frames")

        val roi = ForecastEngine.roiFor(idx.corners, userLocation, idx.imageWidth, idx.imageHeight)
            ?: return Fetch(RadarResponse(idx.corners, emptyList()), SOURCE_V2, bytes)

        val liveTms = idx.frames.map { it.first }.toSet()
        tileCache.keys.removeAll { it.substringBefore('/') !in liveTms }

        val frames = idx.frames.mapNotNull { (tm, rainTiles) ->
            val grid = ByteArray(roi.width * roi.height) { RadarLegend.NO_RAIN_INDEX.toByte() }
            for (ty in roi.row0 / idx.tileSize..(roi.row0 + roi.height - 1) / idx.tileSize) {
                for (tx in roi.col0 / idx.tileSize..(roi.col0 + roi.width - 1) / idx.tileSize) {
                    if ((tx to ty) !in rainTiles) continue
                    val key = "$tm/${tx}_$ty"
                    val tile = tileCache[key] ?: try {
                        val path = idx.tilePath.replace("{tm}", tm).replace("{tx}", "$tx").replace("{ty}", "$ty")
                        val png = get(BASE_URL + path, null).second!!.bytes
                        bytes += png.size
                        decodeTile(png).also { tileCache[key] = it }
                    } catch (e: Exception) {
                        // E.g. a tile the server just retired between our index read and now —
                        // drop this frame rather than analyze it with a hole in it.
                        Log.w(TAG, "tile $key unavailable, skipping frame", e)
                        return@mapNotNull null
                    }
                    blit(tile, tx * idx.tileSize, ty * idx.tileSize, grid, roi)
                }
            }
            RadarFrame(tm, roi.width, roi.height, grid, roi.col0, roi.row0, idx.imageWidth, idx.imageHeight)
        }
        if (frames.size < 2) throw IOException("only ${frames.size} usable v2 frames")
        return Fetch(RadarResponse(idx.corners, frames), SOURCE_V2, bytes)
    }

    private fun blit(tile: Tile, tileCol0: Int, tileRow0: Int, grid: ByteArray, roi: ForecastEngine.Roi) {
        for (r in 0 until tile.height) {
            val roiRow = tileRow0 + r - roi.row0
            if (roiRow !in 0 until roi.height) continue
            for (c in 0 until tile.width) {
                val roiCol = tileCol0 + c - roi.col0
                if (roiCol !in 0 until roi.width) continue
                grid[roiRow * roi.width + roiCol] = tile.grid[r * tile.width + c]
            }
        }
    }

    private fun decodeTile(png: ByteArray): Tile {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw IOException("undecodable tile")
        return try {
            Tile(bitmap.width, bitmap.height, RadarPngDecoder.decode(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun parseIndex(json: String): Index {
        val data = JSONObject(json)
        val cornersJson = data.getJSONArray("corners")
        val framesJson = data.getJSONArray("frames")
        return Index(
            corners = (0 until cornersJson.length()).map { i ->
                val pair = cornersJson.getJSONArray(i)
                LatLon(pair.getDouble(0), pair.getDouble(1))
            },
            imageWidth = data.getInt("imageWidth"),
            imageHeight = data.getInt("imageHeight"),
            tileSize = data.getInt("tileSize"),
            tilePath = data.getString("tilePath"),
            frames = (0 until framesJson.length()).map { i ->
                val frame = framesJson.getJSONObject(i)
                val tiles = frame.getJSONArray("rainTiles")
                frame.getString("tm") to (0 until tiles.length()).map { t ->
                    tiles.getJSONArray(t).let { it.getInt(0) to it.getInt(1) }
                }.toSet()
            },
        )
    }

    private class Body(val bytes: ByteArray, val etag: String?)

    /** (status, body) — body is null for 304. */
    private fun get(url: String, ifNoneMatch: String?): Pair<Int, Body?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept-Encoding", "gzip")
            if (ifNoneMatch != null) connection.setRequestProperty("If-None-Match", ifNoneMatch)
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return status to null
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status for $url")
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) GZIPInputStream(raw) else raw
            return status to Body(stream.use { it.readBytes() }, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "RadarRepository"
        private const val BASE_URL = "https://d8dfs01bak16j.cloudfront.net/rain-assist/v2/"
        private const val TIMEOUT_MS = 10_000
        const val SOURCE_V2 = "v2"
        const val SOURCE_LEGACY = "legacy"
    }
}
