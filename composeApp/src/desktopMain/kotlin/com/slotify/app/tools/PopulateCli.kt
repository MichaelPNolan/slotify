// Headless: import a public Spotify playlist URL and match the local library.
// Run from repo root:
//   source env.sh
//   ./gradlew :composeApp:populatePlaylist --args="https://open.spotify.com/playlist/4FKauaoRTekqmS2e4cVgRQ"
//
// Keeps unused/existing app functions intact — this only wires them for CLI use.

package com.slotify.app.tools

import com.slotify.app.data.AppRepository
import com.slotify.app.data.AppSettings
import com.slotify.app.data.defaultLibraryRoots
import com.slotify.app.library.createLibraryScanner
import com.slotify.app.match.Matcher
import com.slotify.app.model.AppPlaylist
import com.slotify.app.spotify.SpotifyEmbedSource
import com.slotify.app.spotify.parseSpotifyPlaylistId
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val raw = args.firstOrNull()
        ?: error("Usage: populatePlaylist <spotify-playlist-url-or-id>")
    val playlistId = parseSpotifyPlaylistId(raw)
        ?: error("Not a Spotify playlist: $raw")

    val repository = AppRepository()
    val settings = repository.loadSettings()
    val roots = settings.libraryRoots.ifEmpty { defaultLibraryRoots() }
    if (settings.libraryRoots.isEmpty()) {
        repository.saveSettings(AppSettings(libraryRoots = roots))
    }

    println("Playlist id: $playlistId")
    println("Library roots:")
    roots.forEach { println("  - $it") }

    val playlist: AppPlaylist = SpotifyEmbedSource().importPlaylist(playlistId) { line ->
        println(line)
    }
    println("Imported \"${playlist.name}\" — ${playlist.remote.tracks.size} tracks")

    println("Scanning library…")
    val library = createLibraryScanner().scan(roots) { line -> println(line) }
    repository.saveLibrary(library)
    println("Indexed ${library.size} file(s)")

    val manual = repository.loadManualLinks().linksFor(playlistId)
    val threshold = settings.matchThreshold
    val resolved = Matcher.matchPlaylist(
        remote = playlist.remote,
        locals = library,
        threshold = threshold,
        manualLinks = manual,
    )
    val matched = playlist.copy(resolvedTracks = resolved)
    val existing = repository.loadPlaylists().filterNot { it.id == matched.id }
    repository.savePlaylists(existing + matched)

    val toSource = matched.toBeSourced
    println()
    println("=== Result: \"${matched.name}\" ===")
    println("Matched: ${matched.matchedCount}/${matched.resolvedTracks.size}")
    println("To be sourced: ${toSource.size}")
    if (toSource.isNotEmpty()) {
        println("--- To be sourced ---")
        toSource.forEachIndexed { i, row ->
            val artists = row.remote.artistNames.ifBlank { "?" }
            println("${i + 1}. ${row.remote.name} — $artists")
        }
    }
    if (matched.matchedCount > 0) {
        println("--- Matched files ---")
        matched.resolvedTracks.filter { it.isMatched }.forEach { row ->
            val m = row.match!!
            println(
                "${row.remote.name} → ${m.local.filePath} " +
                    "(${(m.confidence * 100).toInt()}%${if (m.isManual) ", manual" else ""})",
            )
        }
    }
    println()
    println("Saved to app data. Launch ./run.sh to browse Playlists → To be sourced.")
}
