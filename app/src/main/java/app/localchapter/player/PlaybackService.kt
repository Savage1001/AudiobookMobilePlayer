package app.localchapter.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal val openBookCommand = SessionCommand("app.localchapter.OPEN_BOOK", Bundle.EMPTY)
internal val timerCommand = SessionCommand("app.localchapter.TIMER", Bundle.EMPTY)

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var library: Library
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var currentBook: Book? = null
    private var deadline = 0L
    private val checkpoint = object : Runnable {
        override fun run() {
            if (player.isPlaying) savePosition()
            handler.postDelayed(this, 2_000)
        }
    }
    private val expire = Runnable {
        deadline = 0L
        player.pause()
    }

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { ContentDataSource(this) }))
            .setSeekBackIncrementMs(30_000).setSeekForwardIncrementMs(30_000)
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                trackSelectionParameters = TrackSelectionParameters.Builder(this@PlaybackService)
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.containsAny(Player.EVENT_POSITION_DISCONTINUITY,
                                Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) savePosition()
                    }
                })
            }
        val launch = Intent(this, MainActivity::class.java).putExtra("showPlaying", true)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(PlaybackCallback()).build()
        handler.post(checkpoint)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun savePosition() {
        val book = currentBook ?: return
        if (player.currentMediaItem == null) return
        val track = book.tracks.getOrNull(player.currentMediaItemIndex) ?: return
        library.remember(book.id, Bookmark(track.uri, player.currentPosition.coerceAtLeast(0),
            player.playbackParameters.speed))
    }

    private fun items(book: Book): List<MediaItem> = book.tracks.mapIndexed { index, track ->
        MediaItem.Builder().setMediaId("${book.id}:$index").setUri(track.uri)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(track.title).setArtist(book.title).setAlbumTitle(book.title)
                .build())
            .build()
    }

    private inner class PlaybackCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val ownApp = controller.uid == applicationInfo.uid
            if (!ownApp && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (ownApp) commands.add(openBookCommand).add(timerCommand)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_SET_SHUFFLE_MODE).remove(Player.COMMAND_SET_REPEAT_MODE).build())
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != applicationInfo.uid) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val result = when (command.customAction) {
                openBookCommand.customAction -> {
                    val book = library.books().find { it.id == args.getString("id") }
                    if (book == null || book.tracks.isEmpty()) SessionResult(SessionError.ERROR_BAD_VALUE)
                    else {
                        open(book, args.getInt("index", -1), args.getBoolean("play"))
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                }
                timerCommand.customAction -> {
                    if (args.containsKey("minutes")) {
                        val minutes = args.getInt("minutes").coerceIn(0, 120)
                        handler.removeCallbacks(expire)
                        deadline = if (minutes == 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
                        if (deadline != 0L) handler.postDelayed(expire, minutes * 60_000L)
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putLong("deadline", deadline) })
                }
                else -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
            }
            return Futures.immediateFuture(result)
        }
    }

    private fun open(book: Book, requestedIndex: Int, play: Boolean) {
        val keepPlaying = currentBook?.id == book.id && player.playWhenReady
        if (currentBook != book || player.mediaItemCount == 0) {
            savePosition()
            val bookmark = library.bookmark(book.id)
            val savedIndex = book.tracks.indexOfFirst { it.uri == bookmark.uri }
            val index = if (requestedIndex in book.tracks.indices) requestedIndex else savedIndex.coerceAtLeast(0)
            val position = if (requestedIndex < 0 && savedIndex >= 0) bookmark.position else 0
            player.pause()
            currentBook = book
            player.setMediaItems(items(book), index, position)
            player.setPlaybackSpeed(bookmark.speed)
            player.prepare()
            savePosition()
        } else {
            if (requestedIndex in book.tracks.indices) player.seekToDefaultPosition(requestedIndex)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
        if (play || keepPlaying) player.play()
    }
}
