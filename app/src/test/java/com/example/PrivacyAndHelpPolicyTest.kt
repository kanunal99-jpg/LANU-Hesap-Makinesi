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
    fun `real calls declare runtime media permissions but no broad gallery access`() {
        val permissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toSet()
        assertTrue("Real calls require explicit Android microphone permission", "android.permission.RECORD_AUDIO" in permissions)
        assertTrue("Real video calls require explicit Android camera permission", "android.permission.CAMERA" in permissions)
        assertFalse("No broad image library permission", "android.permission.READ_MEDIA_IMAGES" in permissions)
        assertFalse("No broad storage permission", "android.permission.READ_EXTERNAL_STORAGE" in permissions)
        assertTrue("Encrypted LANU relay requires internet", "android.permission.INTERNET" in permissions)
    }

    @Test
    fun `disclosures are truthful about foreground sync and manual backups`() {
        val privacy = LanuInformation.sections(InformationTopic.PRIVACY).joinToString(" ") { it.explanation }
        val help = LanuInformation.sections(InformationTopic.HELP).joinToString(" ") { it.explanation }
        val terms = LanuInformation.sections(InformationTopic.TERMS).joinToString(" ") { it.explanation }
        assertTrue(privacy.contains("veritabanının tamamı ayrıca şifrelenmiş değildir"))
        assertTrue(privacy.contains("otomatik yedeklemesine"))
        assertTrue(privacy.contains("Firebase Cloud Messaging"))
        assertTrue(privacy.contains("Mesaj ve gelen arama bildirimleri"))
        assertTrue(privacy.contains("FCM belirteci"))
        assertTrue(privacy.contains("Bildirime dokunmak gizli alanı açmaz"))
        assertTrue(help.contains("Sohbet mesajı ve gelen arama uyarıları ayrı ayrı"))
        assertTrue(terms.contains("önceden izin verilmiş arama ve/veya mesaj bildirimleri"))
        assertFalse(help.contains("gizli alan kapalıyken bildirim yoktur"))
        assertFalse(terms.contains("Kapalı uygulamaya gelen arama bildirimi yoktur"))
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

