package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackHandoffSheet(
    repository:PlaybackHandoffRepository,
    mediaVersionId:String,
    positionMs:Long,
    onDismiss:()->Unit
) {
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf<List<PlaybackDevice>>(emptyList()) }
    var sendingTo by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh,mediaVersionId,positionMs) {
        loading=true
        error=null
        runCatching {
            repository.heartbeat(mediaVersionId,positionMs)
            repository.devices()
        }.onSuccess { devices=it }
            .onFailure { error=it.message ?: "دریافت دستگاه‌ها ناموفق بود" }
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start=16.dp,end=16.dp,bottom=20.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp)
                        .background(FqGold.copy(alpha=.13f),RoundedCornerShape(13.dp)),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(Icons.Default.DevicesOther,null,tint=FqGold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "ادامه روی دستگاه دیگر",
                        fontSize=19.sp,
                        fontWeight=FontWeight.Black
                    )
                    Text(
                        "پخش از همین ثانیه به یکی از دستگاه‌های حساب منتقل می‌شه.",
                        color=FqMuted,
                        fontSize=8.sp
                    )
                }
                IconButton(onClick={refresh++}) {
                    Icon(Icons.Default.Refresh,null)
                }
            }

            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(15.dp),
                modifier=Modifier.fillMaxWidth().padding(top=12.dp)
            ) {
                Row(
                    Modifier.padding(11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Schedule,null,tint=FqGold)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "موقعیت پخش: "+formatSceneTime(positionMs),
                        fontSize=9.sp,
                        modifier=Modifier.weight(1f)
                    )
                    Text("۵ دقیقه اعتبار",color=FqMuted,fontSize=7.sp)
                }
            }

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                )
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=8.dp)
                )
            }

            val otherDevices=devices.filterNot { it.current }
            if(!loading && otherDevices.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical=34.dp),
                    horizontalAlignment=Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Phonelink,
                        null,
                        tint=FqMuted,
                        modifier=Modifier.size(42.dp)
                    )
                    Text(
                        "دستگاه دیگری هنوز دیده نشده",
                        fontSize=11.sp,
                        fontWeight=FontWeight.Bold,
                        modifier=Modifier.padding(top=8.dp)
                    )
                    Text(
                        "Filmiqoo رو روی دستگاه دوم با همین حساب باز کن؛ چند ثانیه بعد اینجا ظاهر می‌شه.",
                        color=FqMuted,
                        fontSize=8.sp,
                        lineHeight=14.sp,
                        modifier=Modifier.padding(top=4.dp)
                    )
                }
            } else {
                Text(
                    "دستگاه‌های حساب",
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(top=14.dp,bottom=6.dp)
                )

                LazyColumn(
                    modifier=Modifier.heightIn(max=420.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    items(otherDevices,key={it.deviceId}) { device ->
                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(17.dp),
                            modifier=Modifier.fillMaxWidth()
                                .clickable(enabled=sendingTo==null) {
                                    sendingTo=device.deviceId
                                    scope.launch {
                                        runCatching {
                                            repository.send(
                                                targetDeviceId=device.deviceId,
                                                mediaVersionId=mediaVersionId,
                                                positionMs=positionMs
                                            )
                                        }.onSuccess {
                                            message="ارسال شد به "+device.deviceName
                                        }.onFailure {
                                            error=it.message ?: "ارسال Handoff ناموفق بود"
                                        }
                                        sendingTo=null
                                    }
                                }
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(46.dp)
                                        .background(
                                            if(device.online)FqGreen.copy(alpha=.12f)
                                            else FqBg,
                                            CircleShape
                                        ),
                                    contentAlignment=Alignment.Center
                                ) {
                                    Icon(
                                        if(device.platform=="android")Icons.Default.PhoneAndroid
                                        else Icons.Default.Devices,
                                        null,
                                        tint=if(device.online)FqGreen else FqMuted
                                    )
                                }
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.deviceName,
                                        fontSize=10.sp,
                                        fontWeight=FontWeight.Bold,
                                        maxLines=1,
                                        overflow=TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if(device.online)"Online" else "اخیراً فعال بوده",
                                        color=if(device.online)FqGreen else FqMuted,
                                        fontSize=7.sp,
                                        modifier=Modifier.padding(top=2.dp)
                                    )
                                }
                                if(sendingTo==device.deviceId) {
                                    CircularProgressIndicator(
                                        color=FqGold,
                                        strokeWidth=2.dp,
                                        modifier=Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.SendToMobile,
                                        null,
                                        tint=FqGold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            message?.let {
                Surface(
                    color=FqGreen.copy(alpha=.10f),
                    shape=RoundedCornerShape(13.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                ) {
                    Row(
                        Modifier.padding(10.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle,null,tint=FqGreen)
                        Spacer(Modifier.width(7.dp))
                        Text(it,color=FqGreen,fontSize=8.sp)
                    }
                }
            }

            Text(
                "وقتی Filmiqoo روی دستگاه مقصد باز باشه، درخواست دریافت می‌شه و با تأیید کاربر دقیقاً از همین زمان ادامه می‌ده.",
                color=FqMuted,
                fontSize=7.sp,
                lineHeight=13.sp,
                modifier=Modifier.padding(top=12.dp)
            )
        }
    }
}
