package com.droiddeck.launcher.ui

import com.droiddeck.launcher.runtime.CurseForgeApp
import com.droiddeck.launcher.runtime.WowAddons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CurseLinkReportTest {
    @get:Rule val temp = TemporaryFolder()

    private fun target(vararg folders: String): WowAddons.Target {
        val addOns = temp.newFolder("AddOns")
        folders.forEach { java.io.File(addOns, it).mkdirs() }
        return WowAddons.Target("_retail_", addOns)
    }

    private fun project(id: String, vararg folders: String, update: Boolean = false) = CurseForgeApp.Project(id, "m$id", folders.toList(), update)

    @Test fun beforeAnyReportNothingIsKnown() {
        assertEquals(emptyMap<String, String?>(), CurseLink.lastReported(target("Questie")))
    }

    @Test fun beforeCurseForgeReportsAddonsWhoseFoldersAreAllOnDiskShowAsInstalled() {
        val target = target("questie", "QuestieDB", "Auctionator")
        CurseLink.report(target, listOf(
            project("334372", "Questie", "QuestieDB", update = true),
            project("1", "Auctionator"),
            project("2", "Details", "Details_Streamer"),
            project("3", "Auctionator", "Auctionator_Extra"),
        ))
        assertEquals(mapOf<String, String?>("334372" to null, "1" to null), CurseLink.lastReported(target))
    }

    @Test fun anAddonRemovedFromDiskSinceTheLastReportIsNotInstalled() {
        val target = target("Questie")
        CurseLink.report(target, listOf(project("334372", "Questie")))
        java.io.File(target.addOns, "Questie").delete()
        assertEquals(emptyMap<String, String?>(), CurseLink.lastReported(target))
    }

    private fun toc(target: WowAddons.Target, folder: String, curseId: String) {
        val dir = java.io.File(target.addOns, folder).apply { mkdirs() }
        java.io.File(dir, "$folder.toc").writeText("## Interface: 110207\n## Title: $folder\n## X-Curse-Project-ID: $curseId\n$folder.lua\n")
    }

    @Test fun beforeAnyReportAnAddonWhoseTocNamesTheProjectShowsAsInstalled() {
        val target = target()
        toc(target, "Questie", "334372")
        toc(target, "Auctionator", "@project-id@")
        assertEquals(mapOf<String, String?>("334372" to null), CurseLink.lastReported(target))
    }

    @Test fun aPlaceholderProjectIdIsIgnored() {
        val target = target()
        toc(target, "Auctionator", "@project-id@")
        assertEquals(emptyMap<String, String?>(), CurseLink.lastReported(target))
    }

    @Test fun curseForgesReportOverridesTheTocProjectId() {
        val target = target()
        toc(target, "Questie", "334372")
        toc(target, "Auctionator", "1")
        CurseLink.report(target, listOf(project("1", "Auctionator", update = true)))
        assertEquals(mapOf<String, String?>("1" to null), CurseLink.lastReported(target))
    }
}
