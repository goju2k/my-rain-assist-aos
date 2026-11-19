package com.goju.ribs.myrainassist.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DfsGridTest {
    // Known KMA grid numbers (기상청 동네예보 격자 엑셀과 같은 값).
    @Test fun seoulCityHall() = assertEquals(60 to 127, DfsGrid.cellFor(37.5665, 126.978))
    @Test fun busan() = assertEquals(98 to 76, DfsGrid.cellFor(35.1796, 129.0756))
    @Test fun outsideGrid() = assertNull(DfsGrid.cellFor(20.0, 110.0))
}
