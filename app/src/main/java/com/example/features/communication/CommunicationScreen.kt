package com.example.features.communication

import android.content.Context
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
    val isRegistered by viewModel.isRegistered.collectAsState()
    val userPhone by viewModel.userPhone.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val networkState by viewModel.networkState.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showRegisterDialog by remember { mutableStateOf(!isRegistered) }

    // Synchronize local register dialog trigger
    LaunchedEffect(isRegistered) {
        showRegisterDialog = !isRegistered
    }

    val triggerVibration = {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "LANU Güvenli Alan",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (networkState) {
                                            CommunicationViewModel.NetworkState.ONLINE -> MaterialTheme.colorScheme.primary
                                            CommunicationViewModel.NetworkState.OFFLINE -> MaterialTheme.colorScheme.error
                                            CommunicationViewModel.NetworkState.RECONNECTING -> Color.Yellow
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (networkState) {
                                    CommunicationViewModel.NetworkState.ONLINE -> "Güvenli Bağlantı Aktif"
                                    CommunicationViewModel.NetworkState.OFFLINE -> "Bağlantı Kesildi"
                                    CommunicationViewModel.NetworkState.RECONNECTING -> "Yeniden Bağlanıyor..."
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            onNavigateToCalculator()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Calculate,
                            contentDescription = stringResource(R.string.back_to_calculator)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = stringResource(R.string.chats_tab)) },
                    label = { Text(stringResource(R.string.chats_tab)) }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Contacts, contentDescription = stringResource(R.string.contacts_tab)) },
                    label = { Text(stringResource(R.string.contacts_tab)) }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_tab)) },
                    label = { Text(stringResource(R.string.settings_tab)) }
                )
                NavigationBarItem(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    icon = { Icon(Icons.Default.Phone, contentDescription = "Aramalar") },
                    label = { Text("Aramalar") }
                )
            }
        },
        floatingActionButton = {
            if (activeTab == 1) {
                FloatingActionButton(
                    onClick = {
                        triggerVibration()
                        showAddContactDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_contact_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_contact))
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (activeTab) {
                0 -> ChatsTab(viewModel, triggerVibration, onNavigateToChat)
                1 -> ContactsTab(viewModel, triggerVibration, onNavigateToChat)
                2 -> SettingsTab(viewModel, securityViewModel, triggerVibration)
                3 -> CallLogsTab(viewModel, triggerVibration)
            }
        }
    }

    // Secure E.164 Phone Registration Overlay Dialog
    if (showRegisterDialog) {
        var inputPhone by remember { mutableStateOf("") }
        var inputName by remember { mutableStateOf("") }
        var verificationStep by remember { mutableStateOf(false) }
        var otpCode by remember { mutableStateOf("") }
        var validationError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = if (!verificationStep) "Güvenli Kimlik Kaydı" else "Mobil OTP Doğrulama",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (!verificationStep) 
                            "İstemci tarafı güvenli kasa tanımlayıcınızla bağlamak için telefon numaranızı girin."
                            else "Doğrulama kodu $inputPhone adresine gönderildi (Simülasyon Kodu: 1989)",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    if (!verificationStep) {
                        OutlinedTextField(
                            value = inputName,
                            onValueChange = { inputName = it },
                            label = { Text("Görünen Adınız") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("reg_name_input")
                        )

                        OutlinedTextField(
                            value = inputPhone,
                            onValueChange = { inputPhone = it },
                            label = { Text("E.164 Formatında Telefon (Örn: +905001234567)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("reg_phone_input")
                        )
                    } else {
                        OutlinedTextField(
                            value = otpCode,
                            onValueChange = { otpCode = it },
                            label = { Text("OTP Kodunu Girin") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("reg_otp_input")
                        )
                    }

                    if (validationError != null) {
                        Text(
                            text = validationError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        triggerVibration()
                        validationError = null
                        if (!verificationStep) {
                            if (inputName.isBlank() || inputPhone.isBlank()) {
                                validationError = "Tüm alanların doldurulması zorunludur"
                            } else if (!inputPhone.startsWith("+")) {
                                validationError = "Telefon E.164 formatında olmalıdır (+ ile başlamalıdır)"
                            } else {
                                verificationStep = true
                            }
                        } else {
                            if (otpCode == "1989" || otpCode == "2026") {
                                viewModel.register(inputPhone, inputName) {
                                    showRegisterDialog = false
                                }
                            } else {
                                validationError = "Geçersiz doğrulama kodu! (1989 deneyin)"
                            }
                        }
                    },
                    modifier = Modifier.testTag("reg_submit_button")
                ) {
                    Text(if (!verificationStep) "OTP Kodu İste" else "Kodu Doğrula")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        triggerVibration()
                        onNavigateToCalculator()
                    }
                ) {
                    Text("İptal")
                }
            }
        )
    }

    // Add Contact Dialog
    if (showAddContactDialog) {
        var contactName by remember { mutableStateOf("") }
        var contactPhone by remember { mutableStateOf("") }
        var contactError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text("Güvenli Kişi Eşleme", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Kişileri gizli arama anahtarları için yerel olarak saklayın. Kişiler asla paylaşılmaz.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Tam Adı") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("contact_name_input")
                    )

                    OutlinedTextField(
                        value = contactPhone,
                        onValueChange = { contactPhone = it },
                        label = { Text("E.164 Telefon (Örn: +905051112233)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("contact_phone_input")
                    )

                    if (contactError != null) {
                        Text(
                            text = contactError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        triggerVibration()
                        if (contactName.isBlank() || contactPhone.isBlank()) {
                            contactError = "Lütfen tüm alanları doldurun"
                        } else if (!contactPhone.startsWith("+")) {
                            contactError = "Numara '+' ile başlamalıdır"
                        } else {
                            viewModel.addContact(contactName, contactPhone)
                            showAddContactDialog = false
                        }
                    },
                    modifier = Modifier.testTag("contact_save_button")
                ) {
                    Text("Kişiyi Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text("Kapat")
                }
            }
        )
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
                                text = contact.phoneNumber,
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
fun SettingsTab(
    viewModel: CommunicationViewModel,
    securityViewModel: SecurityViewModel,
    triggerVibration: () -> Unit
) {
    val userPhone by viewModel.userPhone.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val lockTimeoutMs by securityViewModel.lockTimeoutMs.collectAsState()
    val biometricEnabled by securityViewModel.biometricEnabled.collectAsState()
    val hasPin by securityViewModel.hasPin.collectAsState()
    val savedSupabaseUrl by viewModel.supabaseUrl.collectAsState()
    val savedSupabaseKey by viewModel.supabaseKey.collectAsState()
    val isCloudConfigured = savedSupabaseUrl.isNotBlank() && savedSupabaseKey.isNotBlank()

    var showCredsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // User Profile Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userName.take(2).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = userName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = userPhone,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }
        }

        Divider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

        com.example.features.security.SettingsScreen(
            securityViewModel = securityViewModel,
            triggerVibration = triggerVibration,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Divider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

        // Server Credentials Panel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bulut Sunucu Senkronizasyonu",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isCloudConfigured) "Aktif (Supabase Canlı DB + E2EE)" else "Yapılandırılmadı (Yalnızca Yerel Güvenli Kasa)",
                    fontSize = 11.sp,
                    color = if (isCloudConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = {
                    triggerVibration()
                    showCredsDialog = true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCloudConfigured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = if (isCloudConfigured) "Düzenle" else "Yapılandır",
                    color = if (isCloudConfigured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Advanced Diagnostics Card
        var showDiagnostics by remember { mutableStateOf(false) }
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gelişmiş Ağ Tanılama & Test",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "Gizle" else "Göster", fontSize = 12.sp)
                    }
                }
                if (showDiagnostics) {
                    Text(
                        text = "Sunucu veya ağ kopması durumunda uçtan uca şifreleme ve yeniden bağlanma döngüsünü simüle eder.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Button(
                        onClick = {
                            triggerVibration()
                            viewModel.simulateNetworkLost()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ağ Bağlantı Döngüsünü Simüle Et")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Log Out Button
        TextButton(
            onClick = {
                triggerVibration()
                viewModel.logout {}
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Kimliği İptal Et ve Çıkış Yap", color = MaterialTheme.colorScheme.error)
        }
    }

    // Cloud Credentials Dialog
    if (showCredsDialog) {
        var supabaseUrl by remember(showCredsDialog) { mutableStateOf<String>(savedSupabaseUrl) }
        var supabaseKey by remember(showCredsDialog) { mutableStateOf<String>(savedSupabaseKey) }

        AlertDialog(
            onDismissRequest = { showCredsDialog = false },
            title = { Text("Supabase Bulut Bilgileri", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Canlı Postgres + E2EE senkronizasyonu kurmak için API bilgilerinizi girin. Boş bırakılırsa yerel şifreli depolama kullanılır.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    OutlinedTextField(
                        value = supabaseUrl,
                        onValueChange = { supabaseUrl = it },
                        label = { Text("Supabase API URL") },
                        placeholder = { Text("https://xyzcompany.supabase.co") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = supabaseKey,
                        onValueChange = { supabaseKey = it },
                        label = { Text("Supabase Anon Key") },
                        placeholder = { Text("eyJhbGciOiJIUzI1NiIsIn...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        triggerVibration()
                        viewModel.saveSupabaseCredentials(supabaseUrl, supabaseKey)
                        showCredsDialog = false
                    }
                ) {
                    Text("Parametreleri Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredsDialog = false }) {
                    Text("Kapat")
                }
            }
        )
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
