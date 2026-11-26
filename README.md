# Local Chapter

Local Chapter is an offline audiobook player for Android 8.0 and later. It plays chapters from folders on your phone and remembers where you left off in each book.

## Install

1. Open the [latest GitHub release](https://github.com/Savage1001/LocalChapter/releases/latest).
2. Under **Assets**, download the file ending in `.apk`.
3. Open the downloaded file and follow Android’s installation prompts. If Android asks, allow installs from the app you used to open the file.

Already using Local Chapter? Install the update over your existing copy to keep your library and saved positions.

## Add an audiobook

Put each book in its own folder. Name the MP3, M4A, or MP4 files with at least two digits, followed by a space, hyphen, space, and the chapter title.

```text
00 - Prologue.mp3
01 - Chapter One.m4a
02 - A Change of Plans.mp4
03 - Epilogue.mp3
```

In Local Chapter, tap **Import folder**, select the book’s folder, and grant access. The folder name becomes the book title. Open the **⋯** menu on the player screen to rename it.

To remove a book and its saved progress, press and hold its title in the library, then tap **Delete**. The audio files stay on your device.

- Tracks play in numeric order, so `02` comes before `10`, and `100` comes after `99`. Numbering can start anywhere, including `00`.
- The filenames determine the order and chapter titles. If two tracks have the same number, the app sorts them by filename and keeps both. It reports skipped or repeated numbers.
- The app reads matching files directly inside the selected folder and plays the audio from MP4 files. Import the same folder again after adding or replacing tracks.

## Listen

Each chapter’s length is shown on the right. Tap a chapter to start it, or tap **Play** to resume your saved position. Use the seek bar, previous and next chapter buttons, or 30-second skips to navigate. Playback speed and the sleep timer are below the controls.

Skip buttons carry across chapter boundaries. Open **Settings** in the library to select one interval for rewind and forward. Choose a preset or enter a custom number of seconds. The default is 30 seconds.

Choose **Chapter** or **Book** above the seek bar to see and scrub through either the current chapter or the full audiobook.

### Sleep timer

Pick a duration from 1 to 120 minutes and tap **Start timer**. In library **Settings**, you can turn the 30-second audio fade on or off and enable **Shake to extend**. When enabled, shake near the end of the timer to restart it for the same duration. You can also tap **Extend** in the timer panel for up to 30 seconds after it ends.

### Car Mode

Open **⋯ → Car Mode** on the player. The phone screen stays awake and shows large play, pause, rewind, and forward controls, plus a read-only chapter progress bar with elapsed and remaining time.

Playback continues in the background, with controls available through Android’s media notification. On supported Samsung Galaxy devices, turn on **Media player** under **Settings → Lock screen and AOD → Now bar** for lock-screen controls.

## Get updates

From version `0.0.2` onward, the app checks GitHub for new releases when you open the library, at most once every six hours. Tap **Check for updates** in the library to check immediately. If an update is available, tap **Download update**, then confirm the installation in Android.

Your books and listening progress stay on your device. Internet access is used only to check for and download app updates.

## Build from source

Requires JDK 17 and Android SDK Platform 35. Set `ANDROID_HOME` to your SDK directory.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

On Windows, use `gradlew.bat`. The build produces an unsigned APK at `app/build/outputs/apk/release/app-release-unsigned.apk`. Keep signing keys outside the checkout. See [AGENTS.md](AGENTS.md) for the repository’s privacy and release rules.
