package io.github.affectman.tgwsproxy

import io.github.affectman.tgwsproxy.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun versionCodeComesFromTag() {
        assertEquals(412, UpdateChecker.versionCodeOf("v1.11.3-android.412"))
        assertEquals(7, UpdateChecker.versionCodeOf("v2.0.0-beta-android.7"))
        assertNull(UpdateChecker.versionCodeOf("v1.11.3"))
    }

    @Test
    fun parsesReleaseWithApk() {
        val json = """
            {"tag_name": "v1.11.3-android.412", "html_url": "https://github.com/x/y/releases/tag/v1",
             "body": "notes",
             "assets": [
               {"name": "checksums.txt", "browser_download_url": "https://e/x.txt"},
               {"name": "tg-ws-proxy-android-1.11.3-android.412.apk", "browser_download_url": "https://e/app.apk"}
             ]}
        """.trimIndent()
        val info = UpdateChecker.parseRelease(json)!!
        assertEquals(412, info.versionCode)
        assertEquals("1.11.3-android.412", info.versionName)
        assertEquals("https://e/app.apk", info.apkUrl)
    }

    @Test
    fun releaseWithoutApkIsIgnored() {
        assertNull(UpdateChecker.parseRelease("""{"tag_name": "v1-android.3", "assets": []}"""))
    }
}
