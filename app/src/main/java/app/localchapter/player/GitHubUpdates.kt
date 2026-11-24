package app.localchapter.player

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class UpdateRelease(val tag: String, val url: URL, val size: Long, val sha256: String)

class UpdateFileProvider : FileProvider()

internal class GitHubUpdates(private val context: Context) {
    companion object {
        private const val REPOSITORY = "Savage1001/AudiobookMobilePlayer"
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
        private const val MAX_RELEASE_BYTES = 1024 * 1024
        private val stableTag = Regex("""(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""")
        private val sha256Pattern = Regex("sha256:([a-f0-9]{64})")

        fun isNewer(candidate: String, installed: String): Boolean {
            val version = stableTag.matchEntire(candidate) ?: return false
            val current = stableTag.matchEntire(installed) ?: return false
            return version.groupValues.drop(1).zip(current.groupValues.drop(1))
                .map { (left, right) -> left.toInt().compareTo(right.toInt()) }
                .firstOrNull { it != 0 } == 1
        }

        fun isAssetUrl(url: URL): Boolean = url.protocol == "https" && url.userInfo == null &&
            (url.port == -1 || url.port == 443) &&
            url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    val installedVersion: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun latest(): UpdateRelease? {
        val feed = URL("https://api.github.com/repos/$REPOSITORY/releases/latest")
        val json = request(feed, followAssetRedirects = false) { input ->
            val bytes = input.readNBytesCompat(MAX_RELEASE_BYTES + 1)
            check(bytes.size <= MAX_RELEASE_BYTES) { "Release response too large" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        val tag = json.getString("tag_name")
        if (json.getBoolean("draft") || json.getBoolean("prerelease") || !isNewer(tag, installedVersion)) return null
        val name = "Local-Chapter-$tag.apk"
        val expected = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
        val assets = json.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") != name || asset.optString("state") != "uploaded") continue
            val size = asset.optLong("size")
            val digest = sha256Pattern.matchEntire(asset.optString("digest"))
            check(asset.optString("browser_download_url") == expected && size in 1..MAX_APK_BYTES && digest != null) {
                "Invalid release asset"
            }
            return UpdateRelease(tag, URL(expected), size, digest.groupValues[1])
        }
        error("Release has no installable APK")
    }

    fun download(release: UpdateRelease): File {
        val directory = File(context.cacheDir, "updates")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val output = File.createTempFile("local-chapter-", ".apk", directory)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var received = 0L
            request(release.url, followAssetRedirects = true) { input ->
                output.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= release.size) { "APK exceeds the advertised size" }
                        hash.update(buffer, 0, count)
                        sink.write(buffer, 0, count)
                    }
                }
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(received == release.size && digest == release.sha256) { "APK checksum mismatch" }
            verifyPackage(output, release.tag)
            directory.listFiles()?.filter {
                it != output && System.currentTimeMillis() - it.lastModified() > 86_400_000L
            }?.forEach { it.delete() }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, tag: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Unreadable APK")
        val signers = signers(installed)
        check(candidate.packageName == context.packageName && candidate.versionName == tag &&
            versionCode(candidate) > versionCode(installed) &&
            signers.isNotEmpty() && signers(candidate) == signers) { "APK identity or version mismatch" }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<Signature> =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty().toSet()
        else info.signatures.orEmpty().toSet()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun <T> request(start: URL, followAssetRedirects: Boolean, read: (InputStream) -> T): T {
        var url = start
        repeat(5) {
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Local-Chapter")
                if (!followAssetRedirects) setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                when (connection.responseCode) {
                    200 -> return connection.inputStream.use(read)
                    301, 302, 303, 307, 308 -> {
                        check(followAssetRedirects) { "Unexpected GitHub API redirect" }
                        val location = connection.getHeaderField("Location") ?: error("Missing redirect")
                        url = URL(url, location)
                        check(isAssetUrl(url)) { "Unexpected APK download host" }
                    }
                    else -> error("GitHub returned HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many GitHub redirects")
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (result.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - result.size()))
            if (count < 0) break
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
}
