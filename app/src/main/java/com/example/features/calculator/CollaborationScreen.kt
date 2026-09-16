package com.example.features.calculator

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class Collaborator(
    val name: String,
    val color: Color,
    val initial: String,
    val status: String = "Çevrimiçi"
)

data class SyncCalculation(
    val id: String,
    val user: String,
    val userColor: Color,
    val expression: String,
    val result: String,
    val timestamp: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollaborationScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val triggerVibration = {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    var roomId by remember { mutableStateOf("LANU-SYNC-782") }
    var expressionInput by remember { mutableStateOf("") }
    var isJoined by remember { mutableStateOf(true) }

    // List of active participants
    val participants = remember {
        mutableStateListOf(
            Collaborator("Ahmet K. (Siz)", Color(0xFF3B82F6), "A"),
            Collaborator("Merve S.", Color(0xFF10B981), "M"),
            Collaborator("Caner Y.", Color(0xFFF59E0B), "C"),
            Collaborator("Jane D.", Color(0xFFEC4899), "J")
        )
    }

    // Calculation updates feed
    val syncFeeds = remember {
        mutableStateListOf(
            SyncCalculation("1", "Merve S.", Color(0xFF10B981), "2500 * 0.18", "450 TL (KDV)", "15:28"),
            SyncCalculation("2", "Caner Y.", Color(0xFFF59E0B), "log(100) + sin(30)", "2.5", "15:29"),
            SyncCalculation("3", "Jane D.", Color(0xFFEC4899), "12000 / 12", "1000 TL / Ay", "15:31")
        )
    }

    // Auto simulated updates to show realtime broadcast integration
    LaunchedEffect(isJoined) {
        if (isJoined) {
            val names = listOf("Merve S.", "Caner Y.", "Jane D.")
            val formulas = listOf(
                Pair("5000 * 0.20", "1000 TL"),
                Pair("cos(60) * 100", "50"),
                Pair("1500 - 30%", "1050 TL"),
                Pair("ln(e) + 12", "13"),
                Pair("area(50m² -> yd²)", "59.8 yd²")
            )
            while (true) {
                delay(6000) // Simulates a broadcast received every 6 seconds
                val randomUser = names.random()
                val randomFormula = formulas.random()
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val color = participants.firstOrNull { it.name == randomUser }?.color ?: Color.Gray

                syncFeeds.add(
                    SyncCalculation(
                        UUID.randomUUID().toString(),
                        randomUser,
                        color,
                        randomFormula.first,
                        randomFormula.second,
                        time
                    )
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ortak Çalışma Alanı",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("collab_back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            clipboardManager.setText(AnnotatedString(roomId))
                            android.widget.Toast.makeText(context, "Oda linki kopyalandı!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("collab_share_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Paylaş")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            // Room join / status card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Aktif Senkronizasyon Odası",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = roomId,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981)) // pulsing online indicator
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Bağlı",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }

            // Participants List Row
            Text(
                text = "Çalışma Grubundaki Kişiler",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                participants.forEach { user ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.testTag("collaborator_${user.name.lowercase().replace(" ", "_")}")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(user.color.copy(alpha = 0.2f))
                                .border(1.5.dp, user.color, CircleShape)
                        ) {
                            Text(
                                text = user.initial,
                                fontWeight = FontWeight.Bold,
                                color = user.color,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = user.name.split(" ")[0],
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Shared Broadcast Stream Title
            Text(
                text = "Canlı Hesaplama Yayını (Supabase Realtime Feed)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Feeds list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    reverseLayout = true
                ) {
                    items(syncFeeds.asReversed()) { feed ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("sync_feed_item_${feed.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(feed.userColor.copy(alpha = 0.15f))
                                ) {
                                    Text(
                                        text = feed.user.first().toString(),
                                        fontWeight = FontWeight.Bold,
                                        color = feed.userColor,
                                        fontSize = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = feed.user,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = feed.userColor
                                        )
                                        Text(
                                            text = feed.timestamp,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${feed.expression} = ${feed.result}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live input box to send/broadcast calculation to room
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = expressionInput,
                    onValueChange = { expressionInput = it },
                    placeholder = { Text("Odayla bir hesap paylaşın...") },
                    modifier = Modifier.weight(1f).testTag("collab_input_field"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                IconButton(
                    onClick = {
                        if (expressionInput.isNotBlank()) {
                            triggerVibration()
                            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                            syncFeeds.add(
                                SyncCalculation(
                                    UUID.randomUUID().toString(),
                                    "Ahmet K. (Siz)",
                                    Color(0xFF3B82F6),
                                    expressionInput,
                                    "Yayınlandı",
                                    time
                                )
                            )
                            expressionInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("collab_send_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Gönder",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}
