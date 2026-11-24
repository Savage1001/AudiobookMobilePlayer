package app.localchapter.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

internal fun readTrackDuration(context: Context, uri: String): Long? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, Uri.parse(uri))
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0 }
    } finally {
        retriever.release()
    }
}.getOrNull()
