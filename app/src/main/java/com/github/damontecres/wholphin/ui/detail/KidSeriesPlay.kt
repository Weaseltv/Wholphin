package com.github.damontecres.wholphin.ui.detail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Where the kid series page's Play button finds its episode; every answer comes through the kid content filter */
interface KidEpisodeSource {
    suspend fun nextUp(): List<BaseItemDto>

    suspend fun episodes(
        startIndex: Int,
        limit: Int,
    ): KidTitlePage.EpisodePage
}

/**
 * The kid series page's Play button (review round 2). It is found after the page shows, never holding it up: once when
 * the page opens ([open]; the first [resumed], which comes with that first display, doesn't look again) and once each
 * time the page comes back, eg from playback. The scan of every episode ([KidTitlePage.allowedEpisodes]) runs only when
 * the server's next up is empty (none, or filtered). [publish] gets the button, or null when there's nothing to play.
 */
class KidSeriesPlay(
    private val scope: CoroutineScope,
    private val source: KidEpisodeSource,
    private val publish: (KidPlay?) -> Unit,
    private val context: CoroutineContext = EmptyCoroutineContext,
) {
    private var job: Job? = null
    private var resumes = 0

    fun open() = load()

    fun resumed() {
        if (resumes++ > 0) load()
    }

    private fun load() {
        job?.cancel()
        job = scope.launch(context) { publish(find()) }
    }

    /** The button for the episode [KidTitlePage.nextEpisode] picks; none on failure */
    suspend fun find(): KidPlay? =
        try {
            val nextUp = source.nextUp()
            val episodes = if (nextUp.isEmpty()) KidTitlePage.allowedEpisodes(source::episodes) else emptyList()
            KidTitlePage.seriesPlay(KidTitlePage.nextEpisode(nextUp, episodes))
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.w(ex, "Couldn't find the next episode")
            null
        }
}
