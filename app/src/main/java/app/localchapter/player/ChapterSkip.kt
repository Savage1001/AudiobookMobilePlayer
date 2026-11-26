package app.localchapter.player

internal data class ChapterPosition(val index: Int, val positionMs: Long)

/** A null result means a chapter duration is needed to resolve the seek. */
internal fun resolveChapterSkip(
    start: ChapterPosition, offsetMs: Long, chapterCount: Int, durationMs: (Int) -> Long?
): ChapterPosition? {
    var index = start.index
    var position = start.positionMs + offsetMs.coerceAtMost(Long.MAX_VALUE - start.positionMs)
    while (position < 0 && index > 0) {
        index--
        position += durationMs(index) ?: return null
    }
    if (offsetMs < 0) return ChapterPosition(index, position.coerceAtLeast(0))

    while (true) {
        if (position == 0L) return ChapterPosition(index, 0)
        val duration = durationMs(index) ?: return null
        if (position < duration) return ChapterPosition(index, position)
        if (index == chapterCount - 1) return ChapterPosition(index, duration)
        position -= duration
        index++
    }
}
