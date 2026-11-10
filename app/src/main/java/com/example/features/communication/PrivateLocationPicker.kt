package com.example.features.communication

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.example.core.location.PrivateLocationCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Current-location-only provider. No subscriptions, background tracking, cache
 * or Google/Firebase location request. Reject stale/inaccurate provider fixes.
 * User explicitly chooses the precision and confirms the send separately.
 */
internal object PrivateLocationCapture {
    private const val MAX_FIX_AGE_MS = 45_000L

    suspend fun take(context: Context, precise: Boolean): PrivateLocationCodec.Point? {
        val fine = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse || (precise && !fine)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = if (precise)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf(LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val available = try { manager.isProviderEnabled(provider) }
                catch (_: RuntimeException) { false }
            if (!available) continue
            val location = try {
                withTimeoutOrNull(if (provider == LocationManager.GPS_PROVIDER) 8_000L else 6_000L) {
                    oneShot(context, manager, provider)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            } ?: continue
            if (!location.hasAccuracy() || location.accuracy <= 0f) continue
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() -
                location.elapsedRealtimeNanos) / 1_000_000L
            if (elapsedMs < 0 || elapsedMs > MAX_FIX_AGE_MS) continue
            if (abs(System.currentTimeMillis() - location.time) > 120_000L) continue
            return runCatching {
                PrivateLocationCodec.fromDevice(location.latitude, location.longitude,
                    location.accuracy, location.time, !precise)
            }.getOrNull()
        }
        return null
    }

    // Permission is checked immediately in take(); platform access still
    // catches permission revocation during the asynchronous request.
    @SuppressLint("MissingPermission")
    private suspend fun oneShot(
        context: Context, manager: LocationManager, provider: String
    ): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(
                manager, provider, cancellation,
                ContextCompat.getMainExecutor(context)
            ) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        } catch (e: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        } catch (e: RuntimeException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

/** PIN-screen scoped. Permission return is discarded after navigating/locking. */
@Composable
fun PrivateLocationPicker(
    visible: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fix by remember { mutableStateOf<PrivateLocationCodec.Point?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requestedPrecise by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    val latestVisible by rememberUpdatedState(visible)
    val latestEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(visible, enabled) {
        if (!visible || !enabled) {
            captureJob?.cancel()
            captureJob = null
            loading = false
            fix = null
            error = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { captureJob?.cancel() }
    }

    val capture: (Boolean) -> Unit = { precise ->
        captureJob?.cancel()
        fix = null
        error = null
        loading = true
        captureJob = scope.launch {
            val result = try {
                withContext(Dispatchers.Main.immediate) {
                    PrivateLocationCapture.take(context.applicationContext, precise)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (latestVisible && latestEnabled) {
                fix = result
                loading = false
                if (result == null) error =
                    "Güncel konum alınamadı. Telefonun konum servisini açıp tekrar deneyin."
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (latestVisible && latestEnabled) {
            val coarse = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val fine = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (coarse && (!requestedPrecise || fine)) capture(requestedPrecise)
            else error = "Konum izni verilmedi. Yaklaşık veya hassas konum iznini açın."
        }
    }

    val request: (Boolean) -> Unit = { precise ->
        requestedPrecise = precise
        val coarse = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (coarse && (!precise || fine)) capture(precise)
        else permissions.launch(
            if (precise) arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    if (!visible || !enabled) return
    AlertDialog(
        onDismissRequest = { captureJob?.cancel(); onDismiss() },
        title = { Text("Şifreli konum gönder") },
        text = {
            Column {
                Text("Yalnızca bu sohbetin doğrulanmış alıcısına tek bir konum noktası gönderilir. Canlı takip başlatılmaz.")
                Spacer(Modifier.height(12.dp))
                if (fix == null && !loading) {
                    OutlinedButton(
                        onClick = { request(false) },
                        modifier = Modifier.fillMaxWidth().testTag("location_approximate")
                    ) { Text("Yaklaşık konum (koordinatlar yuvarlanır)") }
                    OutlinedButton(
                        onClick = { request(true) },
                        modifier = Modifier.fillMaxWidth().testTag("location_precise")
                    ) { Text("Hassas konum (GPS)") }
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.testTag("location_loading"))
                    Text("Konum alınıyor…", style = MaterialTheme.typography.bodySmall)
                }
                fix?.let {
                    Text(if (it.approximate)
                        "Yaklaşık konum hazır · kesin nokta değil, koordinatlar yuvarlandı"
                        else "Konum hazır · doğruluk yaklaşık ${it.accuracyMeters} m")
                    Text("Gönderilen konum alıcı tarafından kaydedilebilir. Gönderdikten sonra geri alınamaz.",
                        style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("location_error"))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && fix != null && enabled,
                modifier = Modifier.testTag("location_secure_send"),
                onClick = {
                    val snapshot = fix ?: return@TextButton
                    if (latestVisible && latestEnabled) {
                        onSend(PrivateLocationCodec.encode(snapshot))
                        captureJob?.cancel()
                        onDismiss()
                    }
                }
            ) { Text("Şifreli gönder") }
        },
        dismissButton = {
            TextButton(onClick = { captureJob?.cancel(); onDismiss() }) { Text("Vazgeç") }
        }
    )
}
