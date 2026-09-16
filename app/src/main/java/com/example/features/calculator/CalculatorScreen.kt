package com.example.features.calculator

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalculatorScreen(
    viewModel: CalculatorViewModel,
    onNavigateToLock: () -> Unit,
    onNavigateWithDirectUnlock: (String) -> Unit,
    onNavigateToUnitConverter: (String?) -> Unit,
    onNavigateToFinance: () -> Unit,
    onNavigateToCollaboration: () -> Unit,
    onNavigateToAuditPanel: () -> Unit,
    themePreference: String = "system",
    buttonColorTheme: String = "emerald",
    onToggleTheme: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val expression by viewModel.expression.collectAsState()
    val result by viewModel.result.collectAsState()
    val isDegrees by viewModel.isDegrees.collectAsState()
    val isScientific by viewModel.isScientific.collectAsState()
    val historyList by viewModel.history.collectAsState(initial = emptyList())
    val memoryVal by viewModel.memory.collectAsState()

    var showHistorySheet by remember { mutableStateOf(false) }
    var showSmartClearDialog by remember { mutableStateOf(false) }
    var showClearAllHistoryDialog by remember { mutableStateOf(false) }
    var showResetCalculationDialog by remember { mutableStateOf(false) }
    var showGraph by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(isLandscape) {
        if (isLandscape && !isScientific) {
            viewModel.toggleScientific()
        }
    }

    // Auto-toggle graph mode when x is present in expression
    LaunchedEffect(expression) {
        if (expression.contains("x") || expression.contains("X")) {
            showGraph = true
        }
    }

    // Auto-navigate to security screen when "2011." is entered
    LaunchedEffect(expression) {
        if (expression == "2011.") {
            viewModel.onKeyPress("AC")
            onNavigateToLock()
        }
    }

    LaunchedEffect(result) {
        if (result == "2011.") {
            viewModel.onKeyPress("AC")
            onNavigateToLock()
        }
    }

    // Haptic feedback helper
    val triggerVibration = {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                val parsed = parseVoiceMath(spokenText)
                viewModel.appendExpression(parsed)
                Toast.makeText(context, "Algılanan: $spokenText", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "LANU",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                triggerVibration()
                                onNavigateToLock()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("lanu_title_button")
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            showHistorySheet = true
                        },
                        modifier = Modifier.testTag("history_button")
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.content_desc_history))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            onToggleTheme()
                        },
                        modifier = Modifier.testTag("theme_toggle_button")
                    ) {
                        val (icon, desc) = when (themePreference) {
                            "dark" -> Pair(Icons.Default.DarkMode, stringResource(R.string.theme_professional_dark))
                            "light" -> Pair(Icons.Default.LightMode, stringResource(R.string.theme_clean_light))
                            else -> Pair(Icons.Default.BrightnessAuto, stringResource(R.string.theme_toggle_desc))
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = desc,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = {
                            triggerVibration()
                            try {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Matematiksel ifadeyi söyleyin (örn. 5 artı 3)")
                                }
                                speechRecognizerLauncher.launch(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ses tanıma desteklenmiyor", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("voice_input_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Sesle Giriş",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = {
                            triggerVibration()
                            val initialVal = if (result.isNotBlank() && result != "Error") {
                                result
                            } else if (expression.toDoubleOrNull() != null) {
                                expression
                            } else {
                                null
                            }
                            onNavigateToUnitConverter(initialVal)
                        },
                        modifier = Modifier.testTag("unit_converter_button")
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = stringResource(R.string.content_desc_unit_converter))
                    }
                    IconButton(
                        onClick = {
                            triggerVibration()
                            onNavigateToFinance()
                        },
                        modifier = Modifier.testTag("finance_button")
                    ) {
                        Icon(Icons.Default.TrendingUp, contentDescription = "Finansal Hesaplamalar", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = {
                            triggerVibration()
                            onNavigateToCollaboration()
                        },
                        modifier = Modifier.testTag("collaboration_button")
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = "Ortak Çalışma", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = {
                            triggerVibration()
                            onNavigateToAuditPanel()
                        },
                        modifier = Modifier.testTag("audit_panel_button")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = "Denetim Paneli", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(focusRequester)
                .focusable()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        val key = keyEvent.key
                        val nativeEvent = keyEvent.nativeKeyEvent
                        
                        when (key) {
                            Key.Backspace -> {
                                triggerVibration()
                                viewModel.onKeyPress("Backspace")
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                triggerVibration()
                                val digits = expression.filter { it.isDigit() }
                                if (digits.length >= 4 && !expression.contains("[+\\-*×÷ mod ^!]".toRegex())) {
                                    viewModel.onKeyPress("AC")
                                    onNavigateWithDirectUnlock(digits)
                                } else {
                                    viewModel.onKeyPress("=")
                                }
                                true
                            }
                            Key.Escape, Key.Delete -> {
                                triggerVibration()
                                val hasActiveCalculation = expression.isNotBlank() || (result.isNotBlank() && result != "0" && result != "Error")
                                if (hasActiveCalculation) {
                                    showResetCalculationDialog = true
                                } else {
                                    viewModel.onKeyPress("AC")
                                }
                                true
                            }
                            else -> {
                                val unicodeChar = nativeEvent.unicodeChar
                                if (unicodeChar != 0) {
                                    val char = unicodeChar.toChar()
                                    val mappedKey = when (char) {
                                        '/' -> "÷"
                                        '*' -> "×"
                                        'x', 'X' -> "x"
                                        else -> char.toString()
                                    }
                                    
                                    val flatKeys = if (isScientific) {
                                        listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "+", "-", "×", "÷", "x", "e", "π", "ln", "log", "sin", "cos", "tan", "asin", "acos", "atan")
                                    } else {
                                        listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "+", "-", "×", "÷", "MC", "MR", "M+", "M-", "sin", "cos", "tan", "log", "ln")
                                    }
                                    
                                    if (flatKeys.contains(mappedKey)) {
                                        triggerVibration()
                                        viewModel.onKeyPress(mappedKey)
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                            }
                        }
                    } else {
                        false
                    }
                }
        ) {
            // Calculator Display Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End
            ) {
                // Indicators Panel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isDegrees) "DEG" else "RAD",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    triggerVibration()
                                    viewModel.toggleDegrees()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        if (memoryVal != 0.0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "M",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                triggerVibration()
                                showGraph = !showGraph
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShowChart,
                                contentDescription = "Grafik Görünümü",
                                tint = if (showGraph) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                triggerVibration()
                                viewModel.toggleScientific()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isScientific) Icons.Default.Science else Icons.Default.Calculate,
                                contentDescription = stringResource(R.string.toggle_scientific_mode),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (showGraph) {
                    InteractiveFunctionGrapher(
                        expression = expression,
                        isDegrees = isDegrees,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Formula Display
                Text(
                    text = expression.ifBlank { "0" },
                    fontSize = if (expression.length > 15) 28.sp else 38.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.End,
                    maxLines = 3,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Real-time Result Preview
                val copySuccessMsg = stringResource(R.string.copied_to_clipboard)
                Text(
                    text = result,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (result.isNotBlank() && result != "Error") {
                                    triggerVibration()
                                    clipboardManager.setText(AnnotatedString(result))
                                    scope.launch {
                                        snackbarHostState.showSnackbar(copySuccessMsg)
                                    }
                                }
                            }
                        )
                )
            }

            // Keypad Grid
            val keys = if (isScientific) {
                listOf(
                    listOf("sin", "cos", "tan", "AC"),
                    listOf("asin", "acos", "atan", "CE"),
                    listOf("ln", "log", "factorial", "Backspace"),
                    listOf("π", "e", "xʸ", "÷"),
                    listOf("7", "8", "9", "×"),
                    listOf("4", "5", "6", "-"),
                    listOf("1", "2", "3", "+"),
                    listOf("0", ".", "x", "=")
                )
            } else {
                listOf(
                    listOf("sin", "cos", "tan", "log", "ln"),
                    listOf("MC", "MR", "M+", "M-"),
                    listOf("AC", "CE", "±", "÷"),
                    listOf("7", "8", "9", "×"),
                    listOf("4", "5", "6", "-"),
                    listOf("1", "2", "3", "+"),
                    listOf("0", ".", "Backspace", "=")
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(bottom = 24.dp, top = 12.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                keys.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { key ->
                            CalculatorButton(
                                text = key,
                                buttonColorTheme = buttonColorTheme,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(if (isLandscape) 2.2f else if (isScientific) 1.5f else 1.2f),
                                onClick = {
                                    triggerVibration()
                                    if (key == "=") {
                                        // Easter Egg: check if entered text is exactly a valid PIN
                                        val digits = expression.filter { it.isDigit() }
                                        if (digits.length >= 4 && !expression.contains("[+\\-*×÷ mod ^!]".toRegex())) {
                                            viewModel.onKeyPress("AC")
                                            onNavigateWithDirectUnlock(digits)
                                        } else {
                                            viewModel.onKeyPress(key)
                                        }
                                    } else if (key == "AC") {
                                        val hasActiveCalculation = expression.isNotBlank() || (result.isNotBlank() && result != "0" && result != "Error")
                                        if (hasActiveCalculation) {
                                            showResetCalculationDialog = true
                                        } else {
                                            viewModel.onKeyPress("AC")
                                        }
                                    } else {
                                        viewModel.onKeyPress(key)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Calculator History Bottom Sheet
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.history),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (historyList.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    triggerVibration()
                                    showSmartClearDialog = true
                                }
                            ) {
                                Text(stringResource(R.string.smart_clear))
                            }
                            TextButton(
                                onClick = {
                                    triggerVibration()
                                    showClearAllHistoryDialog = true
                                },
                                modifier = Modifier.testTag("clear_all_history_button")
                            ) {
                                Text(stringResource(R.string.clear_all))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (historyList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_calculations),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontSize = 15.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(historyList) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = item.expression,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            triggerVibration()
                                            viewModel.loadHistoryItem(item.expression)
                                            showHistorySheet = false
                                        }
                                        .padding(vertical = 4.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "= ${item.result}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            triggerVibration()
                                            viewModel.appendExpression(item.result)
                                            showHistorySheet = false
                                        }
                                        .padding(vertical = 4.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.tap_to_rerun),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                        )
                                        Text(
                                            text = stringResource(R.string.tap_to_use_result),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                triggerVibration()
                                                clipboardManager.setText(AnnotatedString(item.result))
                                                Toast.makeText(context, context.getString(R.string.copied_result, item.result), Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("copy_history_item_${item.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = stringResource(R.string.copy_result),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                triggerVibration()
                                                viewModel.deleteHistoryItem(item.id)
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("delete_history_item_${item.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.delete_contact),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    if (showSmartClearDialog) {
        AlertDialog(
            onDismissRequest = { showSmartClearDialog = false },
            title = {
                Text(text = stringResource(R.string.smart_clear_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = stringResource(R.string.smart_clear_desc))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        triggerVibration()
                        viewModel.smartClearHistory(7)
                        showSmartClearDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.confirm_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSmartClearDialog = false }
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    // Confirmation Dialog for Clearing All Calculation History
    if (showClearAllHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllHistoryDialog = false },
            title = {
                Text(text = stringResource(R.string.clear_all_history_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = stringResource(R.string.clear_all_history_desc))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        triggerVibration()
                        viewModel.clearHistory()
                        showClearAllHistoryDialog = false
                    },
                    modifier = Modifier.testTag("confirm_clear_all_history_btn")
                ) {
                    Text(text = stringResource(R.string.clear_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearAllHistoryDialog = false },
                    modifier = Modifier.testTag("cancel_clear_all_history_btn")
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    // Confirmation Dialog for Resetting the Current Calculation
    if (showResetCalculationDialog) {
        AlertDialog(
            onDismissRequest = { showResetCalculationDialog = false },
            title = {
                Text(text = stringResource(R.string.reset_calculation_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = stringResource(R.string.reset_calculation_desc))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        triggerVibration()
                        viewModel.onKeyPress("AC")
                        showResetCalculationDialog = false
                    },
                    modifier = Modifier.testTag("confirm_reset_calc_btn")
                ) {
                    Text(text = stringResource(R.string.reset_calculation_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetCalculationDialog = false },
                    modifier = Modifier.testTag("cancel_reset_calc_btn")
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun CalculatorButton(
    text: String,
    buttonColorTheme: String = "emerald",
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accentColor = when (buttonColorTheme) {
        "indigo" -> Color(0xFF5C6BC0)
        "sunset" -> Color(0xFFFF7043)
        "cyberpunk" -> Color(0xFF00E5FF)
        else -> MaterialTheme.colorScheme.primary
    }

    val isOperator = text in listOf("+", "-", "×", "÷", "=")
    val isAction = text in listOf("AC", "CE", "Backspace", "C", "MC", "MR", "M+", "M-")
    
    val containerColor = when {
        text == "=" -> accentColor
        isOperator -> accentColor.copy(alpha = 0.18f)
        isAction -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    val contentColor = when {
        text == "=" -> if (buttonColorTheme == "cyberpunk") Color.Black else Color.White
        isOperator -> accentColor
        isAction -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    val shape = RoundedCornerShape(14.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        label = "ButtonPressScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = contentColor),
                onClick = onClick
            )
            .testTag("btn_$text"),
        contentAlignment = Alignment.Center
    ) {
        if (text == "Backspace") {
            Icon(
                imageVector = Icons.Default.Backspace,
                contentDescription = stringResource(R.string.backspace),
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = text,
                color = contentColor,
                fontSize = if (text.length > 3) 14.sp else 22.sp,
                fontWeight = if (isOperator || isAction) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

private fun parseVoiceMath(spoken: String): String {
    var text = spoken.lowercase()
        .replace("artı", "+")
        .replace("plus", "+")
        .replace("eksi", "-")
        .replace("minus", "-")
        .replace("çarpı", "×")
        .replace("times", "×")
        .replace("multiply", "×")
        .replace("bölü", "÷")
        .replace("divided by", "÷")
        .replace("divide", "÷")
        .replace("virgül", ".")
        .replace("nokta", ".")
        .replace("point", ".")
        .replace("karekök", "sqrt(")
        .replace("square root", "sqrt(")
        .replace("sinüs", "sin(")
        .replace("kosinüs", "cos(")
        .replace("tanjant", "tan(")
        .replace("logaritma", "log(")
        .replace("pi", "π")
        .replace(" ", "")

    text = text
        .replace("sıfır", "0")
        .replace("bir", "1")
        .replace("iki", "2")
        .replace("üç", "3")
        .replace("dört", "4")
        .replace("beş", "5")
        .replace("altı", "6")
        .replace("yedi", "7")
        .replace("sekiz", "8")
        .replace("dokuz", "9")

    return text
}
