package io.github.figs0203.afinador

import io.github.figs0203.afinador.songbook.PlaylistImporter
import io.github.figs0203.afinador.songbook.SongImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImporterTest {

    private val playlistJson = """
        {"store":{"page":{"data":{"songbook":{"id":1,"name":"Fiesta","tabsCount":3,"tabs":[
          {"tab":{"song_name":"Wonderwall","artist_name":"Oasis","tab_url":"https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-27596","type_name":"Chords"}},
          {"tab":{"song_name":"Iris","artist_name":"Goo Goo Dolls","tab_url":"https://tabs.ultimate-guitar.com/tab/goo-goo-dolls/iris-chords-54512","type_name":"Chords"}},
          {"tab":{"song_name":"Wonderwall","artist_name":"Oasis","tab_url":"https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-27596","type_name":"Chords"}}
        ]}}}}}
    """.trimIndent()

    private fun playlistPage(json: String): String {
        val escaped = json.replace("\"", "&quot;")
        return """
            <html>
            <head><title>Fiesta @ Ultimate-Guitar.Com</title></head>
            <body>
              <div class="js-store" data-content="$escaped"></div>
              <div id="react-root"></div>
            </body>
            </html>
        """.trimIndent()
    }

    @Test
    fun readsTheNameAndEveryTabOfTheList() {
        val playlist = PlaylistImporter.parse(playlistPage(playlistJson))

        assertEquals("Fiesta", playlist.name)
        assertEquals(
            "the list must keep its order and drop repeated links",
            listOf("Wonderwall", "Iris"),
            playlist.entries.map { it.title }
        )
        assertEquals(listOf("Oasis", "Goo Goo Dolls"), playlist.entries.map { it.artist })
        assertTrue(
            playlist.entries.first().url
                .startsWith("https://tabs.ultimate-guitar.com/tab/oasis/wonderwall")
        )
    }

    @Test
    fun fallsBackToTheLinkWhenTheNameIsMissing() {
        val json = """
            {"page":{"data":{"songbook":{"name":"","tabs":[
              {"tab":{"tab_url":"https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-27596"}}
            ]}}}}
        """.trimIndent()

        val entry = PlaylistImporter.parse(playlistPage(json)).entries.single()
        assertEquals("Wonderwall", entry.title)
        assertEquals("Oasis", entry.artist)
    }

    @Test
    fun findsTheListEvenWhenThePageStructureChanges() {
        // The tabs may sit elsewhere in the page JSON; the reader must follow them.
        val json = """
            {"preload":{"list":{"name":"Acústico","tabs":[
              {"tab":{"song_name":"Jolene","artist_name":"Dolly Parton","tab_url":"https://tabs.ultimate-guitar.com/tab/dolly-parton/jolene-chords-183019"}}
            ]}}}
        """.trimIndent()

        val playlist = PlaylistImporter.parse(playlistPage(json))
        assertEquals("Acústico", playlist.name)
        assertEquals("Jolene", playlist.entries.single().title)
    }

    @Test
    fun readsAThousandSongListWithoutCuttingItShort() {
        val tabs = (1..1200).joinToString(",") { index ->
            """{"tab":{"song_name":"Song $index","artist_name":"Artist ${index % 40}","tab_url":"https://tabs.ultimate-guitar.com/tab/artist-$index/song-$index-chords-$index"}}"""
        }
        val json = """{"page":{"data":{"songbook":{"name":"Enorme","tabs":[$tabs]}}}}"""

        val playlist = PlaylistImporter.parse(playlistPage(json))

        assertEquals(1200, playlist.entries.size)
        assertFalse(playlist.truncated)
        assertEquals("Song 1200", playlist.entries.last().title)
    }

    @Test
    fun failsWhenThePageIsNotAPlaylist() {
        val error = assertThrows(SongImporter.ImportException::class.java) {
            PlaylistImporter.parse("<html><body><pre>G D Em C</pre></body></html>")
        }
        assertTrue(error.message!!.contains("lista"))
    }

    @Test
    fun onlyAcceptsSharedUltimateGuitarLists() {
        assertTrue(
            PlaylistImporter.isPlaylistUrl("https://www.ultimate-guitar.com/playlist/shared/vy6zwyg6w3zx")
        )
        assertFalse(
            PlaylistImporter.isPlaylistUrl(
                "https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-27596"
            )
        )
        assertFalse(
            PlaylistImporter.isPlaylistUrl("https://www.cifraclub.com.br/legiao-urbana/tempo-perdido/")
        )
    }
}
