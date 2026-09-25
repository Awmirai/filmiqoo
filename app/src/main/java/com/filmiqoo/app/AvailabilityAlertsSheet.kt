package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class AvailabilityAlertOption(
    val key:String,
    val title:String,
    val subtitle:String,
    val icon:ImageVector,
    val available:(AvailabilityFlags)->Boolean,
    val subscribed:(AvailabilityFlags)->Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvailabilityAlertsSheet(
    mediaId:String,
    backend:BackendRepository,
    onDismiss:()->Unit
) {
    val repo=remember { AvailabilityAlertsRepository(backend) }
    val scope=rememberCoroutineScope()

    var state by remember(mediaId) { mutableStateOf<AvailabilityAlertStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val options=remember {
        listOf(
            AvailabilityAlertOption(
                key="persian_dub",
                title="دوبله فارسی",
                subtitle="وقتی ترک صوتی فارسی اضافه شد خبرم کن",
                icon=Icons.Default.RecordVoiceOver,
                available={it.persianDub},
                subscribed={it.persianDub}
            ),
            AvailabilityAlertOption(
                key="persian_subtitle",
                title="زیرنویس فارسی",
                subtitle="وقتی Subtitle فارسی اضافه شد خبرم کن",
                icon=Icons.Default.Subtitles,
                available={it.persianSubtitle},
                subscribed={it.persianSubtitle}
            ),
            AvailabilityAlertOption(
                key="uhd_4k",
                title="نسخه 4K",
                subtitle="وقتی 2160p / 4K آماده شد خبرم کن",
                icon=Icons.Default.HighQuality,
                available={it.uhd4k},
                subscribed={it.uhd4k}
            ),
            AvailabilityAlertOption(
                key="hdr",
                title="نسخه HDR",
                subtitle="وقتی HDR / Dolby Vision اضافه شد خبرم کن",
                icon=Icons.Default.HdrOn,
                available={it.hdr},
                subscribed={it.hdr}
            )
        )
    }

    suspend fun reload() {
        loading=true
        runCatching { repo.status(mediaId) }
            .onSuccess {
                state=it
                error=null
            }
            .onFailure { error=it.message ?: "خطا در دریافت وضعیت" }
        loading=false
    }

    LaunchedEffect(mediaId) { reload() }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=30.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(
                        FqGold.copy(alpha=.12f),
                        RoundedCornerShape(14.dp)
                    ),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(Icons.Default.NotificationsActive,null,tint=FqGold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("اعلان نسخه و زبان",fontSize=18.sp,fontWeight=FontWeight.Black)
                    Text(
                        state?.title ?: "Availability Alerts",
                        color=FqMuted,
                        fontSize=11.sp
                    )
                }
                IconButton(onClick=onDismiss){Icon(Icons.Default.Close,null)}
            }

            Text(
                "فقط برای گزینه‌هایی که هنوز موجود نیستند Reminder فعال می‌شه. به محض آماده‌شدن، یک اعلان داخل Filmiqoo می‌گیری.",
                color=FqMuted,
                fontSize=11.sp,
                lineHeight=14.sp,
                modifier=Modifier.padding(top=12.dp,bottom=8.dp)
            )

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)
                )
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=11.sp,
                    modifier=Modifier.padding(vertical=6.dp)
                )
            }

            state?.let { current ->
                options.forEach { option ->
                    val available=option.available(current.available)
                    val subscribed=option.subscribed(current.subscribed)
                    val busy=busyKey==option.key

                    Surface(
                        color=when {
                            available -> FqGreen.copy(alpha=.08f)
                            subscribed -> FqGold.copy(alpha=.08f)
                            else -> FqSurface2
                        },
                        shape=RoundedCornerShape(17.dp),
                        modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)
                            .clickable(enabled=!available && !busy) {
                                busyKey=option.key
                                scope.launch {
                                    runCatching {
                                        repo.set(
                                            mediaId=mediaId,
                                            alertType=option.key,
                                            enabled=!subscribed
                                        )
                                    }.onSuccess {
                                        reload()
                                    }.onFailure {
                                        error=it.message
                                    }
                                    busyKey=null
                                }
                            }
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(42.dp).background(
                                    when {
                                        available -> FqGreen.copy(alpha=.14f)
                                        subscribed -> FqGold.copy(alpha=.14f)
                                        else -> FqBg
                                    },
                                    RoundedCornerShape(13.dp)
                                ),
                                contentAlignment=Alignment.Center
                            ) {
                                Icon(
                                    option.icon,
                                    null,
                                    tint=when {
                                        available -> FqGreen
                                        subscribed -> FqGold
                                        else -> Color.White
                                    }
                                )
                            }

                            Spacer(Modifier.width(10.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.title,
                                    fontSize=12.sp,
                                    fontWeight=FontWeight.Bold
                                )
                                Text(
                                    when {
                                        available -> "همین الان موجود است"
                                        subscribed -> "Reminder فعال است"
                                        else -> option.subtitle
                                    },
                                    color=when {
                                        available -> FqGreen
                                        subscribed -> FqGold
                                        else -> FqMuted
                                    },
                                    fontSize=11.sp,
                                    modifier=Modifier.padding(top=3.dp)
                                )
                            }

                            if(busy) {
                                CircularProgressIndicator(
                                    color=FqGold,
                                    strokeWidth=2.dp,
                                    modifier=Modifier.size(20.dp)
                                )
                            } else if(available) {
                                Icon(Icons.Default.CheckCircle,null,tint=FqGreen)
                            } else {
                                Switch(
                                    checked=subscribed,
                                    onCheckedChange=null
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(16.dp),
                modifier=Modifier.fillMaxWidth().padding(top=10.dp)
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info,null,tint=FqMuted,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "تشخیص Availability از نسخه‌های واقعی Catalog انجام می‌شه؛ وضعیت ساختگی نمایش داده نمی‌شه.",
                        color=FqMuted,
                        fontSize=11.sp,
                        lineHeight=13.sp
                    )
                }
            }
        }
    }
}
