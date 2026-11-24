package app.localchapter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterSkipTest {
    private val durations = listOf(60_000L, 12_000L, 90_000L)

    @Test fun carriesRemainingTimeInBothDirections() {
        assertEquals(ChapterPosition(0, 45_000), skip(1, 3_000, -18_000))
        assertEquals(ChapterPosition(1, 7_000), skip(0, 37_000, 30_000))
        assertEquals(ChapterPosition(0, 44_000), skip(2, 2_000, -30_000))
        assertEquals(ChapterPosition(2, 8_000), skip(0, 50_000, 30_000))
    }

    @Test fun handlesExactBoundariesAndBookEnds() {
        assertEquals(ChapterPosition(1, 0), skip(0, 30_000, 30_000))
        assertEquals(ChapterPosition(1, 0), skip(1, 7_000, -7_000))
        assertEquals(ChapterPosition(0, 0), skip(0, 4_000, -30_000))
        assertEquals(ChapterPosition(2, 90_000), skip(2, 84_000, 30_000))
    }

    @Test fun requestsDurationOnlyWhenNeeded() {
        assertNull(resolveChapterSkip(ChapterPosition(1, 5_000), -30_000, 3) { null })
        assertEquals(ChapterPosition(1, 5_000),
            resolveChapterSkip(ChapterPosition(1, 8_000), -3_000, 3) { null })
    }

    private fun skip(index: Int, position: Long, offset: Long) =
        resolveChapterSkip(ChapterPosition(index, position), offset, durations.size) { durations[it] }
}
