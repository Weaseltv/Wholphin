package com.github.damontecres.wholphin.ui.detail.discover

import com.github.damontecres.wholphin.api.seerr.model.Season
import com.github.damontecres.wholphin.data.model.RequestStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability

/** A season in the request seasons dialog: its status and whether it can still be requested (moved out of RequestSeasons.kt) */
data class RequestSeason(
    val season: Season,
    val status: RequestStatus,
    val availability: SeerrAvailability,
    val editable: Boolean,
)
