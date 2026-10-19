package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.features.security.InformationTopic
import com.example.features.security.LanuInformation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyAndHelpPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `private app data is excluded from automatic Android backup and transfer`() {
        val flags = context.applicationInfo.flags
        assertEquals(
            "Android application must not opt in to automatic cloud backup.",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
        val oldBackup = excludes(context.resources.getXml(R.xml.backup_rules))
        assertTrue("Pre-Android-12 backups must exclude the app root", "root:." in oldBackup)
        val modernBackup = excludes(context.resources.getXml(R.xml.data_extraction_rules))
        assertTrue("Cloud backup must exclude the app root", "root:." in modernBackup)
        assertEquals("Both Android 12+ transfer paths must have exclusions", 2, modernBackup.count { it == "root:." })
    }

    @Test
    fun `unfinished demo calls do not require microphone or camera permissions`() {
        val permissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toSet()
        assertFalse("Demo call must not request device microphone", "android.permission.RECORD_AUDIO" in permissions)
        assertFalse("Demo call must not request camera", "android.permission.CAMERA" in permissions)
        assertTrue("Encrypted LANU relay requires internet", "android.permission.INTERNET" in permissions)
    }

    @Test
    fun `disclosures are truthful about foreground sync and manual backups`() {
        val privacy = LanuInformation.sections(InformationTopic.PRIVACY).joinToString(" ") { it.explanation }
        val help = LanuInformation.sections(InformationTopic.HELP).joinToString(" ") { it.explanation }
        val terms = LanuInformation.sections(InformationTopic.TERMS).joinToString(" ") { it.explanation }
        assertTrue(privacy.contains("veritabanının tamamı ayrıca şifrelenmiş değildir"))
        assertTrue(privacy.contains("otomatik yedeklemesine"))
        assertTrue(help.contains("gizli alan kapalıyken bildirim yoktur"))
        assertTrue(terms.contains("gerçek çok kullanıcılı servis değildir"))
        assertTrue(terms.contains("Karşı tarafın cihazından veya sunucudan silmez"))
        assertTrue(LanuInformation.sections(InformationTopic.PRIVACY).all { it.heading.isNotBlank() && it.explanation.isNotBlank() })
    }

    private fun excludes(parser: XmlPullParser): List<String> {
        val result = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                result.add("${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}")
            }
            parser.next()
        }
        return result
    }
}
