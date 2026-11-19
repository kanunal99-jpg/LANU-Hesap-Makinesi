package com.example.features.communication

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Shared by the locked call-only screen and the verified in-app call UI. */
@Composable
internal fun IncomingCallActions(onAnswer: () -> Unit, onDecline: () -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onAnswer, enabled = enabled, modifier = Modifier.testTag("incoming_call_answer"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20), contentColor = Color.White)) {
            Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Yanıtla")
        }
        Button(onClick = onDecline, enabled = enabled, modifier = Modifier.testTag("incoming_call_decline"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(8.dp)); Text("Reddet")
        }
    }
}
