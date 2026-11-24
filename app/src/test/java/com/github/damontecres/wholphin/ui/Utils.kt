package com.github.damontecres.wholphin.ui

import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult

fun <T> successResponse(content: T) = Response(content, 200, emptyMap())

fun successQueryResult(
    items: List<BaseItemDto> = emptyList(),
    totalRecordCount: Int = items.size,
) = successResponse(BaseItemDtoQueryResult(items, totalRecordCount, 0))
