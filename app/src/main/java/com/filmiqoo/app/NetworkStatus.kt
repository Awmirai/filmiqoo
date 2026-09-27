package com.filmiqoo.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun ConnectivityManager.hasUsableNetwork():Boolean {
    val network=activeNetwork ?: return false
    val caps=getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
fun rememberNetworkOnline():Boolean {
    val context=LocalContext.current
    val manager=remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    var online by remember {
        mutableStateOf(manager.hasUsableNetwork())
    }

    DisposableEffect(manager) {
        val callback=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:Network) {
                online=manager.hasUsableNetwork()
            }

            override fun onLost(network:Network) {
                online=manager.hasUsableNetwork()
            }

            override fun onCapabilitiesChanged(
                network:Network,
                networkCapabilities:NetworkCapabilities
            ) {
                online=manager.hasUsableNetwork()
            }
        }

        runCatching {
            manager.registerDefaultNetworkCallback(
                callback,
                Handler(Looper.getMainLooper())
            )
        }

        onDispose {
            runCatching {
                manager.unregisterNetworkCallback(callback)
            }
        }
    }

    return online
}

@Composable
fun NetworkOfflineBanner(
    modifier:Modifier=Modifier
) {
    Surface(
        color=Color(0xFF261B0A),
        shape=RoundedCornerShape(14.dp),
        modifier=modifier
    ) {
        Row(
            Modifier.padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription=null,
                tint=FqGold,
                modifier=Modifier.size(17.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "آفلاین • محتوای موجود هنوز قابل مشاهده است",
                color=Color.White.copy(alpha=.88f),
                fontSize=10.sp,
                fontWeight=FontWeight.Medium
            )
        }
    }
}
