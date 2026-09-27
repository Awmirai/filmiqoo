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
import androidx.compose.runtime.saveable.rememberSaveable
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

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
                Text("مسدودسازی، بی‌صدا کردن و کنترل تجربه اجتماعی",color=FqMuted,fontSize=11.sp)
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
                    "مسدودسازی ارتباط دوطرفه، دنبال‌کردن و پیام خصوصی را قطع می‌کند. بی‌صدا کردن فقط محتوای کاربر را از تجربه تو کنار می‌گذارد.",
                    color=Color.White.copy(alpha=.78f),
                    fontSize=11.sp,
                    lineHeight=14.sp
                )
            }
        }

        OutlinedTextField(
            value=query,
            onValueChange={query=it},
            singleLine=true,
            placeholder={Text("جستجو بین حساب‌ها...")},
            leadingIcon={Icon(Icons.Default.Search,null,modifier=Modifier.size(18.dp))},
            trailingIcon={
                if(query.isNotBlank()) {
                    IconButton(onClick={query=""}) {
                        Icon(Icons.Default.Close,null,modifier=Modifier.size(18.dp))
                    }
                }
            },
            shape=RoundedCornerShape(16.dp),
            colors=OutlinedTextFieldDefaults.colors(
                focusedBorderColor=FqGold.copy(alpha=.6f),
                unfocusedBorderColor=FqBorder,
                focusedContainerColor=FqSurface,
                unfocusedContainerColor=FqSurface
            ),
            modifier=Modifier.fillMaxWidth()
                .padding(horizontal=14.dp,vertical=6.dp)
        )

        TabRow(
            selectedTabIndex=tab,
            containerColor=FqBg,
            contentColor=FqGold
        ) {
            Tab(
                selected=tab==0,
                onClick={tab=0},
                text={Text("مسدودها ("+state.blocked.size+")",fontSize=11.sp)}
            )
            Tab(
                selected=tab==1,
                onClick={tab=1},
                text={Text("بی‌صداها ("+state.muted.size+")",fontSize=11.sp)}
            )
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=11.sp,
                modifier=Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        val users=if(tab==0)state.blocked else state.muted
        val visibleUsers=remember(users,query) {
            val q=query.trim()
            if(q.isBlank()) users
            else users.filter {
                it.displayName.contains(q,ignoreCase=true) ||
                    it.username.contains(q,ignoreCase=true)
            }
        }
        if(!loading && visibleUsers.isEmpty()) {
            val searching=query.isNotBlank()
            PremiumEmptyState(
                icon=if(searching)Icons.Default.SearchOff
                    else if(tab==0)Icons.Default.Block else Icons.Default.VolumeOff,
                title=when {
                    searching -> "حسابی پیدا نشد"
                    tab==0 -> "کسی مسدود نشده"
                    else -> "کسی بی‌صدا نشده"
                },
                body=when {
                    searching -> "نام یا نام کاربری رو با عبارت دیگه‌ای جستجو کن."
                    tab==0 -> "حساب‌هایی که مسدود کنی اینجا قابل مدیریت‌اند."
                    else -> "حساب‌هایی که بی‌صدا کنی اینجا قابل مدیریت‌اند."
                }
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp),
                modifier=Modifier.weight(1f)
            ) {
                items(visibleUsers,key={it.id}) { user ->
                    SafetyUserRow(
                        user=user,
                        actionLabel=if(tab==0)"رفع مسدودیت" else "فعال‌کردن صدا",
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
                    Text(user.displayName,fontSize=12.sp,fontWeight=FontWeight.Bold)
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
                Text("@"+user.username,color=FqMuted,fontSize=11.sp)
            }
            OutlinedButton(
                onClick=onAction,
                contentPadding=PaddingValues(horizontal=10.dp,vertical=5.dp)
            ) {
                Icon(actionIcon,null,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(actionLabel,fontSize=11.sp)
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
                fontSize=11.sp,
                modifier=Modifier.padding(top=3.dp,bottom=12.dp)
            )

            if(!reportMode) {
                SafetySheetAction(
                    icon=Icons.Default.Flag,
                    title="گزارش",
                    subtitle="برای بررسی تیم ایمنی گزارش ارسال کن.",
                    danger=true
                ) { reportMode=true }

                if(!userTargetId.isNullOrBlank()) {
                    SafetySheetAction(
                        icon=Icons.Default.VolumeOff,
                        title="بی‌صدا کردن",
                        subtitle="محتوای این کاربر از تجربه تو کنار گذاشته می‌شود.",
                        danger=false
                    ) {
                        if(!busy) {
                            busy=true
                            scope.launch {
                                runCatching { repo.toggleMute(userTargetId) }
                                    .onSuccess {
                                        message=if(it)"کاربر بی‌صدا شد." else "بی‌صدا بودن برداشته شد."
                                        onChanged()
                                    }
                                    .onFailure { message=it.message }
                                busy=false
                            }
                        }
                    }

                    SafetySheetAction(
                        icon=Icons.Default.Block,
                        title="مسدود کردن",
                        subtitle="دنبال‌کردن و پیام خصوصی دوطرفه قطع می‌شود.",
                        danger=true
                    ) {
                        if(!busy) {
                            busy=true
                            scope.launch {
                                runCatching { repo.toggleBlock(userTargetId) }
                                    .onSuccess {
                                        message=if(it)"کاربر مسدود شد." else "مسدودیت برداشته شد."
                                        onChanged()
                                    }
                                    .onFailure { message=it.message }
                                busy=false
                            }
                        }
                    }
                }
            } else {
                Text("دلیل گزارش",fontSize=12.sp,fontWeight=FontWeight.Bold)
                LazyColumn(
                    modifier=Modifier.heightIn(max=310.dp).padding(top=6.dp)
                ) {
                    items(
                        listOf(
                            "spam" to "هرزنامه",
                            "harassment" to "آزار و اذیت",
                            "hate" to "نفرت‌پراکنی",
                            "sexual" to "محتوای جنسی نامناسب",
                            "violence" to "خشونت",
                            "spoiler" to "اسپویلر بدون هشدار",
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
                            Text(item.second,fontSize=11.sp)
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
                    fontSize=11.sp,
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
            Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=FqMuted,fontSize=11.sp,modifier=Modifier.padding(top=2.dp))
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
    }
}
