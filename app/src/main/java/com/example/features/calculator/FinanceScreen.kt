package com.example.features.calculator

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode

enum class FinanceCategory(val displayName: String, val icon: ImageVector) {
    PERCENTAGE("Yüzde", Icons.Default.Percent),
    DISCOUNT("İndirim", Icons.Default.LocalOffer),
    VAT("KDV", Icons.Default.ReceiptLong),
    INSTALLMENT("Taksit", Icons.Default.CreditCard),
    INTEREST("Faiz", Icons.Default.TrendingUp),
    PROFIT_MARGIN("Kâr Marjı", Icons.Default.ShowChart)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(FinanceCategory.PERCENTAGE) }

    val triggerVibration = {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Finansal Hesaplamalar",
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
                        modifier = Modifier.testTag("finance_back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
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
        ) {
            // Category selector
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FinanceCategory.values()) { category ->
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            triggerVibration()
                            selectedCategory = category
                        },
                        label = { Text(category.displayName) },
                        leadingIcon = {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("finance_chip_${category.name.lowercase()}")
                    )
                }
            }

            // Calculation Panel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                when (selectedCategory) {
                    FinanceCategory.PERCENTAGE -> PercentageCalculatorPanel()
                    FinanceCategory.DISCOUNT -> DiscountCalculatorPanel()
                    FinanceCategory.VAT -> VatCalculatorPanel()
                    FinanceCategory.INSTALLMENT -> InstallmentCalculatorPanel()
                    FinanceCategory.INTEREST -> InterestCalculatorPanel()
                    FinanceCategory.PROFIT_MARGIN -> ProfitMarginCalculatorPanel()
                }
            }
        }
    }
}

@Composable
fun PercentageCalculatorPanel() {
    var amountStr by remember { mutableStateOf("1000") }
    var percentStr by remember { mutableStateOf("18") }

    val amount = amountStr.toDoubleOrNull() ?: 0.0
    val percent = percentStr.toDoubleOrNull() ?: 0.0

    val calculatedValue = (amount * percent) / 100.0
    val totalWithPercent = amount + calculatedValue
    val totalWithoutPercent = amount - calculatedValue

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Yüzde Hesaplama",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = { Text("Miktar (TL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("percentage_amount_input")
            )
        }

        item {
            OutlinedTextField(
                value = percentStr,
                onValueChange = { percentStr = it },
                label = { Text("Yüzde Oranı (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("percentage_percent_input")
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ResultRow("Miktarın %$percent'i:", String.format("%.2f TL", calculatedValue))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("Miktar + Yüzde Tutarı:", String.format("%.2f TL", totalWithPercent))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("Miktar - Yüzde Tutarı:", String.format("%.2f TL", totalWithoutPercent))
                }
            }
        }
    }
}

@Composable
fun DiscountCalculatorPanel() {
    var priceStr by remember { mutableStateOf("500") }
    var discountStr by remember { mutableStateOf("25") }

    val originalPrice = priceStr.toDoubleOrNull() ?: 0.0
    val discountPercent = discountStr.toDoubleOrNull() ?: 0.0

    val savedAmount = (originalPrice * discountPercent) / 100.0
    val finalPrice = originalPrice - savedAmount

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "İndirim Hesaplama",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            OutlinedTextField(
                value = priceStr,
                onValueChange = { priceStr = it },
                label = { Text("Orijinal Fiyat (TL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("discount_price_input")
            )
        }

        item {
            OutlinedTextField(
                value = discountStr,
                onValueChange = { discountStr = it },
                label = { Text("İndirim Oranı (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("discount_percent_input")
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ResultRow("İndirim Tutarı:", String.format("%.2f TL", savedAmount))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("İndirimli Fiyat:", String.format("%.2f TL", finalPrice))
                }
            }
        }
    }
}

@Composable
fun VatCalculatorPanel() {
    var priceStr by remember { mutableStateOf("100") }
    var vatStr by remember { mutableStateOf("20") }
    var isIncluded by remember { mutableStateOf(false) } // false = KDV Hariç, true = KDV Dahil

    val price = priceStr.toDoubleOrNull() ?: 0.0
    val vatRate = vatStr.toDoubleOrNull() ?: 0.0

    val vatAmount: Double
    val baseAmount: Double
    val totalAmount: Double

    if (isIncluded) {
        totalAmount = price
        baseAmount = price / (1.0 + (vatRate / 100.0))
        vatAmount = totalAmount - baseAmount
    } else {
        baseAmount = price
        vatAmount = price * (vatRate / 100.0)
        totalAmount = price + vatAmount
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "KDV Hesaplama",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            OutlinedTextField(
                value = priceStr,
                onValueChange = { priceStr = it },
                label = { Text(if (isIncluded) "KDV Dahil Tutar (TL)" else "KDV Hariç Tutar (TL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("vat_price_input")
            )
        }

        item {
            OutlinedTextField(
                value = vatStr,
                onValueChange = { vatStr = it },
                label = { Text("KDV Oranı (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("vat_rate_input")
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { isIncluded = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isIncluded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!isIncluded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f).testTag("vat_exclude_btn")
                ) {
                    Text("KDV Hariç")
                }

                Button(
                    onClick = { isIncluded = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isIncluded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isIncluded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f).testTag("vat_include_btn")
                ) {
                    Text("KDV Dahil")
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ResultRow("KDV'siz Tutar:", String.format("%.2f TL", baseAmount))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("KDV Tutarı:", String.format("%.2f TL", vatAmount))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("Toplam Tutar:", String.format("%.2f TL", totalAmount))
                }
            }
        }
    }
}

@Composable
fun InstallmentCalculatorPanel() {
    var amountStr by remember { mutableStateOf("12000") }
    var monthsStr by remember { mutableStateOf("12") }
    var interestStr by remember { mutableStateOf("1.5") } // Aylık Faiz (%)

    val totalAmount = amountStr.toDoubleOrNull() ?: 0.0
    val months = monthsStr.toIntOrNull() ?: 1
    val monthlyInterestPercent = interestStr.toDoubleOrNull() ?: 0.0

    val monthlyPayment: Double
    val totalPayment: Double
    val totalInterest: Double

    if (monthlyInterestPercent > 0) {
        val i = monthlyInterestPercent / 100.0
        // Formül: Taksit = P * [ i * (1+i)^n ] / [ (1+i)^n - 1 ]
        val factor = Math.pow(1 + i, months.toDouble())
        monthlyPayment = if (factor > 1) {
            totalAmount * (i * factor) / (factor - 1)
        } else {
            totalAmount / months
        }
        totalPayment = monthlyPayment * months
        totalInterest = totalPayment - totalAmount
    } else {
        monthlyPayment = totalAmount / months
        totalPayment = totalAmount
        totalInterest = 0.0
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Kredi / Taksit Hesaplama",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = { Text("Toplam Tutar (TL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("installment_amount_input")
            )
        }

        item {
            OutlinedTextField(
                value = monthsStr,
                onValueChange = { monthsStr = it },
                label = { Text("Taksit Sayısı (Ay)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("installment_months_input")
            )
        }

        item {
            OutlinedTextField(
                value = interestStr,
                onValueChange = { interestStr = it },
                label = { Text("Aylık Faiz Oranı (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("installment_interest_input")
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ResultRow("Aylık Taksit Tutarı:", String.format("%.2f TL", monthlyPayment))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("Toplam Ödenecek Faiz:", String.format("%.2f TL", totalInterest))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("Toplam Geri Ödeme:", String.format("%.2f TL", totalPayment))
                }
            }
        }
    }
}

@Composable
fun InterestCalculatorPanel() {
    var principalStr by remember { mutableStateOf("10000") }
    var rateStr by remember { mutableStateOf("45") } // Yıllık Faiz (%)
    var daysStr by remember { mutableStateOf("32") } // Gün Sayısı

    val principal = principalStr.toDoubleOrNull() ?: 0.0
    val annualRatePercent = rateStr.toDoubleOrNull() ?: 0.0
    val days = daysStr.toIntOrNull() ?: 1

    // Formül: Faiz = (Anapara * Faiz Oranı * Gün) / 36500
    val earnedInterest = (principal * annualRatePercent * days) / 36500.0
    val totalAmount = principal + earnedInterest

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Mevduat Getirisi (Faiz) Hesaplama",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            OutlinedTextField(
                value = principalStr,
                onValueChange = { principalStr = it },
                label = { Text("Anapara (TL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("interest_principal_input")
            )
        }

        item {
            OutlinedTextField(
                value = rateStr,
                onValueChange = { rateStr = it },
                label = { Text("Yıllık Faiz Oranı (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("interest_rate_input")
            )
        }

        item {
            OutlinedTextField(
                value = daysStr,
                onValueChange = { daysStr = it },
                label = { Text("Vade Süresi (Gün)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("interest_days_input")
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ResultRow("Brüt Faiz Getirisi:", String.format("%.2f TL", earnedInterest))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("Vade Sonu Toplam Tutar:", String.format("%.2f TL", totalAmount))
                }
            }
        }
    }
}

@Composable
fun ProfitMarginCalculatorPanel() {
    var costStr by remember { mutableStateOf("80") }
    var marginStr by remember { mutableStateOf("20") } // Hedef Kâr Marjı (%)

    val cost = costStr.toDoubleOrNull() ?: 0.0
    val targetMarginPercent = marginStr.toDoubleOrNull() ?: 0.0

    // Satış Fiyatı Formülü = Maliyet / (1 - (Marj / 100))
    val sellingPrice = if (targetMarginPercent < 100.0 && targetMarginPercent >= 0.0) {
        cost / (1.0 - (targetMarginPercent / 100.0))
    } else {
        cost
    }
    val profit = sellingPrice - cost

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Kâr Marjı Hesaplama",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            OutlinedTextField(
                value = costStr,
                onValueChange = { costStr = it },
                label = { Text("Maliyet Tutarı (TL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("margin_cost_input")
            )
        }

        item {
            OutlinedTextField(
                value = marginStr,
                onValueChange = { marginStr = it },
                label = { Text("Hedef Kâr Marjı (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("margin_percent_input")
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ResultRow("Önerilen Satış Fiyatı:", String.format("%.2f TL", sellingPrice))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultRow("Net Kâr Tutarı:", String.format("%.2f TL", profit))
                }
            }
        }
    }
}

@Composable
fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
