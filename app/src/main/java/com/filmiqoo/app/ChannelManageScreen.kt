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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class ChannelManageTab { GENERAL, TEAM, ROOMS }

@Composable
fun ChannelManageScreen(
    channelId:String,
    backend:BackendRepository,
    onBack:()->Unit,
    onOpenRoom:(String,String)->Unit
) {
    val repo=remember { CreatorChannelRepository(backend) }
    val scope=rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var overview by remember { mutableStateOf<ChannelManageOverview?>(null) }
    var members by remember { mutableStateOf<List<ChannelMember>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<ManagedChannelRoom>>(emptyList()) }

    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("public") }

    var tab by remember { mutableStateOf(ChannelManageTab.GENERAL) }
    var showCreateRoom by remember { mutableStateOf(false) }
    var editRoom by remember { mutableStateOf<ManagedChannelRoom?>(null) }

    BackHandler(enabled=!saving) { onBack() }

    LaunchedEffect(channelId,refresh) {
        loading=true
        error=null
        val loaded=runCatching {
            Triple(
                repo.manageOverview(channelId),
                repo.channelMembers(channelId),
                repo.managedRooms(channelId)
            )
        }.onFailure { error=it.message }.getOrNull()

        if(loaded!=null) {
            overview=loaded.first
            members=loaded.second
            rooms=loaded.third
            name=loaded.first.name
            bio=loaded.first.bio
            visibility=loaded.first.visibility
        }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack,enabled=!saving) {
                Icon(Icons.Default.ArrowBack,null)
            }
            Column(Modifier.weight(1f)) {
                Text("Channel Studio",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text(
                    overview?.let{"@"+it.slug+" • "+roleLabel(it.myRole)}
                        ?: "مدیریت کانال",
                    color=FqMuted,
                    fontSize=8.sp
                )
            }
            if(saving) {
                CircularProgressIndicator(
                    color=FqGold,
                    strokeWidth=2.dp,
                    modifier=Modifier.size(21.dp)
                )
            } else {
                IconButton(onClick={refresh++}) {
                    Icon(Icons.Default.Refresh,null)
                }
            }
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=8.sp,
                modifier=Modifier.fillMaxWidth()
                    .background(FqDanger.copy(alpha=.08f))
                    .padding(10.dp)
            )
        }

        if(!loading && overview==null) {
            PremiumEmptyState(
                icon=Icons.Default.AdminPanelSettings,
                title="دسترسی مدیریت نداری",
                body="فقط Owner، Admin یا Moderator می‌تونن وارد Channel Studio بشن."
            )
            return@Column
        }

        TabRow(
            selectedTabIndex=tab.ordinal,
            containerColor=FqBg,
            contentColor=FqGold
        ) {
            listOf(
                ChannelManageTab.GENERAL to "عمومی",
                ChannelManageTab.TEAM to "تیم",
                ChannelManageTab.ROOMS to "Roomها"
            ).forEach { item ->
                Tab(
                    selected=tab==item.first,
                    onClick={tab=item.first},
                    text={Text(item.second,fontSize=9.sp)}
                )
            }
        }

        when(tab) {
            ChannelManageTab.GENERAL -> {
                val data=overview
                if(data!=null) {
                    LazyColumn(
                        contentPadding=PaddingValues(14.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Surface(
                                color=FqSurface,
                                shape=RoundedCornerShape(20.dp),
                                modifier=Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        RemoteImage(
                                            data.avatarUrl.takeIf(String::isNotBlank),
                                            Modifier.size(62.dp).clip(CircleShape)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                data.name,
                                                fontSize=14.sp,
                                                fontWeight=FontWeight.Black
                                            )
                                            Text("@"+data.slug,color=FqMuted,fontSize=8.sp)
                                            Text(
                                                roleLabel(data.myRole),
                                                color=FqGold,
                                                fontSize=7.sp,
                                                modifier=Modifier.padding(top=3.dp)
                                            )
                                        }
                                        if(data.verified) {
                                            Icon(
                                                Icons.Default.Verified,
                                                null,
                                                tint=Color(0xFF4AB7FF)
                                            )
                                        }
                                    }

                                    Row(
                                        Modifier.fillMaxWidth().padding(top=12.dp),
                                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                                    ) {
                                        PremiumStat(
                                            compactChannelCount(data.followers),
                                            "Follower",
                                            Modifier.weight(1f)
                                        )
                                        PremiumStat(
                                            compactChannelCount(data.posts),
                                            "Post",
                                            Modifier.weight(1f)
                                        )
                                        PremiumStat(
                                            compactChannelCount(data.reels),
                                            "Reel",
                                            Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            OutlinedTextField(
                                value=name,
                                onValueChange={name=it.take(80)},
                                label={Text("نام Channel")},
                                singleLine=true,
                                shape=RoundedCornerShape(15.dp),
                                modifier=Modifier.fillMaxWidth(),
                                enabled=data.myRole=="owner" || data.myRole=="admin"
                            )
                        }

                        item {
                            OutlinedTextField(
                                value=bio,
                                onValueChange={bio=it.take(500)},
                                label={Text("Bio")},
                                minLines=4,
                                maxLines=7,
                                supportingText={
                                    Text(bio.length.toString()+"/500",fontSize=7.sp)
                                },
                                shape=RoundedCornerShape(15.dp),
                                modifier=Modifier.fillMaxWidth(),
                                enabled=data.myRole=="owner" || data.myRole=="admin"
                            )
                        }

                        item {
                            Text("دسترسی کانال",fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Row(
                                Modifier.fillMaxWidth().padding(top=7.dp),
                                horizontalArrangement=Arrangement.spacedBy(7.dp)
                            ) {
                                listOf(
                                    "public" to "عمومی",
                                    "private" to "خصوصی",
                                    "invite" to "دعوتی"
                                ).forEach { item ->
                                    FilterChip(
                                        selected=visibility==item.first,
                                        onClick={visibility=item.first},
                                        enabled=data.myRole=="owner" || data.myRole=="admin",
                                        label={Text(item.second,fontSize=8.sp)}
                                    )
                                }
                            }
                        }

                        if(data.myRole=="owner" || data.myRole=="admin") {
                            item {
                                Button(
                                    onClick={
                                        saving=true
                                        scope.launch {
                                            runCatching {
                                                repo.updateChannelSettings(
                                                    id=channelId,
                                                    name=name,
                                                    bio=bio,
                                                    visibility=visibility
                                                )
                                            }.onSuccess {
                                                overview=it
                                                message="تنظیمات کانال ذخیره شد."
                                            }.onFailure {
                                                error=it.message
                                            }
                                            saving=false
                                        }
                                    },
                                    enabled=!saving && name.trim().length>=2,
                                    colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                                    shape=RoundedCornerShape(14.dp),
                                    modifier=Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Save,null,tint=Color.Black)
                                    Spacer(Modifier.width(5.dp))
                                    Text("ذخیره تغییرات",color=Color.Black)
                                }
                            }
                        }

                        item {
                            Surface(
                                color=FqSurface,
                                shape=RoundedCornerShape(18.dp),
                                modifier=Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(13.dp),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Info,null,tint=FqGold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Moderator می‌تونه Roomها و Community را مدیریت کند؛ Admin علاوه بر آن تنظیمات کانال را تغییر می‌دهد؛ Owner کنترل نقش‌ها را دارد.",
                                        color=FqMuted,
                                        fontSize=8.sp,
                                        lineHeight=14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ChannelManageTab.TEAM -> {
                if(members.isEmpty()) {
                    PremiumEmptyState(
                        Icons.Default.GroupOff,
                        "عضوی نیست",
                        "اعضای Channel اینجا نمایش داده می‌شن."
                    )
                } else {
                    LazyColumn(
                        contentPadding=PaddingValues(12.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(members,key={it.id}) { member ->
                            TeamMemberManageCard(
                                member=member,
                                myRole=overview?.myRole.orEmpty(),
                                onRole={role->
                                    saving=true
                                    scope.launch {
                                        runCatching {
                                            repo.changeMemberRole(channelId,member.id,role)
                                        }.onSuccess {
                                            message="نقش "+member.displayName+" تغییر کرد."
                                            refresh++
                                        }.onFailure { error=it.message }
                                        saving=false
                                    }
                                },
                                onRemove={
                                    saving=true
                                    scope.launch {
                                        runCatching {
                                            repo.removeMember(channelId,member.id)
                                        }.onSuccess {
                                            message="عضو از Channel حذف شد."
                                            refresh++
                                        }.onFailure { error=it.message }
                                        saving=false
                                    }
                                }
                            )
                        }
                    }
                }
            }

            ChannelManageTab.ROOMS -> {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Roomهای Channel",fontSize=13.sp,fontWeight=FontWeight.Bold)
                            Text(
                                "Slow Mode و Privacy هر Room جداست.",
                                color=FqMuted,
                                fontSize=8.sp
                            )
                        }
                        Button(
                            onClick={showCreateRoom=true},
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            shape=RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add,null,tint=Color.Black)
                            Text("Room",color=Color.Black,fontSize=8.sp)
                        }
                    }

                    if(rooms.isEmpty()) {
                        PremiumEmptyState(
                            Icons.Default.Forum,
                            "Roomی ساخته نشده",
                            "برای Chat عمومی، بحث موضوعی یا Announcement یک Room بساز."
                        )
                    } else {
                        LazyColumn(
                            contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            items(rooms,key={it.id}) { room ->
                                ManagedRoomCard(
                                    room=room,
                                    onOpen={onOpenRoom(room.id,room.name)},
                                    onEdit={editRoom=room}
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if(showCreateRoom) {
        RoomSettingsDialog(
            title="Room جدید",
            initialName="",
            initialTopic="",
            initialVisibility="public",
            initialSlowMode=0,
            allowName=true,
            onDismiss={showCreateRoom=false},
            onSave={roomName,topic,roomVisibility,slow->
                saving=true
                scope.launch {
                    runCatching {
                        repo.createRoom(
                            channelId=channelId,
                            name=roomName,
                            topic=topic,
                            visibility=roomVisibility,
                            slowModeSeconds=slow
                        )
                    }.onSuccess {
                        showCreateRoom=false
                        message="Room ساخته شد."
                        refresh++
                    }.onFailure { error=it.message }
                    saving=false
                }
            }
        )
    }

    editRoom?.let { room ->
        RoomSettingsDialog(
            title="تنظیمات "+room.name,
            initialName=room.name,
            initialTopic=room.topic,
            initialVisibility=room.visibility,
            initialSlowMode=room.slowModeSeconds,
            allowName=false,
            onDismiss={editRoom=null},
            onSave={_,topic,roomVisibility,slow->
                saving=true
                scope.launch {
                    runCatching {
                        repo.updateRoom(
                            channelId=channelId,
                            roomId=room.id,
                            topic=topic,
                            visibility=roomVisibility,
                            slowModeSeconds=slow
                        )
                    }.onSuccess {
                        editRoom=null
                        message="تنظیمات Room ذخیره شد."
                        refresh++
                    }.onFailure { error=it.message }
                    saving=false
                }
            }
        )
    }

    message?.let {
        Snackbar(
            modifier=Modifier.padding(16.dp),
            action={TextButton(onClick={message=null}){Text("باشه")}}
        ) { Text(it) }
    }
}

@Composable
private fun TeamMemberManageCard(
    member:ChannelMember,
    myRole:String,
    onRole:(String)->Unit,
    onRemove:()->Unit
) {
    var roleMenu by remember { mutableStateOf(false) }
    val canEdit=myRole=="owner" && member.role!="owner"
    val canRemove=(myRole=="owner" && member.role!="owner") ||
        (myRole=="admin" && member.role!="owner" && member.role!="admin")

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
                member.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(48.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(member.displayName,fontSize=10.sp,fontWeight=FontWeight.Bold)
                    if(member.verified) {
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Default.Verified,
                            null,
                            tint=Color(0xFF4AB7FF),
                            modifier=Modifier.size(13.dp)
                        )
                    }
                }
                Text("@"+member.username,color=FqMuted,fontSize=7.sp)
            }

            Box {
                AssistChip(
                    onClick={if(canEdit) roleMenu=true},
                    enabled=canEdit,
                    label={Text(roleLabel(member.role),fontSize=7.sp)}
                )
                DropdownMenu(
                    expanded=roleMenu,
                    onDismissRequest={roleMenu=false}
                ) {
                    listOf("admin","moderator","member").forEach { role ->
                        DropdownMenuItem(
                            text={Text(roleLabel(role))},
                            onClick={
                                roleMenu=false
                                onRole(role)
                            }
                        )
                    }
                }
            }

            if(canRemove) {
                IconButton(onClick=onRemove) {
                    Icon(Icons.Default.PersonRemove,null,tint=FqDanger)
                }
            }
        }
    }
}

@Composable
private fun ManagedRoomCard(
    room:ManagedChannelRoom,
    onOpen:()->Unit,
    onEdit:()->Unit
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
            Box(
                Modifier.size(48.dp).background(
                    FqGold.copy(alpha=.12f),
                    RoundedCornerShape(14.dp)
                ),
                contentAlignment=Alignment.Center
            ) {
                Icon(Icons.Default.Forum,null,tint=FqGold)
            }
            Spacer(Modifier.width(9.dp))
            Column(
                Modifier.weight(1f).clickable { onOpen() }
            ) {
                Text(room.name,fontSize=10.sp,fontWeight=FontWeight.Bold)
                Text(
                    room.topic.ifBlank{"بدون Topic"},
                    color=FqMuted,
                    fontSize=7.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    listOf(
                        room.members.toString()+" عضو",
                        when(room.visibility) {
                            "public" -> "عمومی"
                            "private" -> "خصوصی"
                            else -> "دعوتی"
                        },
                        if(room.slowModeSeconds>0)
                            "Slow "+room.slowModeSeconds+"s"
                        else
                            "Slow Mode خاموش"
                    ).joinToString(" • "),
                    color=FqGold,
                    fontSize=7.sp,
                    modifier=Modifier.padding(top=4.dp)
                )
            }
            IconButton(onClick=onEdit) {
                Icon(Icons.Default.Tune,null,tint=FqMuted)
            }
        }
    }
}

@Composable
private fun RoomSettingsDialog(
    title:String,
    initialName:String,
    initialTopic:String,
    initialVisibility:String,
    initialSlowMode:Int,
    allowName:Boolean,
    onDismiss:()->Unit,
    onSave:(String,String,String,Int)->Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var topic by remember { mutableStateOf(initialTopic) }
    var visibility by remember { mutableStateOf(initialVisibility) }
    var slow by remember { mutableIntStateOf(initialSlowMode) }
    var slowMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(title)},
        text={
            Column {
                if(allowName) {
                    OutlinedTextField(
                        value=name,
                        onValueChange={name=it.take(100)},
                        label={Text("نام Room")},
                        singleLine=true,
                        modifier=Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value=topic,
                    onValueChange={topic=it.take(300)},
                    label={Text("Topic")},
                    minLines=2,
                    maxLines=4,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )

                Text(
                    "Privacy",
                    fontSize=9.sp,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(top=10.dp)
                )
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "public" to "عمومی",
                        "private" to "خصوصی",
                        "invite" to "دعوتی"
                    ).forEach { item ->
                        FilterChip(
                            selected=visibility==item.first,
                            onClick={visibility=item.first},
                            label={Text(item.second,fontSize=7.sp)}
                        )
                    }
                }

                Text(
                    "Slow Mode",
                    fontSize=9.sp,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(top=10.dp)
                )
                Box {
                    OutlinedButton(onClick={slowMenu=true}) {
                        Icon(Icons.Default.Timer,null,modifier=Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if(slow==0)"خاموش" else slow.toString()+" ثانیه")
                    }
                    DropdownMenu(
                        expanded=slowMenu,
                        onDismissRequest={slowMenu=false}
                    ) {
                        listOf(0,5,10,30,60,300,900,3600).forEach { value ->
                            DropdownMenuItem(
                                text={
                                    Text(
                                        if(value==0)"خاموش"
                                        else value.toString()+" ثانیه"
                                    )
                                },
                                onClick={
                                    slow=value
                                    slowMenu=false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton={
            Button(
                onClick={onSave(name.trim(),topic.trim(),visibility,slow)},
                enabled=!allowName || name.trim().length>=2,
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ذخیره",color=Color.Black) }
        },
        dismissButton={
            TextButton(onClick=onDismiss){Text("لغو")}
        }
    )
}

private fun roleLabel(role:String)=when(role) {
    "owner" -> "Owner"
    "admin" -> "Admin"
    "moderator" -> "Moderator"
    else -> "Member"
}

private fun compactChannelCount(value:Long):String=when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
