package app.localchapter.player

/** Chapter boundaries in the same order as the playback queue. */
internal class BookTimeline private constructor(private val boundaries: LongArray) {
    val durationMs: Long get() = boundaries.last()

    fun elapsed(index: Int, positionMs: Long): Long {
        val start = boundaries[index]
        return start + positionMs.coerceIn(0, boundaries[index + 1] - start)
    }

    fun chapterAt(elapsedMs: Long): ChapterPosition {
        val elapsed = elapsedMs.coerceIn(0, durationMs)
        val boundary = boundaries.binarySearch(elapsed)
        val index = (if (boundary >= 0) boundary else -boundary - 2)
            .coerceAtMost(boundaries.size - 2)
        return ChapterPosition(index, elapsed - boundaries[index])
    }

    companion object {
        fun fromDurations(durations: LongArray): BookTimeline? {
            if (durations.isEmpty()) return null
            val boundaries = LongArray(durations.size + 1)
            for ((index, duration) in durations.withIndex()) {
                if (duration <= 0 || duration > Long.MAX_VALUE - boundaries[index]) return null
                boundaries[index + 1] = boundaries[index] + duration
            }
            return BookTimeline(boundaries)
        }
    }
}
