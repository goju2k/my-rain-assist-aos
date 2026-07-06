package com.goju.ribs.myrainassist.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object RadarApi {

    private const val TAG = "RadarApi"
    private const val CURRENT_JSON_URL = "https://d8dfs01bak16j.cloudfront.net/rain-assist/current.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun fetchFrames(): RadarResponse = withContext(Dispatchers.IO) {
        val url = URL(CURRENT_JSON_URL)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val stream = if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            val body = stream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): RadarResponse {
        val data = JSONObject(body)

        val cornersJson = data.getJSONArray("corners")
        val corners = (0 until cornersJson.length()).map { i ->
            val pair = cornersJson.getJSONArray(i)
            LatLon(lat = pair.getDouble(0), lon = pair.getDouble(1))
        }

        val framesJson = data.getJSONArray("frames")
        val frames = (0 until framesJson.length()).mapNotNull { i ->
            val f = framesJson.getJSONObject(i)
            val tm = f.getString("tm")
            val pngBytes = Base64.decode(f.getString("pngBase64"), Base64.DEFAULT)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, options)
            if (bitmap == null) {
                Log.w(TAG, "parse: failed to decode PNG for frame tm=$tm, skipping")
                return@mapNotNull null
            }
            val grid = RadarPngDecoder.decode(bitmap)
            RadarFrame(tm = tm, gridWidth = bitmap.width, gridHeight = bitmap.height, grid = grid)
        }

        return RadarResponse(corners = corners, frames = frames)
    }
}
