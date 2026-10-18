package com.goju.ribs.myrainassist.backtest

import com.goju.ribs.myrainassist.data.RadarFrame
import com.goju.ribs.myrainassist.data.RadarPngDecoder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Supplies historical radar frames for the backtest. Tries the project's own S3 frame archive
 * first (kept indefinitely), then KMA directly (KMA only keeps roughly the last day), and caches
 * every PNG on disk so a rainy day can be re-scored later after KMA has dropped it.
 */
class KmaFrameSource(private val cacheDir: File) {

    init {
        cacheDir.mkdirs()
    }

    fun load(tm: String): RadarFrame? {
        val png = File(cacheDir, "$tm.png")
        val missing = File(cacheDir, "$tm.missing")
        if (!png.exists() && !missing.exists()) {
            val bytes = SOURCES.firstNotNullOfOrNull { download(it.format(tm)) }
            if (bytes != null) png.writeBytes(bytes) else if (isSettled(tm)) missing.writeText("")
        }
        if (!png.exists()) return null
        val image = ImageIO.read(png) ?: return null
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return RadarFrame(tm = tm, gridWidth = image.width, gridHeight = image.height, grid = RadarPngDecoder.decodePixels(pixels))
    }

    // Only remember a 404 for timestamps old enough that KMA will never publish them — a recent
    // one may simply not be out yet.
    private fun isSettled(tm: String): Boolean =
        LocalDateTime.parse(tm, TM_FORMAT).isBefore(LocalDateTime.now().minusHours(2))

    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) null else connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val TM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val SOURCES = listOf(
            "https://d8dfs01bak16j.cloudfront.net/rain-assist/v2/frames/%s.png",
            "https://vapi.kma.go.kr/BUFD/rdr_sfc_pty_img_%s_1453.png",
        )
    }
}
