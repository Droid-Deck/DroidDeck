package com.droiddeck.launcher.session

/**
 * What to call a catalog component on screen. Bannerlator's catalog keys (vcredist2010_dll,
 * oalinst_dll, d3dcompiler_47...) have no display name, and some descriptions do not fit the entry,
 * so names come from here: families by pattern, the rest from a table, the key itself otherwise.
 * The "_dll" twin of an installer (the direct DLL copy) shares the installer's name.
 */
object WinComponentNames {
    private val TABLE = mapOf(
        "oalinst" to "OpenAL", "XLiveRedist" to "Games for Windows Live", "gfw" to "Games for Windows Live",
        "physx" to "NVIDIA PhysX", "xact" to "DirectX XACT audio", "xact_x64" to "DirectX XACT audio (64-bit)",
        "xinput" to "XInput", "xna31" to "XNA Framework 3.1", "xna40" to "XNA Framework 4.0",
        "d3dx9" to "DirectX 9 (d3dx9)", "d3dx11" to "DirectX 11 (d3dx11)",
        "directmusic" to "DirectMusic", "directplay" to "DirectPlay", "directshow" to "DirectShow",
        "dsound" to "DirectSound", "dx8vb" to "DirectX 8 for Visual Basic", "mediafoundation" to "Media Foundation",
        "quartz" to "DirectShow (quartz)", "amstream" to "DirectShow streams (amstream)", "devenum" to "DirectShow devices (devenum)",
        "cnc-ddraw" to "cnc-ddraw (DirectDraw)", "gdiplus" to "GDI+", "gecko" to "Wine Gecko", "mono" to "Wine Mono",
        "vcredist6" to "Visual C++ 6", "vcredist6sp6" to "Visual C++ 6 SP6",
        "vcredist2015" to "Visual C++ 2015", "vcredist2019" to "Visual C++ 2015-2019", "vcredist2022" to "Visual C++ 2015-2022",
        "vbrun6" to "Visual Basic 6 runtime", "mfc40" to "MFC 4.0", "mfc42" to "MFC 4.2",
        "dotnetcore3" to ".NET Core 3", "dotnetcoredesktop3" to ".NET Desktop 3", "dotnetcoredesktop6" to ".NET Desktop 6",
        "dotnetcoredesktop7" to ".NET Desktop 7", "dotnetcoredesktop8" to ".NET Desktop 8", "dotnet50" to ".NET 5",
        "webview2" to "Microsoft WebView2", "powershell" to "PowerShell", "powershell_core" to "PowerShell Core",
        "K-Lite" to "K-Lite Codec Pack", "VulkanRT" to "Vulkan Runtime", "ffdshow" to "ffdshow codecs",
        "lavfilters702" to "LAV Filters 0.70.2", "lavfilters741" to "LAV Filters 0.74.1", "quicktime72" to "QuickTime 7.2",
        "cjkfonts" to "CJK fonts", "jet40" to "Jet 4.0 database", "mdac28" to "MDAC 2.8", "wsh57" to "Windows Script Host 5.7",
        "riched20" to "RichEdit 2.0", "msftedit" to "RichEdit 4.1 (msftedit)", "sqlite3" to "SQLite 3",
        "win7" to "Windows 7 mode", "winXP" to "Windows XP mode", "gmdls" to "General MIDI sounds (gm.dls)",
    )
    private val VCREDIST = Regex("vcredist(\\d{4})")
    private val DOTNET = Regex("dotnet(\\d)(\\d)(\\d?)(sp\\d)?")
    private val D3DCOMPILER = Regex("d3dcompiler_(\\d+)")
    private val MSXML = Regex("msxml(\\d)")
    private val MONO = Regex("mono-([\\d.]+)")
    private val XAUDIO = Regex("xaudio([\\d.]+)")

    fun of(id: String): String {
        val base = id.removeSuffix("_dll")
        TABLE[base]?.let { return it }
        VCREDIST.matchEntire(base)?.let { return "Visual C++ " + it.groupValues[1] }
        DOTNET.matchEntire(base)?.let { m ->
            val (major, minor, patch, sp) = m.destructured
            return ".NET Framework $major.$minor" + (if (patch.isNotEmpty()) ".$patch" else "") +
                (if (sp.isNotEmpty()) " " + sp.uppercase() else "")
        }
        D3DCOMPILER.matchEntire(base)?.let { return "D3DCompiler " + it.groupValues[1] }
        MSXML.matchEntire(base)?.let { return "MSXML " + it.groupValues[1] }
        MONO.matchEntire(base)?.let { return "Wine Mono " + it.groupValues[1] }
        XAUDIO.matchEntire(base)?.let { return "XAudio " + it.groupValues[1] }
        if (base.startsWith("dm") || base == "dswave" || base == "dsdmo") return "DirectMusic ($base)"
        return id
    }
}
