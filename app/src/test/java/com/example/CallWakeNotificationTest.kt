package com.example

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.example.core.messaging.MessageWakeNotification
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakeNotification
import com.example.core.calls.CallWakeTapPolicy
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CallWakeNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        CallWakePreferences.setEnabled(context, false)
        CallWakePreferences.acknowledgeRemoteRevoke(context)
        CallWakePolicy.privateAreaVisible = false
    }

    @Test fun notificationTapIsReentryOnlyAndNeverAChatDeepLink() {
        assertTrue(CallWakeTapPolicy.requiresFreshEntry(CallWakeTapPolicy.ACTION))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry(null))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry(android.content.Intent.ACTION_MAIN))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry("lanu_chat_or_media"))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry("LANU_CALL_WAKE"))
        assertTrue(CallWakeTapPolicy.ACTION.startsWith(context.packageName))
    }

    @Test fun AndroidSixThroughSixteenPermissionBehaviorIsExplicit() {
        for (api in 23..32) {
            assertFalse(CallWakePolicy.requiresNotificationPermission(api))
        }
        for (api in 33..36) {
            assertTrue(CallWakePolicy.requiresNotificationPermission(api))
        }
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test fun defaultIsPrivateAndRequiresExplicitConsent() {
        CallWakePreferences.setEnabled(context, false)
        assertFalse(CallWakePreferences.isEnabled(context))
        assertFalse(CallWakeNotification.show(context, mapOf(
            "type" to CallWakePolicy.TYPE, "at" to "1710000000000"), 1710000000000))
        assertTrue(CallWakePreferences.setEnabled(context, true))
        assertTrue(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.setEnabled(context, false))
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test fun arbitraryOrExpiredChatAndMediaEventsNeverWake() {
        val now = 1710000000000L
        assertFalse(CallWakePolicy.eligible(mapOf("type" to "chat", "at" to now.toString()), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to "media", "at" to now.toString()), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to now.toString(), "peer" to "secret"), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to (now-66000).toString()), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to (now+16000).toString()), now))
        assertTrue(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to (now-60000).toString()), now))
    }

    @Test fun disabledConsentPersistsPendingRemoteRevokeUntilAcknowledged() {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        assertTrue(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        // An unrelated preference write must never erase unconfirmed revoke.
        assertTrue(CallWakePreferences.setEnabled(context, false))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertTrue(CallWakePreferences.acknowledgeRemoteRevoke(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test @Config(sdk = [29]) fun callOnlyChannelUsesSystemRingtoneHighImportanceAndHidesLockedContent() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        CallWakeNotification.ensureChannel(manager)
        val channel = manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), channel.sound)
        assertNotNull(channel.audioAttributes)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, channel.audioAttributes.usage)
    }

    @Test fun blockedCallChannelIsNotMistakenForDeliveryAcrossAndroidVersions() {
        for (api in 23..25) {
            assertTrue(CallWakePolicy.channelCanDisplay(api, null))
        }
        for (api in 26..36) {
            assertFalse("Missing channel must fail closed on API $api",
                CallWakePolicy.channelCanDisplay(api, null))
            assertFalse("User-blocked channel must not be posted on API $api",
                CallWakePolicy.channelCanDisplay(api, NotificationManager.IMPORTANCE_NONE))
            assertTrue("User-selected quiet channel must stay usable on API $api",
                CallWakePolicy.channelCanDisplay(api, NotificationManager.IMPORTANCE_LOW))
            assertTrue(CallWakePolicy.channelCanDisplay(api, NotificationManager.IMPORTANCE_HIGH))
        }
    }

    @Test fun blockedCallChannelDoesNotBurnDeduplicationWhenUserReenablesIt() {
        val timestamp = 1710000090083L
        var posts = 0
        if (CallWakePolicy.channelCanDisplay(34, NotificationManager.IMPORTANCE_NONE)) {
            CallWakePolicy.publishOnce(timestamp) { posts++ }
        }
        assertEquals(0, posts)
        assertTrue(CallWakePolicy.channelCanDisplay(34, NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue(CallWakePolicy.publishOnce(timestamp) { posts++ })
        assertFalse(CallWakePolicy.publishOnce(timestamp) { posts++ })
        assertEquals(1, posts)
    }

    @Test fun oldCallChannelExplicitlyMutedDoesNotBecomeLoudOnUpgrade() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            "lanu_private_calls_v1", "Old muted call channel", NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) })
        CallWakeNotification.ensureChannel(manager)
        val channel = manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(null, channel.sound)
    }

    @Test fun failedAndroidNotificationAllowsRetryButNotDuplicateSuccess() {
        val timestamp = 1710000000901L
        var deliveries = 0

        // An OEM NotificationManager throw must not consume the only ring.
        assertFalse(CallWakePolicy.publishOnce(timestamp) {
            deliveries++
            throw SecurityException("simulated revoked notification permission")
        })
        assertEquals(1, deliveries)

        assertTrue(CallWakePolicy.publishOnce(timestamp) { deliveries++ })
        assertEquals(2, deliveries)

        // The successfully delivered wake remains deduplicated.
        assertFalse(CallWakePolicy.publishOnce(timestamp) { deliveries++ })
        assertEquals(2, deliveries)
    }

    @Test fun oemLinkageErrorCannotKillFcmAndUndeliveredCallCanRetry() {
        val timestamp = 1710000000907L
        var delivered = 0
        // Examples of vendor ABI failures: missing method at channel setup
        // and abstract framework dispatch during notification publishing.
        assertFalse(CallWakePolicy.safePlatformDelivery {
            throw NoSuchMethodError("simulated OEM notification ABI mismatch")
        })
        assertFalse(CallWakePolicy.publishOnce(timestamp) {
            delivered++
            throw AbstractMethodError("simulated OEM notification dispatch")
        })
        assertEquals(1, delivered)
        assertTrue("Failed OEM notification must not burn the wake claim",
            CallWakePolicy.publishOnce(timestamp) { delivered++ })
        assertFalse("An actually delivered call remains deduplicated",
            CallWakePolicy.publishOnce(timestamp) { delivered++ })
        assertEquals(2, delivered)
    }

    @Test fun revokedConsentAfterOemLinkageFailureNeverReportsDelivery() {
        val at = 1710000000908L
        var posts = 0
        assertFalse(CallWakePolicy.publishWhileConsented(
            at = at,
            isConsented = { true },
            notify = {
                posts++
                throw NoSuchMethodError("simulated OEM notify API")
            },
            cancel = { error("Nothing was posted") }
        ))
        assertEquals(1, posts)
        assertTrue(CallWakePolicy.publishWhileConsented(
            at = at,
            isConsented = { true },
            notify = { posts++ },
            cancel = { error("A valid notification must not be cancelled") }
        ))
        assertEquals(2, posts)
    }

    @Test fun runtimeNotificationFailureAlsoReleasesOnlyItsOwnTimestamp() {
        val failedTimestamp = 1710000000902L
        val deliveredTimestamp = 1710000000903L
        assertTrue(CallWakePolicy.publishOnce(deliveredTimestamp) { })
        assertFalse(CallWakePolicy.publishOnce(failedTimestamp) {
            throw IllegalStateException("simulated OEM binder failure")
        })
        assertFalse("Another call's committed wake must remain deduped",
            CallWakePolicy.publishOnce(deliveredTimestamp) { })
        assertTrue("Only the undelivered wake can retry",
            CallWakePolicy.publishOnce(failedTimestamp) { })
    }

    @Test fun failureBeforeNotificationDispatchNeverCrashesOrConsumesCallWake() {
        val timestamp = 1710000000911L

        // Some Android/OEM services throw before NotificationManager.notify,
        // e.g. during permission lookup or notification channel setup.
        assertFalse(CallWakePolicy.safePlatformDelivery {
            throw SecurityException("simulated channel permission denial")
        })
        assertFalse(CallWakePolicy.safePlatformDelivery {
            throw IllegalStateException("simulated OEM channel binder failure")
        })

        var attempted = 0
        assertTrue(CallWakePolicy.safePlatformDelivery {
            CallWakePolicy.publishOnce(timestamp) { attempted++ }
        })
        assertEquals(1, attempted)
        assertFalse(CallWakePolicy.safePlatformDelivery {
            CallWakePolicy.publishOnce(timestamp) { attempted++ }
        })
        assertEquals(1, attempted)
    }

    @Test fun ordinaryNotificationResultsPassThroughFailClosedBoundary() {
        assertTrue(CallWakePolicy.safePlatformDelivery { true })
        assertFalse(CallWakePolicy.safePlatformDelivery { false })
    }

    @Test fun identicalWakeCannotRepeatAndPrivateForegroundSuppressesNotification() {
        val timestamp = 1710000000700L
        assertTrue(CallWakePolicy.claim(timestamp))
        assertFalse(CallWakePolicy.claim(timestamp))
        CallWakePreferences.setEnabled(context, true)
        CallWakePolicy.privateAreaVisible = true
        assertFalse(CallWakeNotification.show(context, mapOf(
            "type" to CallWakePolicy.TYPE, "at" to timestamp.toString()), timestamp))
    }
    @Test fun revokingCallConsentWhileAndroidPostsRetractsOnlyCallAlert() {
        var enabled = true
        val effects = mutableListOf<String>()
        assertFalse(CallWakePolicy.publishWhileConsented(
            at = 1710000090001L,
            isConsented = { enabled },
            notify = { effects.add("posted"); enabled = false },
            cancel = { effects.add("cancelled") }
        ))
        assertEquals(listOf("posted", "cancelled"), effects)

        effects.clear()
        assertFalse(CallWakePolicy.publishWhileConsented(
            at = 1710000090002L,
            isConsented = { false },
            notify = { effects.add("posted") },
            cancel = { effects.add("cancelled") }
        ))
        assertTrue(effects.isEmpty())
    }

    @Test fun consentedCallAlertPostsOnceButNeverRepeatsSameFcmTimestamp() {
        var deliveries = 0
        assertTrue(CallWakePolicy.publishWhileConsented(
            at = 1710000090003L,
            isConsented = { true },
            notify = { deliveries++ },
            cancel = { error("Must not cancel enabled alert") }
        ))
        assertFalse(CallWakePolicy.publishWhileConsented(
            at = 1710000090003L,
            isConsented = { true },
            notify = { deliveries++ },
            cancel = { }
        ))
        assertEquals(1, deliveries)
    }

    @Test fun consentWithdrawnBeforeNotifyDoesNotConsumeTheWakeClaim() {
        val at = 1710000090081L
        var checks = 0
        var notifications = 0
        assertFalse(CallWakePolicy.publishWhileConsented(
            at = at,
            isConsented = { ++checks == 1 },
            notify = { notifications++ },
            cancel = { error("No notification was posted") }
        ))
        assertEquals(0, notifications)
        assertTrue("Fresh opt-in may retry an undelivered hint",
            CallWakePolicy.publishWhileConsented(
                at = at,
                isConsented = { true },
                notify = { notifications++ },
                cancel = { error("Consent is present") }
            ))
        assertEquals(1, notifications)
        assertFalse("Successful notification still suppresses duplicates",
            CallWakePolicy.publishWhileConsented(
                at = at,
                isConsented = { true },
                notify = { notifications++ },
                cancel = { }
            ))
        assertEquals(1, notifications)
    }

    @Test fun retractedAlertDoesNotBlockLaterValidWakeRetry() {
        val at = 1710000090082L
        var enabled = true
        var posts = 0
        var cancellations = 0
        assertFalse(CallWakePolicy.publishWhileConsented(
            at = at,
            isConsented = { enabled },
            notify = { posts++; enabled = false },
            cancel = { cancellations++ }
        ))
        assertEquals(1, posts)
        assertEquals(1, cancellations)
        enabled = true
        assertTrue(CallWakePolicy.publishWhileConsented(
            at = at,
            isConsented = { enabled },
            notify = { posts++ },
            cancel = { error("Must not cancel a valid retry") }
        ))
        assertEquals(2, posts)
        assertEquals(1, cancellations)
    }

    @Test fun localCallOptOutOnlyCancelsCallNotificationAndPreservesMessageAlert() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        CallWakeNotification.ensureChannel(manager)
        MessageWakeNotification.ensureChannel(manager)
        manager.notify(8129, NotificationCompat.Builder(context, CallWakeNotification.CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_lanu_monochrome)
            .setContentTitle("Gelen arama").build())
        manager.notify(8130, NotificationCompat.Builder(context, MessageWakeNotification.CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_lanu_monochrome)
            .setContentTitle("LANU Hesap Makinesi").build())
        assertTrue(manager.activeNotifications.any { it.id == 8129 })
        assertTrue(manager.activeNotifications.any { it.id == 8130 })
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        assertTrue(CallWakeNotification.cancel(context))
        assertFalse(manager.activeNotifications.any { it.id == 8129 })
        assertTrue("Message alerts are independently consented",
            manager.activeNotifications.any { it.id == 8130 })
        manager.cancel(8130)
    }


    @Test fun revokedOldWakeCannotCancelNewerConcurrentCallNotification() {
        val firstPosted = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        val secondPosted = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val older = executor.submit<Boolean> {
                assertTrue("Second FCM callback must be ready", secondReady.await(2, TimeUnit.SECONDS))
                var consentChecks = 0
                CallWakePolicy.publishWhileConsented(
                    at = 1710000090101L,
                    isConsented = { ++consentChecks <= 2 },
                    notify = {
                        events.add("older-post")
                        firstPosted.countDown()
                        // Without transaction serialization, the newer alert posts
                        // while this older notification is waiting to be revoked.
                        secondPosted.await(400, TimeUnit.MILLISECONDS)
                    },
                    cancel = { events.add("older-cancel") }
                )
            }
            val newer = executor.submit<Boolean> {
                secondReady.countDown()
                assertTrue(firstPosted.await(2, TimeUnit.SECONDS))
                CallWakePolicy.publishWhileConsented(
                    at = 1710000090102L,
                    isConsented = { true },
                    notify = {
                        events.add("newer-post")
                        secondPosted.countDown()
                    },
                    cancel = { error("A consented newer call must not be cancelled") }
                )
            }
            assertFalse(older.get(4, TimeUnit.SECONDS))
            assertTrue(newer.get(4, TimeUnit.SECONDS))
            assertEquals(
                "An older revoked callback must finish cancelling before the newer post",
                listOf("older-post", "older-cancel", "newer-post"),
                events.toList()
            )
            assertFalse("A successful newer call remains deduplicated",
                CallWakePolicy.publishWhileConsented(
                    at = 1710000090102L,
                    isConsented = { true },
                    notify = { error("Duplicate wake") },
                    cancel = { error("Duplicate wake") }
                ))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun localOptOutCancelCannotRunBeforeInFlightWakePostFinishes() {
        val enteredNotify = CountDownLatch(1)
        val cancellationAttempted = CountDownLatch(1)
        val cancellationFinished = CountDownLatch(1)
        val enabled = AtomicBoolean(true)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val posting = executor.submit<Boolean> {
                CallWakePolicy.publishWhileConsented(
                    at = 1710000090201L,
                    isConsented = { enabled.get() },
                    notify = {
                        events.add("post-start")
                        enteredNotify.countDown()
                        assertTrue(cancellationAttempted.await(2, TimeUnit.SECONDS))
                        assertFalse(
                            "Local cancellation must wait for the in-flight notification",
                            cancellationFinished.await(250, TimeUnit.MILLISECONDS)
                        )
                        events.add("post-finished")
                    },
                    cancel = { events.add("retract-revoked-post") }
                )
            }
            val optingOut = executor.submit<Boolean> {
                assertTrue(enteredNotify.await(2, TimeUnit.SECONDS))
                enabled.set(false)
                cancellationAttempted.countDown()
                val cancelled = CallWakePolicy.cancelPostedCallAlert {
                    events.add("local-cancel")
                    true
                }
                cancellationFinished.countDown()
                cancelled
            }
            assertFalse(posting.get(4, TimeUnit.SECONDS))
            assertTrue(optingOut.get(4, TimeUnit.SECONDS))
            assertEquals(
                listOf("post-start", "post-finished", "retract-revoked-post", "local-cancel"),
                events.toList()
            )
            assertTrue("An undelivered wake must remain retryable after new consent",
                CallWakePolicy.publishWhileConsented(
                    at = 1710000090201L,
                    isConsented = { true },
                    notify = { events.add("retry-post") },
                    cancel = { error("A valid retry cannot be retracted") }
                ))
        } finally {
            executor.shutdownNow()
        }
    }
}
