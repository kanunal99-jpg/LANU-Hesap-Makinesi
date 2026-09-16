package com.example.features.calculator

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

data class ConversionUnit(
    val name: String,
    val abbreviation: String,
    val baseFactor: Double
)

enum class UnitCategory(val displayName: String, val icon: ImageVector) {
    LENGTH("Uzunluk", Icons.Default.Straighten),
    WEIGHT("Ağırlık", Icons.Default.FitnessCenter),
    TEMPERATURE("Sıcaklık", Icons.Default.Thermostat),
    AREA("Alan", Icons.Default.Layers),
    VOLUME("Hacim", Icons.Default.Opacity),
    SPEED("Hız", Icons.Default.Speed),
    TIME("Zaman", Icons.Default.Schedule),
    DATA("Veri", Icons.Default.Storage)
}

val categoryUnits = mapOf(
    UnitCategory.LENGTH to listOf(
        ConversionUnit("Meters", "m", 1.0),
        ConversionUnit("Kilometers", "km", 1000.0),
        ConversionUnit("Centimeters", "cm", 0.01),
        ConversionUnit("Millimeters", "mm", 0.001),
        ConversionUnit("Micrometers", "µm", 0.000001),
        ConversionUnit("Nanometers", "nm", 0.000000001),
        ConversionUnit("Miles", "mi", 1609.344),
        ConversionUnit("Yards", "yd", 0.9144),
        ConversionUnit("Feet", "ft", 0.3048),
        ConversionUnit("Inches", "in", 0.0254)
    ),
    UnitCategory.WEIGHT to listOf(
        ConversionUnit("Kilograms", "kg", 1.0),
        ConversionUnit("Grams", "g", 0.001),
        ConversionUnit("Milligrams", "mg", 0.000001),
        ConversionUnit("Micrograms", "µg", 0.000000001),
        ConversionUnit("Pounds", "lb", 0.45359237),
        ConversionUnit("Ounces", "oz", 0.028349523),
        ConversionUnit("Tons", "t", 1000.0)
    ),
    UnitCategory.TEMPERATURE to listOf(
        ConversionUnit("Celsius", "°C", 1.0),
        ConversionUnit("Fahrenheit", "°F", 1.0),
        ConversionUnit("Kelvin", "K", 1.0)
    ),
    UnitCategory.AREA to listOf(
        ConversionUnit("Square Meters", "m²", 1.0),
        ConversionUnit("Square Kilometers", "km²", 1_000_000.0),
        ConversionUnit("Square Miles", "mi²", 2_589_988.11),
        ConversionUnit("Square Yards", "yd²", 0.83612736),
        ConversionUnit("Square Feet", "ft²", 0.09290304),
        ConversionUnit("Square Inches", "in²", 0.00064516),
        ConversionUnit("Hectares", "ha", 10_000.0),
        ConversionUnit("Acres", "ac", 4046.85642)
    ),
    UnitCategory.VOLUME to listOf(
        ConversionUnit("Liters", "L", 1.0),
        ConversionUnit("Milliliters", "mL", 0.001),
        ConversionUnit("Cubic Meters", "m³", 1000.0),
        ConversionUnit("Gallons (US)", "gal", 3.78541178),
        ConversionUnit("Quarts (US)", "qt", 0.946352946),
        ConversionUnit("Pints (US)", "pt", 0.473176473),
        ConversionUnit("Cups (US)", "cup", 0.24),
        ConversionUnit("Fluid Ounces (US)", "fl oz", 0.02957353)
    ),
    UnitCategory.SPEED to listOf(
        ConversionUnit("Meters / Second", "m/s", 1.0),
        ConversionUnit("Kilometers / Hour", "km/h", 1.0 / 3.6),
        ConversionUnit("Miles / Hour", "mph", 0.44704),
        ConversionUnit("Knots", "kt", 0.514444)
    ),
    UnitCategory.TIME to listOf(
        ConversionUnit("Seconds", "s", 1.0),
        ConversionUnit("Minutes", "min", 60.0),
        ConversionUnit("Hours", "h", 3600.0),
        ConversionUnit("Days", "d", 86400.0),
        ConversionUnit("Weeks", "w", 604800.0),
        ConversionUnit("Months", "mo", 2629746.0),
        ConversionUnit("Years", "yr", 31556952.0)
    ),
    UnitCategory.DATA to listOf(
        ConversionUnit("Bytes", "B", 1.0),
        ConversionUnit("Kilobytes", "KB", 1024.0),
        ConversionUnit("Megabytes", "MB", 1024.0 * 1024.0),
        ConversionUnit("Gigabytes", "GB", 1024.0 * 1024.0 * 1024.0),
        ConversionUnit("Terabytes", "TB", 1024.0 * 1024.0 * 1024.0 * 1024.0),
        ConversionUnit("Bits", "b", 0.125),
        ConversionUnit("Kilobits", "Kb", 1024.0 * 0.125),
        ConversionUnit("Megabits", "Mb", 1024.0 * 1024.0 * 0.125),
        ConversionUnit("Gigabits", "Gb", 1024.0 * 1024.0 * 1024.0 * 0.125),
        ConversionUnit("Terabits", "Tb", 1024.0 * 1024.0 * 1024.0 * 1024.0 * 0.125)
    )
)

fun convert(value: Double, from: ConversionUnit, to: ConversionUnit, category: UnitCategory): Double {
    if (from.name == to.name) return value

    if (category == UnitCategory.TEMPERATURE) {
        val celsiusValue = when (from.name) {
            "Celsius" -> value
            "Fahrenheit" -> (value - 32.0) * 5.0 / 9.0
            "Kelvin" -> value - 273.15
            else -> value
        }
        return when (to.name) {
            "Celsius" -> celsiusValue
            "Fahrenheit" -> celsiusValue * 9.0 / 5.0 + 32.0
            "Kelvin" -> celsiusValue + 273.15
            else -> celsiusValue
        }
    } else {
        return try {
            val valBd = BigDecimal(value.toString())
            val fromFactorBd = BigDecimal(from.baseFactor.toString())
            val toFactorBd = BigDecimal(to.baseFactor.toString())
            val baseValue = valBd.multiply(fromFactorBd)
            baseValue.divide(toFactorBd, MathContext.DECIMAL128).toDouble()
        } catch (e: Exception) {
            val baseValue = value * from.baseFactor
            baseValue / to.baseFactor
        }
    }
}

fun formatConvertedResult(value: Double): String {
    if (value.isNaN()) return "0"
    if (value.isInfinite()) return "Infinity"
    val cleanValue = if (value == -0.0) 0.0 else value
    if (cleanValue == 0.0) return "0"

    val absVal = kotlin.math.abs(cleanValue)
    if (absVal < 1e-4 || absVal >= 1e12) {
        // Use scientific notation for extremely small or large numbers
        val bd = try {
            BigDecimal(cleanValue.toString(), MathContext(8, RoundingMode.HALF_UP))
        } catch (e: Exception) {
            BigDecimal(cleanValue, MathContext(8, RoundingMode.HALF_UP))
        }
        val scientificStr = bd.toString() // e.g. 1.23E-9 or 1.2345678E+12
        return scientificStr.lowercase(Locale.US).replace("e+", "e")
    }

    val bd = try {
        BigDecimal(cleanValue.toString(), MathContext(15, RoundingMode.HALF_UP))
    } catch (e: Exception) {
        BigDecimal(cleanValue)
    }

    val str = bd.stripTrailingZeros().toPlainString()
    if (str.contains(".")) {
        val parts = str.split(".")
        if (parts[1].length > 10) {
            val rounded = String.format(Locale.US, "%.10f", cleanValue)
            return BigDecimal(rounded).stripTrailingZeros().toPlainString()
        }
    }
    return str
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitConverterScreen(
    initialValue: String? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val triggerVibration = {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    var selectedCategory by remember { mutableStateOf(UnitCategory.LENGTH) }
    val units = categoryUnits[selectedCategory] ?: emptyList()

    var fromUnit by remember(selectedCategory) { mutableStateOf(units.firstOrNull() ?: ConversionUnit("Meters", "m", 1.0)) }
    var toUnit by remember(selectedCategory) { mutableStateOf(units.getOrNull(1) ?: units.firstOrNull() ?: ConversionUnit("Meters", "m", 1.0)) }

    var inputValue by remember(initialValue) {
        mutableStateOf(
            if (!initialValue.isNullOrBlank()) {
                val cleaned = initialValue.filter { it.isDigit() || it == '.' || it == '-' }
                if (cleaned.isNotBlank() && cleaned != "-") cleaned else "1"
            } else {
                "1"
            }
        )
    }
    var showFromDropdown by remember { mutableStateOf(false) }
    var showToDropdown by remember { mutableStateOf(false) }

    val numericValue = inputValue.toDoubleOrNull() ?: 0.0
    val convertedResult = convert(numericValue, fromUnit, toUnit, selectedCategory)
    val formattedResult = formatConvertedResult(convertedResult)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.unit_converter),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("converter_back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back_to_calculator))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
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
        ) {
            // Tabbed category selector for all unit conversions
            ScrollableTabRow(
                selectedTabIndex = selectedCategory.ordinal,
                edgePadding = 16.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                UnitCategory.values().forEach { category ->
                    val isSelected = category == selectedCategory
                    Tab(
                        selected = isSelected,
                        onClick = {
                            triggerVibration()
                            selectedCategory = category
                        },
                        text = {
                            Text(
                                text = category.displayName,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = category.displayName,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("category_tab_${category.name.lowercase()}"),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // FROM CARD
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.from_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Dropdown Selector
                            Box {
                                OutlinedCard(
                                    onClick = {
                                        triggerVibration()
                                        showFromDropdown = true
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("from_unit_dropdown")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "${fromUnit.name} (${fromUnit.abbreviation})",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = stringResource(R.string.select_unit),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showFromDropdown,
                                    onDismissRequest = { showFromDropdown = false }
                                ) {
                                    units.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text("${unit.name} (${unit.abbreviation})") },
                                            onClick = {
                                                triggerVibration()
                                                fromUnit = unit
                                                showFromDropdown = false
                                            },
                                            modifier = Modifier.testTag("from_unit_option_${unit.abbreviation}")
                                        )
                                    }
                                }
                            }

                            // Input Field
                            OutlinedTextField(
                                value = inputValue,
                                onValueChange = { input ->
                                    // Restrict to standard decimals / positive numbers
                                    if (input.all { it.isDigit() || it == '.' || it == '-' }) {
                                        inputValue = input
                                    }
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .width(160.dp)
                                    .testTag("from_unit_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )
                        }
                    }
                }

                // SWAP BUTTON ROW
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    FilledIconButton(
                        onClick = {
                            triggerVibration()
                            val temp = fromUnit
                            fromUnit = toUnit
                            toUnit = temp
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("swap_units_button"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = stringResource(R.string.swap_units),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // TO CARD
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.to_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Dropdown Selector
                            Box {
                                OutlinedCard(
                                    onClick = {
                                        triggerVibration()
                                        showToDropdown = true
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("to_unit_dropdown")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "${toUnit.name} (${toUnit.abbreviation})",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = stringResource(R.string.select_unit),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showToDropdown,
                                    onDismissRequest = { showToDropdown = false }
                                ) {
                                    units.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text("${unit.name} (${unit.abbreviation})") },
                                            onClick = {
                                                triggerVibration()
                                                toUnit = unit
                                                showToDropdown = false
                                            },
                                            modifier = Modifier.testTag("to_unit_option_${unit.abbreviation}")
                                        )
                                    }
                                }
                            }

                            // Result Display Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = formattedResult,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("to_unit_result")
                                )

                                IconButton(
                                    onClick = {
                                        triggerVibration()
                                        if (formattedResult.isNotEmpty() && formattedResult != "0" && formattedResult != "Infinity" && formattedResult != "Error") {
                                            clipboardManager.setText(AnnotatedString(formattedResult))
                                            Toast.makeText(context, context.getString(R.string.copied_value, formattedResult, toUnit.abbreviation), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("copy_converted_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.copy_result),
                                        tint = MaterialTheme.colorScheme.primary,
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
