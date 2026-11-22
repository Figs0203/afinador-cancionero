package io.github.figs0203.afinador

import io.github.figs0203.afinador.songbook.SongImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SongImporterTest {

    private val cifraclubHtml = """
        <html>
        <head><title>Tempo Perdido - Legião Urbana - Cifra Club</title></head>
        <body>
          <nav>Menu difícil de leer</nav>
          <div class="cifra_cnt">
            <pre>[Intro] <b>G</b> <b>D</b> <b>Em</b> <b>C</b>
        <b>G</b>                  <b>D</b>
        Todos os dias quando acordo
        </pre>
          </div>
          <script>console.log('ruido')</script>
        </body>
        </html>
    """.trimIndent()

    private val ultimateGuitarHtml = """
        <html>
        <head><title>Wonderwall Chords by Oasis @ Ultimate-Guitar.Com</title></head>
        <body>
          <div class="js-store" data-content="{&quot;tab_view&quot;:&quot;ruido&quot;}"></div>
          <pre>[Intro] <span>Em7</span> <span>G</span> <span>Dsus4</span> <span>A7sus4</span>
        Em7&nbsp;&nbsp;&nbsp;&nbsp;G
        Today is gonna be the day
        </pre>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun extractsTextFromCifraClubPage() {
        val imported = SongImporter.extract(
            cifraclubHtml,
            "https://www.cifraclub.com.br/legiao-urbana/tempo-perdido/"
        )
        assertTrue(imported.text.contains("Todos os dias quando acordo"))
        assertTrue(imported.text.contains("[Intro] G D Em C"))
        assertTrue("the script must be dropped", !imported.text.contains("ruido"))
        assertEquals("Tempo Perdido", imported.title)
        assertEquals("Legião Urbana", imported.artist)
    }

    @Test
    fun extractsTextFromUltimateGuitarPage() {
        val imported = SongImporter.extract(
            ultimateGuitarHtml,
            "https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1234567"
        )
        assertTrue(imported.text.contains("Today is gonna be the day"))
        assertTrue(imported.text.contains("Em7    G"))
        assertTrue(imported.text.contains("Dsus4"))
        assertEquals("Wonderwall", imported.title)
        assertEquals("Oasis", imported.artist)
    }

    @Test
    fun fallsBackToTheLinkForTheTitle() {
        val imported = SongImporter.extract(
            "<html><body><pre>C G Am F\nLetra</pre></body></html>",
            "https://www.cifraclub.com.br/legiao-urbana/tempo-perdido/"
        )
        assertEquals("Tempo Perdido", imported.title)
        assertEquals("Legiao Urbana", imported.artist)
    }

    @Test
    fun dropsChordProMarkupButKeepsSectionHeaders() {
        val html = """
            <html><body><pre>[Intro]
            [ch]Em[/ch]   [ch]G[/ch]
            [Chorus]
            [tab]e|---0---|[/tab]</pre></body></html>
        """.trimIndent()
        val imported = SongImporter.extract(html, "https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-27596")
        assertEquals(true, imported.text.contains("Em   G"))
        assertEquals(true, imported.text.contains("[Chorus]"))
        assertEquals(false, imported.text.contains("[ch]"))
        assertEquals(false, imported.text.contains("[tab]"))
    }

    @Test
    fun readsTheJsonCopyUltimateGuitarEmbedsInThePage() {
        // No <pre>: only the data-content JSON carries the sheet.
        val sheet = "[Intro]\r\r\n[ch]Em[/ch]   [ch]G[/ch]\r\r\nToday is gonna be the day\r\r\n"
        val jsonEscaped = sheet.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")
        // The real page HTML-escapes the quotes of the JSON attribute.
        val attribute = "{\"store\":{\"page\":{\"data\":{\"tab_view\":\"$jsonEscaped\"}}}}".replace("\"", "&quot;")
        val html = "<html><body><div class=\"js-store\" data-content=\"$attribute\"></div></body></html>"
        val imported = SongImporter.extract(html, "https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-27596")
        assertTrue(imported.text.contains("Em   G"))
        assertTrue(imported.text.contains("Today is gonna be the day"))
    }

    @Test
    fun failsClearlyWhenThePageHasNoSheet() {
        assertThrows(SongImporter.ImportException::class.java) {
            SongImporter.extract("<html><body><p>Sin tablatura</p></body></html>", "https://example.com")
        }
    }

    @Test
    fun normalizesLinksTypedByHand() {
        assertEquals(
            "https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1234567",
            SongImporter.normalizeUrl("tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1234567")
        )
    }

    @Test
    fun findsTheLinkInsideSharedText() {
        val shared = "Wonderwall | https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1234567"
        assertEquals(
            "https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1234567",
            SongImporter.extractUrl(shared)
        )
    }

    @Test
    fun decodesAccentedEntitiesFromSharedTitles() {
        assertEquals(
            "Seegraßspinnerei · Legião Urbana",
            SongImporter.unescapeEntities("Seegra&szlig;spinnerei &middot; Legi&atilde;o Urbana")
        )
        assertEquals("&desconocida; &", SongImporter.unescapeEntities("&desconocida; &amp;"))
    }

    @Test
    fun decodesNumericAndBasicEntities() {
        assertEquals(
            "\"Hola\" & <más>",
            SongImporter.unescapeEntities("&quot;Hola&quot; &amp; &lt;más&gt;")
        )
        assertEquals("A B", SongImporter.unescapeEntities("A&nbsp;B"))
        assertEquals("'", SongImporter.unescapeEntities("&#39;"))
        assertEquals("😀", SongImporter.unescapeEntities("&#x1F600;"))
        assertEquals("😀", SongImporter.unescapeEntities("&#128512;"))
        // Las entidades inválidas o incompletas se dejan tal cual.
        assertEquals("&#xZZ; &#12345678; &a;", SongImporter.unescapeEntities("&#xZZ; &#12345678; &a;"))
        // Primero lo numérico y después lo nombrado, como siempre.
        assertEquals("ß", SongImporter.unescapeEntities("&#38;szlig;"))
        assertEquals("&amp;quot;", SongImporter.unescapeEntities("&amp;amp;quot;"))
    }

    @Test(timeout = 10_000)
    fun decodesHundredsOfThousandsOfQuotesQuickly() {
        // Cada página de lista trae cientos de miles de `&quot;`: con el
        // reemplazo por expresión regular y transformación, Android se quedaba
        // horas analizando una lista de mil canciones.
        val input = "{\"title\":\"&quot;x&quot; &amp; &szlig;\"},".repeat(50_000)
        val output = SongImporter.unescapeEntities(input)
        assertFalse(output.contains("&quot;"))
        assertFalse(output.contains("&szlig;"))
        assertEquals(
            input.replace("&quot;", "\"").replace("&szlig;", "ß").replace("&amp;", "&"),
            output
        )
    }

    @Test
    fun labelsTheSourceSite() {
        assertEquals(
            "Ultimate Guitar",
            SongImporter.labelFor("https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1234567")
        )
        assertEquals("CifraClub", SongImporter.labelFor("https://www.cifraclub.com.br/legiao-urbana/"))
    }
}
