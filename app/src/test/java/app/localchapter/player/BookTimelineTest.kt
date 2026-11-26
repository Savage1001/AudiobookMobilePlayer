package app.localchapter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookTimelineTest {
    private val timeline = checkNotNull(BookTimeline.fromDurations(longArrayOf(60_000, 10_000, 90_000)))

    @Test fun mapsChapterPositionsAndBookOffsets() {
        assertEquals(65_000L, timeline.elapsed(1, 5_000))
        assertEquals(ChapterPosition(1, 5_000), timeline.chapterAt(65_000))
        assertEquals(ChapterPosition(2, 20_000), timeline.chapterAt(90_000))
    }

    @Test fun handlesExactBoundariesAndBookEnds() {
        assertEquals(ChapterPosition(0, 0), timeline.chapterAt(-1))
        assertEquals(ChapterPosition(1, 0), timeline.chapterAt(60_000))
        assertEquals(ChapterPosition(2, 0), timeline.chapterAt(70_000))
        assertEquals(ChapterPosition(2, 90_000), timeline.chapterAt(160_001))
    }

    @Test fun requiresKnownDurations() {
        assertNull(BookTimeline.fromDurations(longArrayOf(60_000, 0)))
        assertNull(BookTimeline.fromDurations(longArrayOf()))
    }
}
