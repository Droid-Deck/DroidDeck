package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FexCatalogTest {
    private val sha = "0fc1ca2ba13817ad70d1ea157119a75c9c02446b57d166e6d676cce0807c19c4"
    private val url = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/fex-2610-r5/fex.tzst"

    private fun json(release: String = "fex-2610-r5", url: String = this.url, sha256: String = sha) =
        """{"schemaVersion": 1, "category": "fex", "release": "$release", "url": "$url", "sha256": "$sha256", "size": 4083539}"""

    @Test fun readsTheBuildComponentsNames() {
        assertEquals(FexCatalog.Build("fex-2610-r5", url, sha), FexCatalog.parse(json()))
    }

    @Test fun readsTheSingleReleaseByItsVersion() {
        val single = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/fex/fex.tzst"
        assertEquals(FexCatalog.Build("fex-2610-r6", single, sha), FexCatalog.parse(json(release = "fex", url = single).replace("}", ", \"version\": \"2610-r6\"}")))
        assertNull(FexCatalog.parse(json(release = "fex", url = single).replace("}", ", \"version\": \"latest\"}")))
        assertNull(FexCatalog.parse(json(release = "fex", url = single)))
    }

    @Test fun refusesAnArchiveOutsideItsOwnRelease() {
        assertNull(FexCatalog.parse(json(url = "https://example.com/fex.tzst")))
        assertNull(FexCatalog.parse(json(url = url.replace("fex-2610-r5/", "fex-2610-r4/"))))
        assertNull(FexCatalog.parse(json(release = "latest", url = url.replace("fex-2610-r5", "latest"))))
    }

    @Test fun refusesAMissingOrMalformedDigest() {
        assertNull(FexCatalog.parse(json(sha256 = "abc")))
        assertNull(FexCatalog.parse("""{"release": "fex-2610-r5", "url": "$url"}"""))
        assertNull(FexCatalog.parse("not json"))
    }

    @Test fun theSessionGetsTheBuildAndEverySource() {
        val env = FexCatalog.environment(FexCatalog.Build("fex-2610-r5", url, sha), listOf(url, "https://ghfast.top/$url"))
        assertEquals(listOf("BL_FEX_TAG=fex-2610-r5", "BL_FEX_SHA256=$sha", "BL_FEX_URLS=$url https://ghfast.top/$url"), env)
    }
}
