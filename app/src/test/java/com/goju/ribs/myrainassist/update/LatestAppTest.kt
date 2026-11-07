package com.goju.ribs.myrainassist.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestAppTest {

    private fun json(file: String = "raining-now-v0.5.apk") = """
        {"versionName":"0.5","versionCode":5,"file":"$file","sizeBytes":22866492,
         "sha256":"1F45610594C0120EA4886780398E3F2DB1BA24357EDBF95686161CC9BA866BBC","uploadedAt":"2026-11-07T08:00:00Z"}
    """.trimIndent()

    @Test
    fun `parses the file the web button reads`() {
        val latest = LatestApp.parse(json())!!
        assertEquals(5, latest.versionCode)
        assertEquals("https://d8dfs01bak16j.cloudfront.net/rain-assist/app/raining-now-v0.5.apk", latest.url)
        assertEquals("1f45610594c0120ea4886780398e3f2db1ba24357edbf95686161cc9ba866bbc", latest.sha256)
    }

    @Test
    fun `rejects a file name that leaves the app folder`() {
        assertNull(LatestApp.parse(json(file = "../evil.apk")))
        assertNull(LatestApp.parse(json(file = "raining-now.zip")))
        assertNull(LatestApp.parse("{}"))
    }

    @Test
    fun `only a higher versionCode is an update`() {
        val latest = LatestApp.parse(json())!!
        assertTrue(AppUpdater.isNewer(latest, 4))
        assertFalse(AppUpdater.isNewer(latest, 5))
    }
}
