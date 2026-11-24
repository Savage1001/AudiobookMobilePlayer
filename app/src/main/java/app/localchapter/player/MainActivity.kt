package app.localchapter.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class MainActivity : Activity() {
    private val background = Color.rgb(18, 26, 25)
    private val surface = Color.rgb(32, 50, 46)
    private val mint = Color.rgb(196, 243, 219)
    private val muted = Color.rgb(185, 209, 200)
    private val white = Color.rgb(245, 251, 248)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var library: Library
    private lateinit var root: LinearLayout
    private var connection: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var book: Book? = null
    private var controls: Controls? = null
    private var dragging = false
    private var deadline = 0L
    private var importing = false
    private val tick = object : Runnable {
        override fun run() {
            updatePlayer()
            handler.postDelayed(this, 500)
        }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayer()
        override fun onPlayerError(error: PlaybackException) {
            toast("This track couldn't play. Check folder access and the file's audio format.")
        }
    }

    private data class Controls(
        val chapter: TextView, val times: TextView, val seek: SeekBar, val play: Button,
        val speed: Button, val timer: Button, val previous: Button, val next: Button
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        val id = savedInstanceState?.getString("book") ?: playingBookFromIntent(intent)
        book = library.books().find { it.id == id }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = playingBookFromIntent(intent) ?: return
        book = library.books().find { it.id == id }
        render()
    }

    private fun playingBookFromIntent(intent: Intent): String? =
        if (intent.getBooleanExtra("showPlaying", false)) library.lastBook() else null

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("book", book?.id)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        connection = future
        future.addListener({
            if (connection === future) {
                runCatching { future.get() }.onSuccess {
                    controller = it
                    it.addListener(listener)
                    timer(null)
                    updatePlayer()
                }.onFailure { toast("Could not connect to playback") }
            }
        }, mainExecutorCompat())
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        controller?.removeListener(listener)
        connection?.let { MediaController.releaseFuture(it) }
        connection = null
        controller = null
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (book == null) super.onBackPressed()
        else {
            book = null
            render()
        }
    }

    private fun mainExecutorCompat() = java.util.concurrent.Executor { runnable -> handler.post(runnable) }

    private fun render() {
        dragging = false
        controls = null
        root = column().apply {
            setBackgroundColor(this@MainActivity.background)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = background
        window.navigationBarColor = background
        setContentView(root)
        root.requestApplyInsets()
        val selected = book
        if (selected == null) renderLibrary() else renderBook(selected)
        updatePlayer()
    }

    private fun renderLibrary() {
        val page = column().apply { setPadding(dp(22), dp(24), dp(22), dp(22)) }
        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(label("Local Chapter", 35f, white, bold = true))
        page.addView(label("Your audiobook library", 16f, muted))
        page.addView(button(if (importing) "Scanning folder…" else "＋ Import folder", primary = true) { pickFolder() }
            .apply { isEnabled = !importing }, spaced(top = 22, bottom = 18))
        val books = library.books()
        if (books.isEmpty()) {
            page.addView(label("Choose a folder with numbered MP3, MP4, or M4A tracks.", 16f, muted))
            return
        }
        books.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).forEach { item ->
            val bookmark = library.bookmark(item.id)
            val chapter = item.tracks.indexOfFirst { it.uri == bookmark.uri }
            val details = if (chapter >= 0) "Track ${chapter + 1} of ${item.tracks.size} · ${time(bookmark.position)}"
                else "${item.tracks.size} tracks"
            page.addView(button("${item.title}\n$details") { book = item; render() }.apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(17), dp(12), dp(17), dp(12))
            }, spaced(bottom = 8))
        }
    }

    private fun renderBook(book: Book) {
        val header = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        header.addView(row().apply {
            addView(button("‹ Library") { this@MainActivity.book = null; render() }, weighted())
            addView(button("Rename") { rename(book) }, weighted())
        })
        header.addView(label(book.title, 29f, white, bold = true).apply { maxLines = 2 }, spaced(top = 10))
        val chapter = label("", 17f, mint, bold = true).apply { maxLines = 2 }
        header.addView(chapter, spaced(top = 4))
        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(mint)
            thumbTintList = ColorStateList.valueOf(mint)
            contentDescription = "Track position"
        }
        header.addView(seek, LinearLayout.LayoutParams(-1, dp(42)))
        val times = label("", 13f, muted)
        header.addView(times)
        val previous = button("‹ Track") { active()?.seekToPreviousMediaItem() }
        val next = button("Track ›") { active()?.seekToNextMediaItem() }
        val play = button("Play", primary = true) { toggle(book) }
        header.addView(row().apply {
            addView(button("−30s") { active()?.seekBack() }, weighted())
            addView(play, weighted())
            addView(button("+30s") { active()?.seekForward() }, weighted())
        }, spaced(top = 6))
        val speed = button("Speed") { chooseSpeed() }
        val timer = button("Sleep") { chooseTimer() }
        header.addView(row().apply {
            addView(previous, weighted())
            addView(speed, weighted())
            addView(timer, weighted())
            addView(next, weighted())
        }, spaced(top = 4))
        controls = Controls(chapter, times, seek, play, speed, timer, previous, next)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                applySeek(bar.progress)
                dragging = false
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !dragging) applySeek(progress)
                if (fromUser && dragging) active()?.let { player ->
                    times.text = "${time(player.duration * progress / 1000)}  /  ${time(player.duration)}"
                }
            }
        })
        val warnings = TrackOrder.warnings(book.tracks)
        header.addView(row().apply {
            addView(label("Tracks · ${book.tracks.size}", 17f, white, bold = true), weighted())
            if (warnings.isNotEmpty()) addView(button("Numbering details") { showWarnings(warnings) }, weighted())
        }, spaced(top = 8))
        val titles = book.tracks.map { "${it.filename.substringBefore(" - ")}    ${it.title}" }
        val list = ListView(this).apply {
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, titles) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(white)
                        textSize = 16f
                    }
            }
            setOnItemClickListener { _, _, index, _ -> open(book, index, true) }
        }
        val scroller = ScrollView(this).apply { addView(header) }
        root.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply {
            if (resources.configuration.screenHeightDp < 570) {
                height = 0
                weight = 1.5f
            }
        })
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun applySeek(progress: Int) {
        active()?.let { if (it.duration > 0) it.seekTo(it.duration * progress / 1000) }
    }

    private fun active(): MediaController? {
        val current = controller ?: return null
        val index = current.currentMediaItemIndex
        val item = current.currentMediaItem ?: return null
        return current.takeIf { item.mediaId == "${book?.id}:$index" }
    }

    private fun updatePlayer() {
        val selected = book ?: return
        val views = controls ?: return
        val player = active()
        val saved = if (player == null) library.bookmark(selected.id) else null
        val track = if (player != null) selected.tracks.getOrNull(player.currentMediaItemIndex)
            else selected.tracks.find { it.uri == saved?.uri } ?: selected.tracks.firstOrNull()
        val position = player?.currentPosition ?: saved?.position ?: 0
        val duration = player?.duration ?: 0
        views.chapter.text = track?.title.orEmpty()
        if (!dragging) {
            views.times.text = if (duration > 0) "${time(position)}  /  ${time(duration)}" else time(position)
            views.seek.progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
        }
        views.seek.isEnabled = duration > 0
        views.play.isEnabled = controller != null
        views.play.text = if (player?.playWhenReady == true && player.playbackState != Player.STATE_ENDED
            && player.playbackState != Player.STATE_IDLE) "Pause" else "Play"
        views.speed.text = "${player?.playbackParameters?.speed ?: saved?.speed ?: 1f}×"
        val remaining = deadline - SystemClock.elapsedRealtime()
        views.timer.text = if (remaining > 0) time(remaining) else "Sleep"
        views.previous.isEnabled = player?.hasPreviousMediaItem() == true
        views.next.isEnabled = player?.hasNextMediaItem() == true
    }

    private fun toggle(book: Book) {
        val player = active()
        when {
            player == null || player.playbackState == Player.STATE_IDLE -> open(book, -1, true)
            player.playbackState == Player.STATE_ENDED -> {
                player.seekToDefaultPosition(0)
                player.play()
            }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    private fun open(book: Book, index: Int, play: Boolean) {
        val player = controller ?: return toast("Playback is connecting")
        player.sendCustomCommand(openBookCommand, Bundle().apply {
            putString("id", book.id)
            putInt("index", index)
            putBoolean("play", play)
        })
    }

    private fun chooseSpeed() {
        val player = active() ?: return toast("Start the book to set playback speed")
        val choices = listOf(0.5f, 0.75f, 1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(choices.map { "$it×" }.toTypedArray(),
                choices.indexOf(player.playbackParameters.speed)) { dialog, index ->
                active()?.setPlaybackSpeed(choices[index])
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseTimer() {
        if (active() == null) return toast("Start the book to set a sleep timer")
        val choices = listOf(0, 5, 10, 15, 30, 45, 60, 90, 120)
        AlertDialog.Builder(this).setTitle("Pause playback in")
            .setItems(choices.map { if (it == 0) "Turn timer off" else "$it minutes" }.toTypedArray()) { _, index ->
                timer(choices[index])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun timer(minutes: Int?) {
        val player = controller ?: return
        val result = player.sendCustomCommand(timerCommand, Bundle().apply {
            if (minutes != null) putInt("minutes", minutes)
        })
        result.addListener({
            if (controller === player) runCatching { result.get() }.onSuccess {
                deadline = it.extras.getLong("deadline")
                updatePlayer()
            }
        }, mainExecutorCompat())
    }

    private fun rename(book: Book) {
        val input = EditText(this).apply { setSingleLine(); setText(book.title); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle("Rename book").setView(input)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = input.text.toString().trim()
                if (title.isEmpty()) input.error = "Enter a title"
                else {
                    val updated = book.copy(title = title)
                    library.save(updated)
                    if (active() != null) open(updated, -1, false)
                    this.book = updated
                    render()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 10)
    }

    @Deprecated("Uses the platform folder picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data ?: return
        if (requestCode != 10 || resultCode != RESULT_OK || importing) return
        importing = true
        render()
        Thread {
            val result = runCatching { library.scan(tree) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                importing = false
                result.onSuccess { scanned ->
                    if (scanned.tracks.isEmpty()) toast("No files named like 01 - Chapter.mp3 in this folder")
                    else runCatching {
                        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val existing = library.books().find { it.folder == scanned.folder }
                        val updated = if (existing == null) scanned else scanned.copy(id = existing.id, title = existing.title)
                        library.save(updated)
                        if (existing != null && controller?.currentMediaItem?.mediaId
                                ?.startsWith("${existing.id}:") == true) {
                            open(updated, -1, false)
                        }
                        book = updated
                        val warnings = TrackOrder.warnings(updated.tracks)
                        if (warnings.isNotEmpty()) showWarnings(warnings)
                    }.onFailure { toast("Could not keep access to this folder") }
                }.onFailure { toast("Could not read the selected folder") }
                render()
            }
        }.start()
    }

    private fun showWarnings(warnings: List<String>) {
        AlertDialog.Builder(this).setTitle("Track numbering")
            .setMessage(warnings.joinToString("\n\n") + "\n\nAll matching tracks are included.")
            .setPositiveButton("OK", null).show()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun dp(size: Int) = (size * resources.displayMetrics.density + 0.5f).toInt()
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }
    private fun spaced(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        minimumWidth = 0
        minWidth = 0
        minHeight = dp(48)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFF83978F.toInt(), if (primary) this@MainActivity.background else white)
        ))
        val fill = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (primary) mint else surface)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x407D9D91), fill, null)
        setOnClickListener { action() }
    }

    private fun time(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
