package com.droiddeck.launcher.stores.rockstar

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store

internal data class RockstarTitle(
    val id: String,
    val rosTitleId: Int,
    val name: String,
    val exe: String,
    val registryKey: String,
    val steamAppId: Int,
    val released: String,
    val platform: String = "pcros",
    val description: String = "",
    val page: String = "",
)

internal object RockstarTitles {
    private const val ART = "https://cdn.cloudflare.steamstatic.com/steam/apps/"

    val all = listOf(
        RockstarTitle("gta5_gen9", 11, "Grand Theft Auto V Enhanced", "GTA5_Enhanced.exe", "Rockstar Games\\GTAV Enhanced", 3240220, "2025-03-04", platform = "pcrosalt",
            description = "Story mode and GTA Online with ray-traced visuals, faster loading and DualSense support.",
            page = "https://www.rockstargames.com/gta-v"),
        RockstarTitle("gta5", 11, "Grand Theft Auto V Legacy", "GTA5.exe", "Rockstar Games\\Grand Theft Auto V", 271590, "2015-04-14",
            description = "Three criminals, one city: the original PC release of Grand Theft Auto V.",
            page = "https://www.rockstargames.com/gta-v"),
        RockstarTitle("rdr2", 13, "Red Dead Redemption 2", "RDR2.exe", "Rockstar Games\\Red Dead Redemption 2", 1174180, "2019-11-05",
            description = "Arthur Morgan and the Van der Linde gang on the run at the end of the outlaw era.",
            page = "https://www.rockstargames.com/reddeadredemption2"),
        RockstarTitle("rdr", 8, "Red Dead Redemption", "RDR.exe", "Rockstar Games\\Red Dead Redemption", 2668510, "2024-10-29",
            description = "John Marston's story, with the Undead Nightmare expansion.",
            page = "https://www.rockstargames.com/reddeadredemption"),
        RockstarTitle("gta4", 1, "Grand Theft Auto IV: The Complete Edition", "GTAIV.exe", "Rockstar Games\\Grand Theft Auto IV", 12210, "2008-12-02",
            description = "Niko Bellic's Liberty City, with The Lost and Damned and The Ballad of Gay Tony.",
            page = "https://www.rockstargames.com/games/IV"),
        RockstarTitle("lanoire", 9, "L.A. Noire", "LANoire.exe", "Rockstar Games\\LANoireMTL", 110800, "2011-11-08",
            description = "A detective story in 1947 Los Angeles.",
            page = "https://www.rockstargames.com/games/lanoire"),
        RockstarTitle("lanoirevr", 24, "L.A. Noire: The VR Case Files", "LANoireVR.exe", "Rockstar Games\\LANoireVR", 722230, "2017-12-15",
            description = "Seven cases from L.A. Noire rebuilt for virtual reality."),
        RockstarTitle("mp3", 10, "Max Payne 3", "MaxPayne3.exe", "Rockstar Games\\MP3MTL", 204100, "2012-05-29",
            description = "Max Payne, years after New York, working private security in São Paulo.",
            page = "https://www.rockstargames.com/games/maxpayne3"),
        RockstarTitle("bully", 23, "Bully: Scholarship Edition", "Bully.exe", "Rockstar Games\\Bully", 12200, "2008-10-21",
            description = "Jimmy Hopkins against the bullies, teachers and cliques of Bullworth Academy.", page = "https://www.rockstargames.com/bully"),
        RockstarTitle("gta3unreal", 28, "Grand Theft Auto III – The Definitive Edition", "Gameface\\Binaries\\Win64\\LibertyCity.exe", "Rockstar Games\\GTA III - DE", 1546970, "2021-11-11",
            description = "Liberty City, remastered with new lighting, controls and visual detail."),
        RockstarTitle("gtavcunreal", 29, "Grand Theft Auto: Vice City – The Definitive Edition", "Gameface\\Binaries\\Win64\\ViceCity.exe", "Rockstar Games\\GTA Vice City - DE", 1546990, "2021-11-11",
            description = "Tommy Vercetti's 1980s Vice City, remastered with new lighting, controls and visual detail."),
        RockstarTitle("gtasaunreal", 30, "Grand Theft Auto: San Andreas – The Definitive Edition", "Gameface\\Binaries\\Win64\\SanAndreas.exe", "Rockstar Games\\GTA San Andreas - DE", 1547000, "2021-11-11",
            description = "CJ's return to San Andreas, remastered with new lighting, controls and visual detail."),
        RockstarTitle("gtasa", 18, "Grand Theft Auto: San Andreas", "gta_sa.exe", "Rockstar Games\\GTA: San Andreas", 12120, "2005-06-07",
            description = "Carl Johnson's return to Los Santos and the state of San Andreas.", page = "https://www.rockstargames.com/games/sanandreas"),
        RockstarTitle("gtavc", 27, "Grand Theft Auto: Vice City", "gta-vc.exe", "Rockstar Games\\Grand Theft Auto: Vice City", 12110, "2003-05-12",
            description = "Tommy Vercetti's rise through 1980s Vice City.", page = "https://www.rockstargames.com/games/vicecity"),
        RockstarTitle("gta3", 26, "Grand Theft Auto III", "gta3.exe", "Rockstar Games\\Grand Theft Auto III", 12100, "2002-05-20",
            description = "Claude's revenge in Liberty City, the game that took the series into 3D.", page = "https://www.rockstargames.com/games/grandtheftauto3"),
    )

    private val byId = all.associateBy { it.id }

    private val PARENTS = mapOf(
        "rdr2_sp" to "rdr2", "rdr2_rdo" to "rdr2", "rdr2_sp_rgl" to "rdr2", "rdr2_sp_steam" to "rdr2", "rdr2_sp_epic" to "rdr2",
        "gtatrilogy" to "gtasaunreal",
    )

    fun forName(name: String): List<RockstarTitle> =
        if (name.equals("gtatrilogy", true)) listOf("gta3unreal", "gtavcunreal", "gtasaunreal").mapNotNull { byId[it] } else listOfNotNull(byId(name))

    fun byId(id: String?): RockstarTitle? = id?.lowercase()?.let { byId[it] ?: byId[PARENTS[it]] }

    fun cover(t: RockstarTitle) = "$ART${t.steamAppId}/library_600x900.jpg"
    fun hero(t: RockstarTitle) = "$ART${t.steamAppId}/library_hero.jpg"
    fun wide(t: RockstarTitle) = "$ART${t.steamAppId}/header.jpg"

    fun item(t: RockstarTitle, owned: Boolean = false, extra: Map<String, String> = emptyMap()) = CatalogItem(
        store = Store.ROCKSTAR, id = t.id, title = t.name, imageUrl = hero(t), tallImageUrl = cover(t),
        storeUrl = t.page.ifEmpty { "https://store.rockstargames.com" }, developer = "Rockstar Games", publisher = "Rockstar Games",
        releaseDate = t.released, description = t.description, owned = owned, mature = true,
        extra = mapOf("rosTitleId" to t.rosTitleId.toString()) + extra,
    )
}
