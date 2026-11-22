package com.slotify.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Read-only parse checks for playlist JSON (no filesystem writes).
 * Verifies a full export array yields **more than one** playlist.
 */
class ParsePlaylistJsonFileTest {

    private val repository = AppRepository()

    private val fullExport = """
        [
          {
            "remote": {
              "id": "pl1",
              "name": "Sweet",
              "trackCount": 1,
              "tracks": [
                {"id": "t1", "name": "Just A Girl", "artists": [{"name": "No Doubt"}]}
              ]
            },
            "resolvedTracks": [
              {
                "remote": {"id": "t1", "name": "Just A Girl", "artists": [{"name": "No Doubt"}]},
                "match": null
              }
            ],
            "importedAtEpochMs": 1,
            "source": "COOKIE",
            "markedForDelete": false,
            "trashed": false
          },
          {
            "remote": {
              "id": "pl2",
              "name": "Tai Orathai Mix",
              "trackCount": 2,
              "tracks": [
                {"id": "t2", "name": "Song A", "artists": [{"name": "Tai Orathai"}]},
                {"id": "t3", "name": "Song B", "artists": [{"name": "Tai Orathai"}]}
              ]
            },
            "resolvedTracks": [
              {"remote": {"id": "t2", "name": "Song A", "artists": [{"name": "Tai Orathai"}]}},
              {"remote": {"id": "t3", "name": "Song B", "artists": [{"name": "Tai Orathai"}]}}
            ],
            "importedAtEpochMs": 2,
            "source": "COOKIE",
            "markedForDelete": false,
            "trashed": false
          },
          {
            "remote": {
              "id": "pl3",
              "name": "Country Mix",
              "trackCount": 1,
              "tracks": [
                {"id": "t4", "name": "Thinkin Bout Me", "artists": [{"name": "Morgan Wallen"}]}
              ]
            },
            "resolvedTracks": [
              {"remote": {"id": "t4", "name": "Thinkin Bout Me", "artists": [{"name": "Morgan Wallen"}]}}
            ],
            "importedAtEpochMs": 3,
            "source": "COOKIE",
            "markedForDelete": false,
            "trashed": false
          }
        ]
    """.trimIndent()

    private val looseSingle = """
        {
          "name": "One Off",
          "tracks": [
            {"name": "Solo Track", "artists": ["Someone"], "durationMs": 1000}
          ]
        }
    """.trimIndent()

    @Test
    fun fullExportArrayYieldsAllPlaylists() {
        val parsed = repository.parsePlaylistJsonFile(fullExport)
        assertEquals(3, parsed.size, parsed.map { it.name }.toString())
        assertEquals(listOf("Sweet", "Tai Orathai Mix", "Country Mix"), parsed.map { it.name })
        assertEquals(2, parsed[1].remote.tracks.size)
    }

    @Test
    fun looseSingleObjectYieldsOnePlaylist() {
        val parsed = repository.parsePlaylistJsonFile(looseSingle)
        assertEquals(1, parsed.size)
        assertEquals("One Off", parsed[0].name)
        assertEquals(1, parsed[0].remote.tracks.size)
    }

    @Test
    fun fullExportKeepsRemoteIds() {
        val parsed = repository.parsePlaylistJsonFile(fullExport)
        assertEquals(
            listOf("pl1", "pl2", "pl3"),
            parsed.map { it.remote.id },
        )
        assertTrue(parsed.all { !it.trashed && !it.markedForDelete })
    }
}
