package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class AddedGamesLaunchArgumentsTest {
    @Test fun plainOptionsSplitOnSpaces() = assertEquals(listOf("-windowed", "-novid"), AddedGames.launchArguments("-windowed -novid"))
    @Test fun quotedSpansKeepTheirSpaces() = assertEquals(listOf("-name", "My Game"), AddedGames.launchArguments("-name \"My Game\""))
    @Test fun aQuoteInsideATokenStaysInThatToken() = assertEquals(listOf("--path=C:\\My Games"), AddedGames.launchArguments("--path=\"C:\\My Games\""))
    @Test fun anEmptyQuotedArgumentIsKept() = assertEquals(listOf("-name", ""), AddedGames.launchArguments("-name \"\""))
    @Test fun emptyOptionsProduceNothing() = assertEquals(emptyList<String>(), AddedGames.launchArguments(""))
    @Test fun theCommandPlaceholderProducesNothing() = assertEquals(emptyList<String>(), AddedGames.launchArguments("-novid %command% -windowed"))
}
