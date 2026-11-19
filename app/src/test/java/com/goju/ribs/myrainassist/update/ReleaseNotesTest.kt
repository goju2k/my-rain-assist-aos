package com.goju.ribs.myrainassist.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test fun showsOnlyVersionsAfterLastSeen() {
        assertEquals(listOf(8), ReleaseNotes.since(7, 8).map { it.versionCode })
        assertTrue(ReleaseNotes.since(8, 8).isEmpty())
    }

    @Test fun everyEntryHasLines() {
        assertTrue(ReleaseNotes.ENTRIES.all { it.lines.isNotEmpty() })
    }
}
