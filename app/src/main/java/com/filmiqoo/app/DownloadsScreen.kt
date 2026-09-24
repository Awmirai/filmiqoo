package com.filmiqoo.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

@Composable
fun DownloadsScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onPlay: (PlaybackTarget) -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val settingsRepo=remember { SettingsRepository(context.applicationContext,backend) }
    var appSettings by remember { mutableStateOf(AppPreferences(context.applicationContext).read()) }
    var downloads by remember { mutableStateOf(OfflineDownloadManager.list(context)) }
    var wifiOnly by remember { mutableStateOf(OfflineDownloadManager.wifiOnly(context)) }
    var tab by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        while(isActive) {
            downloads=OfflineDownloadManager.list(context)
            delay(700)
        }
    }

    val active=downloads.filter { it.status!="completed" }
    val completed=downloads.filter { it.status=="completed" }
    val visible=if(tab==0) active else completed

    LazyColumn(
        Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=30.dp)
    ) {
        item {
            DownloadsHero(
                downloads=downloads,
                wifiOnly=wifiOnly,
                onWifiOnly={
                    wifiOnly=it
                    OfflineDownloadManager.setWifiOnly(context,it)
                },
                onBack=onBack
            )
        }

        item {
            SmartDownloadsControl(
                enabled=appSettings.smartDownloads,
                limitMb=appSettings.downloadStorageLimitMb,
                onEnabled={ enabled ->
                    val next=appSettings.copy(smartDownloads=enabled)
                    appSettings=next
                    AppPreferences(context.applicationContext).write(next)
                    if(backend.session.isLoggedIn) {
                        scope.launch {
                            runCatching { settingsRepo.save(next) }
                                .onSuccess { appSettings=it }
                        }
                    }
                }
            )
        }

        item {
            TabRow(
                selectedTabIndex=tab,
                containerColor=FqBg,
                contentColor=FqGold
            ) {
                Tab(
                    selected=tab==0,
                    onClick={tab=0},
                    text={Text("در حال دانلود ("+active.size+")")}
                )
                Tab(
                    selected=tab==1,
                    onClick={tab=1},
                    text={Text("دانلودشده ("+completed.size+")")}
                )
            }
        }

        if(visible.isEmpty()) {
            item {
                PremiumEmptyState(
                    icon=if(tab==0)Icons.Default.CloudDownload else Icons.Default.OfflinePin,
                    title=if(tab==0)"صف دانلود خالیه" else "هنوز چیزی آفلاین نکردی",
                    body=if(tab==0)
                        "از صفحه فیلم یا قسمت، روی دانلود بزن تا اینجا پیشرفت واقعی رو ببینی."
                    else
                        "دانلودهای کامل‌شده اینجا می‌مونن و بدون اینترنت پخش می‌شن."
                )
            }
        } else {
            items(visible,key={it.id}) { item ->
                DownloadCard(
                    item=item,
                    onPause={OfflineDownloadManager.pause(context,item.id)},
                    onResume={OfflineDownloadManager.resume(context,item.id)},
                    onRetry={OfflineDownloadManager.retry(context,item.id)},
                    onDelete={OfflineDownloadManager.delete(context,item.id)},
                    onPlay={
                        val path=item.localPath ?: return@DownloadCard
                        val file=File(path)
                        if(!file.exists()) return@DownloadCard
                        onPlay(
                            PlaybackTarget(
                                mediaVersionId=item.mediaVersionId,
                                title=item.title,
                                subtitle=item.subtitle,
                                posterUrl=item.posterUrl,
                                nextMediaVersionId=item.nextMediaVersionId,
                                nextTitle=item.nextTitle,
                                nextSubtitle=item.nextSubtitle,
                                localUri=Uri.fromFile(file).toString()
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SmartDownloadsControl(
    enabled:Boolean,
    limitMb:Long,
    onEnabled:(Boolean)->Unit
) {
    Surface(
        color=if(enabled)FqGold.copy(alpha=.09f) else FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp)
    ) {
        Row(
            Modifier.padding(13.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).background(
                    if(enabled)FqGold.copy(alpha=.14f) else FqSurface2,
                    RoundedCornerShape(13.dp)
                ),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    null,
                    tint=if(enabled)FqGold else FqMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Smart Downloads",fontSize=11.sp,fontWeight=FontWeight.Bold)
                Text(
                    if(enabled)
                        "قسمت دیده‌شده پاک می‌شود و قسمت بعدی صف می‌شود • سقف "+smartLimitLabel(limitMb)
                    else
                        "مدیریت خودکار قسمت‌های سریال خاموش است.",
                    color=FqMuted,
                    fontSize=7.sp,
                    lineHeight=13.sp
                )
            }
            Switch(checked=enabled,onCheckedChange=onEnabled)
        }
    }
}

private fun smartLimitLabel(value:Long):String =
    if(value<=0)"نامحدود"
    else if(value>=1024)(value/1024).toString()+" GB"
    else value.toString()+" MB"

@Composable
private fun DownloadsHero(
    downloads: List<OfflineDownloadItem>,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val completed=downloads.count { it.status=="completed" }
    val active=downloads.count { it.status in setOf("queued","downloading","paused") }
    val used=downloads.filter { it.status=="completed" }
        .sumOf { if(it.totalBytes>0)it.totalBytes else it.downloadedBytes }

    Box(
        Modifier.fillMaxWidth().height(250.dp).background(
            Brush.verticalGradient(
                listOf(Color(0xFF111827),Color(0xFF17140B),FqBg)
            )
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
            Spacer(Modifier.weight(1f))
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier.padding(horizontal=10.dp,vertical=6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Wifi,null,tint=FqGold,modifier=Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("فقط Wi‑Fi",fontSize=8.sp)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked=wifiOnly,
                        onCheckedChange=onWifiOnly,
                        modifier=Modifier.height(28.dp)
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start=18.dp,end=18.dp,bottom=20.dp)
        ) {
            Text("دانلودهای آفلاین",fontSize=28.sp,fontWeight=FontWeight.Black)
            Text(
                "فیلم و سریال‌ها رو برای تماشا بدون اینترنت نگه دار.",
                color=FqMuted,
                fontSize=10.sp,
                modifier=Modifier.padding(top=4.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top=16.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                PremiumStat(
                    value=active.toString(),
                    label="فعال",
                    modifier=Modifier.weight(1f)
                )
                PremiumStat(
                    value=completed.toString(),
                    label="کامل",
                    modifier=Modifier.weight(1f)
                )
                PremiumStat(
                    value=formatDownloadBytes(used),
                    label="فضای استفاده‌شده",
                    modifier=Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: OfflineDownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=7.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                RemoteImage(
                    item.posterUrl,
                    Modifier.width(76.dp).height(108.dp).clip(RoundedCornerShape(14.dp)),
                    ContentScale.Crop
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        fontSize=13.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                    if(item.subtitle.isNotBlank()) {
                        Text(
                            item.subtitle,
                            color=FqMuted,
                            fontSize=8.sp,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=3.dp)
                        )
                    }

                    Row(
                        Modifier.padding(top=8.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        DownloadStatusPill(item.status)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            buildString {
                                append(formatDownloadBytes(item.downloadedBytes))
                                if(item.totalBytes>0) {
                                    append(" / ")
                                    append(formatDownloadBytes(item.totalBytes))
                                }
                            },
                            color=FqMuted,
                            fontSize=8.sp
                        )
                    }

                    if(item.status!="completed") {
                        LinearProgressIndicator(
                            progress={item.progress},
                            color=FqGold,
                            trackColor=FqSurface3,
                            modifier=Modifier.fillMaxWidth().padding(top=9.dp).height(5.dp)
                                .clip(RoundedCornerShape(99.dp))
                        )
                        Text(
                            if(item.totalBytes>0) ((item.progress*100).toInt()).toString()+"٪"
                            else "در حال محاسبه حجم...",
                            color=FqMuted,
                            fontSize=7.sp,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }

                    item.error?.let {
                        Text(
                            it,
                            color=FqDanger,
                            fontSize=7.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=5.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color=FqSurface3)

            Row(
                Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=7.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                when(item.status) {
                    "downloading","queued" -> {
                        TextButton(onClick=onPause) {
                            Icon(Icons.Default.Pause,null,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("توقف",fontSize=8.sp)
                        }
                    }
                    "paused" -> {
                        TextButton(onClick=onResume) {
                            Icon(Icons.Default.PlayArrow,null,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ادامه",fontSize=8.sp)
                        }
                    }
                    "failed" -> {
                        TextButton(onClick=onRetry) {
                            Icon(Icons.Default.Refresh,null,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تلاش دوباره",fontSize=8.sp)
                        }
                    }
                    "completed" -> {
                        Button(
                            onClick=onPlay,
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow,null,tint=Color.Black,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("پخش آفلاین",color=Color.Black,fontSize=8.sp)
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick=onDelete) {
                    Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)
                }
            }
        }
    }
}

@Composable
private fun DownloadStatusPill(status: String) {
    val pair=when(status) {
        "queued" -> "در صف" to FqGold
        "downloading" -> "در حال دانلود" to FqBlue
        "paused" -> "متوقف" to FqMuted
        "completed" -> "آماده آفلاین" to FqGreen
        "failed" -> "خطا" to FqDanger
        else -> status to FqMuted
    }
    Surface(
        color=pair.second.copy(alpha=.13f),
        shape=RoundedCornerShape(999.dp)
    ) {
        Text(
            pair.first,
            color=pair.second,
            fontSize=7.sp,
            fontWeight=FontWeight.Bold,
            modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp)
        )
    }
}

private fun formatDownloadBytes(bytes: Long): String {
    if(bytes<=0) return "0 MB"
    val mb=bytes/1024.0/1024.0
    return if(mb>=1024) {
        String.format(Locale.US,"%.1f GB",mb/1024.0)
    } else {
        String.format(Locale.US,"%.0f MB",mb)
    }
}
