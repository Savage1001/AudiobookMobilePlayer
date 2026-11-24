package app.localchapter.player

import java.math.BigInteger

internal data class Track(val filename: String, val uri: String, val number: BigInteger, val title: String)

internal object TrackOrder {
    private val pattern = Regex("""^(\d{2,}) - (.+)\.(mp3|mp4|m4a)$""", RegexOption.IGNORE_CASE)

    fun scan(files: List<Pair<String, String>>): List<Track> = files.mapNotNull { (name, uri) ->
        val match = pattern.matchEntire(name) ?: return@mapNotNull null
        Track(name, uri, match.groupValues[1].toBigInteger(), match.groupValues[2])
    }.sortedWith(compareBy({ it.number }, { it.filename }, { it.uri }))

    fun warnings(tracks: List<Track>): List<String> {
        val counts = tracks.groupingBy { it.number }.eachCount()
        val gaps = counts.keys.zipWithNext().mapNotNull { (previous, next) ->
            val first = previous + BigInteger.ONE
            val last = next - BigInteger.ONE
            when {
                first > last -> null
                first == last -> first.toString()
                else -> "$first–$last"
            }
        }
        val duplicates = counts.filterValues { it > 1 }.map { (number, count) -> "$number (×$count)" }
        return buildList {
            if (gaps.isNotEmpty()) add("Missing numbers: " + gaps.joinToString())
            if (duplicates.isNotEmpty()) add("Duplicate numbers: " + duplicates.joinToString())
        }
    }
}
