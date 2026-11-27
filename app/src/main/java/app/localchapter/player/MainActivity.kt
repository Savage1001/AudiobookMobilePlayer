package app.localchapter.player

import android.animation.AnimatorInflater
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        const val PICK_FOLDER = 10
        const val INSTALL_PERMISSION = 11
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val SCREEN_INSET = 20
        const val DIALOG_INSET = 24
        const val ROW_INSET = 16
        const val GAP = 8
        const val SECTION_GAP = 16
    }

    private val background by lazy { getColor(R.color.player_background) }
    private val surface by lazy { getColor(R.color.player_surface) }
    private val border by lazy { getColor(R.color.player_border) }
    private val accent by lazy { getColor(R.color.player_accent) }
    private val muted by lazy { getColor(R.color.player_muted) }
    private val foreground by lazy { getColor(R.color.player_text) }
    private val selectedSurface by lazy { getColor(R.color.player_selected) }
    private val motionInterpolator by lazy {
        android.view.animation.AnimationUtils.loadInterpolator(this, android.R.interpolator.fast_out_slow_in)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val chapterDurationReader = Executors.newSingleThreadExecutor()
    private var chapterDurationTask: Future<*>? = null
    private lateinit var library: Library
    private lateinit var playbackSettings: PlaybackPreferences
    private lateinit var updates: GitHubUpdates
    private val updatePreferences by lazy { getSharedPreferences("updates", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var updateButton: Button? = null
    private var updateButtonText = "Check for updates"
    private var updateInProgress = false
    private var waitingForInstallPermission = false
    private var inForeground = false
    private var connection: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var book: Book? = null
    private var controls: Controls? = null
    private var dragging = false
    private var bookTimeline: BookTimeline? = null
    private var chapterDurations = LongArray(0)
    private var loadingTimeline = false
    private var carDurationMs = 0L
    private var deadline = 0L
    private var timerCanExtend = false
    private var carMode = false
    private var shownScreen: Int? = null
    private var carControls: CarControls? = null
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
        val previous: View, val next: View, val rewind: View, val forward: View, val chapters: ListView,
        val progressHint: TextView
    )

    private data class CarControls(
        val chapter: TextView, val detail: TextView, val progress: android.widget.ProgressBar,
        val elapsed: TextView, val remaining: TextView, val play: Button, val rewind: View, val forward: View
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        playbackSettings = PlaybackPreferences(this)
        updates = GitHubUpdates(applicationContext)
        waitingForInstallPermission = savedInstanceState?.getBoolean("installPermission") ?: false
        val id = savedInstanceState?.getString("book") ?: playingBookFromIntent(intent)
        book = library.books().find { it.id == id }
        carMode = book != null && savedInstanceState?.getBoolean("carMode") == true
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
        outState.putBoolean("carMode", carMode)
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
        val future = MediaController.Builder(this, token).setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                if (this@MainActivity.controller === controller) updateTimerState(extras)
            }
        }).buildAsync()
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

    override fun onDestroy() {
        chapterDurationReader.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (carMode) exitCarMode()
        else if (book == null) super.onBackPressed()
        else {
            book = null
            render()
        }
    }

    private fun mainExecutorCompat() = java.util.concurrent.Executor { runnable -> handler.post(runnable) }

    private fun render() {
        chapterDurationTask?.cancel(true)
        bookTimeline = null
        chapterDurations = LongArray(0)
        loadingTimeline = false
        carDurationMs = 0L
        dragging = false
        controls = null
        carControls = null
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
        if (selected != null && carMode) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            renderCarMode(selected)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (selected == null) renderLibrary() else renderBook(selected)
        }
        val screen = if (selected == null) 0 else if (carMode) 2 else 1
        val previousScreen = shownScreen
        shownScreen = screen
        if (previousScreen != screen && ValueAnimator.areAnimatorsEnabled()) {
            val direction = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
            root.alpha = 0.65f
            if (previousScreen == null) root.translationY = dp(GAP).toFloat()
            else root.translationX = (dp(SECTION_GAP) * direction *
                if (screen > previousScreen) 1 else -1).toFloat()
            root.animate().alpha(1f).translationX(0f).translationY(0f)
                .setDuration(220).setInterpolator(motionInterpolator).start()
        }
        updatePlayer()
        if (inForeground && selected == null && !waitingForInstallPermission) checkUpdates()
    }

    private fun renderLibrary() {
        val page = paddedColumn(SCREEN_INSET)
        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(row().apply {
            addView(label("Local Chapter", 35f, this@MainActivity.foreground, bold = true), weighted())
            addView(transportIcon(R.drawable.ic_settings, "Settings") { showPlaybackSettings() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        page.addView(label("Your audiobook library", 16f, muted))
        page.addView(button(if (importing) "Scanning folder…" else "＋ Import folder", primary = true) { pickFolder() }
            .apply { isEnabled = !importing }, spaced(top = SECTION_GAP, bottom = SECTION_GAP))
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
                setPaddingRelative(dp(ROW_INSET), dp(GAP), dp(ROW_INSET), dp(GAP))
                setOnLongClickListener {
                    confirmDelete(item)
                    true
                }
            }, spaced(bottom = GAP))
        }
    }

    private fun confirmDelete(item: Book) {
        AlertDialog.Builder(this).setTitle("Delete book?")
            .setMessage("Remove “${item.title}” and its saved progress from your library? The audio files will stay on your device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> delete(item) }
            .create().showWithMotion()
    }

    private fun showPlaybackSettings() {
        val form = dialogFields()
        val presets = listOf(10L, 15L, 30L, 60L)
        val customIndex = presets.size
        val current = playbackSettings.skipSeconds
        form.addView(label("Rewind and forward interval", 14f, foreground, bold = true))
        val interval = android.widget.Spinner(this).apply {
            contentDescription = "Skip interval"
            minimumHeight = dp(48)
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                presets.map { "$it seconds" } + "Custom").apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        form.addView(interval, spaced(top = GAP))
        val custom = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            contentDescription = "Custom skip seconds"
            hint = "Enter seconds"
            if (current !in presets) setText(current.toString())
            visibility = if (current in presets) View.GONE else View.VISIBLE
        }
        form.addView(custom)
        interval.setSelection(presets.indexOf(current).takeIf { it >= 0 } ?: customIndex)
        interval.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val visibility = if (position == customIndex) View.VISIBLE else View.GONE
                if (custom.visibility != visibility) {
                    if (form.isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
                        android.transition.TransitionManager.beginDelayedTransition(form,
                            android.transition.AutoTransition().apply { duration = 180 })
                    }
                    custom.visibility = visibility
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        val fade = settingsSwitch("Fade audio in the final 30 seconds", playbackSettings.fadeTimer)
        val shake = settingsSwitch("Shake to extend at the end of the timer", playbackSettings.shakeTimer)
        form.addView(fade, spaced(top = SECTION_GAP))
        form.addView(shake, spaced(top = GAP))
        val checkButton = button(updateButtonText) { checkUpdates(manual = true) }.apply {
            isEnabled = !updateInProgress
        }
        updateButton = checkButton
        form.addView(checkButton, spaced(top = SECTION_GAP))
        val dialog = AlertDialog.Builder(this).setTitle("Settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnDismissListener {
            if (updateButton === checkButton) updateButton = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = presets.getOrNull(interval.selectedItemPosition)
                    ?: custom.text.toString().trim().toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / 1_000) }
                if (seconds == null) {
                    custom.error = "Enter a positive number of seconds"
                    custom.requestFocus()
                    return@setOnClickListener
                }
                playbackSettings.skipSeconds = seconds
                playbackSettings.fadeTimer = fade.isChecked
                playbackSettings.shakeTimer = shake.isChecked
                timer(null)
                dialog.dismiss()
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.showWithMotion()
    }

    private fun settingsSwitch(title: String, enabled: Boolean) = android.widget.Switch(this).apply {
        text = title
        isChecked = enabled
        minimumHeight = dp(56)
        switchPadding = dp(GAP)
        showText = false
    }

    private fun enterCarMode(selected: Book) {
        book = selected
        carMode = true
        render()
    }

    private fun exitCarMode() {
        carMode = false
        render()
    }

    private fun renderCarMode(selected: Book) {
        val page = paddedColumn(SCREEN_INSET)
        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(button("Exit Car Mode") { exitCarMode() })
        page.addView(label(selected.title, 21f, muted, bold = true), spaced(top = SECTION_GAP))
        val chapter = label("", 28f, foreground, bold = true).apply { maxLines = 2 }
        page.addView(chapter, spaced(top = GAP))
        val detail = label("", 17f, muted)
        page.addView(detail, spaced(top = GAP))
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(border)
            contentDescription = "Chapter progress"
        }
        page.addView(progress, LinearLayout.LayoutParams(-1, dp(16)))
        val elapsed = label("", 20f, foreground)
        val remaining = label("", 20f, foreground).apply { gravity = Gravity.END }
        page.addView(row().apply {
            addView(elapsed, weighted())
            addView(remaining, weighted())
        }, spaced(bottom = SECTION_GAP))
        val play = button("Play", primary = true) { toggle(selected) }.apply { textSize = 26f }
        page.addView(play, LinearLayout.LayoutParams(-1, dp(92)))
        val rewind = carSkipButton(forward = false) { active()?.seekBack() }
        val forward = carSkipButton(forward = true) { active()?.seekForward() }
        page.addView(row().apply {
            addView(rewind, LinearLayout.LayoutParams(0, dp(72), 1f))
            addView(forward, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(GAP) })
        }, spaced(top = GAP))
        carControls = CarControls(chapter, detail, progress, elapsed, remaining, play, rewind, forward)
        if (active() == null) {
            val saved = library.bookmark(selected.id)
            val track = selected.tracks.find { it.uri == saved.uri } ?: selected.tracks.firstOrNull()
            if (track != null) chapterDurationTask = chapterDurationReader.submit {
                val duration = readTrackDuration(applicationContext, track.uri) ?: return@submit
                handler.post {
                    if (isDestroyed || carControls?.progress !== progress) return@post
                    carDurationMs = duration
                    updatePlayer()
                }
            }
        }
    }

    private fun carSkipButton(forward: Boolean, action: () -> Unit) = android.widget.ImageButton(this).apply {
        val seconds = playbackSettings.skipSeconds
        contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds"
        scaleType = android.widget.ImageView.ScaleType.CENTER
        val colors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, this@MainActivity.foreground)
        )
        setImageDrawable(CarSkipIcon(seconds, forward, dp(48), colors))
        background = buttonBackground(false)
        addPressMotion()
        setOnClickListener { action() }
    }

    private fun delete(item: Book) {
        val player = controller ?: return toast("Playback is connecting. Try again in a moment.")
        val result = player.sendCustomCommand(deleteBookCommand, Bundle().apply { putString("id", item.id) })
        result.addListener({
            if (isDestroyed || isFinishing) return@addListener
            if (runCatching { result.get().resultCode }.getOrNull() == SessionResult.RESULT_SUCCESS) {
                timer(null)
                if (book == null) render()
            } else {
                toast("Could not delete the book")
            }
        }, mainExecutorCompat())
    }

    private fun renderBook(book: Book) {
        val compact = resources.configuration.screenHeightDp < 650 || resources.configuration.fontScale > 1.25f
        val page = paddedColumn(SCREEN_INSET)
        if (compact) root.addView(ScrollView(this).apply { isFillViewport = true; addView(page) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        else root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))

        page.addView(row().apply {
            val actionSize = dp(48)
            addView(transportIcon(R.drawable.ic_back, "Back to library") {
                this@MainActivity.book = null
                render()
            }, LinearLayout.LayoutParams(actionSize, actionSize))
            addView(label(book.title, 16f, this@MainActivity.foreground, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPaddingRelative(dp(GAP), 0, dp(GAP), 0)
                minHeight = dp(48)
                setOnLongClickListener {
                    rename(book)
                    true
                }
                ViewCompat.replaceAccessibilityAction(
                    this, AccessibilityActionCompat.ACTION_LONG_CLICK, "Rename book"
                ) { view, _ -> view.performLongClick() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(transportIcon(R.drawable.ic_car, "Car Mode") { enterCarMode(book) },
                LinearLayout.LayoutParams(actionSize, actionSize))
        })

        val warnings = TrackOrder.warnings(book.tracks)
        page.addView(row().apply {
            addView(label("Chapters · ${book.tracks.size}", 14f, muted, bold = true), weighted())
            if (warnings.isNotEmpty()) addView(button("Numbering details") { showWarnings(warnings) }
                .apply { setPaddingRelative(dp(ROW_INSET), 0, dp(ROW_INSET), 0) })
        }, spaced(top = SECTION_GAP, bottom = GAP))
        val chapters = chapterList(book)
        page.addView(chapters, if (compact) LinearLayout.LayoutParams(-1, dp(190))
            else LinearLayout.LayoutParams(-1, 0, 1f))

        val chapter = label("", 23f, foreground, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        page.addView(chapter, spaced(top = GAP))

        val scope = ProgressScopeSwitch(this, playbackSettings.wholeBookProgress) { wholeBook ->
            playbackSettings.wholeBookProgress = wholeBook
            updatePlayer()
        }
        val scopeWidth = if (resources.configuration.fontScale > 1.25f) 270 else 232
        page.addView(scope, LinearLayout.LayoutParams(
            dp(minOf(scopeWidth, resources.configuration.screenWidthDp - SCREEN_INSET * 2).coerceAtLeast(1)), -2
        ).apply { gravity = Gravity.CENTER; topMargin = dp(GAP) })
        val progressHint = label("", 13f, muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        page.addView(progressHint)

        val seek = SeekBar(this).apply {
            max = 100_000
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(border)
            thumbTintList = ColorStateList.valueOf(accent)
            contentDescription = "Chapter position"
        }
        page.addView(seek, LinearLayout.LayoutParams(-1, dp(40)))
        val elapsed = label("", 14f, muted)
        val remaining = label("", 14f, muted).apply { gravity = Gravity.END }
        page.addView(row().apply {
            setPaddingRelative(dp(ROW_INSET), 0, dp(ROW_INSET), 0)
            addView(elapsed, weighted())
            addView(remaining, weighted())
        })

        val previous = transportIcon(R.drawable.ic_previous, "Previous chapter") { active()?.seekToPreviousMediaItem() }
        val next = transportIcon(R.drawable.ic_next, "Next chapter") { active()?.seekToNextMediaItem() }
        val play = transportIcon(R.drawable.ic_play, "Play", primary = true) { toggle(book) }
        val skip = playbackSettings.skipSeconds
        val rewind = button("−$skip") { active()?.seekBack() }.apply {
            contentDescription = "Back $skip seconds"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val forward = button("+$skip") { active()?.seekForward() }.apply {
            contentDescription = "Forward $skip seconds"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        page.addView(row().apply {
            gravity = Gravity.CENTER
            addView(previous, transportSlot())
            addView(rewind, transportSlot())
            addView(play, LinearLayout.LayoutParams(dp(68), dp(60)).apply {
                marginStart = dp(GAP / 2)
                marginEnd = dp(GAP / 2)
            })
            addView(forward, transportSlot())
            addView(next, transportSlot())
        }, spaced(top = SECTION_GAP))

        val speed = button("Speed") { chooseSpeed() }
        val timer = button("Sleep timer") { chooseTimer() }
        page.addView(row().apply {
            addView(speed, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timer, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(GAP) })
        }, spaced(top = GAP))

        controls = Controls(chapter, elapsed, remaining, seek, play, speed, timer, previous, next, rewind, forward,
            chapters, progressHint)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                applySeek(bar)
                updatePlayer()
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (!dragging) applySeek(bar)
                else if (playbackSettings.wholeBookProgress) {
                    bookTimeline?.let { timeline ->
                        val destination = seekPosition(bar, timeline.durationMs)
                        updateTimes(elapsed, remaining, destination, timeline.durationMs)
                        val target = timeline.chapterAt(destination)
                        chapter.text = book.tracks[target.index].title
                    }
                } else {
                    val player = active()
                    val index = player?.currentMediaItemIndex ?: savedChapterIndex(book)
                    val duration = chapterDuration(index, player)
                    if (duration > 0) updateTimes(elapsed, remaining, seekPosition(bar, duration), duration)
                }
            }
        })
    }


    private fun chapterList(book: Book): ListView {
        val durations = LongArray(book.tracks.size)
        chapterDurations = durations
        loadingTimeline = true
        val list = ListView(this).apply {
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
            adapter = object : BaseAdapter() {
                override fun getCount() = book.tracks.size
                override fun getItem(position: Int) = book.tracks[position]
                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val track = getItem(position)
                    return ((convertView as? ChapterRow) ?: ChapterRow()).apply {
                        tag = position
                        title.text = "${track.filename.substringBefore(" - ")}    ${track.title}"
                        duration.text = durations[position].takeIf { it > 0 }?.let(::time) ?: "—"
                    }
                }
            }
            setOnItemClickListener { _, _, index, _ -> open(book, index, true) }
        }
        val current = active()?.currentMediaItemIndex ?: savedChapterIndex(book)
        chapterDurationTask = chapterDurationReader.submit {
            val durationValues = LongArray(book.tracks.size)
            // Load the chapter being displayed before scanning the rest of a long book.
            val prioritized = sequenceOf(current).filter { it in book.tracks.indices } +
                book.tracks.indices.asSequence().filter { it != current }
            for (index in prioritized) {
                if (Thread.currentThread().isInterrupted) return@submit
                val duration = readTrackDuration(applicationContext, book.tracks[index].uri) ?: continue
                durationValues[index] = duration
                val text = time(duration)
                handler.post {
                    if (isDestroyed || controls?.chapters !== list) return@post
                    durations[index] = duration
                    val row = list.getChildAt(index - list.firstVisiblePosition) as? ChapterRow
                    if (row?.tag == index) row.duration.text = text
                    if (index == current) updatePlayer()
                }
            }
            val timeline = BookTimeline.fromDurations(durationValues)
            handler.post {
                if (isDestroyed || controls?.chapters !== list) return@post
                bookTimeline = timeline
                loadingTimeline = false
                updatePlayer()
            }
        }
        return list
    }

    private inner class ChapterRow : LinearLayout(this@MainActivity) {
        val title = label("", 16f, this@MainActivity.foreground).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val duration = label("", 14f, muted).apply {
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
            minWidth = dp(44)
        }

        init {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPaddingRelative(dp(ROW_INSET), dp(GAP), dp(ROW_INSET), dp(GAP))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_activated),
                    android.graphics.drawable.ColorDrawable(selectedSurface))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            addView(title, LayoutParams(0, -2, 1f))
            addView(duration, LayoutParams(-2, -2).apply { marginStart = dp(GAP) })
        }
    }

    private fun transportSlot() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(GAP / 2)
        marginEnd = dp(GAP / 2)
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
            addPressMotion()
            setOnClickListener { action() }
        }

    private fun updateTimes(elapsed: TextView, remaining: TextView, position: Long, duration: Long) {
        val elapsedText = time(position)
        val remainingText = if (duration > 0) "−${time(duration - position)}" else "—"
        if (elapsed.text != elapsedText) elapsed.text = elapsedText
        if (remaining.text != remainingText) remaining.text = remainingText
    }

    private fun seekPosition(bar: SeekBar, duration: Long): Long =
        (duration.toDouble() * bar.progress / bar.max).toLong().coerceIn(0L, duration)

    private fun progressValue(position: Long, duration: Long, max: Int): Int =
        if (duration > 0) (position.coerceIn(0, duration).toDouble() * max / duration).toInt() else 0

    private fun showProgressHint(view: TextView, hint: String?) {
        view.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint != null && view.text != hint) view.text = hint
    }

    private fun applySeek(bar: SeekBar) {
        val player = active()
        if (playbackSettings.wholeBookProgress) {
            val timeline = bookTimeline ?: return
            val target = timeline.chapterAt(seekPosition(bar, timeline.durationMs))
            if (player != null) player.seekTo(target.index, target.positionMs)
            else book?.let { open(it, target.index, false, target.positionMs) }
        } else {
            val selected = book ?: return
            val index = player?.currentMediaItemIndex ?: savedChapterIndex(selected)
            val duration = chapterDuration(index, player)
            if (duration <= 0) return
            val position = seekPosition(bar, duration)
            if (player != null) player.seekTo(position) else open(selected, index, false, position)
        }
    }

    private fun savedChapterIndex(selected: Book, bookmark: Bookmark = library.bookmark(selected.id)): Int =
        selected.tracks.indexOfFirst { it.uri == bookmark.uri }.coerceAtLeast(0)

    private fun chapterDuration(index: Int, player: MediaController?): Long =
        player?.duration?.takeIf { it > 0 } ?: chapterDurations.getOrElse(index) { 0L }

    private fun active(): MediaController? {
        val current = controller ?: return null
        val index = current.currentMediaItemIndex
        val item = current.currentMediaItem ?: return null
        return current.takeIf { item.mediaId == "${book?.id}:$index" }
    }

    private fun updatePlayer() {
        val selected = book ?: return
        val player = active()
        val saved = if (player == null) library.bookmark(selected.id) else null
        val index = player?.currentMediaItemIndex ?: saved?.let { savedChapterIndex(selected, it) } ?: 0
        val title = selected.tracks.getOrNull(index)?.title.orEmpty()
        val position = player?.currentPosition ?: saved?.position ?: 0
        val duration = chapterDuration(index, player)
        val playing = player?.playWhenReady == true && player.playbackState != Player.STATE_ENDED
            && player.playbackState != Player.STATE_IDLE
        val playAction = if (playing) "Pause" else "Play"
        carControls?.let {
            updateAnimatedText(it.chapter, title)
            updateAnimatedText(it.play, playAction)
            it.play.isEnabled = controller != null
            it.rewind.isEnabled = player != null && duration > 0
            it.forward.isEnabled = player != null && duration > 0
            val chapterDuration = if (player == null) carDurationMs else duration
            val percent = progressValue(position, chapterDuration, 100)
            updateProgress(it.progress, percent)
            val detail = if (chapterDuration > 0) "Chapter ${index + 1} of ${selected.tracks.size} · $percent%"
                else "Chapter ${index + 1} of ${selected.tracks.size}"
            if (it.detail.text != detail) it.detail.text = detail
            val elapsed = "${time(position)} elapsed"
            val remaining = if (chapterDuration > 0) "${time(chapterDuration - position)} left" else "—"
            if (it.elapsed.text != elapsed) it.elapsed.text = elapsed
            if (it.remaining.text != remaining) it.remaining.text = remaining
        }
        val views = controls ?: return
        if (!dragging || !playbackSettings.wholeBookProgress) updateAnimatedText(views.chapter, title)
        if (views.chapters.checkedItemPosition != index) {
            views.chapters.setItemChecked(index, true)
            if (index !in views.chapters.firstVisiblePosition..views.chapters.lastVisiblePosition) {
                views.chapters.setSelection(index)
            }
        }
        val entireBook = playbackSettings.wholeBookProgress
        val timeline = bookTimeline
        if (!dragging) {
            val total = if (entireBook) timeline?.durationMs ?: 0 else duration
            val elapsed = if (entireBook) timeline?.elapsed(index, position) ?: position else position
            if (entireBook && timeline == null) {
                if (views.elapsed.text != "—") views.elapsed.text = "—"
                if (views.remaining.text != "—") views.remaining.text = "—"
            } else updateTimes(views.elapsed, views.remaining, elapsed, total)
            views.seek.visibility = if (total > 0 || (!entireBook && position == 0L)) View.VISIBLE else View.INVISIBLE
            updateProgress(views.seek, progressValue(elapsed, total, views.seek.max))
            showProgressHint(views.progressHint, when {
                !entireBook || timeline != null -> null
                loadingTimeline -> "Reading chapter durations…"
                else -> "Book progress unavailable. Switch to Chapter."
            })
        }
        views.seek.contentDescription = if (entireBook) "Book position" else "Chapter position"
        views.seek.isEnabled = controller != null && if (entireBook) timeline != null else duration > 0
        views.play.isEnabled = controller != null
        if (views.play.contentDescription != playAction) {
            views.play.contentDescription = playAction
            views.play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            fadeInChangedContent(views.play)
        }
        val speedText = "Speed · ${player?.playbackParameters?.speed ?: saved?.speed ?: 1f}×"
        if (views.speed.text != speedText) views.speed.text = speedText
        val remaining = deadline - SystemClock.elapsedRealtime()
        val timerText = when {
            remaining > 0 -> "Sleep · ${time(remaining)}"
            timerCanExtend -> "Sleep timer ended"
            else -> "Sleep timer"
        }
        if (views.timer.text != timerText) views.timer.text = timerText
        views.previous.isEnabled = player?.hasPreviousMediaItem() == true
        views.next.isEnabled = player?.hasNextMediaItem() == true
        views.rewind.isEnabled = player != null && duration > 0
        views.forward.isEnabled = player != null && duration > 0
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

    private fun open(book: Book, index: Int, play: Boolean, positionMs: Long = 0L) {
        val player = controller ?: return toast("Playback is connecting")
        player.sendCustomCommand(openBookCommand, Bundle().apply {
            putString("id", book.id)
            putInt("index", index)
            putBoolean("play", play)
            putLong("positionMs", positionMs)
        })
    }

    private fun chooseSpeed() {
        val player = active() ?: return toast("Start the book to set playback speed")
        val minimum = 50
        val maximum = 300
        val step = 5
        val panel = controlPanel("Playback speed")
        val readout = panelReadout()
        panel.addView(readout, spaced(bottom = SECTION_GAP))
        val slider = panelSlider("Playback speed", (maximum - minimum) / step)
        val slower = panelButton("−", "Decrease speed")
        val faster = panelButton("+", "Increase speed")
        panel.addView(sliderRow(slower, slider, faster))
        val presets = listOf(100 to "1.0", 125 to "1.25", 150 to "1.5", 200 to "2.0", 300 to "3.0")
        val buttons = presets.map { (_, title) -> panelButton(title, "$title times speed") }
        panel.addView(panelButtonRow(buttons), spaced(top = SECTION_GAP))

        fun showSpeed(progress: Int, apply: Boolean) {
            val percent = minimum + progress.coerceIn(0, slider.max) * step
            val speed = percent / 100f
            slider.progress = (percent - minimum) / step
            readout.text = String.format(Locale.ROOT, "%.2f×", speed)
            slower.isEnabled = percent > minimum
            faster.isEnabled = percent < maximum
            presets.zip(buttons).forEach { (preset, button) ->
                button.isSelected = preset.first == percent
            }
            if (apply) active()?.setPlaybackSpeed(speed)
        }

        showSpeed(((player.playbackParameters.speed * 100 - minimum) / step).roundToInt(), apply = false)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showSpeed(progress, apply = true)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        slower.setOnClickListener { showSpeed(slider.progress - 1, apply = true) }
        faster.setOnClickListener { showSpeed(slider.progress + 1, apply = true) }
        presets.zip(buttons).forEach { (preset, button) ->
            button.setOnClickListener { showSpeed((preset.first - minimum) / step, apply = true) }
        }

        showControlPanel(panel)
    }

    private fun controlPanel(title: String) = paddedColumn(DIALOG_INSET).apply {
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0xFF3A3A3A.toInt())
        }
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = title
    }

    private fun panelReadout() = label("", 30f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
    }

    private fun panelSlider(description: String, steps: Int) = SeekBar(this).apply {
        max = steps
        contentDescription = description
        progressTintList = ColorStateList.valueOf(Color.WHITE)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF777777.toInt())
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun sliderRow(decrease: Button, slider: SeekBar, increase: Button) = row().apply {
        addView(decrease, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(slider, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(increase, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun panelButtonRow(buttons: List<Button>) = row().apply {
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) marginStart = dp(GAP)
            })
        }
    }

    private fun showControlPanel(panel: View): Dialog {
        val dialog = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(panel)
            setCanceledOnTouchOutside(true)
        }
        dialog.showWithMotion()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val width = minOf(resources.displayMetrics.widthPixels - dp(DIALOG_INSET * 2), dp(440))
            setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun panelButton(text: String, description: String) = Button(this).apply {
        this.text = text
        contentDescription = description
        textSize = if (text.length == 1) 23f else 14f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        addPressMotion()
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val selected = intArrayOf(android.R.attr.state_selected)
        setTextColor(ColorStateList(arrayOf(disabled, selected, intArrayOf()),
            intArrayOf(0xFF777777.toInt(), Color.BLACK, Color.WHITE)))
        val fill = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            color = ColorStateList(arrayOf(selected, intArrayOf()), intArrayOf(Color.WHITE, 0xFF252525.toInt()))
        }
        background = RippleDrawable(ColorStateList.valueOf(0x40777777), fill, null)
    }

    private fun chooseTimer() {
        if (active() == null) return toast("Start the book to set a sleep timer")
        val step = 5
        val panel = controlPanel("Sleep timer")
        panel.addView(label("Pause playback in", 14f, 0xFFBBBBBB.toInt()).apply { gravity = Gravity.CENTER })
        val readout = panelReadout()
        panel.addView(readout, spaced(bottom = SECTION_GAP))
        val slider = panelSlider("Timer duration", 119)
        val shorter = panelButton("−", "Decrease timer by five minutes")
        val longer = panelButton("+", "Increase timer by five minutes")
        panel.addView(sliderRow(shorter, slider, longer))
        val presets = listOf(5, 15, 30, 60, 120)
        val buttons = presets.map { panelButton("${it}m", "$it minutes") }
        panel.addView(panelButtonRow(buttons), spaced(top = SECTION_GAP))

        fun showDuration(progress: Int) {
            slider.progress = progress.coerceIn(0, slider.max)
            val minutes = slider.progress + 1
            readout.text = "$minutes min"
            shorter.isEnabled = slider.progress > 0
            longer.isEnabled = slider.progress < slider.max
            presets.zip(buttons).forEach { (preset, button) -> button.isSelected = preset == minutes }
        }

        val remaining = deadline - SystemClock.elapsedRealtime()
        val selectedMinutes = if (remaining > 0) ((remaining + 59_999L) / 60_000L).toInt() else 30
        showDuration(selectedMinutes - 1)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showDuration(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        shorter.setOnClickListener { showDuration(slider.progress - step) }
        longer.setOnClickListener { showDuration(slider.progress + step) }
        presets.zip(buttons).forEach { (preset, button) ->
            button.setOnClickListener { showDuration(preset - 1) }
        }
        val canExtend = timerCanExtend
        val off = if (canExtend) panelButton("Extend", "Extend last timer")
            else panelButton("Turn off", "Turn timer off").apply { isEnabled = remaining > 0 }
        val start = panelButton(if (remaining > 0) "Reset timer" else "Start timer",
            if (remaining > 0) "Reset timer" else "Start timer").apply { isSelected = true }
        panel.addView(panelButtonRow(listOf(off, start)), spaced(top = GAP))
        val dialog = showControlPanel(panel)
        off.setOnClickListener { if (canExtend) timer(null, extend = true) else timer(0); dialog.dismiss() }
        start.setOnClickListener { timer(slider.progress + 1); dialog.dismiss() }
    }

    private fun timer(minutes: Int?, extend: Boolean = false) {
        val player = controller ?: return
        val result = player.sendCustomCommand(timerCommand, Bundle().apply {
            if (minutes != null) putInt("minutes", minutes)
            if (extend) putBoolean("extend", true)
        })
        result.addListener({
            if (controller === player) runCatching { result.get() }.onSuccess {
                if (it.resultCode == SessionResult.RESULT_SUCCESS) updateTimerState(it.extras)
            }
        }, mainExecutorCompat())
    }

    private fun updateTimerState(extras: Bundle) {
        deadline = extras.getLong("deadline")
        timerCanExtend = extras.getBoolean("canExtend")
        updatePlayer()
    }

    private fun rename(book: Book) {
        val input = EditText(this).apply { setSingleLine(); setText(book.title); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle("Rename book").setView(dialogFields().apply { addView(input) })
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
        dialog.showWithMotion()
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
                            .setNegativeButton("Later", null).create().showWithMotion()
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
                }.setNegativeButton("Cancel", null).create().showWithMotion()
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
        updateButtonText = when {
            downloading -> "Downloading update…"
            busy -> "Checking GitHub…"
            else -> "Check for updates"
        }
        updateButton?.text = updateButtonText
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
            .setPositiveButton("OK", null).create().showWithMotion()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun Dialog.showWithMotion() {
        window?.setWindowAnimations(if (ValueAnimator.areAnimatorsEnabled()) R.style.DialogMotion else 0)
        show()
    }

    private fun View.addPressMotion() {
        stateListAnimator = AnimatorInflater.loadStateListAnimator(this@MainActivity, R.animator.button_press)
    }

    private fun fadeInChangedContent(view: View) {
        if (!view.isLaidOut || !ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.alpha = 0.6f
        view.animate().alpha(1f).setDuration(150).setInterpolator(motionInterpolator).start()
    }

    private fun updateAnimatedText(view: TextView, text: String) {
        if (view.text == text) return
        view.text = text
        fadeInChangedContent(view)
    }

    private fun updateProgress(bar: android.widget.ProgressBar, progress: Int) {
        val delta = progress - bar.progress
        if (delta != 0) {
            val animate = delta > 0 && delta <= bar.max / 50 && ValueAnimator.areAnimatorsEnabled()
            bar.setProgress(progress, animate)
        }
    }
    private fun paddedColumn(inset: Int) = column().apply {
        val padding = dp(inset)
        setPaddingRelative(padding, padding, padding, padding)
    }
    private fun dialogFields() = column().apply {
        setPaddingRelative(dp(DIALOG_INSET), dp(GAP), dp(DIALOG_INSET), dp(GAP))
    }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun dp(size: Int) = (size * resources.displayMetrics.density + 0.5f).toInt()
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)
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
        addPressMotion()
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
