package com.example.features.communication

import android.graphics.BitmapFactory
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.media.InlineMediaCodec

/**
 * UI only. The parent FragmentActivity owns ActivityResultRegistry launchers,
 * so results aren't lost when the private chat locks while a system picker is open.
 * The PIN-gated ViewModel retains a short-lived draft in process memory.
 */
@Composable
fun InlineMediaComposer(
    enabled: Boolean,
    pendingEnvelope: String?,
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onDocument: () -> Unit,
    onCancel: () -> Unit,
    onSend: (String, () -> Unit) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            enabled = enabled,
            modifier = Modifier.testTag("inline_media_add"),
            onClick = { menu = true }
        ) { Icon(Icons.Default.Add, contentDescription = "Fotoğraf veya belge ekle") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Galeriden küçük fotoğraf") },
                leadingIcon = { Icon(Icons.Default.ImageIcon, contentDescription = null) },
                onClick = { menu = false; onPhoto() }
            )
            DropdownMenuItem(
                text = { Text("Kamera (küçük fotoğraf)") },
                leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                onClick = { menu = false; onCamera() }
            )
            DropdownMenuItem(
                text = { Text("Küçük PDF / TXT (18 KB)") },
                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                onClick = { menu = false; onDocument() }
            )
        }
    }

    val prepared = remember(pendingEnvelope) {
        pendingEnvelope?.let { runCatching { InlineMediaCodec.decode(it) }.getOrNull() }
    }
    if (pendingEnvelope != null && prepared != null) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Şifreli ek gönder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prepared.name)
                    Text("En fazla 18 KB. Alıcının da yeni LANU medya sürümüne güncellenmiş olması gerekir. Eski sürümler dosyayı açamayabilir.")
                    if (prepared.isImage) InlineMediaImage(prepared, modifier = Modifier
                        .fillMaxWidth().heightIn(max = 200.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = enabled,
                    modifier = Modifier.testTag("inline_media_send"),
                    onClick = {
                        onSend(pendingEnvelope, onCancel)
                    }
                ) { Text("Şifreli gönder") }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Vazgeç") }
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
