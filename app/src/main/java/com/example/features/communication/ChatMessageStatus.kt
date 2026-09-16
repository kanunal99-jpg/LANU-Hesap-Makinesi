package com.example.features.communication

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun ChatMessageStatus(status: String, modifier: Modifier = Modifier) {
    val sendingContentDesc = stringResource(id = R.string.status_sending)
    
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                scaleIn(initialScale = 0.8f, animationSpec = tween(220, delayMillis = 90)))
                .togetherWith(fadeOut(animationSpec = tween(90)))
        },
        label = "status_animation",
        modifier = modifier
    ) { targetStatus ->
        when (targetStatus) {
            "SENDING" -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(12.dp)
                        .semantics { contentDescription = sendingContentDesc },
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            "SENT" -> {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = stringResource(R.string.status_sent),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
            }
            "DELIVERED" -> {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.status_delivered),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
            }
            "READ" -> {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.status_read),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
            else -> {
                Spacer(modifier = Modifier.size(14.dp))
            }
        }
    }
}
