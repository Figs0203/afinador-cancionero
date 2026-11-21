package io.github.figs0203.afinador.music

/**
 * Chord symbols: detection, transposition and spelling.
 *
 * The parser is deliberately conservative. It only accepts a chord root (A–G),
 * an optional accidental, a whitelisted quality suffix and an optional slash
 * bass, so ordinary lyric words are never mistaken for chords. Anything it does
 * not understand is returned untouched.
 */
object Chords {

    private val SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /**
     * Quality suffix: m, M, maj7, min, dim, aug, sus2, sus4, add9, 7b5, m7b5,
     * 6/9, (add9)… Anything outside this list makes the token "not a chord".
     */
    private val QUALITY = Regex(
        "(?:maj|min|m|M|dim|aug|sus|add|no|alt|Δ|[+\\-°ºø])*" +
            "(?:\\d{1,2}(?:sus[24]?|add\\d{1,2}|[b#]\\d{1,2}|[+\\-])*)?" +
            "(?:/\\d{1,2})?" +
            "(?:\\([^()]*\\))?"
    )

    private data class Parsed(
        val root: Int,
        val accidental: Char?,
        val quality: String,
        val bass: Parsed? = null
    )

    /** The pieces of a chord symbol, used by the diagram library. */
    data class Symbol(val root: Int, val quality: String, val bass: Int? = null)

    /**
     * Parses a chord token written in a sheet. Roots and basses come back as
     * semitones of the octave (C = 0, C# = 1…) so shapes can be moved to them.
     */
    fun symbolOf(token: String): Symbol? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        val wrapped = trimmed.length > 2 &&
            (trimmed.first() == '(' || trimmed.first() == '[') &&
            (trimmed.last() == ')' || trimmed.last() == ']')
        val inner = if (wrapped) trimmed.substring(1, trimmed.length - 1) else trimmed
        val parsed = parse(normalize(inner)) ?: return null
        return Symbol(parsed.root, parsed.quality, parsed.bass?.root)
    }

    fun isChord(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return false
        val wrapped = trimmed.length > 2 &&
            (trimmed.first() == '(' || trimmed.first() == '[') &&
            (trimmed.last() == ')' || trimmed.last() == ']')
        val inner = if (wrapped) trimmed.substring(1, trimmed.length - 1) else trimmed
        return parse(normalize(inner)) != null
    }

    /**
     * Moves [chord] by [semitones], keeping its quality, slash bass and any
     * surrounding brackets. A chord written with a flat stays flat, one written
     * with a sharp stays sharp; a natural chord follows the direction of the
     * transposition (sharps up, flats down), which is what players expect.
     */
    fun transpose(chord: String, semitones: Int): String {
        if (semitones % 12 == 0) return chord
        val trimmed = chord.trim()
        if (trimmed.isEmpty()) return chord

        val hasWrapper = trimmed.length > 2 &&
            (trimmed.first() == '(' || trimmed.first() == '[') &&
            (trimmed.last() == ')' || trimmed.last() == ']')
        val inner = if (hasWrapper) trimmed.substring(1, trimmed.length - 1) else trimmed

        val parsed = parse(normalize(inner)) ?: return chord
        val spelling = when (parsed.accidental) {
            'b' -> FLAT
            '#' -> SHARP
            else -> if (semitones < 0) FLAT else SHARP
        }

        val rootName = spelling[Math.floorMod(parsed.root + semitones, 12)]
        val bassName = parsed.bass?.let { bass ->
            "/" + spelling[Math.floorMod(bass.root + semitones, 12)]
        }.orEmpty()
        val transposed = rootName + parsed.quality + bassName

        return if (hasWrapper) {
            trimmed.first() + transposed + trimmed.last()
        } else {
            transposed
        }
    }

    private fun parse(token: String): Parsed? {
        if (token.isEmpty()) return null
        val letter = noteIndex(token[0]) ?: return null

        var index = 1
        var accidental: Char? = null
        if (index < token.length && (token[index] == '#' || token[index] == 'b')) {
            accidental = token[index]
            index++
        }

        var rest = token.substring(index)
        var bass: Parsed? = null

        val slash = rest.indexOf('/')
        if (slash >= 0) {
            val tail = rest.substring(slash + 1)
            // "6/9" is a quality, not a slash chord.
            if (!(tail.isNotEmpty() && tail.all { it.isDigit() })) {
                bass = parseSimple(tail) ?: return null
                rest = rest.substring(0, slash)
            }
        }

        if (!QUALITY.matches(rest)) return null
        return Parsed(shift(letter, accidental), accidental, rest, bass)
    }

    /** A bare note such as the bass of "C/G" or "Bb/D". */
    private fun parseSimple(token: String): Parsed? {
        if (token.isEmpty()) return null
        val letter = noteIndex(token[0]) ?: return null

        var index = 1
        var accidental: Char? = null
        if (index < token.length && (token[index] == '#' || token[index] == 'b')) {
            accidental = token[index]
            index++
        }
        if (index != token.length) return null
        return Parsed(shift(letter, accidental), accidental, "")
    }

    /** The written note as a semitone of the octave: B is 11, Bb is 10, C# is 1. */
    private fun shift(letter: Int, accidental: Char?): Int = when (accidental) {
        'b' -> Math.floorMod(letter - 1, 12)
        '#' -> Math.floorMod(letter + 1, 12)
        else -> letter
    }

    private fun noteIndex(letter: Char): Int? = when (letter) {
        'C' -> 0
        'D' -> 2
        'E' -> 4
        'F' -> 5
        'G' -> 7
        'A' -> 9
        'B' -> 11
        else -> null
    }

    private fun normalize(text: String): String = text
        .replace('♯', '#')
        .replace('♭', 'b')
        .replace("♮", "")
}
