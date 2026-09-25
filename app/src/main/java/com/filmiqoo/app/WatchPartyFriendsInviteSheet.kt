package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchPartyFriendsInviteSheet(
    partyId:String,
    partyRepo:WatchPartyRepository,
    onDismiss:()->Unit
) {
    val scope=rememberCoroutineScope()
    var users by remember { mutableStateOf<List<WatchPartyInviteUser>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var busyUser by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val following=runCatching { partyRepo.followingUsers() }
            .onFailure { error=it.message }
            .getOrDefault(emptyList())
        val invites=runCatching { partyRepo.directInvites(partyId) }
            .getOrDefault(emptyMap())
        users=following
        statuses=invites
    }

    LaunchedEffect(partyId) {
        loading=true
        refresh()
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,bottom=28.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("دعوت دوست‌ها",fontSize=21.sp,fontWeight=FontWeight.Black)
                    Text(
                        "از Following مستقیم Invite بفرست؛ لازم نیست لینک رو جدا بفرستی.",
                        color=FqMuted,fontSize=11.sp
                    )
                }
                IconButton(
                    onClick={
                        scope.launch {
                            loading=true
                            refresh()
                            loading=false
                        }
                    }
                ) { Icon(Icons.Default.Refresh,null) }
            }

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                )
            }

            if(!loading && users.isEmpty()) {
                PremiumEmptyState(
                    Icons.Default.GroupAdd,
                    "Following خالیه",
                    "اول چند Creator یا کاربر رو Follow کن تا از اینجا مستقیم دعوتشون کنی."
                )
            } else {
                LazyColumn(
                    modifier=Modifier.heightIn(max=520.dp).padding(top=10.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    items(users,key={it.id}) { user ->
                        val status=statuses[user.id]
                        Surface(
                            color=if(status=="pending")FqGold.copy(alpha=.07f) else FqSurface2,
                            shape=RoundedCornerShape(17.dp),
                            modifier=Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Box {
                                    RemoteImage(
                                        user.avatarUrl.takeIf(String::isNotBlank),
                                        Modifier.size(48.dp).clip(CircleShape)
                                    )
                                    if(user.watchingNow) {
                                        Box(
                                            Modifier.size(12.dp)
                                                .align(Alignment.BottomEnd)
                                                .background(FqGreen,CircleShape)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(9.dp))

                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        Text(
                                            user.displayName,
                                            fontSize=12.sp,
                                            fontWeight=FontWeight.Bold,
                                            maxLines=1,
                                            overflow=TextOverflow.Ellipsis
                                        )
                                        if(user.verified) {
                                            Spacer(Modifier.width(3.dp))
                                            Icon(
                                                Icons.Default.Verified,
                                                null,
                                                tint=Color(0xFF4AB7FF),
                                                modifier=Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                    Text("@"+user.username,color=FqMuted,fontSize=11.sp)
                                    if(user.watchingNow) {
                                        Text(
                                            "الان آنلاین و در حال تماشا",
                                            color=FqGreen,
                                            fontSize=6.sp,
                                            modifier=Modifier.padding(top=2.dp)
                                        )
                                    }
                                }

                                when(status) {
                                    "accepted" -> {
                                        AssistChip(
                                            onClick={},
                                            label={Text("عضو شده",fontSize=11.sp)},
                                            leadingIcon={
                                                Icon(
                                                    Icons.Default.Check,
                                                    null,
                                                    tint=FqGreen,
                                                    modifier=Modifier.size(14.dp)
                                                )
                                            }
                                        )
                                    }
                                    "pending" -> {
                                        AssistChip(
                                            onClick={},
                                            label={Text("دعوت شد",fontSize=11.sp)},
                                            leadingIcon={
                                                Icon(
                                                    Icons.Default.HourglassTop,
                                                    null,
                                                    tint=FqGold,
                                                    modifier=Modifier.size(14.dp)
                                                )
                                            }
                                        )
                                    }
                                    else -> {
                                        Button(
                                            enabled=busyUser!=user.id,
                                            onClick={
                                                busyUser=user.id
                                                scope.launch {
                                                    runCatching {
                                                        partyRepo.inviteUser(partyId,user.id)
                                                    }.onSuccess {
                                                        statuses=statuses+(user.id to "pending")
                                                    }.onFailure {
                                                        error=it.message
                                                    }
                                                    busyUser=null
                                                }
                                            },
                                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                                            contentPadding=PaddingValues(horizontal=10.dp,vertical=5.dp)
                                        ) {
                                            if(busyUser==user.id) {
                                                CircularProgressIndicator(
                                                    color=Color.Black,
                                                    strokeWidth=2.dp,
                                                    modifier=Modifier.size(15.dp)
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.GroupAdd,
                                                    null,
                                                    tint=Color.Black,
                                                    modifier=Modifier.size(15.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            Text("دعوت",color=Color.Black,fontSize=11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=11.sp,
                    modifier=Modifier.fillMaxWidth().padding(top=9.dp)
                )
            }
        }
    }
}
