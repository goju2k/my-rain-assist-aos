package com.goju.ribs.myrainassist.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process channel between [RainMonitorService] (producer) and MainActivity's WebView (consumer), mirroring [RainForecastBus]. */
object NotificationEventBus {
    private val _events = MutableStateFlow<NotificationEvent?>(null)
    val events: StateFlow<NotificationEvent?> = _events.asStateFlow()

    fun publish(event: NotificationEvent) {
        _events.value = event
    }
}
