package app.localchapter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackOrderTest {
    @Test fun sortsMixedExtensionsByIntegerThenFilename() {
        val tracks = scan("100 - Epilogue.MP4", "99 - Interview.mp3", "02 - Second.M4A",
            "00 - Prologue.mp3", "002 - Alternate.mp3", "10 - Chapter Ten.mp3",
            "2 - Too short.mp3", "03 - Text.txt", "03- No spaces.mp3")
        assertEquals(listOf("Prologue", "Alternate", "Second", "Chapter Ten", "Interview", "Epilogue"),
            tracks.map { it.title })
        assertEquals(listOf("Missing numbers: 1, 3–9, 11–98", "Duplicate numbers: 2 (×2)"),
            TrackOrder.warnings(tracks))
    }

    @Test fun acceptsArbitraryStartAndLargeIntegers() {
        val tracks = scan("9223372036854775809 - Ending.m4a", "9223372036854775808 - Beginning.mp4")
        assertEquals(listOf("Beginning", "Ending"), tracks.map { it.title })
        assertTrue(TrackOrder.warnings(tracks).isEmpty())
        assertTrue(TrackOrder.warnings(scan("42 - Standalone.mp3")).isEmpty())
    }

    private fun scan(vararg names: String) = TrackOrder.scan(names.map { it to it })
}
