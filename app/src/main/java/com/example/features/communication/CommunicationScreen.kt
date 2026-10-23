package com.example.features.communication

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.example.core.messaging.UserCodes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.database.ContactEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.CallLogEntity
import com.example.features.security.SecurityViewModel
import com.example.features.update.UpdateCheckScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunicationScreen(
    viewModel: CommunicationViewModel,
    securityViewModel: SecurityViewModel,
    onNavigateToChat: (String) -> Unit,
    onNavigateToCalculator: () -> Unit
) {
    val context = LocalContext.current
    val code by viewModel.userPhone.collectAsState()
    val name by viewModel.userName.collectAsState()
    val network by viewModel.networkState.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    // Silent UI policy for contacts, chats, and settings.
    val vibrate: () -> Unit = {}
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
        viewModel.startSync("home")
        onPauseOrDispose { viewModel.stopSync("home") }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Column {
                Text("LANU Güvenli Alan", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(when (network) {
                    CommunicationViewModel.NetworkState.ONLINE -> "Sunucu bağlantısı doğrulandı"
                    CommunicationViewModel.NetworkState.UNCONFIGURED -> "Mesajlaşma henüz etkin değil"
                    CommunicationViewModel.NetworkState.OFFLINE -> "Çevrimdışı · mesajlar bekliyor"
                    CommunicationViewModel.NetworkState.RECONNECTING -> "Bağlanıyor…"
                }, fontSize = 11.sp)
            } }, actions = {
                IconButton(onClick = { viewModel.refresh() }) { Icon(Icons.Default.Refresh, "Yenile") }
                IconButton(onClick = onNavigateToCalculator) { Icon(Icons.Default.Calculate, "Kilitle ve dön") }
            })
        },
        bottomBar = {
            NavigationBar {
                listOf("Sohbetler", "Kişiler", "Ayarlar", "Güncelleme").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(when (index) { 0 -> Icons.Default.ChatBubble; 1 -> Icons.Default.Contacts; 2 -> Icons.Default.Settings; else -> Icons.Default.SystemUpdateAlt }, label) },
                        label = { Text(label) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 1) FloatingActionButton(onClick = { showAdd = true }, modifier = Modifier.testTag("add_contact_fab")) { Icon(Icons.Default.Add, "Kodla kişi ekle") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (error != null) {
                Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error ?: "", modifier = Modifier.weight(1f), fontSize = 12.sp)
                        TextButton(onClick = { viewModel.clearError() }) { Text("Kapat") }
                    }
                }
            }
            if (code.isBlank() && tab != 3) {
                Text("Kimliğiniz hazırlanıyor. Açılamazsa yeniden deneyin.", Modifier.padding(16.dp))
                TextButton(onClick = { viewModel.checkRegistration() }) { Text("Yeniden dene") }
            } else {
                if (tab < 2) IdentityCodeCard(code, name)
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> ChatsTab(viewModel, vibrate, onNavigateToChat)
                        1 -> ContactsTab(viewModel, vibrate, onNavigateToChat)
                        2 -> SettingsTab(viewModel, securityViewModel, vibrate)
                        3 -> UpdateCheckScreen()
                    }
                }
            }
        }
    }
    if (showAdd) {
        var alias by remember { mutableStateOf("") }
        var otherCode by remember { mutableStateOf("") }
        var validation by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Kodla kişi ekle") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Karşı tarafın LANU kodunu kaydedin. Yazışmak için onun da sizin kodunuzu kaydetmesi gerekir.")
                OutlinedTextField(alias, { alias = it.take(60) }, label = { Text("Kişiye vereceğiniz ad") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("contact_name_input"))
                OutlinedTextField(otherCode, { otherCode = it.take(40) }, label = { Text("LANU kullanıcı kodu") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("contact_code_input"))
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button(onClick = {
            val normalized = UserCodes.normalize(otherCode)
            when {
                normalized.isBlank() -> validation = "LANU-XXXX-XXXX-XXXX-XXXX-XXXX biçiminde bir kod girin."
                normalized == code -> validation = "Kendi kodunuzu ekleyemezsiniz."
                else -> { viewModel.addContact(alias, normalized); showAdd = false }
            }
        }, modifier = Modifier.testTag("contact_save_button")) { Text("Kişiyi kaydet") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Vazgeç") } })
    }
}

@Composable
private fun IdentityCodeCard(code: String, name: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "LANU Kullanıcısı" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text("Kişisel iletişim kodunuz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = code.ifBlank { "Kod hazırlanıyor…" },
                modifier = Modifier.fillMaxWidth().testTag("my_user_code"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = code.isNotBlank(),
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Kopyala")
                }
                OutlinedButton(
                    enabled = code.isNotBlank(),
                    onClick = {
                        try {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, code)
                            }, "LANU kodunu paylaş"))
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(
                                context, "Paylaşım ekranı açılamadı.", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paylaş")
                }
            }
        }
    }
}

@Composable
fun ChatsTab(
    viewModel: CommunicationViewModel,
    triggerVibration: () -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    val conversations by viewModel.conversations.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) {
            conversations
        } else {
            conversations.filter {
                it.partnerName.contains(searchQuery, ignoreCase = true) ||
                it.lastMessageText.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Chat Search Field
        if (conversations.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Sohbet veya mesaj ara...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Ara", modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Temizle", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )
            )
        }

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = stringResource(R.string.no_chats_desc),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_chats_active),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.navigate_contacts_hint),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (filteredConversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\"$searchQuery\" ile eşleşen sohbet bulunamadı",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredConversations) { conv ->
                    ChatCard(
                        conversation = conv,
                        triggerVibration = triggerVibration,
                        onClick = { onNavigateToChat(conv.id) },
                        onLongClick = { conversationToDelete = conv }
                    )
                }
            }
        }
    }

    if (conversationToDelete != null) {
        val target = conversationToDelete!!
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            title = { Text("Sohbeti Sil") },
            text = { Text("${target.partnerName} ile olan sohbeti ve tüm şifreli mesaj geçmişini silmek istiyor musunuz?") },
            confirmButton = {
                TextButton(onClick = {
                    triggerVibration()
                    viewModel.deleteConversation(target.id)
                    conversationToDelete = null
                }) {
                    Text("Sil", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text("Vazgeç")
                }
            }
        )
    }
}

@Composable
fun ChatCard(
    conversation: ConversationEntity,
    triggerVibration: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(conversation.lastMessageTime))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                triggerVibration()
                onClick()
            }
            .testTag("chat_card_${conversation.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile Initials Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = conversation.partnerName.take(2).uppercase(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1.0f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.partnerName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = dateStr,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessageText.ifBlank { stringResource(R.string.encryption_initialized) },
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )

                    if (conversation.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${conversation.unreadCount}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = {
                    triggerVibration()
                    onLongClick()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Sohbeti Sil",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ContactsTab(
    viewModel: CommunicationViewModel,
    triggerVibration: () -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    val contactList by viewModel.contacts.collectAsState(initial = emptyList())

    if (contactList.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_contacts_bound),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(contactList) { contact ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            triggerVibration()
                            viewModel.startChat(contact.phoneNumber, contact.name) { id ->
                                onNavigateToChat(id)
                            }
                        }
                        .testTag("contact_card_${contact.phoneNumber}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Contact Avatar Badge
                        Box(
                            modifier = Modifier
                                        .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = contact.name.take(2).uppercase(),
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = contact.phoneNumber + "\n" + (contact.statusText ?: ""),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }

                        IconButton(
                            onClick = {
                                triggerVibration()
                                viewModel.deleteContact(contact.phoneNumber)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete_contact),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(viewModel: CommunicationViewModel, securityViewModel: SecurityViewModel, triggerVibration: () -> Unit) {
    val code by viewModel.userPhone.collectAsState()
    val name by viewModel.userName.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showRotate by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    var backupAction by remember { mutableStateOf<String?>(null) }
    var filePassword by remember { mutableStateOf("") }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val password = filePassword; filePassword = ""
        if (uri != null) viewModel.exportIdentity(password) { raw ->
            withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Yedek dosyası açılamadı")
                stream.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = filePassword; filePassword = ""
        if (uri != null) viewModel.restoreIdentity(password) {
            withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri) ?: error("Yedek dosyası açılamadı")
                stream.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 32768) { "Yedek dosyası çok büyük" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IdentityCodeCard(code, name)
        Text("Kodunuz uygulama yeniden açıldığında değişmez. Yalnızca siz yeni kod oluşturmayı seçerseniz değiştirilir.", fontSize = 12.sp)
        OutlinedButton(onClick = { showName = true }, modifier = Modifier.fillMaxWidth()) { Text("Görünen adı düzenle") }
        OutlinedButton(onClick = { showRotate = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("change_user_code")) { Text("Kullanıcı kodumu değiştir") }
        Text("Uygulamayı silmeden veya cihaz değiştirmeden önce kimliğini yedekle. Parolalı yedeği geri yükleyerek aynı kodu kullanabilirsin.", fontSize = 12.sp)
        OutlinedButton(onClick = { backupAction = "export" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Kimliğimi yedekle") }
        OutlinedButton(onClick = { backupAction = "restore" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Kimlik yedeğini geri yükle") }
        com.example.features.security.SettingsScreen(securityViewModel, triggerVibration)
    }
    if (showRotate) AlertDialog(onDismissRequest = { showRotate = false }, title = { Text("Yeni kullanıcı kodu oluşturulsun mu?") },
        text = { Text("Yeni kişiler sizi yeni kodunuzla ekleyecek. Kimliğiniz, kayıtlı kişileriniz ve mesaj geçmişiniz korunur. Bu işlem yalnızca sizin isteğinizle yapılır.") },
        confirmButton = { Button(onClick = { showRotate = false; viewModel.rotateCode() }, enabled = !busy) { Text("Yeni kod oluştur") } },
        dismissButton = { TextButton(onClick = { showRotate = false }) { Text("Vazgeç") } })
    if (backupAction != null) {
        var password by remember { mutableStateOf("") }
        val exporting = backupAction == "export"
        AlertDialog(onDismissRequest = { backupAction = null }, title = { Text(if (exporting) "Kimliğini parola ile yedekle" else "Kimlik yedeğini aç") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("En az 12 karakterli bir parola kullan. Parolan olmadan yedeğin açılamaz; kullanıcı kodun bu parolanın yerine geçmez.")
                OutlinedTextField(password, { password = it.take(256) }, label = { Text("Yedek parolası") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            } },
            confirmButton = { Button(onClick = {
                filePassword = password; backupAction = null
                if (exporting) exportLauncher.launch("LANU-kimlik-yedegi.lanu.json") else restoreLauncher.launch(arrayOf("*/*"))
            }, enabled = password.length >= 12) { Text(if (exporting) "Dosyaya kaydet" else "Yedek dosyasını seç") } },
            dismissButton = { TextButton(onClick = { backupAction = null }) { Text("Vazgeç") } })
    }
    if (showName) {
        var input by remember { mutableStateOf(name) }
        AlertDialog(onDismissRequest = { showName = false }, title = { Text("Görünen ad") },
            text = { OutlinedTextField(input, { input = it.take(60) }, singleLine = true) },
            confirmButton = { Button(onClick = { viewModel.updateDisplayName(input); showName = false }, enabled = input.isNotBlank()) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { showName = false }) { Text("Vazgeç") } })
    }
}

@Composable
fun CallLogsTab(
    viewModel: CommunicationViewModel,
    triggerVibration: () -> Unit
) {
    val callLogs by viewModel.callLogs.collectAsState(initial = emptyList())

    if (callLogs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Henüz arama kaydı bulunmuyor",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Arama Geçmişi",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                TextButton(onClick = {
                    triggerVibration()
                    viewModel.clearAllCallLogs()
                }) {
                    Text("Tümünü Temizle", color = MaterialTheme.colorScheme.error)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(callLogs, key = { it.id }) { log ->
                    CallLogCard(log, triggerVibration) {
                        viewModel.deleteCallLog(log.id)
                    }
                }
            }
        }
    }
}

@Composable
fun CallLogCard(
    log: CallLogEntity,
    triggerVibration: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("tr", "TR"))
    val dateStr = sdf.format(Date(log.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (log.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = log.partnerName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${if (log.isOutgoing) "Giden" else "Gelen"} ${if (log.isVideo) "Görüntülü Arama" else "Sesli Arama"} • ${log.durationSeconds} sn",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = dateStr,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = {
                triggerVibration()
                onDelete()
            }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Arama Kaydını Sil",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}
