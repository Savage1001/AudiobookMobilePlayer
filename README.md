# Local Chapter

Local Chapter is a native Kotlin audiobook player for Android 8.0 and newer. It keeps the library and listening positions on the device and plays through Android's background media controls.

## Import a book

1. Keep each book's tracks together in a folder on your phone.
2. Tap **Import folder** and select that folder. Local Chapter reads its top-level files through the Android folder picker.
3. Tap **Play** to resume, or tap a track to start it from the beginning. Use **Rename** to change the folder-derived book title.

Name tracks with at least two digits, a space, a hyphen, a space, and the title.

    00 - Prologue.mp3
    01 - Chapter One.m4a
    02 - A Change of Plans.mp4
    03 - Epilogue.mp3

Extensions are case-insensitive. MP4 files play their audio track. The starting number can be any number, including zero. The filename determines order and title, with filename sorting used to break numeric ties. The app reports gaps and duplicates and includes every matching file. Select the same folder again to refresh the track list.

Playback includes 30-second skips, chapter navigation, speed, a sleep timer, and a saved track position for each book. On Galaxy devices, playback is available to One UI through the Android media session. Enable **Media player** under **Settings → Lock screen and AOD → Now bar** if available on your device.

## Updates

Install [0.0.2 or newer](https://github.com/Savage1001/AudiobookMobilePlayer/releases/latest) to enable in-app updates. Local Chapter checks public GitHub releases when you open the library, at most once every six hours. Use **Check for updates** to check immediately. If a new release is available, tap **Download update** and confirm the installation in Android. Android may first ask you to allow Local Chapter to install updates.

The updater verifies the APK checksum, application ID, version, and signing certificate. Release checks send no audiobook or listening data. Import and playback work offline.

## Build

Use JDK 17 and Android SDK Platform 35. Set ANDROID_HOME to the SDK root and run:

    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease

On Windows use gradlew.bat. The release task produces an unsigned APK. Sign it with a keystore stored outside the checkout. See [AGENTS.md](AGENTS.md) for repository privacy rules.
