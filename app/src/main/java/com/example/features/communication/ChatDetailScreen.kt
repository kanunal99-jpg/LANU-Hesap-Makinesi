package com.example.features.communication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.crypto.CryptoUtils
import com.example.core.database.MessageEntity
import com.example.core.media.InlineMediaCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    viewModel: CommunicationViewModel,
    conversationId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activeConversation by viewModel.activeConversation.collectAsState()
    val messages by viewModel.activeMessages.collectAsState()
    val userPhone by viewModel.userId.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isSending by viewModel.isSending.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var isSelfDestructEnabled by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val mediaScope = rememberCoroutineScope()
    var attachmentToSave by remember { mutableStateOf<InlineMediaCodec.Attachment?>(null) }
    var savingAttachment by remember { mutableStateOf(false) }
    val saveAttachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { response ->
        val attachment = attachmentToSave
        attachmentToSave = null
        val target = response.data?.data
        if (response.resultCode == Activity.RESULT_OK && target != null && attachment != null) {
            if (!savingAttachment) {
                savingAttachment = true
                mediaScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(target, "w")?.use { it.write(attachment.bytes) }
                                ?: throw IllegalArgumentException("Dosya kaydedilemedi.")
                        }
                        Toast.makeText(context, "Dosya kaydedildi.", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(context, "Dosya kaydedilemedi.", Toast.LENGTH_LONG).show()
                    } finally { savingAttachment = false }
                }
            }
        }
    }

    // Silent UI policy: no sound or haptic feedback for chat actions.
    val triggerVibration: () -> Unit = {}

    androidx.lifecycle.compose.LifecycleResumeEffect(conversationId) {
        viewModel.selectConversation(conversationId)
        viewModel.startSync("chat:$conversationId")
        onPauseOrDispose {
            viewModel.leaveConversation(conversationId)
            viewModel.stopSync("chat:$conversationId")
        }
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.clearError() }
    }


    // Auto scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (activeConversation?.partnerName ?: "Chat").take(2).uppercase(),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = activeConversation?.partnerName ?: "Yükleniyor...",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.e2ee_secured),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Uçtan Uca Şifreli (AES-GCM)",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        triggerVibration()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            isSelfDestructEnabled = !isSelfDestructEnabled
                            val msg = if (isSelfDestructEnabled) R.string.self_destruct_on else R.string.self_destruct_off
                            Toast.makeText(context, context.getString(msg), Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = if (isSelfDestructEnabled) Icons.Default.Timer else Icons.Default.TimerOff,
                            contentDescription = "Yalnız bu telefondaki mesajı 10 saniye sonra gizle",
                            tint = if (isSelfDestructEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Seçenekler")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sohbeti Temizle") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                triggerVibration()
                                viewModel.clearConversationMessages(conversationId)
                                Toast.makeText(context, "Sohbet temizlendi", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Self destruct banner if enabled
            if (isSelfDestructEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Yalnız gönderimi onaylanan mesaj 10 sn sonra bu cihazda gizlenir. Sunucudan veya karşı taraftan silinmez.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Decrypted E2EE Message Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(messages) { index, message ->
                    val showDateHeader = index == 0 || !isSameDay(messages[index - 1].timestamp, message.timestamp)
                    if (showDateHeader) {
                        DateHeader(formatDateHeader(message.timestamp))
                    }

                    val isMine = message.senderPhone == userPhone
                    // Real-time client-side decryption using the secure utility
                    val decryptedText = message.content
                    
                    MessageBubble(
                        text = decryptedText,
                        mediaUrl = message.mediaUrl,
                        timestamp = message.timestamp,
                        isMine = isMine,
                        status = message.status,
                        reaction = message.reaction,
                        onAddReaction = { emoji ->
                            viewModel.addReaction(message.id, emoji)
                        },
                        onDelete = {
                            viewModel.deleteMessage(message.id)
                        },
                        onEdit = { newText ->
                            if (!InlineMediaCodec.isAttachment(message.content)) {
                                viewModel.editMessage(message.id, newText)
                            }
                        },
                        onSaveAttachment = { attachment ->
                            if (!savingAttachment) {
                                attachmentToSave = attachment
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType(attachment.mime)
                                    .putExtra(Intent.EXTRA_TITLE, attachment.name)
                                try { saveAttachmentLauncher.launch(intent) }
                                catch (_: Exception) {
                                    attachmentToSave = null
                                    Toast.makeText(context, "Dosya kaydetme ekranı açılamadı.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }

            }

            // Input Control Panel
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InlineMediaComposer(
                        enabled = !isSending,
                        onSend = { envelope, onQueued ->
                            viewModel.sendInlineAttachment(envelope, onQueued)
                        }
                    )
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Şifreli mesaj yazın...") },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1.0f)
                            .testTag("msg_input_field"),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank() && !isSending) {
                                triggerVibration()
                                val queuedText = textInput
                                viewModel.sendMessage(
                                    text = queuedText,
                                    isSelfDestruct = isSelfDestructEnabled,
                                    destructDelaySeconds = 10,
                                    onQueued = { if (textInput == queuedText) textInput = "" }
                                )
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("msg_send_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = stringResource(R.string.send_secure_message),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DateHeader(dateText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = dateText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

private fun isSameDay(t1: Long, t2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun formatDateHeader(timestamp: Long): String {
    val messageCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val nowCal = Calendar.getInstance()
    return when {
        nowCal.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == messageCal.get(Calendar.DAY_OF_YEAR) -> "Bugün"
        nowCal.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) - messageCal.get(Calendar.DAY_OF_YEAR) == 1 -> "Dün"
        else -> SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
fun MessageBubble(
    text: String,
    mediaUrl: String? = null,
    timestamp: Long,
    isMine: Boolean,
    status: String,
    reaction: String? = null,
    onAddReaction: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onSaveAttachment: (InlineMediaCodec.Attachment) -> Unit = {}
) {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = sdf.format(Date(timestamp))

    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(text) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    val bubbleShape = if (isMine) {
        RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    }

    val bubbleBg = if (isMine) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    val bubbleBorder = if (isMine) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    if (text.startsWith("📞 ")) {
        // System message for calls
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            // Long-press menu remains available without vibration.
                            showMenu = true
                        }
                    )
                }
                .clip(bubbleShape)
                .background(bubbleBg)
                .border(1.dp, bubbleBorder, bubbleShape)
                .padding(12.dp)
        ) {
            // Inline attachments are already E2EE-decrypted here, but their
            // checksums and MIME are verified again before display/export.
            // Never render their base64 JSON as ordinary text.
            if (InlineMediaCodec.isAttachment(text)) {
                InlineMediaAttachment(payload = text, onSave = onSaveAttachment)
            } else {
                // Legacy media previews remain read-only; new media never uses public URLs.
                if (!mediaUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = mediaUrl,
                        contentDescription = "Ek Medya",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                if (text.isNotBlank() && (mediaUrl.isNullOrBlank() || text != "📷 Fotoğraf")) {
                    Text(
                        text = text,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeStr,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
                if (isMine) {
                    Spacer(modifier = Modifier.width(4.dp))
                    ChatMessageStatus(status = status)
                }
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val emojis = listOf("❤️", "👍", "😂", "😮", "😢")
                    emojis.forEach { emoji ->
                        IconButton(onClick = {
                            onAddReaction(emoji)
                            showMenu = false
                        }) {
                            Text(text = emoji, fontSize = 24.sp)
                        }
                    }
                }
                HorizontalDivider()
                if (!InlineMediaCodec.isAttachment(text)) DropdownMenuItem(
                    text = { Text("Metni Kopyala") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        showMenu = false
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Mesaj panoya kopyalandı", Toast.LENGTH_SHORT).show()
                    }
                )
                if (isMine && !InlineMediaCodec.isAttachment(text)) {
                    DropdownMenuItem(
                        text = { Text("Mesajı Düzenle") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showMenu = false
                            showEditDialog = true
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Mesajı Sil", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
        
        // Display reaction if present
        if (reaction != null) {
            Box(
                modifier = Modifier
                    .align(if (isMine) Alignment.BottomStart else Alignment.BottomEnd)
                    .offset(x = if (isMine) (-12).dp else 12.dp, y = 12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .padding(4.dp)
            ) {
                Text(text = reaction, fontSize = 14.sp)
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Mesajı Düzenle") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editText.isNotBlank()) {
                        onEdit(editText)
                        showEditDialog = false
                    }
                }) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("İptal")
                }
            }
        )
    }
}
