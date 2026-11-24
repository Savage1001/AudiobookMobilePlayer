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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import androidx.core.content.FileProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class MainActivity : Activity() {
    private companion object {
        const val PICK_FOLDER = 10
        const val INSTALL_PERMISSION = 11
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    private val background by lazy { getColor(R.color.player_background) }
    private val surface by lazy { getColor(R.color.player_surface) }
    private val border by lazy { getColor(R.color.player_border) }
    private val accent by lazy { getColor(R.color.player_accent) }
    private val muted by lazy { getColor(R.color.player_muted) }
    private val foreground by lazy { getColor(R.color.player_text) }
    private val selectedSurface by lazy { getColor(R.color.player_selected) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var library: Library
    private lateinit var updates: GitHubUpdates
    private val updatePreferences by lazy { getSharedPreferences("updates", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var updateButton: Button? = null
    private var updateInProgress = false
    private var waitingForInstallPermission = false
    private var inForeground = false
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
        val chapter: TextView, val elapsed: TextView, val remaining: TextView, val seek: SeekBar,
        val play: android.widget.ImageButton, val speed: Button, val timer: Button,
        val previous: View, val next: View, val rewind: View, val forward: View, val chapters: ListView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        updates = GitHubUpdates(applicationContext)
        waitingForInstallPermission = savedInstanceState?.getBoolean("installPermission") ?: false
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
        outState.putBoolean("installPermission", waitingForInstallPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        if (book == null && !waitingForInstallPermission) checkUpdates()
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
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
        updateButton = null
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
        val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        setContentView(root)
        root.requestApplyInsets()
        val selected = book
        if (selected == null) renderLibrary() else renderBook(selected)
        updatePlayer()
        if (inForeground && selected == null && !waitingForInstallPermission) checkUpdates()
    }

    private fun renderLibrary() {
        val page = column().apply { setPadding(dp(22), dp(24), dp(22), dp(22)) }
        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(label("Local Chapter", 35f, foreground, bold = true))
        page.addView(label("Your audiobook library", 16f, muted))
        page.addView(button(if (importing) "Scanning folder…" else "＋ Import folder", primary = true) { pickFolder() }
            .apply { isEnabled = !importing }, spaced(top = 22, bottom = 18))
        updateButton = button("Check for updates") { checkUpdates(manual = true) }.also {
            page.addView(it, spaced(bottom = 18))
        }
        updateButton?.isEnabled = !updateInProgress
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
        val compact = resources.configuration.screenHeightDp < 650 || resources.configuration.fontScale > 1.25f
        val page = column().apply { setPadding(dp(20), dp(6), dp(20), dp(12)) }
        if (compact) root.addView(ScrollView(this).apply { isFillViewport = true; addView(page) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        else root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))

        page.addView(row().apply {
            addView(button("‹ Library") { this@MainActivity.book = null; render() }
                .apply { setPadding(dp(12), 0, dp(12), 0) })
            addView(label(book.title, 16f, this@MainActivity.foreground, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("⋯") { showBookOptions(book) }.apply {
                contentDescription = "Book options"
                textSize = 20f
            },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        })

        page.addView(label("Chapters · ${book.tracks.size}", 14f, muted, bold = true), spaced(top = 12, bottom = 6))
        val chapters = chapterList(book)
        page.addView(chapters, if (compact) LinearLayout.LayoutParams(-1, dp(190))
            else LinearLayout.LayoutParams(-1, 0, 1f))

        val chapter = label("", 23f, foreground, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        page.addView(chapter, spaced(top = 8))

        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(border)
            thumbTintList = ColorStateList.valueOf(accent)
            contentDescription = "Chapter position"
        }
        page.addView(seek, LinearLayout.LayoutParams(-1, dp(40)))
        val elapsed = label("", 14f, muted)
        val remaining = label("", 14f, muted).apply { gravity = Gravity.END }
        page.addView(row().apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(elapsed, weighted())
            addView(remaining, weighted())
        })

        val previous = transportIcon(R.drawable.ic_previous, "Previous chapter") { active()?.seekToPreviousMediaItem() }
        val next = transportIcon(R.drawable.ic_next, "Next chapter") { active()?.seekToNextMediaItem() }
        val play = transportIcon(R.drawable.ic_play, "Play", primary = true) { toggle(book) }
        val rewind = button("−30") { active()?.seekBack() }.apply { contentDescription = "Back 30 seconds" }
        val forward = button("+30") { active()?.seekForward() }.apply { contentDescription = "Forward 30 seconds" }
        page.addView(row().apply {
            gravity = Gravity.CENTER
            addView(previous, transportSlot())
            addView(rewind, transportSlot())
            addView(play, LinearLayout.LayoutParams(dp(68), dp(60)).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
            addView(forward, transportSlot())
            addView(next, transportSlot())
        }, spaced(top = 14))

        val speed = button("Speed") { chooseSpeed() }
        val timer = button("Sleep timer") { chooseTimer() }
        page.addView(row().apply {
            addView(speed, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(timer, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }, spaced(top = 12))

        controls = Controls(chapter, elapsed, remaining, seek, play, speed, timer, previous, next, rewind, forward, chapters)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                applySeek(bar.progress)
                dragging = false
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !dragging) applySeek(progress)
                if (fromUser && dragging) active()?.let { player ->
                    updateTimes(elapsed, remaining, player.duration * progress / 1000, player.duration)
                }
            }
        })
    }


    private fun showBookOptions(book: Book) {
        val warnings = TrackOrder.warnings(book.tracks)
        val choices = if (warnings.isEmpty()) arrayOf("Rename book") else arrayOf("Rename book", "Numbering details")
        AlertDialog.Builder(this).setTitle(book.title).setItems(choices) { _, index ->
            if (index == 0) rename(book) else showWarnings(warnings)
        }.show()
    }

    private fun chapterList(book: Book): ListView {
        val titles = book.tracks.map { "${it.filename.substringBefore(" - ")}    ${it.title}" }
        return ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            isNestedScrollingEnabled = true
            background = GradientDrawable().apply {
                setColor(surface)
                setStroke(dp(1), border)
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            divider = android.graphics.drawable.ColorDrawable(border)
            dividerHeight = dp(1)
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, titles) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(this@MainActivity.foreground)
                        textSize = 16f
                        minHeight = dp(52)
                        background = android.graphics.drawable.StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_activated),
                                android.graphics.drawable.ColorDrawable(selectedSurface))
                            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                        }
                    }
            }
            setOnItemClickListener { _, _, index, _ -> open(book, index, true) }
        }
    }

    private fun transportSlot() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun transportIcon(icon: Int, description: String, primary: Boolean = false, action: () -> Unit) =
        android.widget.ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            imageTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(muted, if (primary) Color.WHITE else this@MainActivity.foreground)
            )
            background = buttonBackground(primary)
            setOnClickListener { action() }
        }

    private fun updateTimes(elapsed: TextView, remaining: TextView, position: Long, duration: Long) {
        val elapsedText = time(position)
        val remainingText = if (duration > 0) "−${time(duration - position)}" else "—"
        if (elapsed.text != elapsedText) elapsed.text = elapsedText
        if (remaining.text != remainingText) remaining.text = remainingText
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
        val index = player?.currentMediaItemIndex
            ?: selected.tracks.indexOfFirst { it.uri == saved?.uri }.coerceAtLeast(0)
        val title = selected.tracks.getOrNull(index)?.title.orEmpty()
        val position = player?.currentPosition ?: saved?.position ?: 0
        val duration = player?.duration ?: 0
        if (views.chapter.text != title) views.chapter.text = title
        if (views.chapters.checkedItemPosition != index) {
            views.chapters.setItemChecked(index, true)
            if (index !in views.chapters.firstVisiblePosition..views.chapters.lastVisiblePosition) {
                views.chapters.setSelection(index)
            }
        }
        if (!dragging) {
            updateTimes(views.elapsed, views.remaining, position, duration)
            views.seek.progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
        }
        views.seek.isEnabled = duration > 0
        views.play.isEnabled = controller != null
        val playing = player?.playWhenReady == true && player.playbackState != Player.STATE_ENDED
            && player.playbackState != Player.STATE_IDLE
        val playAction = if (playing) "Pause" else "Play"
        if (views.play.contentDescription != playAction) {
            views.play.contentDescription = playAction
            views.play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        val speedText = "Speed · ${player?.playbackParameters?.speed ?: saved?.speed ?: 1f}×"
        if (views.speed.text != speedText) views.speed.text = speedText
        val remaining = deadline - SystemClock.elapsedRealtime()
        val timerText = if (remaining > 0) "Sleep · ${time(remaining)}" else "Sleep timer"
        if (views.timer.text != timerText) views.timer.text = timerText
        views.previous.isEnabled = player?.hasPreviousMediaItem() == true
        views.next.isEnabled = player?.hasNextMediaItem() == true
        views.rewind.isEnabled = duration > 0
        views.forward.isEnabled = duration > 0
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

    private fun checkUpdates(manual: Boolean = false, installAfterCheck: Boolean = false) {
        if (updateInProgress) return
        val now = System.currentTimeMillis()
        val last = updatePreferences.getLong("lastCheck", 0L)
        if (!manual && now - last in 0 until CHECK_INTERVAL_MS) return
        updatePreferences.edit().putLong("lastCheck", now).apply()
        setUpdateBusy(true)
        Thread {
            val result = runCatching { updates.latest() }
            runOnUiThread {
                setUpdateBusy(false)
                if (!inForeground || isFinishing || (!manual && book != null)) return@runOnUiThread
                result.onSuccess { release ->
                    when {
                        release == null -> if (manual) toast("Local Chapter is up to date")
                        installAfterCheck -> downloadUpdate(release)
                        else -> AlertDialog.Builder(this)
                            .setTitle("Local Chapter ${release.tag} is available")
                            .setMessage("Download the update from GitHub and confirm installation in Android.")
                            .setPositiveButton("Download update") { _, _ -> downloadUpdate(release) }
                            .setNegativeButton("Later", null).show()
                    }
                }.onFailure { if (manual) toast("Could not check GitHub for updates. Try again later.") }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun downloadUpdate(release: UpdateRelease) {
        if (updateInProgress) return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("Allow Local Chapter to install updates")
                .setMessage("In Android settings, allow installs from this app. Android will still ask before installing.")
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching {
                        waitingForInstallPermission = true
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")), INSTALL_PERMISSION)
                    }.onFailure {
                        waitingForInstallPermission = false
                        toast("Could not open Android's install settings")
                    }
                }.setNegativeButton("Cancel", null).show()
            return
        }
        setUpdateBusy(true, downloading = true)
        Thread {
            val result = runCatching { updates.download(release) }
            runOnUiThread {
                setUpdateBusy(false)
                if (!inForeground || isFinishing) {
                    result.getOrNull()?.delete()
                    return@runOnUiThread
                }
                result.onSuccess { file ->
                    runCatching {
                        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                        startActivity(Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure {
                        file.delete()
                        toast("Could not open Android's package installer")
                    }
                }.onFailure { toast("Could not download or verify this update") }
            }
        }.start()
    }

    private fun setUpdateBusy(busy: Boolean, downloading: Boolean = false) {
        updateInProgress = busy
        updateButton?.isEnabled = !busy
        updateButton?.text = when {
            downloading -> "Downloading update…"
            busy -> "Checking GitHub…"
            else -> "Check for updates"
        }
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK_FOLDER)
    }

    @Deprecated("Uses the platform folder picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == INSTALL_PERMISSION) {
            waitingForInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) {
                handler.post { checkUpdates(manual = true, installAfterCheck = true) }
            }
            return
        }
        val tree = data?.data ?: return
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || importing) return
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
            intArrayOf(muted, if (primary) Color.WHITE else this@MainActivity.foreground)
        ))
        background = buttonBackground(primary)
        setOnClickListener { action() }
    }

    private fun buttonBackground(primary: Boolean): RippleDrawable {
        val fill = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) accent else surface)
            if (!primary) setStroke(dp(1), border)
        }
        return RippleDrawable(ColorStateList.valueOf(0x40346BF1), fill, null)
    }

    private fun time(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
