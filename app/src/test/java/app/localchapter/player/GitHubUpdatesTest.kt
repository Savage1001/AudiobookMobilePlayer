package app.localchapter.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class GitHubUpdatesTest {
    @Test fun comparesStableNumericVersions() {
        assertTrue(GitHubUpdates.isNewer("0.0.10", "0.0.2"))
        assertTrue(GitHubUpdates.isNewer("1.0.0", "0.99.99"))
        assertFalse(GitHubUpdates.isNewer("0.0.2", "0.0.2"))
        assertFalse(GitHubUpdates.isNewer("0.0.1", "0.0.2"))
        assertFalse(GitHubUpdates.isNewer("0.0.3-beta", "0.0.2"))
        assertFalse(GitHubUpdates.isNewer("0.00.3", "0.0.2"))
    }

    @Test fun restrictsDownloadRedirectsToGitHubOverHttps() {
        assertTrue(GitHubUpdates.isAssetUrl(URL("https://release-assets.githubusercontent.com/example")))
        assertFalse(GitHubUpdates.isAssetUrl(URL("http://github.com/example")))
        assertFalse(GitHubUpdates.isAssetUrl(URL("https://github.com.example.org/example")))
        assertFalse(GitHubUpdates.isAssetUrl(URL("https://example.org@github.com/example")))
        assertFalse(GitHubUpdates.isAssetUrl(URL("https://github.com:8443/example")))
    }
}
