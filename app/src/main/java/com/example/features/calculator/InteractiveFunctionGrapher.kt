package com.example.features.calculator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.calculator.CalculatorEngine

@Composable
fun InteractiveFunctionGrapher(
    expression: String,
    isDegrees: Boolean,
    modifier: Modifier = Modifier
) {
    var zoomScale by remember { mutableStateOf(1.0f) }

    val minX = -10.0f * zoomScale
    val maxX = 10.0f * zoomScale
    val minY = -5.0f * zoomScale
    val maxY = 5.0f * zoomScale

    // Clean expression by replacing human readable multipliers with asterisk, etc.
    val cleanedExpression = remember(expression) {
        expression
            .replace("×", "*")
            .replace("÷", "/")
    }

    val points = remember(cleanedExpression, isDegrees, zoomScale) {
        val list = mutableListOf<Pair<Float, Float>>()
        if (cleanedExpression.isNotBlank()) {
            val steps = 180
            val stepSize = (maxX - minX) / steps
            for (i in 0..steps) {
                val xVal = minX + i * stepSize
                try {
                    // Safe substitution: replace variable 'x' with current value in parentheses
                    // Handle x/X
                    val tempExpr = cleanedExpression
                        .replace("x", "($xVal)")
                        .replace("X", "($xVal)")
                    
                    val yVal = CalculatorEngine.evaluate(tempExpr, isDegrees)
                    if (!yVal.isNaN() && !yVal.isInfinite()) {
                        list.add(Pair(xVal, yVal.toFloat()))
                    }
                } catch (_: Exception) {
                    // Skip invalid points
                }
            }
        }
        list
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
    ) {
        val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        val axisColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        val lineColor = MaterialTheme.colorScheme.primary
        val textColor = MaterialTheme.colorScheme.onSurfaceVariant

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val xRange = maxX - minX
            val yRange = maxY - minY

            // Draw grid lines
            // Vertical grid lines
            val xStep = if (zoomScale > 2.5f) 5f else if (zoomScale < 0.5f) 0.5f else 2.0f
            var xGrid = (minX / xStep).toInt() * xStep
            while (xGrid <= maxX) {
                val gridX = ((xGrid - minX) / xRange) * width
                if (gridX in 0f..width) {
                    drawLine(
                        color = gridColor,
                        start = Offset(gridX, 0f),
                        end = Offset(gridX, height),
                        strokeWidth = 1f
                    )
                }
                xGrid += xStep
            }

            // Horizontal grid lines
            val yStep = if (zoomScale > 2.5f) 2.5f else if (zoomScale < 0.5f) 0.25f else 1.0f
            var yGrid = (minY / yStep).toInt() * yStep
            while (yGrid <= maxY) {
                val gridY = height - ((yGrid - minY) / yRange) * height
                if (gridY in 0f..height) {
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, gridY),
                        end = Offset(width, gridY),
                        strokeWidth = 1f
                    )
                }
                yGrid += yStep
            }

            // X Axis
            val zeroY = height - ((0f - minY) / yRange) * height
            if (zeroY in 0f..height) {
                drawLine(
                    color = axisColor,
                    start = Offset(0f, zeroY),
                    end = Offset(width, zeroY),
                    strokeWidth = 2.5f
                )
            }

            // Y Axis
            val zeroX = ((0f - minX) / xRange) * width
            if (zeroX in 0f..width) {
                drawLine(
                    color = axisColor,
                    start = Offset(zeroX, 0f),
                    end = Offset(zeroX, height),
                    strokeWidth = 2.5f
                )
            }

            // Draw function points
            if (points.isNotEmpty()) {
                var prevPoint: Offset? = null
                for (p in points) {
                    val xVal = p.first
                    val yVal = p.second

                    val px = ((xVal - minX) / xRange) * width
                    val py = height - ((yVal - minY) / yRange) * height

                    val currentPoint = Offset(px, py)

                    // Draw line if it's within sensible canvas bounds to avoid overflow distortions
                    if (prevPoint != null && !py.isNaN() && !py.isInfinite() && py in -height..(height * 2)) {
                        drawLine(
                            color = lineColor,
                            start = prevPoint,
                            end = currentPoint,
                            strokeWidth = 3.5f
                        )
                    }
                    prevPoint = currentPoint
                }
            }
        }

        // Title and Legend Overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "f(x) = ${expression.ifBlank { "0" }}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "X: [${String.format("%.1f", minX)}, ${String.format("%.1f", maxX)}] | Y: [${String.format("%.1f", minY)}, ${String.format("%.1f", maxY)}]",
                fontSize = 8.5.sp,
                color = textColor
            )
        }

        // Graph Interactive Zoom Controls Row
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    RoundedCornerShape(8.dp)
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { zoomScale = (zoomScale * 0.7f).coerceIn(0.1f, 10f) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = "Yakınlaş",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { zoomScale = (zoomScale * 1.4f).coerceIn(0.1f, 10f) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomOut,
                    contentDescription = "Uzaklaş",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { zoomScale = 1.0f },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Sıfırla",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
