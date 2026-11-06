package com.droiddeck.launcher.artifact

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun catalogBranchesFollowGitBranchSyntaxWithoutChangingCase() {
        val valid = listOf("main", "wallentx/powervr", "release+gpu%2", "éclair/驱动", "a".repeat(256))
        valid.forEach { assertEquals(it, RepositorySources.normalizeCatalogBranch("  $it  ")) }
        assertNull(RepositorySources.normalizeCatalogBranch(null))
        assertNull(RepositorySources.normalizeCatalogBranch("   "))

        val invalid = listOf(
            "HEAD", "-leading", "/leading", "trailing/", "two//slashes", "two..dots", "bad@{name",
            ".hidden", "path/.hidden", "branch.lock", "path/branch.lock", "trailing.", "has space",
            "tab\tname", "tilde~", "caret^", "colon:", "question?", "star*", "open[", "back\\slash",
            "a".repeat(257),
        )
        invalid.forEach { branch ->
            assertTrue(branch, runCatching { RepositorySources.normalizeCatalogBranch(branch) }.isFailure)
        }
    }

    @Test fun schemaOneWithoutCatalogBranchMigratesAsDefaultBranch() {
        val original = RepositorySources.list(app, key, defaults).single()
        RepositorySources.save(app, key, defaults, listOf(original))
        val file = RepositorySources.policyFile(app, key)
        assertFalse(file.readText().contains("catalogBranch"))
        assertNull(RepositorySources.list(app, key, defaults).single().catalogBranch)
    }

    @Test fun savingAndClearingCatalogBranchPreservesSourceIdentityAndBinding() {
        val original = RepositorySources.list(app, key, defaults).single()
        val firstGeneration = RepositorySources.generation(app, key, defaults)
        val bound = RepositorySources.bind(app, key, defaults, original, 123, firstGeneration)
        val branched = RepositorySources.save(
            app, key, defaults, listOf(bound.copy(catalogBranch = " Wallentx/PowerVR ")),
        ).single()
        val branchGeneration = RepositorySources.generation(app, key, defaults)

        assertEquals(original.id, branched.id)
        assertEquals(123L, branched.repositoryId)
        assertEquals("Wallentx/PowerVR", branched.catalogBranch)
        assertTrue(branchGeneration > firstGeneration)
        assertFalse(RepositorySources.isCurrent(app, key, defaults, original, branchGeneration))
        assertTrue(RepositorySources.isCurrent(app, key, defaults, branched, branchGeneration))
        assertTrue(runCatching {
            RepositorySources.bind(app, key, defaults, original, 123, branchGeneration)
        }.isFailure)

        val cleared = RepositorySources.save(
            app, key, defaults, listOf(branched.copy(catalogBranch = "  ")),
        ).single()
        assertNull(cleared.catalogBranch)
        assertEquals(original.id, cleared.id)
        assertEquals(123L, cleared.repositoryId)
        assertTrue(RepositorySources.generation(app, key, defaults) > branchGeneration)
    }

    @Test fun malformedPersistedBranchDisablesOnlyThatSourceAndRemainsRepairable() {
        val original = RepositorySources.list(app, key, defaults).single()
        val custom = RepositorySources.source("Wallentx/DroidDeck", catalogBranch = "wallentx/powervr")
        RepositorySources.save(app, key, defaults, listOf(original, custom))
        RepositorySources.bind(
            app, key, defaults, custom, 456, RepositorySources.generation(app, key, defaults),
        )
        val file = RepositorySources.policyFile(app, key)
        val json = JSONObject(file.readText())
        json.getJSONArray("sources").getJSONObject(1).put("catalogBranch", "bad branch")
        file.writeText(json.toString())

        val listed = RepositorySources.list(app, key, defaults)
        assertEquals(listOf(original.id, custom.id), listed.map { it.id })
        assertEquals("bad branch", listed[1].catalogBranch)
        assertEquals(456L, listed[1].repositoryId)
        assertEquals(listOf(original.id), RepositorySources.visible(app, key, defaults).map { it.id })

        val repaired = RepositorySources.save(
            app, key, defaults, listed.map { if (it.id == custom.id) it.copy(catalogBranch = null) else it },
        )
        assertEquals(2, RepositorySources.visible(app, key, defaults).size)
        assertNull(repaired[1].catalogBranch)
    }

    @Test fun nonStringPersistedBranchFailsClosedWithoutRewritingThePolicy() {
        val policyKey = "wcp/nonstring"
        val original = RepositorySources.list(app, policyKey, defaults).single()
        val custom = RepositorySources.source("Wallentx/DroidDeck")
        RepositorySources.save(app, policyKey, defaults, listOf(original, custom))
        val file = RepositorySources.policyFile(app, policyKey)
        val json = JSONObject(file.readText())
        json.getJSONArray("sources").getJSONObject(1).put("catalogBranch", 123)
        file.writeText(json.toString())
        val stored = file.readText()

        val listed = RepositorySources.list(app, policyKey, defaults)
        assertEquals(listOf(original.id, custom.id), listed.map { it.id })
        assertNull(listed[1].catalogBranch)
        assertEquals(listOf(original.id), RepositorySources.visible(app, policyKey, defaults).map { it.id })
        assertEquals(stored, file.readText())

        RepositorySources.bind(
            app, policyKey, defaults, listed[0], 789, RepositorySources.generation(app, policyKey, defaults),
        )
        val afterBinding = RepositorySources.list(app, policyKey, defaults)
        assertEquals(listOf(original.id, custom.id), afterBinding.map { it.id })
        assertEquals(listOf(original.id), RepositorySources.visible(app, policyKey, defaults).map { it.id })
        assertEquals(123, JSONObject(file.readText()).getJSONArray("sources").getJSONObject(1).getInt("catalogBranch"))
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

    @Test fun guardedWritesExcludePolicyChangesUntilTheyFinish() {
        val source = RepositorySources.list(app, key, defaults).single()
        val generation = RepositorySources.generation(app, key, defaults)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val writer = thread {
            runCatching {
                RepositorySources.whileCurrent(app, key, defaults, source.id, generation) {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
            }.onFailure { errors.add(it) }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val policy = thread {
            runCatching {
                RepositorySources.save(app, key, defaults, listOf(source.copy(hidden = true)))
            }.onFailure { errors.add(it) }
            saved.countDown()
        }
        assertFalse(saved.await(150, TimeUnit.MILLISECONDS))
        release.countDown()
        writer.join(2_000)
        policy.join(2_000)
        assertFalse(writer.isAlive)
        assertFalse(policy.isAlive)
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(RepositorySources.visible(app, key, defaults).isEmpty())
    }
}
