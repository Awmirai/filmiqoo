package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMembersSheet(
    roomId:String,
    social:SocialRepository,
    messaging:MessagingRepository,
    meId:String?,
    onDismiss:()->Unit,
    onChanged:()->Unit={}
) {
    val scope=rememberCoroutineScope()
    var state by remember(roomId) { mutableStateOf<RoomMembersState?>(null) }
    var loading by remember(roomId) { mutableStateOf(true) }
    var error by remember(roomId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<RoomMemberCandidate>>(emptyList()) }
    var addingId by remember { mutableStateOf<String?>(null) }
    var transferTarget by remember { mutableStateOf<RoomMemberItem?>(null) }

    suspend fun reload() {
        loading=true
        runCatching { social.roomMembers(roomId) }
            .onSuccess {
                state=it
                error=null
            }
            .onFailure { error=it.message }
        loading=false
    }

    LaunchedEffect(roomId) { reload() }

    val canManage=state?.let {
        it.roomType!="dm" && (it.myRole=="owner" || it.myRole=="admin")
    }==true

    LaunchedEffect(query,canManage) {
        val q=query.trim()
        if(!canManage || q.isEmpty()) {
            candidates=emptyList()
            return@LaunchedEffect
        }
        delay(300)
        runCatching { social.searchRoomMemberCandidates(roomId,q) }
            .onSuccess { candidates=it }
            .onFailure { error=it.message }
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start=14.dp,end=14.dp,bottom=22.dp)
        ) {
            Row(
                verticalAlignment=Alignment.CenterVertically,
                modifier=Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Group,null,tint=FqGold)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("اعضای گفتگو",fontSize=19.sp)
                    val online=state?.online ?: 0L
                    val total=state?.items?.size ?: 0
                    Text(
                        online.toString()+" آنلاین • "+total+" عضو",
                        color=FqMuted,
                        fontSize=8.sp
                    )
                }
                IconButton(onClick=onDismiss) {
                    Icon(Icons.Default.Close,null)
                }
            }

            if(canManage) {
                OutlinedTextField(
                    value=query,
                    onValueChange={query=it.take(80)},
                    placeholder={Text("افزودن با نام یا @username")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(top=6.dp),
                    shape=RoundedCornerShape(15.dp)
                )

                if(query.trim().isNotEmpty() && candidates.isNotEmpty()) {
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                    ) {
                        Column {
                            candidates.take(6).forEach { candidate ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=7.dp),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    RemoteImage(
                                        candidate.avatarUrl.takeIf(String::isNotBlank),
                                        Modifier.size(34.dp).clip(CircleShape)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            Text(candidate.displayName,fontSize=9.sp)
                                            if(candidate.verified) {
                                                Spacer(Modifier.width(3.dp))
                                                Icon(
                                                    Icons.Default.Verified,
                                                    null,
                                                    tint=Color(0xFF4AB7FF),
                                                    modifier=Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                        Text("@"+candidate.username,color=FqMuted,fontSize=7.sp)
                                    }
                                    IconButton(
                                        enabled=addingId==null,
                                        onClick={
                                            addingId=candidate.id
                                            scope.launch {
                                                runCatching {
                                                    social.addRoomMember(roomId,candidate.id)
                                                }.onSuccess {
                                                    query=""
                                                    candidates=emptyList()
                                                    reload()
                                                    onChanged()
                                                }.onFailure {
                                                    error=it.message
                                                }
                                                addingId=null
                                            }
                                        }
                                    ) {
                                        if(addingId==candidate.id) {
                                            CircularProgressIndicator(
                                                modifier=Modifier.size(18.dp),
                                                strokeWidth=2.dp,
                                                color=FqGold
                                            )
                                        } else {
                                            Icon(Icons.Default.AddCircle,null,tint=FqGold)
                                        }
                                    }
                                }
                            }
                        }
                    }
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

            LazyColumn(
                modifier=Modifier.heightIn(max=560.dp).padding(top=10.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                val members=state?.items.orEmpty()
                items(members.size,key={members[it].id}) { index ->
                    val member=members[index]
                    var roleMenu by remember(member.id) { mutableStateOf(false) }
                    val myRole=state?.myRole.orEmpty()
                    val canChangeRole=myRole=="owner" && member.role!="owner" && member.id!=meId
                    val canRemove=member.id!=meId && when(myRole) {
                        "owner" -> member.role!="owner"
                        "admin" -> member.role=="member" || member.role=="moderator"
                        else -> false
                    }

                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(15.dp),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Box {
                                RemoteImage(
                                    member.avatarUrl.takeIf(String::isNotBlank),
                                    Modifier.size(42.dp).clip(CircleShape)
                                )
                                Box(
                                    Modifier.size(11.dp)
                                        .align(Alignment.BottomEnd)
                                        .background(
                                            if(member.presence=="online" || member.presence=="watching") FqGreen else FqMuted,
                                            CircleShape
                                        )
                                )
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(member.displayName,fontSize=10.sp)
                                    if(member.verified) {
                                        Spacer(Modifier.width(3.dp))
                                        Icon(
                                            Icons.Default.Verified,
                                            null,
                                            tint=Color(0xFF4AB7FF),
                                            modifier=Modifier.size(12.dp)
                                        )
                                    }
                                    if(member.id==meId) {
                                        Spacer(Modifier.width(5.dp))
                                        Text("شما",color=FqGold,fontSize=6.sp)
                                    }
                                }
                                Text(
                                    when(member.presence) {
                                        "watching" -> "آنلاین • در حال تماشا"
                                        "online" -> "آنلاین"
                                        else -> relativeLastSeen(member.lastSeenAt)
                                    },
                                    color=if(member.presence=="offline")FqMuted else FqGreen,
                                    fontSize=7.sp
                                )
                            }

                            Box {
                                TextButton(
                                    enabled=canChangeRole,
                                    onClick={roleMenu=true},
                                    contentPadding=PaddingValues(horizontal=7.dp)
                                ) {
                                    Text(roleLabel(member.role),fontSize=7.sp)
                                    if(canChangeRole) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            null,
                                            modifier=Modifier.size(15.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded=roleMenu,
                                    onDismissRequest={roleMenu=false}
                                ) {
                                    listOf("admin","moderator","member").forEach { role ->
                                        DropdownMenuItem(
                                            text={Text(roleLabel(role))},
                                            onClick={
                                                roleMenu=false
                                                scope.launch {
                                                    runCatching {
                                                        social.updateRoomMemberRole(roomId,member.id,role)
                                                    }.onSuccess {
                                                        reload()
                                                        onChanged()
                                                    }.onFailure { error=it.message }
                                                }
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text={Text("انتقال مالکیت",color=FqGold)},
                                        leadingIcon={
                                            Icon(
                                                Icons.Default.Star,
                                                null,
                                                tint=FqGold
                                            )
                                        },
                                        onClick={
                                            roleMenu=false
                                            transferTarget=member
                                        }
                                    )
                                }
                            }

                            if(canRemove) {
                                IconButton(
                                    onClick={
                                        scope.launch {
                                            runCatching {
                                                social.removeRoomMember(roomId,member.id)
                                            }.onSuccess {
                                                reload()
                                                onChanged()
                                            }.onFailure { error=it.message }
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

    transferTarget?.let { member ->
        AlertDialog(
            onDismissRequest={transferTarget=null},
            icon={Icon(Icons.Default.Star,null,tint=FqGold)},
            title={Text("انتقال مالکیت گروه")},
            text={
                Text(
                    "مالکیت گروه به «"+member.displayName+
                        "» منتقل می‌شود و نقش شما به ادمین تغییر می‌کند."
                )
            },
            confirmButton={
                Button(
                    onClick={
                        transferTarget=null
                        scope.launch {
                            runCatching {
                                messaging.transferRoomOwnership(roomId,member.id)
                            }.onSuccess {
                                reload()
                                onChanged()
                            }.onFailure { error=it.message }
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                ) { Text("انتقال مالکیت",color=Color.Black) }
            },
            dismissButton={
                TextButton(onClick={transferTarget=null}){Text("لغو")}
            }
        )
    }
}

private fun roleLabel(role:String):String=when(role) {
    "owner" -> "مالک"
    "admin" -> "ادمین"
    "moderator" -> "مدیر"
    else -> "عضو"
}

private fun relativeLastSeen(raw:String?):String {
    if(raw.isNullOrBlank()) return "آفلاین"
    return runCatching {
        val seconds=Duration.between(Instant.parse(raw),Instant.now()).seconds.coerceAtLeast(0L)
        when {
            seconds<60 -> "آخرین بازدید همین الان"
            seconds<3600 -> "آخرین بازدید "+(seconds/60)+" دقیقه پیش"
            seconds<86_400 -> "آخرین بازدید "+(seconds/3600)+" ساعت پیش"
            seconds<604_800 -> "آخرین بازدید "+(seconds/86_400)+" روز پیش"
            else -> "آخرین بازدید "+raw.take(10)
        }
    }.getOrDefault("آخرین بازدید "+raw.take(16))
}
