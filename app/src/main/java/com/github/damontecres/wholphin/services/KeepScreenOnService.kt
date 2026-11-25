package com.github.damontecres.wholphin.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the TV screen on while something plays (video or a slideshow): MainActivity sets `FLAG_KEEP_SCREEN_ON` while
 * [keepScreenOn] is true. The rest of the time the TV's own screensaver and sleep timer apply; the app has no
 * screensaver of its own (owner, 2026-11-23).
 */
@Singleton
class KeepScreenOnService
    @Inject
    constructor() {
        private val _keepScreenOn = MutableStateFlow(false)
        val keepScreenOn: StateFlow<Boolean> = _keepScreenOn

        fun keepScreenOn(keep: Boolean) {
            _keepScreenOn.value = keep
        }
    }
