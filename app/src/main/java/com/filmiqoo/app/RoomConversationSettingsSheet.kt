package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomConversationSettingsSheet(
    roomId:String,
    messaging:MessagingRepository,
    onDismiss:()->Unit,
    onTitleChanged:(String)->Unit,
    onLeave:()->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var state by remember(roomId) { mutableStateOf<RoomConversationSettings?>(null) }
    var invite by remember(roomId) { mutableStateOf<RoomInviteInfo?>(null) }
    var loading by remember(roomId) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var leaveConfirm by remember { mutableStateOf(false) }
    var regenerateConfirm by remember { mutableStateOf(false) }

    var name by remember(roomId) { mutableStateOf("") }
    var topic by remember(roomId) { mutableStateOf("") }
    var visibility by remember(roomId) { mutableStateOf("public") }
    var slowMode by remember(roomId) { mutableIntStateOf(0) }
    var slowMenu by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading=true
        runCatching { messaging.roomSettings(roomId) }
            .onSuccess { loaded ->
                state=loaded
                name=loaded.name
                topic=loaded.topic
                visibility=loaded.visibility
                slowMode=loaded.slowModeSeconds
                error=null

                if(loaded.canManage && loaded.type=="group") {
                    runCatching { messaging.roomInvite(roomId) }
                        .onSuccess { invite=it }
                        .onFailure { error=it.message }
                } else {
                    invite=null
                }
            }
            .onFailure { error=it.message }
        loading=false
    }

    LaunchedEffect(roomId) { reload() }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start=14.dp,end=14.dp,bottom=24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Settings,null,tint=FqGold)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("تنظیمات گفتگو",fontSize=20.sp)
                    Text(
                        state?.let { roomTypeLabel(it.type) } ?: "Room",
                        color=FqMuted,
                        fontSize=8.sp
                    )
                }
                IconButton(onClick=onDismiss) {
                    Icon(Icons.Default.Close,null)
                }
            }

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=8.sp,
                    modifier=Modifier.fillMaxWidth()
                        .background(FqDanger.copy(alpha=.08f),RoundedCornerShape(10.dp))
                        .padding(9.dp)
                )
            }

            state?.let { current ->
                if(current.myRole.isNotBlank()) {
                    Text(
                        "اعلان‌ها",
                        color=Color.White,
                        fontSize=12.sp,
                        modifier=Modifier.padding(top=12.dp,bottom=6.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "all" to "همه",
                            "mentions" to "فقط منشن",
                            "off" to "بی‌صدا"
                        ).forEach { option ->
                            FilterChip(
                                selected=current.notificationLevel==option.first,
                                onClick={
                                    scope.launch {
                                        saving=true
                                        runCatching {
                                            messaging.updateRoomPreferences(
                                                roomId=roomId,
                                                notificationLevel=option.first
                                            )
                                        }.onSuccess { prefs ->
                                            state=current.copy(
                                                notificationLevel=prefs.notificationLevel,
                                                archived=prefs.archived
                                            )
                                            error=null
                                        }.onFailure { error=it.message }
                                        saving=false
                                    }
                                },
                                label={Text(option.second,fontSize=8.sp)},
                                leadingIcon={
                                    Icon(
                                        when(option.first) {
                                            "all" -> Icons.Default.NotificationsActive
                                            "mentions" -> Icons.Default.AlternateEmail
                                            else -> Icons.Default.NotificationsOff
                                        },
                                        null,
                                        modifier=Modifier.size(15.dp)
                                    )
                                }
                            )
                        }
                    }

                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal=12.dp,vertical=8.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Archive,null,tint=FqGold)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("آرشیو گفتگو",fontSize=10.sp)
                                Text(
                                    "از Inbox اصلی پنهان می‌شود؛ پیام‌ها حذف نمی‌شوند.",
                                    color=FqMuted,
                                    fontSize=7.sp
                                )
                            }
                            Switch(
                                checked=current.archived,
                                onCheckedChange={checked->
                                    scope.launch {
                                        saving=true
                                        runCatching {
                                            messaging.updateRoomPreferences(
                                                roomId=roomId,
                                                archived=checked
                                            )
                                        }.onSuccess { prefs ->
                                            state=current.copy(
                                                notificationLevel=prefs.notificationLevel,
                                                archived=prefs.archived
                                            )
                                            error=null
                                        }.onFailure { error=it.message }
                                        saving=false
                                    }
                                }
                            )
                        }
                    }
                }

                if(current.canManage && current.type=="group") {
                    HorizontalDivider(
                        color=Color.White.copy(alpha=.08f),
                        modifier=Modifier.padding(vertical=14.dp)
                    )
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.AdminPanelSettings,null,tint=FqGold)
                        Spacer(Modifier.width(7.dp))
                        Text("مدیریت گروه",fontSize=12.sp)
                    }

                    OutlinedTextField(
                        value=name,
                        onValueChange={name=it.take(100)},
                        label={Text("نام گروه")},
                        singleLine=true,
                        modifier=Modifier.fillMaxWidth().padding(top=9.dp),
                        shape=RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value=topic,
                        onValueChange={topic=it.take(300)},
                        label={Text("موضوع / توضیح")},
                        minLines=2,
                        maxLines=4,
                        modifier=Modifier.fillMaxWidth().padding(top=8.dp),
                        shape=RoundedCornerShape(14.dp)
                    )

                    Text(
                        "دسترسی",
                        color=FqMuted,
                        fontSize=8.sp,
                        modifier=Modifier.padding(top=10.dp,bottom=4.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "public" to "عمومی",
                            "private" to "خصوصی",
                            "invite" to "با دعوت"
                        ).forEach { option ->
                            FilterChip(
                                selected=visibility==option.first,
                                onClick={visibility=option.first},
                                label={Text(option.second,fontSize=8.sp)}
                            )
                        }
                    }

                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal=12.dp,vertical=6.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Timer,null,tint=FqGold)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Slow Mode",fontSize=10.sp)
                                Text(
                                    slowModeLabel(slowMode),
                                    color=FqMuted,
                                    fontSize=7.sp
                                )
                            }
                            Box {
                                TextButton(onClick={slowMenu=true}) {
                                    Text(slowModeLabel(slowMode),fontSize=8.sp)
                                    Icon(Icons.Default.KeyboardArrowDown,null)
                                }
                                DropdownMenu(
                                    expanded=slowMenu,
                                    onDismissRequest={slowMenu=false}
                                ) {
                                    listOf(0,5,10,30,60,300).forEach { seconds ->
                                        DropdownMenuItem(
                                            text={Text(slowModeLabel(seconds))},
                                            onClick={
                                                slowMode=seconds
                                                slowMenu=false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        enabled=!saving && name.trim().length>=2,
                        onClick={
                            scope.launch {
                                saving=true
                                runCatching {
                                    messaging.updateRoomSettings(
                                        roomId=roomId,
                                        name=name.trim(),
                                        topic=topic.trim(),
                                        visibility=visibility,
                                        slowModeSeconds=slowMode
                                    )
                                }.onSuccess { updated ->
                                    state=updated
                                    onTitleChanged(updated.name)
                                    error=null
                                }.onFailure { error=it.message }
                                saving=false
                            }
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        modifier=Modifier.fillMaxWidth().padding(top=10.dp),
                        shape=RoundedCornerShape(14.dp)
                    ) {
                        if(saving) {
                            CircularProgressIndicator(
                                color=Color.Black,
                                strokeWidth=2.dp,
                                modifier=Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("ذخیره تنظیمات گروه",color=Color.Black)
                    }

                    HorizontalDivider(
                        color=Color.White.copy(alpha=.08f),
                        modifier=Modifier.padding(vertical=14.dp)
                    )

                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Link,null,tint=FqGold)
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text("لینک دعوت گروه",fontSize=12.sp)
                            Text(
                                invite?.let {
                                    if(it.usageCount>0)
                                        it.usageCount.toString()+" بار استفاده شده"
                                    else "هنوز استفاده نشده"
                                } ?: "در حال ساخت...",
                                color=FqMuted,
                                fontSize=7.sp
                            )
                        }
                    }

                    invite?.let { currentInvite ->
                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(12.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                        ) {
                            Text(
                                currentInvite.deepLink,
                                color=FqGold,
                                fontSize=8.sp,
                                maxLines=2,
                                modifier=Modifier.padding(10.dp)
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top=7.dp),
                            horizontalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick={
                                    FilmiqooDeepLinks.share(
                                        context,
                                        "دعوت به "+current.name,
                                        currentInvite.deepLink
                                    )
                                },
                                modifier=Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share,null)
                                Spacer(Modifier.width(5.dp))
                                Text("اشتراک لینک",fontSize=8.sp)
                            }
                            OutlinedButton(
                                onClick={regenerateConfirm=true},
                                modifier=Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh,null)
                                Spacer(Modifier.width(5.dp))
                                Text("لینک جدید",fontSize=8.sp)
                            }
                        }
                    }
                }

                if(current.type!="dm" && current.myRole.isNotBlank()) {
                    HorizontalDivider(
                        color=Color.White.copy(alpha=.08f),
                        modifier=Modifier.padding(vertical=14.dp)
                    )
                    OutlinedButton(
                        onClick={leaveConfirm=true},
                        colors=ButtonDefaults.outlinedButtonColors(contentColor=FqDanger),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Logout,null)
                        Spacer(Modifier.width(6.dp))
                        Text("ترک گروه / Room")
                    }
                    if(current.myRole=="owner" && current.members>1) {
                        Text(
                            "برای خروج مالک، ابتدا مالکیت را از صفحه اعضا به شخص دیگری منتقل کن.",
                            color=FqMuted,
                            fontSize=7.sp,
                            modifier=Modifier.padding(top=5.dp)
                        )
                    }
                }
            }
        }
    }

    if(regenerateConfirm) {
        AlertDialog(
            onDismissRequest={regenerateConfirm=false},
            icon={Icon(Icons.Default.LinkOff,null,tint=FqGold)},
            title={Text("ساخت لینک دعوت جدید؟")},
            text={Text("لینک قبلی بلافاصله از کار می‌افتد.")},
            confirmButton={
                Button(
                    onClick={
                        regenerateConfirm=false
                        scope.launch {
                            saving=true
                            runCatching { messaging.regenerateRoomInvite(roomId) }
                                .onSuccess {
                                    invite=it
                                    error=null
                                }
                                .onFailure { error=it.message }
                            saving=false
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                ) { Text("ساخت لینک جدید",color=Color.Black) }
            },
            dismissButton={
                TextButton(onClick={regenerateConfirm=false}){Text("لغو")}
            }
        )
    }

    if(leaveConfirm) {
        AlertDialog(
            onDismissRequest={leaveConfirm=false},
            icon={Icon(Icons.Default.Logout,null,tint=FqDanger)},
            title={Text("ترک این گفتگو؟")},
            text={Text("از لیست اعضا خارج می‌شوی. پیام‌های قبلی گروه حذف نمی‌شوند.")},
            confirmButton={
                Button(
                    onClick={
                        leaveConfirm=false
                        scope.launch {
                            saving=true
                            runCatching { messaging.leaveRoom(roomId) }
                                .onSuccess { onLeave() }
                                .onFailure { error=it.message }
                            saving=false
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=FqDanger)
                ) { Text("ترک",color=Color.White) }
            },
            dismissButton={
                TextButton(onClick={leaveConfirm=false}){Text("لغو")}
            }
        )
    }
}

private fun roomTypeLabel(type:String):String=when(type) {
    "dm" -> "پیام خصوصی"
    "group" -> "گروه"
    "watch_party" -> "Watch Party"
    "episode" -> "Episode Room"
    else -> "Community Room"
}

private fun slowModeLabel(seconds:Int):String=when(seconds) {
    0 -> "خاموش"
    5 -> "۵ ثانیه"
    10 -> "۱۰ ثانیه"
    30 -> "۳۰ ثانیه"
    60 -> "۱ دقیقه"
    300 -> "۵ دقیقه"
    else -> seconds.toString()+" ثانیه"
}
