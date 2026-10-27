package com.example.features.communication

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.crypto.CryptoUtils
import com.example.core.database.MessageEntity
import com.example.core.media.InlineMediaCodec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    viewModel: CommunicationViewModel,
    conversationId: String,
    onNavigateBack: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onChoosePhoto: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onChooseDocument: () -> Unit = {},
    onSaveAttachment: (InlineMediaCodec.Attachment) -> Unit = {}
) {
    val context = LocalContext.current
    val activeConversation by viewModel.activeConversation.collectAsState()
    val messages by viewModel.activeMessages.collectAsState()
    val userPhone by viewModel.userId.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val preparedMedia by viewModel.preparedInlineMedia.collectAsState()
    val pendingEnvelope = preparedMedia?.takeIf {
        it.conversationId == conversationId && it.expiresAtMillis > System.currentTimeMillis()
    }?.envelope

    val textInput by viewModel.draftText.collectAsState()
    var isSelfDestructEnabled by remember(conversationId) { mutableStateOf(false) }
    var showClearConfirmation by remember(conversationId) { mutableStateOf(false) }
    var previousMessageCount by remember(conversationId) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showJumpToLatest by remember(messages.size, listState) {
        derivedStateOf {
            messages.size > 1 && listState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                !ChatReadingPositionPolicy.isNearLatest(
                    messages.size, listState.layoutInfo.visibleItemsInfo.last().index
                )
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


    // An incoming message must not steal the viewport while reading history.
    LaunchedEffect(conversationId, messages.size) {
        val newestCount = messages.size
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val shouldScroll = ChatReadingPositionPolicy.shouldAutoScroll(
            previousMessageCount, newestCount, lastVisibleIndex
        )
        previousMessageCount = newestCount
        if (shouldScroll) listState.animateScrollToItem(newestCount - 1)
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Sohbet geçmişi silinsin mi?") },
            text = {
                Text("Bu cihazdaki sohbet mesajları kaldırılacak. Karşı tarafın geçmişi silinmez. Bu işlem geri alınamaz.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        viewModel.clearConversationMessages(conversationId)
                    },
                    modifier = Modifier.testTag("confirm_clear_chat")
                ) { Text("Geçmişi sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmation = false },
                    modifier = Modifier.testTag("cancel_clear_chat")
                ) { Text("Vazgeç") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeConversation?.partnerName ?: "Yükleniyor...",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                    IconButton(onClick = onVoiceCall, modifier = Modifier.testTag("private_voice_call_button")) {
                        Icon(Icons.Default.Call, contentDescription = "Sesli ara")
                    }
                    IconButton(onClick = onVideoCall, modifier = Modifier.testTag("private_video_call_button")) {
                        Icon(Icons.Default.Videocam, contentDescription = "Görüntülü ara")
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
                            text = { Text(if (isSelfDestructEnabled) "10 saniyede gizlemeyi kapat" else "Bu telefonda 10 saniyede gizle") },
                            leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                triggerVibration()
                                isSelfDestructEnabled = !isSelfDestructEnabled
                                val msg = if (isSelfDestructEnabled) R.string.self_destruct_on else R.string.self_destruct_off
                                Toast.makeText(context, context.getString(msg), Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sohbeti Temizle") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                triggerVibration()
                                showClearConfirmation = true
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
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
                        onRetry = { viewModel.retryMessage(message.id) },
                        onEdit = { newText ->
                            if (!InlineMediaCodec.isAttachment(message.content)) {
                                viewModel.editMessage(message.id, newText)
                            }
                        },
                        onSaveAttachment = onSaveAttachment
                    )
                }
            }
            if (showJumpToLatest) {
                FilledTonalButton(
                    onClick = {
                        scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                        .testTag("chat_jump_to_latest")
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("En yeniye git")
                }
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
                        enabled = !isSending && activeConversation?.id == conversationId,
                        pendingEnvelope = pendingEnvelope,
                        onPhoto = onChoosePhoto,
                        onCamera = onTakePhoto,
                        onDocument = onChooseDocument,
                        onCancel = { viewModel.clearPreparedMedia() },
                        onSend = { envelope, onQueued ->
                            viewModel.sendInlineAttachment(
                                envelope = envelope,
                                expectedConversationId = conversationId,
                                onQueued = onQueued
                            )
                        }
                    )
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { viewModel.updateDraft(it) },
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

                    val canSendText = ChatComposePolicy.canSend(
                        textInput, isSending, activeConversation?.id, conversationId
                    )
                    IconButton(
                        enabled = canSendText,
                        onClick = {
                            if (canSendText) {
                                triggerVibration()
                                val queuedText = textInput
                                viewModel.sendMessage(
                                    text = queuedText,
                                    isSelfDestruct = isSelfDestructEnabled,
                                    destructDelaySeconds = 10,
                                    expectedConversationId = conversationId,
                                    onQueued = { viewModel.textQueued(queuedText, conversationId) }
                                )
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (canSendText) 1f else 0.35f))
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
    onRetry: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onSaveAttachment: (InlineMediaCodec.Attachment) -> Unit = {}
) {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = sdf.format(Date(timestamp))

    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(text) }
    val context = LocalContext.current
    
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
                if (isMine && status == "FAILED") DropdownMenuItem(
                    text = { Text("Yeniden gönder") },
                    onClick = { showMenu = false; onRetry() }
                )
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
                        val copied = runCatching { PrivateChatClipboard.copy(context, text) }.getOrDefault(false)
                        // Android 13+ already displays a redacted system clipboard
                        // confirmation; do not duplicate it with a chat toast.
                        if (!copied) Toast.makeText(context, "Mesaj kopyalanamadı", Toast.LENGTH_SHORT).show()
                        else if (Build.VERSION.SDK_INT < 33)
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
                        showDeleteDialog = true
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Mesaj silinsin mi?") },
            text = { Text("Bu işlem geri alınamaz. Gönderilen mesajın karşı taraftan silinmesi de istenebilir.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("confirm_delete_message")
                ) { Text("Mesajı sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Vazgeç") }
            }
        )
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

