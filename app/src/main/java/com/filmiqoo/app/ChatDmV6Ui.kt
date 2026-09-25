package com.filmiqoo.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipeToReplyMessage(
    key:String,
    enabled:Boolean,
    onReply:()->Unit,
    content:@Composable ()->Unit
) {
    var drag by remember(key) { mutableFloatStateOf(0f) }
    val threshold=76f

    Box(
        Modifier.fillMaxWidth()
            .pointerInput(key,enabled) {
                if(!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onHorizontalDrag={change,amount->
                        change.consume()
                        drag=(drag+amount).coerceIn(-120f,120f)
                    },
                    onDragEnd={
                        if(abs(drag)>=threshold) onReply()
                        drag=0f
                    },
                    onDragCancel={drag=0f}
                )
            }
    ) {
        if(abs(drag)>18f) {
            Box(
                Modifier.size(34.dp)
                    .align(
                        if(drag>0f) Alignment.CenterStart
                        else Alignment.CenterEnd
                    )
                    .background(FqGold.copy(alpha=.14f),CircleShape),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    Icons.Default.Reply,
                    null,
                    tint=FqGold,
                    modifier=Modifier.size(18.dp)
                )
            }
        }
        Box(
            Modifier.offset {
                IntOffset(drag.roundToInt(),0)
            }
        ) {
            content()
        }
    }
}

@Composable
fun RichMessageAttachment(
    message:RoomMessageItem
) {
    val context=LocalContext.current
    when(message.type) {
        "document" -> {
            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(14.dp),
                modifier=Modifier.fillMaxWidth().padding(top=7.dp),
                onClick={
                    val url=message.attachmentUrl
                    if(!url.isNullOrBlank()) {
                        val uri=Uri.parse(url)
                        val intent=Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri,message.attachmentMime ?: "*/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .recoverCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW,uri)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                    }
                }
            ) {
                Row(
                    Modifier.padding(11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                            .background(FqGold.copy(alpha=.12f)),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.InsertDriveFile,null,tint=FqGold)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            message.attachmentFileName ?: "فایل",
                            fontSize=11.sp,
                            maxLines=2
                        )
                        Text(
                            listOfNotNull(
                                message.attachmentMime,
                                formatChatFileSize(message.attachmentSizeBytes)
                                    .takeIf(String::isNotBlank)
                            ).joinToString(" • "),
                            color=FqMuted,
                            fontSize=11.sp
                        )
                    }
                    Icon(Icons.Default.OpenInNew,null,tint=FqMuted)
                }
            }
        }

        "location" -> {
            val lat=message.locationLatitude
            val lng=message.locationLongitude
            if(lat!=null && lng!=null) {
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=7.dp),
                    onClick={
                        val label=message.locationLabel.orEmpty()
                        val uri=Uri.parse(
                            "geo:$lat,$lng?q=$lat,$lng("+
                                Uri.encode(label.ifBlank { "Location" })+")"
                        )
                        val intent=Intent(Intent.ACTION_VIEW,uri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                ) {
                    Row(
                        Modifier.padding(11.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(FqGold.copy(alpha=.13f)),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.LocationOn,null,tint=FqGold)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                message.locationLabel ?: "موقعیت مکانی",
                                fontSize=12.sp
                            )
                            Text(
                                String.format(
                                    java.util.Locale.US,
                                    "%.5f, %.5f",
                                    lat,lng
                                ),
                                color=FqMuted,
                                fontSize=11.sp
                            )
                        }
                        Icon(Icons.Default.OpenInNew,null,tint=FqMuted)
                    }
                }
            }
        }

        "contact" -> {
            val phone=message.contactPhone.orEmpty()
            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(14.dp),
                modifier=Modifier.fillMaxWidth().padding(top=7.dp),
                onClick={
                    if(phone.isNotBlank()) {
                        val intent=Intent(
                            Intent.ACTION_DIAL,
                            Uri.parse("tel:"+Uri.encode(phone))
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                }
            ) {
                Row(
                    Modifier.padding(11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(FqGold.copy(alpha=.13f)),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.Person,null,tint=FqGold)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(message.contactName ?: "مخاطب",fontSize=12.sp)
                        if(phone.isNotBlank()) {
                            Text(phone,color=FqMuted,fontSize=11.sp)
                        }
                        message.contactEmail?.takeIf(String::isNotBlank)?.let {
                            Text(it,color=FqMuted,fontSize=11.sp)
                        }
                    }
                    if(phone.isNotBlank()) {
                        Icon(Icons.Default.Phone,null,tint=FqGold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAttachmentMenuSheet(
    onDismiss:()->Unit,
    onMedia:()->Unit,
    onDocument:()->Unit,
    onLocation:()->Unit,
    onContact:()->Unit
) {
    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start=14.dp,end=14.dp,bottom=24.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("ارسال پیوست",fontSize=19.sp,modifier=Modifier.weight(1f))
                IconButton(onClick=onDismiss){Icon(Icons.Default.Close,null)}
            }
            ChatAttachmentOption(Icons.Default.AddPhotoAlternate,"عکس یا ویدیو","از گالری") {
                onDismiss(); onMedia()
            }
            ChatAttachmentOption(Icons.Default.InsertDriveFile,"فایل / Document","PDF، Word، Excel، ZIP و متن") {
                onDismiss(); onDocument()
            }
            ChatAttachmentOption(Icons.Default.LocationOn,"موقعیت مکانی","ارسال مختصات و نام مکان") {
                onDismiss(); onLocation()
            }
            ChatAttachmentOption(Icons.Default.Person,"مخاطب","نام، شماره و ایمیل") {
                onDismiss(); onContact()
            }
        }
    }
}

@Composable
private fun ChatAttachmentOption(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    subtitle:String,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface2,
        shape=RoundedCornerShape(15.dp),
        onClick=onClick,
        modifier=Modifier.fillMaxWidth().padding(top=7.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(FqGold.copy(alpha=.12f)),
                contentAlignment=Alignment.Center
            ) {
                Icon(icon,null,tint=FqGold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title,fontSize=12.sp)
                Text(subtitle,color=FqMuted,fontSize=11.sp)
            }
        }
    }
}

@Composable
fun LocationMessageDialog(
    onDismiss:()->Unit,
    onSend:(Double,Double,String)->Unit
) {
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val lat=latitude.replace(',','.').toDoubleOrNull()
    val lng=longitude.replace(',','.').toDoubleOrNull()
    val valid=lat!=null && lng!=null && lat in -90.0..90.0 && lng in -180.0..180.0

    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.LocationOn,null,tint=FqGold)},
        title={Text("ارسال موقعیت")},
        text={
            Column {
                OutlinedTextField(
                    value=label,
                    onValueChange={label=it.take(120)},
                    label={Text("نام مکان (اختیاری)")},
                    singleLine=true
                )
                OutlinedTextField(
                    value=latitude,
                    onValueChange={latitude=it.take(16)},
                    label={Text("Latitude")},
                    singleLine=true,
                    modifier=Modifier.padding(top=7.dp)
                )
                OutlinedTextField(
                    value=longitude,
                    onValueChange={longitude=it.take(16)},
                    label={Text("Longitude")},
                    singleLine=true,
                    modifier=Modifier.padding(top=7.dp)
                )
            }
        },
        confirmButton={
            Button(
                enabled=valid,
                onClick={
                    if(valid) onSend(lat!!,lng!!,label.trim())
                },
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ارسال",color=Color.Black) }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}

@Composable
fun ContactMessageDialog(
    onDismiss:()->Unit,
    onSend:(String,String,String)->Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.Person,null,tint=FqGold)},
        title={Text("ارسال مخاطب")},
        text={
            Column {
                OutlinedTextField(
                    value=name,
                    onValueChange={name=it.take(100)},
                    label={Text("نام")},
                    singleLine=true
                )
                OutlinedTextField(
                    value=phone,
                    onValueChange={phone=it.take(40)},
                    label={Text("شماره تماس")},
                    singleLine=true,
                    modifier=Modifier.padding(top=7.dp)
                )
                OutlinedTextField(
                    value=email,
                    onValueChange={email=it.take(120)},
                    label={Text("ایمیل (اختیاری)")},
                    singleLine=true,
                    modifier=Modifier.padding(top=7.dp)
                )
            }
        },
        confirmButton={
            Button(
                enabled=name.trim().isNotEmpty() && phone.trim().isNotEmpty(),
                onClick={onSend(name.trim(),phone.trim(),email.trim())},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ارسال",color=Color.Black) }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesSheet(
    roomId:String,
    currentText:String,
    spoiler:Boolean,
    replyToMessageId:String?,
    messaging:MessagingRepository,
    onDismiss:()->Unit,
    onScheduled:()->Unit,
    onError:(String)->Unit
) {
    val scope=rememberCoroutineScope()
    var items by remember(roomId) { mutableStateOf<List<ScheduledRoomMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading=true
        runCatching { messaging.scheduledMessages(roomId) }
            .onSuccess { items=it }
            .onFailure { onError(it.message ?: "خطا در دریافت پیام‌های زمان‌بندی‌شده") }
        loading=false
    }

    LaunchedEffect(roomId) { reload() }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start=14.dp,end=14.dp,bottom=24.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule,null,tint=FqGold)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text("پیام زمان‌بندی‌شده",fontSize=19.sp)
                    Text("ارسال خودکار در زمان انتخابی",color=FqMuted,fontSize=11.sp)
                }
                IconButton(onClick=onDismiss){Icon(Icons.Default.Close,null)}
            }

            if(currentText.trim().isNotEmpty()) {
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                ) {
                    Text(
                        currentText.trim(),
                        maxLines=3,
                        fontSize=11.sp,
                        modifier=Modifier.padding(10.dp)
                    )
                }

                Text(
                    "ارسال در:",
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=10.dp,bottom=5.dp)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(6.dp)
                ) {
                    schedulePresets().forEach { preset ->
                        AssistChip(
                            enabled=!busy,
                            onClick={
                                busy=true
                                scope.launch {
                                    runCatching {
                                        messaging.scheduleTextMessage(
                                            roomId=roomId,
                                            body=currentText,
                                            spoiler=spoiler,
                                            replyToMessageId=replyToMessageId,
                                            scheduledAt=preset.second.toString()
                                        )
                                    }.onSuccess {
                                        onScheduled()
                                        reload()
                                    }.onFailure {
                                        onError(it.message ?: "زمان‌بندی پیام ناموفق بود")
                                    }
                                    busy=false
                                }
                            },
                            label={Text(preset.first,fontSize=11.sp)}
                        )
                    }
                }
            } else {
                Text(
                    "برای ساخت پیام زمان‌بندی‌شده اول متن پیام را بنویس.",
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(vertical=10.dp)
                )
            }

            HorizontalDivider(
                color=Color.White.copy(alpha=.08f),
                modifier=Modifier.padding(vertical=12.dp)
            )

            if(loading) {
                LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
            } else if(items.isEmpty()) {
                Text(
                    "پیام زمان‌بندی‌شده‌ای نداری.",
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(vertical=12.dp)
                )
            } else {
                items.forEach { item ->
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Schedule,null,tint=FqGold)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.body.ifBlank { "پیام زمان‌بندی‌شده" },
                                    fontSize=11.sp,
                                    maxLines=2
                                )
                                Text(
                                    formatScheduledTime(item.scheduledAt),
                                    color=FqMuted,
                                    fontSize=11.sp
                                )
                            }
                            IconButton(
                                onClick={
                                    scope.launch {
                                        runCatching {
                                            messaging.cancelScheduledMessage(roomId,item.id)
                                        }.onSuccess { reload() }
                                            .onFailure {
                                                onError(it.message ?: "لغو پیام ناموفق بود")
                                            }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnreadMessagesDivider(count:Long) {
    Row(
        Modifier.fillMaxWidth().padding(vertical=3.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        HorizontalDivider(
            color=FqGold.copy(alpha=.35f),
            modifier=Modifier.weight(1f)
        )
        Surface(
            color=FqGold.copy(alpha=.13f),
            shape=CircleShape,
            modifier=Modifier.padding(horizontal=8.dp)
        ) {
            Text(
                if(count>0) count.toString()+" پیام خوانده‌نشده" else "پیام‌های جدید",
                color=FqGold,
                fontSize=11.sp,
                modifier=Modifier.padding(horizontal=9.dp,vertical=4.dp)
            )
        }
        HorizontalDivider(
            color=FqGold.copy(alpha=.35f),
            modifier=Modifier.weight(1f)
        )
    }
}

@Composable
fun BulkMessageSelectionBar(
    count:Int,
    onForward:()->Unit,
    onDelete:()->Unit,
    onClear:()->Unit
) {
    Surface(
        color=FqSurface2,
        tonalElevation=6.dp,
        modifier=Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onClear){Icon(Icons.Default.Close,null)}
            Text(
                count.toString()+" انتخاب",
                color=FqGold,
                fontSize=12.sp,
                modifier=Modifier.weight(1f)
            )
            IconButton(onClick=onForward) {
                Icon(Icons.Default.Forward,null,tint=FqGold)
            }
            IconButton(onClick=onDelete) {
                Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)
            }
        }
    }
}

private fun schedulePresets():List<Pair<String,Instant>> {
    val now=java.time.ZonedDateTime.now()
    val tonight=LocalDateTime.of(
        LocalDate.now(),
        LocalTime.of(21,0)
    ).atZone(ZoneId.systemDefault()).let {
        if(it.isAfter(now)) it else it.plusDays(1)
    }
    val tomorrowNine=LocalDateTime.of(
        LocalDate.now().plusDays(1),
        LocalTime.of(9,0)
    ).atZone(ZoneId.systemDefault())
    return listOf(
        "۱۰ دقیقه" to Instant.now().plusSeconds(10*60L),
        "۱ ساعت" to Instant.now().plusSeconds(60*60L),
        "امشب ۲۱" to tonight.toInstant(),
        "فردا ۹" to tomorrowNine.toInstant()
    )
}

private fun formatScheduledTime(raw:String):String {
    return runCatching {
        val instant=Instant.parse(raw)
        val local=instant.atZone(ZoneId.systemDefault())
        String.format(
            java.util.Locale.US,
            "%04d/%02d/%02d  %02d:%02d",
            local.year,local.monthValue,local.dayOfMonth,
            local.hour,local.minute
        )
    }.getOrDefault(raw.take(16))
}

private fun formatChatFileSize(bytes:Long):String {
    if(bytes<=0L) return ""
    val kb=bytes/1024.0
    val mb=kb/1024.0
    return if(mb>=1.0) {
        String.format(java.util.Locale.US,"%.1f MB",mb)
    } else {
        String.format(java.util.Locale.US,"%.0f KB",kb)
    }
}
