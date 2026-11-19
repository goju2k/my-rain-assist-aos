package com.goju.ribs.myrainassist.notification

import com.goju.ribs.myrainassist.analysis.ForecastState
import com.goju.ribs.myrainassist.data.PrecipKind
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationWordingTest {
    @Test fun rainWordingUnchanged() {
        assertEquals("20분 뒤 약한 비가 옵니다", NotificationHelper.ongoingTextFor(ForecastState.INCOMING, false, 20, 1.0))
        assertEquals("비가 그쳤어요", NotificationHelper.ongoingTextFor(ForecastState.NONE, true, null))
    }

    @Test fun snowWording() {
        val snow = PrecipKind.SNOW
        assertEquals("20분 뒤 약한 눈이 옵니다", NotificationHelper.ongoingTextFor(ForecastState.INCOMING, false, 20, 1.0, kind = snow))
        assertEquals("많은 눈이 오고 있어요", NotificationHelper.ongoingTextFor(ForecastState.ACTIVE, false, null, 20.0, kind = snow))
        assertEquals("눈이 그쳤어요", NotificationHelper.ongoingTextFor(ForecastState.NONE, true, null, kind = snow))
    }
}
