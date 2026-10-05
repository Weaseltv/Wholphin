package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.fallbackAssetName
import com.github.damontecres.wholphin.services.getDownloadUrl
import com.github.damontecres.wholphin.services.parseLatestTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.nio.file.Paths
import kotlin.io.path.readText

class TestUpdateChecker {
    lateinit var releaseJson: JsonObject
    val assetsJson: JsonArray by lazy { releaseJson["assets"]!!.jsonArray }

    @Before
    fun setup() {
        val resource = javaClass.classLoader?.getResource("release_develop.json")
        Assert.assertNotNull(resource)
        val fileContents =
            Paths
                .get(resource!!.toURI())
                .readText()
                .replace("Wholphin-", "${BuildConfig.UPDATE_ASSET_NAME}-")
        releaseJson = Json.parseToJsonElement(fileContents).jsonObject
    }

    private fun assetUrl(suffix: String) =
        "https://github.com/damontecres/Wholphin/releases/download/develop/${BuildConfig.UPDATE_ASSET_NAME}-$suffix.apk"

    @Test
    fun `Release chooses release`() {
        val url = getDownloadUrl(assetsJson, false, listOf())
        Assert.assertEquals(assetUrl("release"), url)
    }

    @Test
    fun `Choose abi`() {
        val url = getDownloadUrl(assetsJson, false, listOf("arm64-v8a"))
        Assert.assertEquals(assetUrl("release-arm64-v8a"), url)
    }

    @Test
    fun `Choose unknown abi`() {
        val url = getDownloadUrl(assetsJson, false, listOf("unknown"))
        Assert.assertEquals(assetUrl("release"), url)
    }

    @Test
    fun `Debug chooses debug`() {
        val url = getDownloadUrl(assetsJson, true, listOf())
        Assert.assertEquals(assetUrl("debug"), url)
    }

    @Test
    fun `Choose debug abi`() {
        val url = getDownloadUrl(assetsJson, true, listOf("arm64-v8a"))
        Assert.assertEquals(assetUrl("debug-arm64-v8a"), url)
    }

    @Test
    fun `Latest tag is parsed from the release page redirect`() {
        Assert.assertEquals(
            "v1.2.11",
            parseLatestTag("https://github.com/Weaseltv/Wholphin/releases/tag/v1.2.11"),
        )
        Assert.assertEquals("v1.2.11", parseLatestTag("/Weaseltv/Wholphin/releases/tag/v1.2.11?x=1"))
        Assert.assertNull(parseLatestTag("https://github.com/Weaseltv/Wholphin/releases"))
        Assert.assertNull(parseLatestTag(null))
    }

    @Test
    fun `Fallback asset is the ABI split when published, else universal`() {
        Assert.assertEquals(
            "${UpdateChecker.ASSET_NAME}-release-arm64-v8a.apk",
            fallbackAssetName(listOf("arm64-v8a", "armeabi-v7a")),
        )
        Assert.assertEquals("${UpdateChecker.ASSET_NAME}-release.apk", fallbackAssetName(listOf("x86")))
        Assert.assertEquals("${UpdateChecker.ASSET_NAME}-release.apk", fallbackAssetName(emptyList()))
    }
}
