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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun InboxScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onOpenRoom: (InboxConversation) -> Unit
) {
    val repo=remember { MessagingRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<InboxConversation>>(emptyList()) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.inbox() }
            .onSuccess { items=it }
            .onFailure { error=it.message ?: "خطا در دریافت پیام‌ها" }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF111827),Color(0xFF231B09),FqBg))
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                Column(Modifier.weight(1f)) {
                    Text("پیام‌ها",fontSize=23.sp,fontWeight=FontWeight.Black)
                    Text("DM، گروه‌ها و Roomهای عضو‌شده",color=FqMuted,fontSize=8.sp)
                }
                IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
            }
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=9.sp,
                modifier=Modifier.fillMaxWidth().background(FqDanger.copy(alpha=.09f)).padding(10.dp)
            )
        }

        if(!loading && items.isEmpty()) {
            PremiumEmptyState(
                icon=Icons.Default.MarkChatUnread,
                title="هنوز مکالمه‌ای نداری",
                body="از پروفایل یک Creator روی «پیام» بزن یا وارد Roomهای Community شو."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(start=12.dp,end=12.dp,top=10.dp,bottom=24.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                items(items,key={it.id}) { conversation ->
                    InboxCard(
                        item=conversation,
                        onClick={
                            scope.launch {
                                runCatching { repo.markRoomRead(conversation.id) }
                                onOpenRoom(conversation)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxCard(
    item: InboxConversation,
    onClick: () -> Unit
) {
    Surface(
        color=if(item.unread>0)FqGold.copy(alpha=.08f) else FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Box {
                if(item.avatarUrl.isNotBlank()) {
                    RemoteImage(item.avatarUrl,Modifier.size(58.dp).clip(CircleShape))
                } else {
                    Box(
                        Modifier.size(58.dp).clip(CircleShape).background(FqSurface2),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(
                            if(item.type=="dm")Icons.Default.Person else Icons.Default.Groups,
                            null,
                            tint=FqGold
                        )
                    }
                }
                if(item.type=="dm") {
                    Box(
                        Modifier.size(12.dp).align(Alignment.BottomEnd)
                            .background(FqGreen,CircleShape)
                    )
                }
            }

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontSize=12.sp,
                        fontWeight=if(item.unread>0)FontWeight.Black else FontWeight.Bold,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    if(item.type=="dm" && item.otherUsername.isNotBlank()) {
                        Spacer(Modifier.width(5.dp))
                        Text("@"+item.otherUsername,color=FqMuted,fontSize=7.sp)
                    }
                }

                Text(
                    item.lastMessage.ifBlank { item.topic.ifBlank { "مکالمه جدید" } },
                    color=if(item.unread>0)Color.White.copy(alpha=.85f) else FqMuted,
                    fontSize=8.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=4.dp)
                )

                Row(Modifier.padding(top=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Surface(color=FqSurface2,shape=RoundedCornerShape(7.dp)) {
                        Text(
                            when(item.type) {
                                "dm" -> "DM"
                                "group" -> "Group"
                                "watch_party" -> "Watch Party"
                                "episode" -> "Episode Room"
                                else -> "Room"
                            },
                            color=FqGold,
                            fontSize=6.sp,
                            modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                        )
                    }
                    if(item.members>2) {
                        Spacer(Modifier.width(6.dp))
                        Text(compactInboxCount(item.members)+" عضو",color=FqMuted,fontSize=7.sp)
                    }
                }
            }

            if(item.unread>0) {
                Surface(color=FqGold,contentColor=Color.Black,shape=CircleShape) {
                    Text(
                        if(item.unread>99)"99+" else item.unread.toString(),
                        fontSize=7.sp,
                        fontWeight=FontWeight.Black,
                        modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                    )
                }
            } else {
                Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
            }
        }
    }
}

@Composable
fun ConnectedNotificationsScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onOpenRoom: (String,String) -> Unit,
    onOpenCreator: (Creator) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onOpenCollection: (String) -> Unit,
    onFollowRequests: () -> Unit
) {
    val repo=remember { MessagingRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var unread by remember { mutableLongStateOf(0L) }
    var items by remember { mutableStateOf<List<FilmiqooNotification>>(emptyList()) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.notifications() }
            .onSuccess {
                items=it.first
                unread=it.second
            }
            .onFailure { error=it.message ?: "خطا در دریافت اعلان‌ها" }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("اعلان‌ها",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text(
                    if(unread>0)compactInboxCount(unread)+" خوانده‌نشده" else "همه‌چی دیده شده",
                    color=if(unread>0)FqGold else FqMuted,
                    fontSize=8.sp
                )
            }
            if(unread>0) {
                TextButton(onClick={
                    scope.launch {
                        runCatching { repo.markAllNotificationsRead() }
                            .onSuccess { refresh++ }
                    }
                }) { Text("خواندن همه",fontSize=8.sp) }
            }
            IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
        }

        if(loading) LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        error?.let {
            Text(it,color=FqDanger,fontSize=9.sp,modifier=Modifier.padding(12.dp))
        }

        if(!loading && items.isEmpty()) {
            PremiumEmptyState(Icons.Default.NotificationsNone,"اعلانی نداری","Follow، Story reaction، Reply و پیام‌های جدید اینجا نمایش داده می‌شن.")
        } else {
            LazyColumn(
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                items(items,key={it.id}) { item ->
                    NotificationCard(
                        item=item,
                        onClick={
                            scope.launch {
                                if(!item.read) runCatching { repo.markNotificationRead(item.id) }
                                when {
                                    item.type=="follow_request" -> onFollowRequests()
                                    item.entityType=="collection" && !item.entityId.isNullOrBlank() ->
                                        onOpenCollection(item.entityId)
                                    item.entityType=="room" && !item.entityId.isNullOrBlank() ->
                                        onOpenRoom(item.entityId,item.actor?.displayName ?: "پیام")
                                    item.media!=null ->
                                        onOpenMedia(item.media)
                                    item.entityType=="user" && item.actor!=null ->
                                        onOpenCreator(
                                            Creator(
                                                name=item.actor.displayName,
                                                handle="@"+item.actor.username,
                                                followers="",
                                                bio="",
                                                verified=item.actor.verified,
                                                id=item.actor.id,
                                                entityType="user",
                                                avatarUrl=item.actor.avatarUrl
                                            )
                                        )
                                    else -> refresh++
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    item: FilmiqooNotification,
    onClick: () -> Unit
) {
    Surface(
        color=if(item.read)FqSurface else FqGold.copy(alpha=.08f),
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            when {
                item.media?.posterPath!=null -> {
                    RemoteImage(
                        item.media.posterPath,
                        Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
                    )
                }
                item.actor?.avatarUrl?.isNotBlank()==true -> {
                    RemoteImage(item.actor.avatarUrl,Modifier.size(50.dp).clip(CircleShape))
                }
                else -> {
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(FqSurface2),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(notificationIcon(item.type),null,tint=FqGold)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(item.title,fontSize=11.sp,fontWeight=if(item.read)FontWeight.Bold else FontWeight.Black)
                    if(!item.read) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).background(FqGold,CircleShape))
                    }
                }
                if(item.body.isNotBlank()) {
                    Text(
                        item.body,
                        color=FqMuted,
                        fontSize=8.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=4.dp)
                    )
                }
                Text(notificationTypeLabel(item.type),color=FqGold,fontSize=7.sp,modifier=Modifier.padding(top=4.dp))
            }
            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
        }
    }
}

private fun notificationIcon(type:String)=when(type) {
    "follow" -> Icons.Default.PersonAdd
    "follow_request" -> Icons.Default.PersonAddAlt1
    "follow_accepted" -> Icons.Default.HowToReg
    "story_reaction" -> Icons.Default.Favorite
    "story_reply" -> Icons.Default.Reply
    "dm_message" -> Icons.Default.MarkChatUnread
    "release_ready" -> Icons.Default.NewReleases
    "collection_update" -> Icons.Default.CollectionsBookmark
    else -> Icons.Default.Notifications
}

private fun notificationTypeLabel(type:String)=when(type) {
    "follow" -> "Follow"
    "follow_request" -> "درخواست Follow"
    "follow_accepted" -> "Follow پذیرفته شد"
    "story_reaction" -> "Story Reaction"
    "story_reply" -> "Story Reply"
    "dm_message" -> "پیام"
    "release_ready" -> "انتشار"
    "collection_update" -> "Collection"
    else -> "Filmiqoo"
}

private fun compactInboxCount(value:Long):String=when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
