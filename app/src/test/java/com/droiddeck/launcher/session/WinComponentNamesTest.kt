package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class WinComponentNamesTest {
    @Test fun catalogKeysReadAsPeopleKnowThem() {
        val names = mapOf(
            "oalinst_dll" to "OpenAL", "vcredist2010_dll" to "Visual C++ 2010", "vcredist2022" to "Visual C++ 2015-2022",
            "dotnet48" to ".NET Framework 4.8", "dotnet452" to ".NET Framework 4.5.2", "dotnet20sp1" to ".NET Framework 2.0 SP1",
            "d3dcompiler_47" to "D3DCompiler 47", "xaudio2.7" to "XAudio 2.7", "mono-10.4.1" to "Wine Mono 10.4.1",
            "msxml6" to "MSXML 6", "dmsynth" to "DirectMusic (dmsynth)", "XLiveRedist" to "Games for Windows Live",
            "something-new" to "something-new",
        )
        for ((id, name) in names) assertEquals(id, name, WinComponentNames.of(id))
    }
}
