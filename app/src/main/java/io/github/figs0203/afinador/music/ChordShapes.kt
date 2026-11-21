package io.github.figs0203.afinador.music

/**
 * Fingering of one chord: six frets from the low E string to the high e string,
 * where -1 means the string is not played and 0 means it is played open.
 *
 * [baseFret] is the first fret drawn. Open chords start at the nut; a barre
 * chord high up the neck starts at the fret where its index finger sits, so the
 * diagram stays readable instead of showing ten empty frets.
 */
data class ChordShape(
    val frets: List<Int>,
    val baseFret: Int = 1
)

/**
 * Guitar fingerings, offline and without a dependency.
 *
 * Open-position shapes are listed explicitly because they are the ones players
 * expect; every other chord is built by moving a standard E-shape or A-shape
 * barre form to the fret of its root. That covers all twelve roots for the
 * qualities the sheet parser understands, so tapping almost any chord in a song
 * shows a correct diagram. A quality outside the list returns null and the
 * screen explains that the fingering is not available instead of drawing a
 * wrong chord.
 */
object ChordShapes {

    private enum class Family {
        MAJOR, MINOR, SEVEN, MINOR_SEVEN, MAJOR_SEVEN,
        SUS2, SUS4, SEVEN_SUS4, SIX, MINOR_SIX,
        ADD9, MINOR_ADD9, NINE, FIVE, DIM, DIM_SEVEN, AUG
    }

    /** A barre shape and the open string its root sits on (4 = low E, 9 = A). */
    private data class Form(val rootString: Int, val frets: List<Int>)

    private val FORMS: Map<Family, List<Form>> = mapOf(
        Family.MAJOR to listOf(
            Form(4, listOf(0, 2, 2, 1, 0, 0)),
            Form(9, listOf(-1, 0, 2, 2, 2, 0))
        ),
        Family.MINOR to listOf(
            Form(4, listOf(0, 2, 2, 0, 0, 0)),
            Form(9, listOf(-1, 0, 2, 2, 1, 0))
        ),
        Family.SEVEN to listOf(
            Form(4, listOf(0, 2, 0, 1, 0, 0)),
            Form(9, listOf(-1, 0, 2, 0, 2, 0))
        ),
        Family.MINOR_SEVEN to listOf(
            Form(4, listOf(0, 2, 0, 0, 0, 0)),
            Form(9, listOf(-1, 0, 2, 0, 1, 0))
        ),
        Family.MAJOR_SEVEN to listOf(
            Form(4, listOf(0, 2, 1, 1, 0, 0)),
            Form(9, listOf(-1, 0, 2, 1, 2, 0))
        ),
        Family.SUS2 to listOf(
            Form(4, listOf(0, 2, 4, 4, 0, 0)),
            Form(9, listOf(-1, 0, 2, 2, 0, 0))
        ),
        Family.SUS4 to listOf(
            Form(4, listOf(0, 2, 2, 2, 0, 0)),
            Form(9, listOf(-1, 0, 2, 2, 3, 0))
        ),
        Family.SEVEN_SUS4 to listOf(
            Form(4, listOf(0, 2, 0, 2, 0, 0)),
            Form(9, listOf(-1, 0, 2, 0, 3, 0))
        ),
        Family.SIX to listOf(
            Form(4, listOf(0, 2, 2, 1, 2, 0)),
            Form(9, listOf(-1, 0, 2, 2, 2, 2))
        ),
        Family.MINOR_SIX to listOf(
            Form(4, listOf(0, 2, 2, 0, 2, 0)),
            Form(9, listOf(-1, 0, 2, 2, 1, 2))
        ),
        Family.ADD9 to listOf(
            Form(4, listOf(0, 2, 2, 1, 0, 2)),
            Form(9, listOf(-1, 0, 2, 4, 2, 0))
        ),
        Family.MINOR_ADD9 to listOf(
            Form(4, listOf(0, 2, 2, 0, 0, 2)),
            Form(9, listOf(-1, 0, 2, 4, 1, 0))
        ),
        Family.NINE to listOf(
            Form(4, listOf(0, 2, 0, 1, 0, 2)),
            Form(9, listOf(-1, 0, 2, 4, 2, 3))
        ),
        Family.FIVE to listOf(
            Form(4, listOf(0, 2, 2, -1, -1, -1)),
            Form(9, listOf(-1, 0, 2, 2, -1, -1))
        ),
        Family.DIM to listOf(
            Form(4, listOf(0, 1, 2, 0, -1, -1)),
            Form(9, listOf(-1, 0, 1, 2, 1, -1))
        ),
        Family.DIM_SEVEN to listOf(
            Form(4, listOf(0, 1, 2, 0, 2, -1)),
            Form(9, listOf(-1, 0, 1, 2, 1, 2))
        ),
        Family.AUG to listOf(
            Form(4, listOf(0, 3, 2, 1, 1, 0)),
            Form(9, listOf(-1, 0, 3, 2, 2, 1))
        )
    )

    /**
     * Open chords, keyed by root semitone (C = 0) and family. They win over the
     * movable shapes because they are the voicing a player recognises.
     */
    private val OPEN: Map<String, List<Int>> = mapOf(
        key(0, Family.MAJOR) to listOf(-1, 3, 2, 0, 1, 0),   // C
        key(2, Family.MAJOR) to listOf(-1, -1, 0, 2, 3, 2),  // D
        key(4, Family.MAJOR) to listOf(0, 2, 2, 1, 0, 0),    // E
        key(7, Family.MAJOR) to listOf(3, 2, 0, 0, 0, 3),    // G
        key(9, Family.MAJOR) to listOf(-1, 0, 2, 2, 2, 0),   // A
        key(2, Family.MINOR) to listOf(-1, -1, 0, 2, 3, 1),  // Dm
        key(0, Family.SEVEN) to listOf(-1, 3, 2, 3, 1, 0),   // C7
        key(2, Family.SEVEN) to listOf(-1, -1, 0, 2, 1, 2),  // D7
        key(7, Family.SEVEN) to listOf(3, 2, 0, 0, 0, 1),    // G7
        key(11, Family.SEVEN) to listOf(-1, 2, 1, 2, 0, 2),  // B7
        key(2, Family.MINOR_SEVEN) to listOf(-1, -1, 0, 2, 1, 1), // Dm7
        key(0, Family.MAJOR_SEVEN) to listOf(-1, 3, 2, 0, 0, 0),  // Cmaj7
        key(2, Family.MAJOR_SEVEN) to listOf(-1, -1, 0, 2, 2, 2), // Dmaj7
        key(7, Family.MAJOR_SEVEN) to listOf(3, 2, 0, 0, 0, 2),   // Gmaj7
        key(2, Family.SUS2) to listOf(-1, -1, 0, 2, 3, 0),   // Dsus2
        key(4, Family.SUS2) to listOf(0, 2, 4, 4, 0, 0),     // Esus2
        key(9, Family.SUS2) to listOf(-1, 0, 2, 2, 0, 0),    // Asus2
        key(2, Family.SUS4) to listOf(-1, -1, 0, 2, 3, 3),   // Dsus4
        key(9, Family.SUS4) to listOf(-1, 0, 2, 2, 3, 0),    // Asus4
        key(0, Family.ADD9) to listOf(-1, 3, 2, 0, 3, 0),    // Cadd9
        key(0, Family.SIX) to listOf(-1, 3, 2, 2, 1, 0),     // C6
        key(9, Family.MINOR_SIX) to listOf(-1, 0, 2, 2, 1, 2), // Am6
        key(2, Family.MINOR_SIX) to listOf(-1, -1, 0, 2, 0, 1), // Dm6
        key(11, Family.DIM) to listOf(-1, 2, 3, 4, 3, -1)    // Bdim
    )

    private fun key(root: Int, family: Family) = "$root:${family.name}"

    /**
     * Diagram for a chord token as written in the sheet ("C", "F#m7", "C/G").
     * Slash basses keep the main chord shape; the screen shows the bass note
     * separately. Returns null when the quality has no shape in the library.
     */
    fun find(chord: String): ChordShape? {
        val symbol = Chords.symbolOf(chord) ?: return null
        val family = familyOf(symbol.quality) ?: return null
        val frets = OPEN[key(symbol.root, family)] ?: movable(symbol.root, family) ?: return null
        return ChordShape(frets, baseFretOf(frets))
    }

    /** Moves every form of [family] to [root] and keeps the lowest one. */
    private fun movable(root: Int, family: Family): List<Int>? =
        FORMS[family]?.map { form ->
            val position = Math.floorMod(root - form.rootString, 12)
            form.frets.map { fret -> if (fret < 0) -1 else fret + position }
        }?.minByOrNull { shape -> shape.filter { it > 0 }.maxOrNull() ?: 0 }

    private fun familyOf(quality: String): Family? {
        val clean = quality.replace("(", "").replace(")", "").trim()
        return when (clean) {
            "", "maj", "M", "Δ", "major" -> Family.MAJOR
            "m", "min", "-", "minor" -> Family.MINOR
            "7" -> Family.SEVEN
            "m7", "min7" -> Family.MINOR_SEVEN
            "maj7", "M7", "Δ7" -> Family.MAJOR_SEVEN
            "sus2" -> Family.SUS2
            "sus", "sus4" -> Family.SUS4
            "7sus", "7sus4" -> Family.SEVEN_SUS4
            "6" -> Family.SIX
            "m6", "min6" -> Family.MINOR_SIX
            "add9" -> Family.ADD9
            "madd9", "minadd9" -> Family.MINOR_ADD9
            "9" -> Family.NINE
            "5" -> Family.FIVE
            "dim", "°" -> Family.DIM
            "dim7", "°7" -> Family.DIM_SEVEN
            "aug", "+", "#5" -> Family.AUG
            else -> null
        }
    }

    /**
     * The nut is only drawn for shapes that fit near it; anything that reaches
     * past the fifth fret starts at its lowest fretted note so the dots stay
     * inside the five-fret window.
     */
    private fun baseFretOf(frets: List<Int>): Int {
        val played = frets.filter { it > 0 }
        if (played.isEmpty()) return 1
        return if (played.max() <= 5) 1 else played.min()
    }
}
