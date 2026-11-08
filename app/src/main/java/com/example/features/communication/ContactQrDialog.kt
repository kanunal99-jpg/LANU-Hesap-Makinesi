package com.example.features.communication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun ContactQrDialog(code: String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val qr = remember(code) { runCatching {
        val matrix = QRCodeWriter().encode(ContactInvitation.encode(code), BarcodeFormat.QR_CODE, 384, 384)
        Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(384 * 384) { i -> if (matrix[i % 384, i / 384]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 384, 0, 0, 384, 384)
        }
    }.getOrNull() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            require(out.size() + n <= 8 * 1024 * 1024)
                            out.write(buffer, 0, n)
                        }; out.toByteArray()
                    } ?: error("Image unavailable")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                    val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
                    try {
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        ContactInvitation.decode(MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text)
                    } finally { image.recycle() }
                }
                onImport(imported)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "QR okunamadı. Net bir kişi QR görseli seçin veya kodu elle girin." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Kişi QR kodu") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                qr?.let { Image(it.asImageBitmap(), "Kişisel kişi kodu", Modifier.fillMaxWidth().aspectRatio(1f)) }
                Text("Diğer telefondaki kodu QR görselinden içe aktarın. Kişi kaydı onayınızdan sonra yapılır.")
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("QR görseli seç") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } })
}
