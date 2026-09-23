package com.filmiqoo.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SafetyCenterScreen(
    backend:BackendRepository,
    onBack:()->Unit,
    onCreator:(Creator)->Unit
) {
    val repo=remember { SafetyRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(SafetyState(emptyList(),emptyList())) }
    var tab by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.state() }
            .onSuccess { state=it }
            .onFailure { error=it.message }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("مرکز ایمنی",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text("Block، Mute و کنترل تجربه اجتماعی",color=FqMuted,fontSize=8.sp)
            }
            IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
        }

        Surface(
            color=FqGold.copy(alpha=.08f),
            shape=RoundedCornerShape(18.dp),
            modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=7.dp)
        ) {
            Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Shield,null,tint=FqGold)
                Spacer(Modifier.width(9.dp))
                Text(
                    "Block ارتباط دوطرفه، Follow و DM را قطع می‌کند. Mute فقط محتوای کاربر را از تجربه تو کنار می‌گذارد.",
                    color=Color.White.copy(alpha=.78f),
                    fontSize=8.sp,
                    lineHeight=14.sp
                )
            }
        }

        TabRow(
            selectedTabIndex=tab,
            containerColor=FqBg,
            contentColor=FqGold
        ) {
            Tab(
                selected=tab==0,
                onClick={tab=0},
                text={Text("Block شده ("+state.blocked.size+")",fontSize=8.sp)}
            )
            Tab(
                selected=tab==1,
                onClick={tab=1},
                text={Text("Mute شده ("+state.muted.size+")",fontSize=8.sp)}
            )
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=8.sp,
                modifier=Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        val users=if(tab==0)state.blocked else state.muted
        if(!loading && users.isEmpty()) {
            PremiumEmptyState(
                icon=if(tab==0)Icons.Default.Block else Icons.Default.VolumeOff,
                title=if(tab==0)"کسی Block نشده" else "کسی Mute نشده",
                body=if(tab==0)
                    "کاربرانی که Block کنی اینجا قابل مدیریت‌اند."
                else
                    "کاربرانی که Mute کنی اینجا قابل مدیریت‌اند."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp),
                modifier=Modifier.weight(1f)
            ) {
                items(users,key={it.id}) { user ->
                    SafetyUserRow(
                        user=user,
                        actionLabel=if(tab==0)"Unblock" else "Unmute",
                        actionIcon=if(tab==0)Icons.Default.LockOpen else Icons.Default.VolumeUp,
                        onUser={onCreator(user.asCreator())},
                        onAction={
                            scope.launch {
                                runCatching {
                                    if(tab==0) repo.toggleBlock(user.id)
                                    else repo.toggleMute(user.id)
                                }.onSuccess { refresh++ }
                                    .onFailure { error=it.message }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyUserRow(
    user:SafetyUser,
    actionLabel:String,
    actionIcon:androidx.compose.ui.graphics.vector.ImageVector,
    onUser:()->Unit,
    onAction:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(11.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            RemoteImage(
                user.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(50.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(9.dp))
            Column(
                Modifier.weight(1f).clickable { onUser() }
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(user.displayName,fontSize=10.sp,fontWeight=FontWeight.Bold)
                    if(user.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Verified,
                            null,
                            tint=Color(0xFF4AB7FF),
                            modifier=Modifier.size(13.dp)
                        )
                    }
                }
                Text("@"+user.username,color=FqMuted,fontSize=7.sp)
            }
            OutlinedButton(
                onClick=onAction,
                contentPadding=PaddingValues(horizontal=10.dp,vertical=5.dp)
            ) {
                Icon(actionIcon,null,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(actionLabel,fontSize=7.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyActionSheet(
    backend:BackendRepository,
    targetType:String,
    targetId:String,
    targetLabel:String,
    userTargetId:String?=null,
    onDismiss:()->Unit,
    onChanged:()->Unit = {}
) {
    val repo=remember { SafetyRepository(backend) }
    val scope=rememberCoroutineScope()
    var reportMode by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("spam") }
    var detail by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=28.dp)
        ) {
            Text(
                if(reportMode)"گزارش محتوا" else "ایمنی و کنترل",
                fontSize=19.sp,
                fontWeight=FontWeight.Black
            )
            Text(
                targetLabel,
                color=FqMuted,
                fontSize=8.sp,
                modifier=Modifier.padding(top=3.dp,bottom=12.dp)
            )

            if(!reportMode) {
                SafetySheetAction(
                    icon=Icons.Default.Flag,
                    title="گزارش",
                    subtitle="برای بررسی Moderation گزارش ارسال کن.",
                    danger=true
                ) { reportMode=true }

                if(!userTargetId.isNullOrBlank()) {
                    SafetySheetAction(
                        icon=Icons.Default.VolumeOff,
                        title="Mute",
                        subtitle="محتوای این کاربر را کمتر ببین.",
                        danger=false
                    ) {
                        if(!busy) {
                            busy=true
                            scope.launch {
                                runCatching { repo.toggleMute(userTargetId) }
                                    .onSuccess {
                                        message=if(it)"کاربر Mute شد." else "Mute برداشته شد."
                                        onChanged()
                                    }
                                    .onFailure { message=it.message }
                                busy=false
                            }
                        }
                    }

                    SafetySheetAction(
                        icon=Icons.Default.Block,
                        title="Block",
                        subtitle="Follow و DM دوطرفه قطع می‌شود.",
                        danger=true
                    ) {
                        if(!busy) {
                            busy=true
                            scope.launch {
                                runCatching { repo.toggleBlock(userTargetId) }
                                    .onSuccess {
                                        message=if(it)"کاربر Block شد." else "Block برداشته شد."
                                        onChanged()
                                    }
                                    .onFailure { message=it.message }
                                busy=false
                            }
                        }
                    }
                }
            } else {
                Text("دلیل گزارش",fontSize=10.sp,fontWeight=FontWeight.Bold)
                LazyColumn(
                    modifier=Modifier.heightIn(max=310.dp).padding(top=6.dp)
                ) {
                    items(
                        listOf(
                            "spam" to "Spam",
                            "harassment" to "آزار و اذیت",
                            "hate" to "نفرت‌پراکنی",
                            "sexual" to "محتوای جنسی نامناسب",
                            "violence" to "خشونت",
                            "spoiler" to "Spoiler بدون هشدار",
                            "copyright" to "نقض حق نشر",
                            "impersonation" to "جعل هویت",
                            "misinformation" to "اطلاعات گمراه‌کننده",
                            "other" to "سایر"
                        )
                    ) { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { reason=item.first }
                                .padding(vertical=7.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected=reason==item.first,
                                onClick={reason=item.first}
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(item.second,fontSize=9.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value=detail,
                    onValueChange={detail=it.take(1200)},
                    label={Text("توضیح اختیاری")},
                    minLines=2,
                    maxLines=4,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )

                Button(
                    enabled=!busy,
                    onClick={
                        busy=true
                        scope.launch {
                            runCatching {
                                repo.report(targetType,targetId,reason,detail)
                            }.onSuccess {
                                message="گزارش برای بررسی ارسال شد."
                                reportMode=false
                            }.onFailure {
                                message=it.message
                            }
                            busy=false
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=FqDanger),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                ) {
                    if(busy) {
                        CircularProgressIndicator(
                            color=Color.White,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(17.dp)
                        )
                    } else {
                        Icon(Icons.Default.Flag,null)
                    }
                    Spacer(Modifier.width(5.dp))
                    Text("ارسال گزارش")
                }
            }

            message?.let {
                Text(
                    it,
                    color=if(it.contains("شد") || it.contains("ارسال"))FqGreen else FqDanger,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=10.dp)
                )
            }
        }
    }
}

@Composable
private fun SafetySheetAction(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    subtitle:String,
    danger:Boolean,
    onClick:()->Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical=9.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).background(
                if(danger)FqDanger.copy(alpha=.1f) else FqSurface2,
                RoundedCornerShape(13.dp)
            ),
            contentAlignment=Alignment.Center
        ) {
            Icon(icon,null,tint=if(danger)FqDanger else FqGold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=10.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=FqMuted,fontSize=7.sp,modifier=Modifier.padding(top=2.dp))
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
    }
}
