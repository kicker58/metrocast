package com.metrocast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Metro Transit Police numbers (as published on wmata.com). */
object MetroPolice {
    const val PHONE = "202-962-2121"
    const val TEXT_NUMBER = "696873"
    const val TEXT_WORD = "MYMTPD"
}

/** Call 911, call Metro Transit Police, or text them — each opens the phone's own dialer or messages app. */
@Composable
fun PoliceActions(compact: Boolean = false) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val h = if (compact) 48.dp else 56.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { runCatching { uri.openUri("tel:911") } },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(h),
        ) { Text("📞 Emergency: call 911", fontWeight = FontWeight.Bold) }
        FilledTonalButton(onClick = { runCatching { uri.openUri("tel:${MetroPolice.PHONE.replace("-", "")}") } }, modifier = Modifier.fillMaxWidth().height(h)) {
            Text("📞 Call Metro Transit Police · ${MetroPolice.PHONE}", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = { runCatching { uri.openUri("sms:${MetroPolice.TEXT_NUMBER}") } }, modifier = Modifier.fillMaxWidth().height(h)) {
            Text("💬 Text ${MetroPolice.TEXT_WORD} (${MetroPolice.TEXT_NUMBER})", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Menu → Metro Police: how to get help, fast. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoliceScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Metro Police & help", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("In danger or a medical emergency? Call 911.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PoliceActions()
            Text("When to call or text Metro Transit Police", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "• Suspicious activity or an unattended bag\n• Harassment or someone making you feel unsafe\n• A crime on a train, platform, bus or in a station\n\n" +
                    "Texting is discreet: text ${MetroPolice.TEXT_WORD} (${MetroPolice.TEXT_NUMBER}) with what's happening, the line, the direction, the car number (on the end of each car) and the station.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "On a train you can also use the intercom by the doors at each end of the car to reach the operator. Station managers are at the kiosk by the faregates.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Numbers from wmata.com. MetroCast doesn't send anything itself — these buttons open your phone's dialer or messages so you're in control.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
