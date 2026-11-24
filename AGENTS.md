# Repository and privacy rules

Keep this repository suitable for a future public release. Only the owner may authorize a visibility change or the selection of an open-source license.

- Never commit credentials, tokens, signing keys, machine-specific settings, personal paths, device identifiers, user library data, logs, or audiobook files and artwork without redistribution rights.
- Keep signing material outside the checkout. Commit source and reproducible build configuration. Publish only the intended APK as a release asset.
- Use invented titles and generated audio for tests. Inspect staged changes, history, and release assets for private data before pushing or changing visibility.
- Keep playback and the library offline. Use Android's folder picker for scoped access and keep user data in app-private storage. Network access is only for public GitHub release metadata and APK downloads. Never include library data, identifiers, credentials, or analytics in update requests.
- Publish stable updates with a numeric version tag, matching versionName, increasing versionCode, and an APK named Local-Chapter-<version>.apk. Use the existing release signing key and require Android's installation confirmation.
- Store filename order and use the same ordered list for display and playback. Do not silently drop duplicates or renumber gaps.
