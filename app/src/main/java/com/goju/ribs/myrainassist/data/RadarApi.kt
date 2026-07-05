package com.goju.ribs.myrainassist.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object RadarApi {

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
        val frames = (0 until framesJson.length()).map { i ->
            val f = framesJson.getJSONObject(i)
            val gridWidth = f.getInt("gridWidth")
            val gridHeight = f.getInt("gridHeight")
            val rawBytes = Base64.decode(f.getString("gridDataBase64"), Base64.DEFAULT)
            val grid = GridRleDecoder.decode(rawBytes, gridWidth, gridHeight)
            RadarFrame(tm = f.getString("tm"), gridWidth = gridWidth, gridHeight = gridHeight, grid = grid)
        }

        return RadarResponse(corners = corners, frames = frames)
    }
}
