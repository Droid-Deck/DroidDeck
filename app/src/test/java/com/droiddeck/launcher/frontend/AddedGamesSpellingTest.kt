package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class AddedGamesSpellingTest {
    @Test fun folderSpacingWinsOverAJoinedExeName() = assertEquals("Insane 2", AddedGames.betterSpelling("Insane2", "Insane 2"))
    @Test fun exeSpacingWinsOverAJoinedFolderName() = assertEquals("DiRT 3", AddedGames.betterSpelling("DiRT 3", "dirt3"))
    @Test fun mixedCaseWinsATie() = assertEquals("Hollow Knight", AddedGames.betterSpelling("HOLLOW KNIGHT", "Hollow Knight"))
    @Test fun differentTitlesKeepTheExeName() = assertEquals("The Witcher 3", AddedGames.betterSpelling("The Witcher 3", "witcher3-goty"))
    @Test fun sameSpellingKeepsTheExeName() = assertEquals("Celeste", AddedGames.betterSpelling("Celeste", "Celeste"))
    @Test fun underscoresBecomeSpaces() = assertEquals("Watch Dogs", AddedGames.betterSpelling(AddedGames.underscoresToSpaces("Watch_Dogs"), "Watch Dogs"))
    @Test fun underscoresBecomeSpacesWithoutAFolderMatch() = assertEquals("Watch Dogs 2", AddedGames.underscoresToSpaces("Watch_Dogs_2"))
}
