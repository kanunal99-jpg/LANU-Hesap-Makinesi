package com.example.features.communication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.media.InlineMediaCodec
import com.example.core.media.InlineMediaPreparer
import kotlinx.coroutines.launch

/**
 * Private gallery/camera/system-document intents. No READ_MEDIA_* permission.
 * The confirmation previews the actual stripped/compressed payload that will
 * be encrypted, not the possibly metadata-bearing original.
 */
@Composable
fun InlineMediaComposer(
    enabled: Boolean,
    onSend: (String, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var pendingEnvelope by remember { mutableStateOf<String?>(null) }

    fun prepare(operation: suspend () -> String) {
        if (processing || !enabled) return
        processing = true
        scope.launch {
            try {
                pendingEnvelope = operation()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Dosya hazırlanamadı.", Toast.LENGTH_LONG).show()
            } finally {
                processing = false
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) prepare { InlineMediaPreparer.imageFromPicker(context, uri) }
    }
    val cameraPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) prepare { InlineMediaPreparer.cameraPreview(bitmap) }
    }
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) prepare { InlineMediaPreparer.documentFromPicker(context, uri) }
    }

    Box {
        IconButton(
            enabled = enabled && !processing,
            modifier = Modifier.testTag("inline_media_add"),
            onClick = { menu = true }
        ) { Icon(Icons.Default.Add, contentDescription = "Fotoğraf veya belge ekle") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Galeriden küçük fotoğraf") },
                leadingIcon = { Icon(Icons.Default.ImageIcon, contentDescription = null) },
                onClick = {
                    menu = false
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
            DropdownMenuItem(
                text = { Text("Kamera (küçük fotoğraf)") },
                leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                onClick = {
                    menu = false
                    try { cameraPicker.launch(null) }
                    catch (_: Exception) { Toast.makeText(context, "Kamera açılamadı.", Toast.LENGTH_SHORT).show() }
                }
            )
            DropdownMenuItem(
                text = { Text("Küçük PDF / TXT (18 KB)") },
                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                onClick = {
                    menu = false
                    documentPicker.launch(arrayOf("application/pdf", "text/plain"))
                }
            )
        }
    }

    if (processing) CircularProgressIndicator(modifier = Modifier.size(20.dp))

    val prepared = remember(pendingEnvelope) {
        pendingEnvelope?.let { runCatching { InlineMediaCodec.decode(it) }.getOrNull() }
    }
    if (pendingEnvelope != null && prepared != null) {
        AlertDialog(
            onDismissRequest = { pendingEnvelope = null },
            title = { Text("Şifreli ek gönder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prepared.name)
                    Text("Bu ilk güvenli sürüm en fazla 18 KB fotoğraf/belge gönderebilir. Büyük video ve belgeler henüz desteklenmiyor.")
                    if (prepared.isImage) InlineMediaImage(prepared, modifier = Modifier
                        .fillMaxWidth().heightIn(max = 200.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = enabled && !processing,
                    modifier = Modifier.testTag("inline_media_send"),
                    onClick = {
                        val envelope = pendingEnvelope ?: return@TextButton
                        onSend(envelope) { pendingEnvelope = null }
                    }
                ) { Text("Şifreli gönder") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEnvelope = null }) { Text("Vazgeç") }
            }
        )
    }
}

/** Image preview is decoded only inside bounded pixel dimensions. */
@Composable
fun InlineMediaImage(attachment: InlineMediaCodec.Attachment, modifier: Modifier = Modifier) {
    val thumbnail = remember(attachment.bytes) {
        runCatching {
            val bytes = attachment.bytes
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth in 1..1600 && options.outHeight in 1..1600 &&
                options.outWidth.toLong() * options.outHeight <= 1_500_000L)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail,
            contentDescription = "Şifreli fotoğraf önizlemesi",
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    } else {
        Text("Fotoğraf önizlemesi güvenle açılamadı.")
    }
}

/** Saving a verified attachment is exclusively an explicit Android document action. */
@Composable
fun InlineMediaAttachment(
    payload: String,
    onSave: (InlineMediaCodec.Attachment) -> Unit
) {
    val attachment = remember(payload) { runCatching { InlineMediaCodec.decode(payload) }.getOrNull() }
    if (attachment == null) {
        Text("Ek dosya bütünlüğü doğrulanamadı.", color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (attachment.isImage) "📷 Küçük fotoğraf" else "📎 " + attachment.name)
        if (attachment.isImage) InlineMediaImage(
            attachment, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp))
        Text((attachment.bytes.size / 1024 + 1).toString() + " KB · E2EE", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { onSave(attachment) }, modifier = Modifier.testTag("inline_media_save")) {
            Text("Cihaza kaydet")
        }
    }
}
