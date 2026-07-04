package com.goju.ribs.myrainassist.service

import com.goju.ribs.myrainassist.analysis.RainForecastResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process channel between [RainMonitorService] (producer) and MainActivity's WebView (consumer). */
object RainForecastBus {
    private val _forecast = MutableStateFlow<RainForecastResult?>(null)
    val forecast: StateFlow<RainForecastResult?> = _forecast.asStateFlow()

    fun publish(result: RainForecastResult) {
        _forecast.value = result
    }
}
