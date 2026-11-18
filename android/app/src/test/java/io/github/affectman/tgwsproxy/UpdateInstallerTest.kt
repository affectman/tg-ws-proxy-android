package io.github.affectman.tgwsproxy

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import io.github.affectman.tgwsproxy.update.UpdateInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 36])
class UpdateInstallerTest {
    private lateinit var context: Application
    private lateinit var viewIntent: Intent

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://${context.packageName}.files/updates/update.apk")
        viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
    }

    @Test
    fun selectsSystemInstallerEvenWhenThirdPartyHandlerComesFirst() {
        addInstaller("com.aefyr.sai", system = false)
        addInstaller("com.example.oem.installer", system = true)

        val intent = UpdateInstaller.installIntent(context, viewIntent.data!!)

        assertEquals(
            ComponentName("com.example.oem.installer", "com.example.oem.installer.InstallActivity"), intent.component,
        )
        assertEquals(viewIntent.data, intent.data)
        assertEquals(viewIntent.type, intent.type)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test(expected = ActivityNotFoundException::class)
    fun doesNotFallBackToThirdPartyInstaller() {
        addInstaller("com.aefyr.sai", system = false)
        UpdateInstaller.installIntent(context, viewIntent.data!!)
    }

    @Test
    fun providerExposesApkNameSizeAndContents() {
        // FileProvider checks Android '/' paths; host Windows paths cannot pass
        // that check under Robolectric. This test runs on both SDKs in Linux CI.
        assumeFalse("FileProvider requires Unix paths", File.separatorChar == '\\')
        val apk = File(context.cacheDir, "updates/update.apk")
        apk.parentFile!!.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("update.apk", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(apk.length(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        context.contentResolver.openInputStream(uri)!!.use { input ->
            assertTrue(apk.readBytes().contentEquals(input.readBytes()))
        }
    }

    private fun addInstaller(packageName: String, system: Boolean) {
        val info = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.InstallActivity"
            exported = true
            enabled = true
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                flags = ApplicationInfo.FLAG_INSTALLED or (if (system) ApplicationInfo.FLAG_SYSTEM else 0)
                enabled = true
            }
        }
        val pm = shadowOf(context.packageManager)
        pm.addOrUpdateActivity(info)
        pm.addIntentFilterForActivity(ComponentName(packageName, info.name), IntentFilter().apply {
            addAction(Intent.ACTION_VIEW)
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("content")
            addDataType("application/vnd.android.package-archive")
            priority = if (system) 0 else 100
        })
    }
}
