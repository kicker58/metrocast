package com.metrocast.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Is the phone online? (Tests can set it.) */
object Net {
    @JvmField var testOnline: Boolean? = null

    fun online(context: Context): Boolean {
        testOnline?.let { return it }
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true) // can't tell: don't claim offline
    }
}

/** Online right now, updated as the connection comes and goes. */
@Composable
fun rememberOnline(): Boolean {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(Net.online(ctx)) }
    DisposableEffect(ctx) {
        val cm = runCatching { ctx.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = Net.online(ctx) }
            override fun onLost(network: Network) { online = Net.online(ctx) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { online = Net.online(ctx) }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return online
}

/** "Offline — showing times from 2:14 PM" (or another message). Solid colour, white text: readable in light and dark. */
@Composable
fun OfflineBanner(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF3D4650), contentColor = Color.White, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("⚠", fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
