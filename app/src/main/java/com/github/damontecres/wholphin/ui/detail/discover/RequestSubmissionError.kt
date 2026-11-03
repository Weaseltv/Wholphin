package com.github.damontecres.wholphin.ui.detail.discover

import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientError
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.infrastructure.ServerError
import com.github.damontecres.wholphin.api.seerr.infrastructure.ServerException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** Explain the failure without displaying server bodies, credentials or exception dumps. */
fun requestSubmissionError(error: Exception): String {
    val response =
        when (error) {
            is ClientException -> error.response
            is ServerException -> error.response
            else -> null
        }
    val body =
        when (response) {
            is ClientError<*> -> response.body as? String
            is ServerError<*> -> response.body as? String
            else -> null
        }
    val missingListing =
        body?.takeIf { it.length <= 8192 }?.let {
            runCatching {
                val fields = Json.parseToJsonElement(it).jsonObject
                listOf("message", "error").any { key ->
                    (fields[key] as? JsonPrimitive)?.contentOrNull?.trim()?.equals("Media data not found", ignoreCase = true) == true
                }
            }.getOrDefault(false)
        } == true
    if (missingListing) return "This title can't be requested right now because its listing couldn't be matched."

    val status =
        when (error) {
            is ClientException -> error.statusCode
            is ServerException -> error.statusCode
            else -> null
        }
    return when (status) {
        401, 403 -> {
            "The request service didn't authorize this request. Reopen Requests before trying again. (Error $status)"
        }

        404 -> {
            "The request service couldn't find this title. Please try again later. (Error 404)"
        }

        409 -> {
            "The request service reported a conflict. Check My Requests before trying again. (Error 409)"
        }

        429 -> {
            "The request service is limiting requests right now. Please wait before trying again. (Error 429)"
        }

        in 400..499 -> {
            "The request service couldn't accept this request. (Error $status)"
        }

        in 500..599 -> {
            "The request service couldn't finish this request. Check My Requests before trying again. (Error $status)"
        }

        else -> {
            if (error is IOException) {
                "Couldn't confirm whether your request was sent. Check My Requests before trying again."
            } else {
                "Couldn't confirm the request result. Check My Requests before trying again."
            }
        }
    }
}
