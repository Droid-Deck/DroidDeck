package com.droiddeck.launcher.artifact

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositorySourcesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val defaults = listOf("Droid-Deck/Components")
    private val key = "wcp/dxvk"

    @Test fun canonicalNamesShareOneIdentity() {
        assertEquals("droid-deck/components", RepositorySources.canonicalRepository("https://github.com/Droid-Deck/Components.git"))
        assertEquals(RepositorySources.source("Droid-Deck/Components").id,
            RepositorySources.source("https://github.com/droid-deck/components").id)
        val invalid = listOf(
            "https://me@github.com/a/b", "https://github.com:443/a/b", "https://github.com/a/b?x=1",
            "https://github.com/a/b#x", "https://github.com/a/%62", "https://github.com/a/b/",
            "https://github.com/a/..", "a/b/c", "a\\b", "http://github.com/a/b", "https://github.com.evil/a/b",
            "https://github.com/[bad/repo",
        )
        invalid.forEach { assertTrue(it, runCatching { RepositorySources.canonicalRepository(it) }.isFailure) }
    }

    @Test fun builtInsCanBeHiddenButNotRemovedOrDuplicated() {
        val original = RepositorySources.list(app, key, defaults).single()
        assertTrue(original.builtIn)
        assertTrue(runCatching { RepositorySources.save(app, key, defaults, emptyList()) }.isFailure)
        assertTrue(runCatching { RepositorySources.save(app, key, defaults, listOf(original, RepositorySources.source("DROID-DECK/COMPONENTS"))) }.isFailure)
        RepositorySources.save(app, key, defaults, listOf(original.copy(hidden = true)))
        assertTrue(RepositorySources.visible(app, key, defaults).isEmpty())
        assertTrue(RepositorySources.list(app, key, defaults).single().hidden)
    }

    @Test fun scopesAndGenerationsAreIndependent() {
        val before = RepositorySources.generation(app, key, defaults)
        val source = RepositorySources.list(app, key, defaults).single()
        RepositorySources.save(app, key, defaults, listOf(source.copy(hidden = true)))
        assertFalse(RepositorySources.isCurrent(app, key, defaults, source.id, before))
        assertEquals(0L, RepositorySources.generation(app, "wcp/fex", defaults))
        assertEquals(1, RepositorySources.visible(app, "proton", defaults).size)
    }

    @Test fun removingCustomSourcePreservesDownloadedDataAndRepositoryBinding() {
        val custom = RepositorySources.source("Example/Custom")
        RepositorySources.save(app, key, defaults, RepositorySources.list(app, key, defaults) + custom)
        val generation = RepositorySources.generation(app, key, defaults)
        val bound = RepositorySources.bind(app, key, defaults, custom, 123, generation)
        val downloaded = File(RepositorySources.cacheDirectory(app, key, custom), "downloaded.tar")
        downloaded.parentFile!!.mkdirs()
        downloaded.writeText("saved")
        RepositorySources.save(app, key, defaults, RepositorySources.list(app, key, defaults).filter { it.builtIn })
        assertTrue(downloaded.exists())
        assertFalse(RepositorySources.isCurrent(app, key, defaults, bound.id, generation))
        RepositorySources.save(app, key, defaults, RepositorySources.list(app, key, defaults) + custom)
        val readded = RepositorySources.list(app, key, defaults).first { !it.builtIn }
        assertEquals(123L, readded.repositoryId)
        assertTrue(runCatching {
            RepositorySources.bind(app, key, defaults, readded, 456, RepositorySources.generation(app, key, defaults))
        }.isFailure)
    }

    @Test fun damagedPolicyCannotReenableDefaults() {
        val file = RepositorySources.policyFile(app, key)
        file.parentFile!!.mkdirs()
        file.writeText("{broken")
        assertTrue(RepositorySources.visible(app, key, defaults).isEmpty())
        assertTrue(RepositorySources.list(app, key, defaults).all { it.hidden })
        assertFalse(RepositorySources.isCurrent(app, key, defaults, RepositorySources.source(defaults.single()).id, 0))
        RepositorySources.save(app, key, defaults, RepositorySources.list(app, key, defaults))
        assertTrue(RepositorySources.visible(app, key, defaults).isEmpty())
    }

    @Test fun bindingDoesNotInvalidateConcurrentChecksButCannotBindHiddenSources() {
        val row = RepositorySources.list(app, key, defaults).single()
        val generation = RepositorySources.generation(app, key, defaults)
        RepositorySources.bind(app, key, defaults, row, 123, generation)
        assertEquals(generation, RepositorySources.generation(app, key, defaults))
        val bound = RepositorySources.list(app, key, defaults).single()
        RepositorySources.save(app, key, defaults, listOf(bound.copy(hidden = true)))
        assertTrue(runCatching { RepositorySources.bind(app, key, defaults, row, 123, generation) }.isFailure)
    }

    @Test fun newBuiltInsDoNotSilentlyEnableAnExistingPolicy() {
        RepositorySources.save(app, key, defaults, RepositorySources.list(app, key, defaults))
        val expanded = defaults + "Example/NewDefault"
        assertEquals(1, RepositorySources.visible(app, key, expanded).size)
        assertTrue(RepositorySources.list(app, key, expanded).last().hidden)
    }

    @Test fun bootstrapSourceIsRemovableAndNeverReaddedAfterUserSave() {
        val initial = listOf(RepositorySources.source(defaults.single(), hidden = true), RepositorySources.source("Example/Fork"))
        val seeded = RepositorySources.initialize(app, key, defaults, initial)
        assertTrue(seeded.first().builtIn)
        assertTrue(seeded.first().hidden)
        assertFalse(seeded.last().builtIn)
        RepositorySources.save(app, key, defaults, seeded.filter { it.builtIn })
        assertEquals(listOf(defaults.single().lowercase()), RepositorySources.initialize(app, key, defaults, initial).map { it.repository })
        assertTrue(RepositorySources.visible(app, key, defaults).isEmpty())
    }

    @Test fun bootstrapInitializationNeverRepairsDamagedPolicyByEnablingSources() {
        val file = RepositorySources.policyFile(app, key)
        file.parentFile!!.mkdirs()
        file.writeText("{broken")
        val initial = listOf(RepositorySources.source(defaults.single()), RepositorySources.source("Example/Fork"))
        val rows = RepositorySources.initialize(app, key, defaults, initial)
        assertEquals(1, rows.size)
        assertTrue(rows.single().hidden)
    }
}
