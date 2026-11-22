package io.github.figs0203.afinador.songbook

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Imports a chord sheet from a public song page.
 *
 * Both sites render the song inside a `<pre>` block (Ultimate Guitar additionally
 * keeps a JSON copy in `data-content`), so the text is recovered from there and
 * everything else — navigation, ads, scripts — is discarded. Only the plain text
 * is kept; nothing from the page is loaded into a WebView, which is what removes
 * the ads in the first place.
 *
 * If a page cannot be read (paywall, layout change, captcha) the caller shows the
 * error and the user can paste the text manually, so importing never silently
 * produces an empty song.
 */
object SongImporter {

    open class ImportException(message: String) : Exception(message)

    /**
     * La descarga completa de una página (no una sola lectura) superó el tiempo
     * máximo. Sin este tope, una página servida a cuentagotas puede mantener la
     * conexión viva y dejar la importación esperando para siempre.
     */
    class TimeoutException(message: String) : ImportException(message)

    data class Imported(val title: String, val artist: String, val text: String)

    private val PRE_BLOCK = Regex("(?is)<pre[^>]*>(.*?)</pre>")
    private val DATA_CONTENT = Regex("(?is)data-content=\"([^\"]+)\"")
    private val META_TITLE = Regex("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]*>")
    private val META_TITLE_CONTENT = Regex("(?i)content=[\"']([^\"']+)[\"']")
    private val DOC_TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val URL_PATTERN = Regex("https?://[^\\s\"'<>]+")

    /**
     * Ultimate Guitar's JSON copy of a tab wraps chords in [ch]…[/ch] and tab
     * sections in [tab]…[/tab]. Section headers such as [Chorus] are real text
     * and must survive, so only these lowercase paired tags are dropped.
     */
    private val CHORDPRO_TAGS = Regex("(?i)\\[/?(?:ch|tab)\\]")

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    fun extractUrl(text: String): String? = URL_PATTERN.find(text)?.value

    fun isUrl(text: String): Boolean {
        val value = text.trim()
        return URL_PATTERN.matches(value) && value.substringAfter("://").contains('.')
    }

    /** Accepts a full URL or a bare "cifraclub.com.br/…" the user typed. */
    fun normalizeUrl(text: String): String? {
        val candidate = extractUrl(text) ?: text.trim()
        if (candidate.isBlank()) return null
        val withScheme =
            if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
                candidate
            } else {
                "https://$candidate"
            }
        return if (isUrl(withScheme)) withScheme else null
    }

    fun labelFor(url: String): String {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull().orEmpty()
        return when {
            host.contains("ultimate-guitar") -> "Ultimate Guitar"
            host.contains("cifraclub") -> "CifraClub"
            else -> host.ifBlank { "Web" }
        }
    }

    fun importFromUrl(
        url: String,
        attempts: Int = 3,
        connectTimeout: Int = 20_000,
        readTimeout: Int = 45_000
    ): Imported = extract(fetch(url, attempts, connectTimeout, readTimeout), url)

    /**
     * Descarga la página con varios intentos: Ultimate Guitar responde a veces
     * con 500/"Check 1,2..." o se queda colgado bajo carga, así que un reintento
     * tras una pausa corta resuelve la mayoría de los fallos transitorios.
     */
    fun fetch(
        url: String,
        attempts: Int = 3,
        connectTimeout: Int = 20_000,
        readTimeout: Int = 45_000,
        deadlineMs: Long = 0L,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): String {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return fetchOnce(url, connectTimeout, readTimeout, deadlineMs, onProgress)
            } catch (error: TimeoutException) {
                // Se agotó el tiempo total: reintentar solo alarga la espera.
                throw error
            } catch (error: ImportException) {
                // Los errores HTTP definitivos (403/429/404) no mejoran reintentando.
                if (error.message?.contains("bloqueado") == true ||
                    error.message?.contains("error 4") == true
                ) throw error
                lastError = error
                if (attempt < attempts - 1) runCatching { Thread.sleep(1500L * (attempt + 1)) }
            } catch (error: Exception) {
                lastError = error
                if (attempt < attempts - 1) runCatching { Thread.sleep(1500L * (attempt + 1)) }
            }
        }
        throw ImportException(
            lastError?.message ?: "No se pudo descargar la página: sin conexión"
        )
    }

    private fun fetchOnce(
        url: String,
        connectTimeout: Int,
        readTimeout: Int,
        deadlineMs: Long,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?
    ): String {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw ImportException("Enlace no válido")
        }

        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        // Ultimate Guitar redirige www -> es. (u otra localización) según el
        // idioma pedido, y la página de listas compartidas responde 500 cuando se
        // pide en español. Pedir inglés evita esa redirección y devuelve 200.
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ImportException(
                    when (code) {
                        403, 429 -> "El sitio ha bloqueado la descarga ($code). " +
                            "Abre la página, copia el texto y pégalo aquí."
                        500, 502, 503, 504 -> "Ultimate Guitar no responde ahora " +
                            "(mantenimiento o sobrecarga, $code). Intenta más tarde " +
                            "o abre la página y pega el texto."
                        else -> "La página respondió con un error $code"
                    }
                )
            }
            val startedAt = System.currentTimeMillis()
            val counted = CountingInputStream(connection.inputStream)
            val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(counted)
            } else {
                counted
            }
            // Tamaño del cuerpo tal como llega por la red; -1 si el servidor no
            // lo declara (o si Android descomprime el gzip por su cuenta).
            val total = connection.contentLength.toLong().coerceAtLeast(0L)
            onProgress?.invoke(0L, total)

            val body = StringBuilder(
                if (total in 1_024L..33_554_432L) total.toInt() else 0
            )
            val buffer = CharArray(16 * 1024)
            var lastReport = startedAt
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    // Límite total, no por lectura: una página que gotea bytes
                    // despacio mantendría viva la conexión para siempre.
                    if (deadlineMs > 0 && System.currentTimeMillis() - startedAt > deadlineMs) {
                        throw TimeoutException(
                            "La descarga tardó más de ${deadlineMs / 1000L} s"
                        )
                    }
                    val read = reader.read(buffer)
                    if (read < 0) break
                    body.append(buffer, 0, read)
                    val now = System.currentTimeMillis()
                    if (onProgress != null && now - lastReport >= 250L) {
                        lastReport = now
                        onProgress(counted.count, total)
                    }
                }
            }
            onProgress?.invoke(counted.count, total)

            val bodyText = body.toString()
            if (bodyText.contains("Check 1,2", ignoreCase = true)) {
                throw ImportException(
                    "Ultimate Guitar está en mantenimiento (\"Check 1,2...\"). " +
                        "Intenta más tarde o abre la página y pega el texto."
                )
            }
            return bodyText
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("No se pudo descargar la página: ${error.message ?: "sin conexión"}")
        } finally {
            connection.disconnect()
        }
    }

    /** Counts the bytes that actually came through the socket, for progress. */
    private class CountingInputStream(input: java.io.InputStream) :
        java.io.FilterInputStream(input) {

        var count = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) count += read
            return read
        }
    }

    fun extract(html: String, url: String): Imported {
        val cleaned = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), "\n")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), "\n")
            .replace(Regex("(?s)<!--.*?-->"), "")

        val candidates = mutableListOf<String>()
        PRE_BLOCK.findAll(cleaned).forEach { match ->
            candidates.add(htmlToText(match.groupValues[1]))
        }
        DATA_CONTENT.findAll(cleaned).forEach { match ->
            runCatching { jsonCandidates(unescapeEntities(match.groupValues[1])) }
                .getOrNull()
                ?.let { candidates.addAll(it) }
        }

        val best = candidates
            .map { it.trim('\n', ' ', '\t') }
            .filter { it.isNotBlank() }
            .maxByOrNull { score(it) }

        if (best == null || score(best) == 0 || best.lineSequence().count() < 2) {
            throw ImportException(
                "No se encontró la tablatura en la página. " +
                    "Ábrela en el navegador, copia el texto y pégalo aquí."
            )
        }

        val (title, artist) = titleAndArtist(html, url)
        return Imported(title = title, artist = artist, text = normalizeSheet(best))
    }

    private fun score(text: String): Int {
        var score = 0
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            when {
                ChordSheet.isChordLine(line) -> score += 5
                trimmed.count { it == '-' } >= 4 -> score += 3
                trimmed.length > 8 -> score += 1
            }
        }
        return score
    }

    private fun normalizeSheet(text: String): String = text
        .replace("\r\n", "\n")
        .replace(Regex("\n{4,}"), "\n\n\n")
        .trim('\n')

    /**
     * Ultimate Guitar stores the tab as escaped HTML inside `data-content`;
     * walk the JSON and keep every string that looks like a sheet.
     */
    private fun jsonCandidates(decoded: String): List<String> {
        if (!decoded.trimStart().startsWith("{")) return emptyList()
        val root = JSONObject(decoded)
        val found = mutableListOf<String>()

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> walk(value.opt(key)) }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
                is String -> if (value.contains('\n')) {
                    found.add(htmlToText(unescapeEntities(value)))
                }
            }
        }

        walk(root)
        return found
    }

    private fun htmlToText(fragment: String): String {
        var text = fragment
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6])>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), "")

        text = unescapeEntities(text)
        text = text.replace(CHORDPRO_TAGS, "")
        text = text.replace('\u00A0', ' ').replace("\u200B", "")
        text = text.split('\n').joinToString("\n") { it.trimEnd() }
        return text.trim('\n', ' ', '\t')
    }

    // Los títulos de Ultimate Guitar escapan los acentos con entidades HTML
    // (por ejemplo "Seegra&szlig;spinnerei"), así que hay que deshacerlas.
    private val NAMED_ENTITIES = mapOf(
        "szlig" to "ß",
        "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
        "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç",
        "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
        "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï",
        "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô",
        "otilde" to "õ", "ouml" to "ö", "oslash" to "ø", "oelig" to "œ",
        "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü",
        "yacute" to "ý", "yuml" to "ÿ", "thorn" to "þ", "eth" to "ð",
        "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã",
        "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç",
        "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë",
        "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î", "Iuml" to "Ï",
        "Ntilde" to "Ñ", "Ograve" to "Ò", "Oacute" to "Ó", "Ocirc" to "Ô",
        "Otilde" to "Õ", "Ouml" to "Ö", "Oslash" to "Ø", "OElig" to "Œ",
        "Ugrave" to "Ù", "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü",
        "Yacute" to "Ý", "Thorn" to "Þ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "middot" to "·",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "bull" to "•", "deg" to "°",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€",
    )

    internal fun unescapeEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        // Primero las entidades numéricas y después las con nombre, igual que
        // antes: así un `&#38;szlig;` sigue produciendo «ß».
        var value = decodeEntities(text, named = false)
        value = decodeEntities(value, named = true)
        // Las entidades básicas se deshacen al final para que un `&amp;quot;`
        // no se convierta en una comilla de más.
        value = replaceLiteral(value, "&nbsp;", " ")
        value = replaceLiteral(value, "&quot;", "\"")
        value = replaceLiteral(value, "&apos;", "'")
        value = replaceLiteral(value, "&#39;", "'")
        value = replaceLiteral(value, "&lt;", "<")
        value = replaceLiteral(value, "&gt;", ">")
        value = replaceLiteral(value, "&amp;", "&")
        return value
    }

    /**
     * Deshace entidades HTML recorriendo el texto una sola vez.
     *
     * No se usan expresiones regulares con transformación (`Regex.replace {}`):
     * en Android cada coincidencia vuelve a empezar el emparejamiento sobre el
     * texto completo. Una lista de mil canciones trae cientos de miles de
     * `&quot;`, así que ese camino se quedaba horas en «Analizando la lista…».
     * Con un `StringBuilder` y `indexOf` el coste es lineal.
     */
    private fun decodeEntities(text: String, named: Boolean): String {
        var amp = text.indexOf('&')
        if (amp < 0) return text
        val out = StringBuilder(text.length)
        var copied = 0
        while (amp >= 0) {
            val end = if (named) namedEntityEnd(text, amp) else numericEntityEnd(text, amp)
            if (end > 0) {
                out.append(text, copied, amp)
                val decoded =
                    if (named) NAMED_ENTITIES[text.substring(amp + 1, end - 1)]
                    else numericEntity(text, amp, end)
                if (decoded != null) out.append(decoded) else out.append(text, amp, end)
                copied = end
                amp = text.indexOf('&', copied)
            } else {
                amp = text.indexOf('&', amp + 1)
            }
        }
        out.append(text, copied, text.length)
        return out.toString()
    }

    /** `&#x1F600;` o `&#128512;`, con los mismos límites que antes. */
    private fun numericEntityEnd(text: String, start: Int): Int {
        var index = start + 1
        if (index >= text.length || text[index] != '#') return -1
        index++
        if (index < text.length && text[index] == 'x') {
            index++
            val digits = index
            while (index < text.length && index - digits < 6 && isHexDigit(text[index])) index++
            if (index == digits) return -1
            return if (index < text.length && text[index] == ';') index + 1 else -1
        }
        val digits = index
        while (index < text.length && index - digits < 7 && text[index] in '0'..'9') index++
        if (index == digits) return -1
        return if (index < text.length && text[index] == ';') index + 1 else -1
    }

    /** `&szlig;`: una letra y hasta 31 caracteres alfanuméricos. */
    private fun namedEntityEnd(text: String, start: Int): Int {
        var index = start + 1
        if (index >= text.length || !isAsciiLetter(text[index])) return -1
        val nameStart = index
        index++
        while (index < text.length && index - nameStart < 32 && isAsciiLetterOrDigit(text[index])) index++
        if (index - nameStart < 2) return -1
        return if (index < text.length && text[index] == ';') index + 1 else -1
    }

    private fun numericEntity(text: String, start: Int, end: Int): String? {
        val hex = start + 2 < end && text[start + 2] == 'x'
        val digitsStart = if (hex) start + 3 else start + 2
        val code = text.substring(digitsStart, end - 1)
            .toIntOrNull(if (hex) 16 else 10) ?: return null
        return runCatching { String(Character.toChars(code)) }.getOrNull()
    }

    private fun replaceLiteral(text: String, target: String, replacement: String): String {
        var found = text.indexOf(target)
        if (found < 0) return text
        val out = StringBuilder(text.length)
        var copied = 0
        while (found >= 0) {
            out.append(text, copied, found)
            out.append(replacement)
            copied = found + target.length
            found = text.indexOf(target, copied)
        }
        out.append(text, copied, text.length)
        return out.toString()
    }

    private fun isAsciiLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'

    private fun isAsciiLetterOrDigit(c: Char) = isAsciiLetter(c) || c in '0'..'9'

    private fun isHexDigit(c: Char) =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    // ------------------------------------------------------------ metadata

    private fun titleAndArtist(html: String, url: String): Pair<String, String> {
        val raw = META_TITLE.find(html)?.value
            ?.let { META_TITLE_CONTENT.find(it)?.groupValues?.get(1) }
            ?: DOC_TITLE.find(html)?.groupValues?.get(1)

        val metadata = raw?.let { unescapeEntities(it) }?.trim().orEmpty()
        val parsed = parseTitle(metadata, url)
        if (parsed.first.isNotBlank()) return parsed
        return fromUrl(url)
    }

    /**
     * Both sites put the type of page in the title, in English and in Spanish:
     * "Wonderwall Chords by Oasis", "WONDERWALL ACORDES (ver 2) por Oasis".
     * The artist is stripped first, then the leftovers.
     */
    private fun parseTitle(raw: String, url: String): Pair<String, String> {
        var text = raw
            .replace(Regex("(?i)\\s*@\\s*ultimate[- ]guitar.*$"), "")
            .replace(Regex("(?i)\\s*[-|–—]\\s*cifra ?club.*$"), "")
            .trim()

        var artist = ""
        Regex(
            "(?i)^(.+?)\\s+(?:chords?|acordes?|cifras?|tabs?|bass tabs?|ukulele chords|power tab|drums?)" +
                "(?:\\s*\\(ver\\.?\\s*\\d+\\))?\\s+(?:by|por)\\s+(.+)$"
        ).find(text)?.let { match ->
            text = match.groupValues[1].trim()
            artist = match.groupValues[2].trim().trim('-', '–', '—').trim()
        }

        if (artist.isEmpty()) {
            Regex("(?i)^(.+?)\\s+(?:by|por)\\s+(.+)$").find(text)?.let { match ->
                text = match.groupValues[1].trim()
                artist = match.groupValues[2].trim()
            }
        }

        text = text
            .replace(Regex("(?i)\\s*\\(ver\\.?\\s*\\d+\\)\\s*$"), "")
            .replace(Regex("(?i)\\s*\\((?:acordes?|cifras?|tabs?|chords?)\\)\\s*$"), "")
            .replace(Regex("(?i)\\s+(?:chords?|acordes?|cifras?|tabs?|drums?)$"), "")
            .trim()

        if (artist.isEmpty()) {
            val dash = Regex("\\s+[-–—]\\s+|\\s+\\|\\s+").find(text)
            if (dash != null) {
                val left = text.substring(0, dash.range.first).trim()
                val right = text.substring(dash.range.last + 1).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    // Ultimate Guitar sorts it as "artist - song", CifraClub as "song - artist".
                    return when {
                        url.contains("ultimate-guitar", ignoreCase = true) -> right.take(120) to left.take(120)
                        url.contains("cifraclub", ignoreCase = true) -> left.take(120) to right.take(120)
                        else -> left.take(120) to right.take(120)
                    }
                }
            }
        }

        return text.take(120) to artist.take(120)
    }

    /** Last resort: derive a readable title from the link path. */
    internal fun fromUrl(url: String): Pair<String, String> {
        val parts = runCatching { URL(url).path }
            .getOrNull()
            .orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return "" to ""

        val (artistSlug, songSlug) = when {
            parts.size >= 3 && parts[0].equals("tab", true) -> parts[1] to parts[2]
            parts.size >= 2 -> parts[0] to parts[1]
            else -> "" to parts[0]
        }
        // "wonderwall-chords-27596" -> "wonderwall"
        val song = songSlug
            .replace(Regex("-\\d+$"), "")
            .replace(
                Regex("-(?:chords?|tabs?|cifras?|acordes?|bass|ukulele|drums|power)$", RegexOption.IGNORE_CASE),
                ""
            )
        return prettify(song) to prettify(artistSlug)
    }

    private fun prettify(slug: String): String = slug
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .take(120)
}
