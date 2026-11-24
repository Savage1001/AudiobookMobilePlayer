# Repository and privacy rules

Keep this repository suitable for a future public release. Only the owner may authorize a visibility change or the selection of an open-source license.

- Never commit credentials, tokens, signing keys, machine-specific settings, personal paths, device identifiers, user library data, logs, or audiobook files and artwork without redistribution rights.
- Keep signing material outside the checkout. Commit source and reproducible build configuration. Publish only the intended APK as a release asset.
- Use invented titles and generated audio for tests. Inspect staged changes, history, and release assets for private data before pushing or changing visibility.
- Keep playback and the library offline. Use Android's folder picker for scoped access and keep user data in app-private storage. Do not add accounts, analytics, network permissions, or backups of the library without explicit approval.
- Store filename order and use the same ordered list for display and playback. Do not silently drop duplicates or renumber gaps.
