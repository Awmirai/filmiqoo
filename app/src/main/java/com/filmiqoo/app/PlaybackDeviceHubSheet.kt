package com.filmiqoo.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.cast.framework.CastButtonFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DeviceHubPanel=Color(0xFF0B0B0E)
private val DeviceHubCard=Color(0xFF121217)
private val DeviceHubBorder=Color.White.copy(alpha=.075f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackDeviceHubSheet(
    castController:FilmiqooCastController,
    repository:PlaybackHandoffRepository,
    authenticated:Boolean,
    mediaVersionId:String,
    positionMs:Long,
    onRequireAuth:()->Unit,
    onDismiss:()->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val castState by castController.state.collectAsState()
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)

    var devices by remember { mutableStateOf<List<PlaybackDevice>>(emptyList()) }
    var loadingDevices by remember { mutableStateOf(false) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    var sendingTo by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authenticated,mediaVersionId,refreshEpoch) {
        if(!authenticated) {
            devices=emptyList()
            return@LaunchedEffect
        }
        while(true) {
            loadingDevices=devices.isEmpty()
            runCatching {
                repository.heartbeat(mediaVersionId,positionMs)
                repository.devices()
            }.onSuccess {
                devices=it
                error=null
            }.onFailure {
                error=it.message ?: "دریافت دستگاه‌های Filmiqoo ناموفق بود."
            }
            loadingDevices=false
            delay(5_000)
        }
    }

    BackHandler(onBack=onDismiss)

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        sheetState=sheetState,
        containerColor=DeviceHubPanel,
        contentColor=Color.White,
        tonalElevation=0.dp,
        scrimColor=Color.Black.copy(alpha=.76f)
    ) {
        LazyColumn(
            modifier=Modifier
                .fillMaxWidth()
                .fillMaxHeight(.90f)
                .navigationBarsPadding(),
            contentPadding=PaddingValues(
                start=15.dp,
                end=15.dp,
                top=4.dp,
                bottom=30.dp
            ),
            verticalArrangement=Arrangement.spacedBy(11.dp)
        ) {
            item("header") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "تماشا روی تلویزیون و دستگاه‌ها",
                            fontSize=20.sp,
                            fontWeight=FontWeight.Black
                        )
                        Text(
                            "Cast، Smart View و انتقال بدون از دست رفتن موقعیت پخش",
                            color=FqMuted,
                            fontSize=10.sp,
                            modifier=Modifier.padding(top=3.dp)
                        )
                    }
                    IconButton(onClick=onDismiss) {
                        Icon(Icons.Default.Close,"بستن")
                    }
                }
            }

            item("cast") {
                CastPrimaryCard(
                    castState=castState,
                    castController=castController
                )
            }

            item("system-mirror") {
                DeviceHubActionCard(
                    icon=Icons.Default.ScreenShare,
                    title="Smart View / Cast صفحه",
                    subtitle="برای تلویزیون‌هایی که Google Cast ندارن، صفحه Cast خود گوشی رو باز کن.",
                    accent=false,
                    onClick={
                        if(!openSystemCastSettings(context)) {
                            error="این گوشی صفحه Cast سیستم رو در اختیار برنامه نمی‌ذاره."
                        }
                    }
                )
            }

            item("account-title") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top=4.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "دستگاه‌های Filmiqoo",
                            fontSize=13.sp,
                            fontWeight=FontWeight.Black
                        )
                        Text(
                            "انتقال مستقیم همین عنوان و همین ثانیه بین دستگاه‌های حساب",
                            color=FqMuted,
                            fontSize=9.sp
                        )
                    }
                    if(authenticated) {
                        IconButton(onClick={refreshEpoch++}) {
                            Icon(Icons.Default.Refresh,"تازه‌سازی")
                        }
                    }
                }
            }

            if(!authenticated) {
                item("login") {
                    DeviceHubActionCard(
                        icon=Icons.Default.DevicesOther,
                        title="ورود برای انتقال بین دستگاه‌ها",
                        subtitle="با یک حساب، پخش رو بین موبایل، تبلت و دستگاه‌های Filmiqoo منتقل کن.",
                        accent=true,
                        onClick=onRequireAuth
                    )
                }
            } else if(loadingDevices) {
                item("devices-loading") {
                    Surface(
                        color=DeviceHubCard,
                        shape=RoundedCornerShape(20.dp),
                        border=BorderStroke(1.dp,DeviceHubBorder),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier=Modifier.size(22.dp),
                                strokeWidth=2.dp,
                                color=FqGold
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("در حال پیدا کردن دستگاه‌های حساب…",fontSize=10.sp)
                        }
                    }
                }
            } else {
                val otherDevices=devices
                    .filterNot { it.current }
                    .sortedWith(
                        compareByDescending<PlaybackDevice> { it.online }
                            .thenBy { it.deviceName.lowercase() }
                    )

                if(otherDevices.isEmpty()) {
                    item("devices-empty") {
                        Surface(
                            color=DeviceHubCard,
                            shape=RoundedCornerShape(20.dp),
                            border=BorderStroke(1.dp,DeviceHubBorder),
                            modifier=Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(18.dp),
                                horizontalAlignment=Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.DevicesOther,
                                    null,
                                    tint=FqMuted,
                                    modifier=Modifier.size(34.dp)
                                )
                                Text(
                                    "دستگاه دوم هنوز آنلاین نیست",
                                    fontSize=11.sp,
                                    fontWeight=FontWeight.Bold,
                                    modifier=Modifier.padding(top=8.dp)
                                )
                                Text(
                                    "Filmiqoo رو روی دستگاه دوم با همین حساب باز کن؛ دستگاه به‌صورت خودکار اینجا ظاهر می‌شه.",
                                    color=FqMuted,
                                    fontSize=9.sp,
                                    lineHeight=14.sp,
                                    modifier=Modifier.padding(top=4.dp)
                                )
                            }
                        }
                    }
                } else {
                    items(otherDevices,key={it.deviceId}) { device ->
                        PlaybackAccountDeviceRow(
                            device=device,
                            busy=sendingTo==device.deviceId,
                            onClick={
                                if(sendingTo!=null) return@PlaybackAccountDeviceRow
                                sendingTo=device.deviceId
                                message=null
                                error=null
                                scope.launch {
                                    runCatching {
                                        repository.send(
                                            targetDeviceId=device.deviceId,
                                            mediaVersionId=mediaVersionId,
                                            positionMs=positionMs
                                        )
                                    }.onSuccess {
                                        message="درخواست انتقال برای "+device.deviceName+" ارسال شد."
                                    }.onFailure {
                                        error=it.message ?: "انتقال پخش ناموفق بود."
                                    }
                                    sendingTo=null
                                }
                            }
                        )
                    }
                }
            }

            message?.let { value ->
                item("message") {
                    Surface(
                        color=FqGreen.copy(alpha=.10f),
                        shape=RoundedCornerShape(15.dp),
                        border=BorderStroke(1.dp,FqGreen.copy(alpha=.20f)),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle,null,tint=FqGreen)
                            Spacer(Modifier.width(8.dp))
                            Text(value,color=FqGreen,fontSize=9.sp)
                        }
                    }
                }
            }

            error?.let { value ->
                item("error") {
                    Surface(
                        color=FqDanger.copy(alpha=.10f),
                        shape=RoundedCornerShape(15.dp),
                        border=BorderStroke(1.dp,FqDanger.copy(alpha=.20f)),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Text(
                            value,
                            color=Color(0xFFFFA3AC),
                            fontSize=9.sp,
                            modifier=Modifier.padding(12.dp)
                        )
                    }
                }
            }

            item("compatibility") {
                Surface(
                    color=Color.White.copy(alpha=.035f),
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text(
                            "سازگاری",
                            fontSize=10.sp,
                            fontWeight=FontWeight.Bold
                        )
                        Text(
                            "Google Cast: Chromecast، Google TV، Android TV و TVهای Castدار • Smart View/Cast سیستم: Screen Mirroring روی TVهای سازگار • Filmiqoo Handoff: انتقال بین دستگاه‌های واردشده به حساب.",
                            color=FqMuted,
                            fontSize=8.sp,
                            lineHeight=13.sp,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastPrimaryCard(
    castState:FilmiqooCastState,
    castController:FilmiqooCastController
) {
    val connected=castState.connected
    Surface(
        color=if(connected)FqGold.copy(alpha=.075f) else DeviceHubCard,
        shape=RoundedCornerShape(24.dp),
        border=BorderStroke(
            1.dp,
            if(connected)FqGold.copy(alpha=.36f) else DeviceHubBorder
        ),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(
                            if(connected)FqGold.copy(alpha=.15f)
                            else Color.White.copy(alpha=.05f),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(
                        if(connected)Icons.Default.CastConnected else Icons.Default.Tv,
                        null,
                        tint=if(connected)FqGold else Color.White
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if(connected)
                            castState.deviceName.ifBlank { "تلویزیون متصل" }
                        else
                            "Google Cast",
                        fontSize=15.sp,
                        fontWeight=FontWeight.Black
                    )
                    Text(
                        when {
                            connected -> "پخش مستقیم روی TV • گوشی تبدیل به ریموت می‌شه"
                            castState.connecting -> "در حال اتصال به دستگاه…"
                            castState.connection==FilmiqooCastConnection.SUSPENDED ->
                                "در حال بازیابی ارتباط…"
                            else -> "Chromecast، Google TV، Android TV و تلویزیون‌های Castدار"
                        },
                        color=FqMuted,
                        fontSize=9.sp,
                        lineHeight=13.sp,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }

                if(castState.connecting) {
                    CircularProgressIndicator(
                        modifier=Modifier.size(24.dp),
                        strokeWidth=2.dp,
                        color=FqGold
                    )
                } else if(!connected) {
                    FilmiqooCastRouteButton()
                }
            }

            if(connected) {
                HorizontalDivider(
                    color=Color.White.copy(alpha=.07f),
                    modifier=Modifier.padding(vertical=13.dp)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    DeviceHubSmallAction(
                        icon=if(castState.isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,
                        label=if(castState.isPlaying)"توقف" else "پخش",
                        modifier=Modifier.weight(1f),
                        onClick={castController.togglePlayback()}
                    )
                    DeviceHubSmallAction(
                        icon=Icons.Default.Stop,
                        label="قطع اتصال",
                        danger=true,
                        modifier=Modifier.weight(1f),
                        onClick={castController.disconnect(true)}
                    )
                }
                if(castState.durationMs>0L) {
                    Text(
                        deviceHubTime(castState.positionMs)+" / "+
                            deviceHubTime(castState.durationMs),
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(top=10.dp)
                    )
                }
            }

            castState.error?.let {
                Text(
                    it,
                    color=Color(0xFFFF9AA4),
                    fontSize=9.sp,
                    modifier=Modifier.padding(top=9.dp)
                )
            }
        }
    }
}

@Composable
private fun FilmiqooCastRouteButton() {
    AndroidView(
        factory={ctx->
            FrameLayout(ctx).apply {
                val button=androidx.mediarouter.app.MediaRouteButton(ctx)
                val child=runCatching {
                    CastButtonFactory.setUpMediaRouteButton(ctx,button)
                    button
                }.getOrElse {
                    android.widget.ImageView(ctx).apply {
                        setImageResource(android.R.drawable.ic_menu_share)
                        setColorFilter(android.graphics.Color.WHITE)
                        alpha=.72f
                        contentDescription="Cast unavailable"
                    }
                }
                addView(
                    child,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        },
        modifier=Modifier.size(48.dp)
    )
}

@Composable
private fun DeviceHubSmallAction(
    icon:ImageVector,
    label:String,
    modifier:Modifier=Modifier,
    danger:Boolean=false,
    onClick:()->Unit
) {
    Surface(
        color=if(danger)FqDanger.copy(alpha=.10f) else Color.White.copy(alpha=.055f),
        contentColor=if(danger)Color(0xFFFF9AA4) else Color.White,
        shape=RoundedCornerShape(14.dp),
        border=BorderStroke(
            1.dp,
            if(danger)FqDanger.copy(alpha=.18f) else Color.White.copy(alpha=.07f)
        ),
        modifier=modifier.clickable(onClick=onClick)
    ) {
        Row(
            Modifier.padding(horizontal=12.dp,vertical=11.dp),
            horizontalArrangement=Arrangement.Center,
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(icon,null,modifier=Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label,fontSize=9.sp,fontWeight=FontWeight.Bold)
        }
    }
}

@Composable
private fun DeviceHubActionCard(
    icon:ImageVector,
    title:String,
    subtitle:String,
    accent:Boolean,
    onClick:()->Unit
) {
    Surface(
        color=DeviceHubCard,
        shape=RoundedCornerShape(20.dp),
        border=BorderStroke(1.dp,DeviceHubBorder),
        modifier=Modifier
            .fillMaxWidth()
            .clickable(onClick=onClick)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(43.dp)
                    .background(
                        if(accent)FqGold.copy(alpha=.12f)
                        else Color.White.copy(alpha=.045f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    tint=if(accent)FqGold else Color.White.copy(alpha=.82f)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold)
                Text(
                    subtitle,
                    color=FqMuted,
                    fontSize=9.sp,
                    lineHeight=13.sp,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
        }
    }
}

@Composable
private fun PlaybackAccountDeviceRow(
    device:PlaybackDevice,
    busy:Boolean,
    onClick:()->Unit
) {
    val icon=platformDeviceIcon(device.platform)
    Surface(
        color=DeviceHubCard,
        shape=RoundedCornerShape(19.dp),
        border=BorderStroke(
            1.dp,
            if(device.online)FqGreen.copy(alpha=.18f) else DeviceHubBorder
        ),
        modifier=Modifier
            .fillMaxWidth()
            .clickable(enabled=!busy,onClick=onClick)
    ) {
        Row(
            Modifier.padding(13.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if(device.online)FqGreen.copy(alpha=.10f)
                        else Color.White.copy(alpha=.04f),
                        CircleShape
                    ),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    tint=if(device.online)FqGreen else FqMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.deviceName,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(if(device.online)"آنلاین" else "اخیراً فعال")
                        val label=platformLabel(device.platform)
                        if(label.isNotBlank()) append(" • ").append(label)
                    },
                    color=if(device.online)FqGreen else FqMuted,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
            if(busy) {
                CircularProgressIndicator(
                    color=FqGold,
                    strokeWidth=2.dp,
                    modifier=Modifier.size(22.dp)
                )
            } else {
                Icon(
                    Icons.Default.SendToMobile,
                    "انتقال",
                    tint=FqGold
                )
            }
        }
    }
}

private fun platformDeviceIcon(platform:String):ImageVector =
    when(platform.lowercase()) {
        "android","ios","phone","mobile" -> Icons.Default.PhoneAndroid
        "web","browser" -> Icons.Default.Language
        "tv","android_tv","google_tv" -> Icons.Default.Tv
        "windows","macos","linux","desktop" -> Icons.Default.Computer
        else -> Icons.Default.DevicesOther
    }

private fun platformLabel(platform:String):String =
    when(platform.lowercase()) {
        "android" -> "Android"
        "ios" -> "iPhone / iPad"
        "web","browser" -> "مرورگر"
        "tv","android_tv" -> "Android TV"
        "google_tv" -> "Google TV"
        "windows" -> "Windows"
        "macos" -> "macOS"
        "linux" -> "Linux"
        else -> platform
    }

private fun openSystemCastSettings(context:Context):Boolean {
    val intents=listOf(
        Intent(Settings.ACTION_CAST_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )
    for(intent in intents) {
        val resolved=intent.resolveActivity(context.packageManager)
        if(resolved!=null) {
            return runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
    return false
}

private fun deviceHubTime(ms:Long):String {
    if(ms<=0L) return "00:00"
    val total=ms/1000L
    val hours=total/3600L
    val minutes=(total%3600L)/60L
    val seconds=total%60L
    return if(hours>0L) {
        "%02d:%02d:%02d".format(hours,minutes,seconds)
    } else {
        "%02d:%02d".format(minutes,seconds)
    }
}
