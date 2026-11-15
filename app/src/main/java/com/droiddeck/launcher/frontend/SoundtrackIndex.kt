package com.droiddeck.launcher.frontend

import android.content.Context

object SoundtrackIndex {
    class Hit(val ref: SoundtrackCollections.TrackRef, val game: Library.SteamGame)

    private var cacheKey = ""
    private var cache: List<Hit> = emptyList()

    private fun fingerprint(dir: java.io.File?): Long =
        if (dir == null) 0L else Soundtrack.audioFiles(dir).fold(17L) { h, f -> 31 * (31 * (31 * h + f.path.hashCode()) + f.length()) + f.lastModified() }

    fun build(context: Context, games: List<Library.SteamGame>): List<Hit> {
        val roots = games.filter { it.gameFiles != null }
        val key = roots.joinToString("|") { "${it.appId}:${it.gameFiles?.path}:${it.art?.path}:${fingerprint(it.gameFiles)}" }
        synchronized(this) { if (key == cacheKey) return cache }
        val hits = roots.flatMap { g ->
            val dir = g.gameFiles ?: return@flatMap emptyList()
            val album = Soundtrack.load(context, dir, g.art)
            val cover = (g.art ?: album.cover)?.path.orEmpty()
            album.tracks.map { t ->
                Hit(SoundtrackCollections.TrackRef(t.file.path, t.title, t.artist.ifBlank { album.artist }, g.name, t.durationMs, cover), g)
            }
        }
        synchronized(this) {
            cacheKey = key
            cache = hits
        }
        return hits
    }
}
