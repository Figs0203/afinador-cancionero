package io.github.figs0203.afinador

import io.github.figs0203.afinador.music.ChordShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordShapesTest {

    @Test
    fun knowsTheOpenChordsPlayersExpect() {
        assertEquals(listOf(-1, 3, 2, 0, 1, 0), ChordShapes.find("C")!!.frets)
        assertEquals(listOf(-1, -1, 0, 2, 3, 2), ChordShapes.find("D")!!.frets)
        assertEquals(listOf(0, 2, 2, 1, 0, 0), ChordShapes.find("E")!!.frets)
        assertEquals(listOf(3, 2, 0, 0, 0, 3), ChordShapes.find("G")!!.frets)
        assertEquals(listOf(-1, 0, 2, 2, 2, 0), ChordShapes.find("A")!!.frets)
        assertEquals(listOf(1, 3, 3, 2, 1, 1), ChordShapes.find("F")!!.frets)
        assertEquals(listOf(-1, 0, 2, 2, 1, 0), ChordShapes.find("Am")!!.frets)
        assertEquals(listOf(-1, -1, 0, 2, 3, 1), ChordShapes.find("Dm")!!.frets)
        assertEquals(listOf(-1, 1, 3, 3, 3, 1), ChordShapes.find("Bb")!!.frets)
    }

    @Test
    fun everyRootAndCommonQualityHasAPlayableShape() {
        val qualities = listOf(
            "", "m", "7", "m7", "maj7", "sus2", "sus4", "7sus4",
            "6", "m6", "add9", "madd9", "9", "5", "dim", "dim7", "aug"
        )
        val roots = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        for (root in roots) {
            for (quality in qualities) {
                val name = root + quality
                val shape = ChordShapes.find(name) ?: error("sin diagrama para $name")
                assertEquals("$name debe tener seis cuerdas", 6, shape.frets.size)
                assertTrue(
                    "$name necesita al menos tres cuerdas sonando",
                    shape.frets.count { it >= 0 } >= 3
                )
                assertTrue(
                    "$name tiene trastes imposibles: ${shape.frets}",
                    shape.frets.all { it in -1..15 }
                )
                val played = shape.frets.filter { it > 0 }
                if (played.isNotEmpty()) {
                    assertTrue(
                        "$name se sale de la ventana de cinco trastes",
                        played.max() <= shape.baseFret + 4
                    )
                    assertTrue("$name empieza antes de su cejilla", played.min() >= shape.baseFret)
                }
            }
        }
    }

    @Test
    fun movesBarreChordsUpTheNeck() {
        assertEquals(4, ChordShapes.find("C#m")!!.baseFret)
        assertEquals(listOf(-1, 4, 6, 6, 5, 4), ChordShapes.find("C#m")!!.frets)
        assertEquals(1, ChordShapes.find("Gm")!!.baseFret)
        assertEquals(listOf(3, 5, 5, 3, 3, 3), ChordShapes.find("Gm")!!.frets)
    }

    @Test
    fun slashChordsKeepTheMainShape() {
        val shape = ChordShapes.find("C/G")!!
        assertEquals(listOf(-1, 3, 2, 0, 1, 0), shape.frets)
    }

    @Test
    fun leavesUnknownQualitiesWithoutADiagram() {
        assertNull(ChordShapes.find("C7b5"))
        assertNull(ChordShapes.find("Cmaj9"))
        assertNull(ChordShapes.find("Hm"))
    }
}
