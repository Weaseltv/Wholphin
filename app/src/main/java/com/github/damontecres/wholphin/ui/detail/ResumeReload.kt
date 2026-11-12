package com.github.damontecres.wholphin.ui.detail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A details page loads when its view model is created, and loads again each time the page resumes, so it shows what
 * changed elsewhere (eg the resume position after playback).
 *
 * The first resume is the page opening: it arrives when the page transition ends, while that first load is still
 * running or has just finished, so loading again there sent every request twice (speed plan step 1, 2026-11).
 * [onResume] leaves the first load alone and loads again only if it failed, which the second load used to cover.
 * Every later resume reloads as before.
 */
class ResumeReload(
    private val scope: CoroutineScope,
    private val firstLoad: Job,
    private val failed: () -> Boolean,
    private val reload: () -> Unit,
) {
    private var opened = false

    /** Called from the page's resume effect, on the main thread */
    fun onResume() {
        if (opened) {
            reload()
            return
        }
        opened = true
        scope.launch {
            firstLoad.join()
            if (failed()) reload()
        }
    }
}
