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
import java.time.Instant
import java.time.Duration

@Composable
fun InboxScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onOpenRoom: (InboxConversation) -> Unit
) {
    val repo=remember { MessagingRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var archivedView by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<InboxConversation>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    val filteredItems=remember(items,query) {
        val q=query.trim()
        if(q.isBlank()) items
        else items.filter {
            it.title.contains(q,ignoreCase=true) ||
                it.otherUsername.contains(q,ignoreCase=true) ||
                it.lastMessage.contains(q,ignoreCase=true) ||
                it.topic.contains(q,ignoreCase=true)
        }
    }

    BackHandler { onBack() }

    LaunchedEffect(refresh,archivedView) {
        loading=true
        error=null
        runCatching { repo.inbox(archived=archivedView) }
            .onSuccess { items=it }
            .onFailure { error=it.message ?: "خطا در دریافت پیام‌ها" }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Column(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF111722),FqBg)
                    )
                )
                .statusBarsPadding()
                .padding(start=12.dp,end=12.dp,top=8.dp,bottom=10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically
            ) {
                FqIconButton(
                    icon=Icons.Default.ArrowBack,
                    contentDescription="بازگشت",
                    onClick=onBack
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "پیام‌ها",
                        fontSize=26.sp,
                        fontWeight=FontWeight.Black
                    )
                    Text(
                        if(archivedView)"آرشیو گفتگوها" else "گفتگوهای تو",
                        color=FqMuted,
                        fontSize=11.sp
                    )
                }
                FqIconButton(
                    icon=Icons.Default.Refresh,
                    contentDescription="همگام‌سازی",
                    onClick={refresh++}
                )
            }

            Row(
                Modifier.fillMaxWidth()
                    .padding(top=10.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(FqSurface)
                    .padding(4.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    false to "اصلی",
                    true to "آرشیو"
                ).forEach { (archived,label) ->
                    val active=archivedView==archived
                    Surface(
                        color=if(active) Color.White else Color.Transparent,
                        contentColor=if(active) Color.Black else FqMuted,
                        shape=RoundedCornerShape(13.dp),
                        modifier=Modifier.weight(1f)
                            .height(38.dp)
                            .clickable { archivedView=archived }
                    ) {
                        Box(contentAlignment=Alignment.Center) {
                            Text(
                                label,
                                fontSize=11.sp,
                                fontWeight=if(active)FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value=query,
                onValueChange={query=it},
                singleLine=true,
                placeholder={Text("جستجو در گفتگوها...")},
                leadingIcon={
                    Icon(Icons.Default.Search,null,modifier=Modifier.size(20.dp))
                },
                trailingIcon={
                    if(query.isNotBlank()) {
                        IconButton(onClick={query=""}) {
                            Icon(Icons.Default.Close,null,modifier=Modifier.size(18.dp))
                        }
                    }
                },
                shape=RoundedCornerShape(16.dp),
                colors=OutlinedTextFieldDefaults.colors(
                    focusedBorderColor=Color.White.copy(alpha=.18f),
                    unfocusedBorderColor=FqBorder,
                    focusedContainerColor=FqSurface,
                    unfocusedContainerColor=FqSurface
                ),
                modifier=Modifier.fillMaxWidth().padding(top=10.dp)
            )
        }

        if(loading) {
            LinearProgressIndicator(color=Color.White,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=11.sp,
                modifier=Modifier.fillMaxWidth()
                    .background(FqDanger.copy(alpha=.09f))
                    .padding(10.dp)
            )
        }

        if(!loading && filteredItems.isEmpty()) {
            PremiumEmptyState(
                icon=if(archivedView)Icons.Default.Archive else Icons.Default.MarkChatUnread,
                title=if(archivedView)"آرشیو خالیه" else "هنوز مکالمه‌ای نداری",
                body=if(archivedView)
                    "گفتگوهایی که آرشیو می‌کنی اینجا می‌مونن."
                else
                    "از پروفایل یک نفر روی «پیام» بزن یا وارد Roomهای Club شو."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(start=12.dp,end=12.dp,top=10.dp,bottom=24.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                items(filteredItems,key={it.id}) { conversation ->
                    InboxCard(
                        item=conversation,
                        onClick={
                            items=items.map {
                                if(it.id==conversation.id) it.copy(unread=0L) else it
                            }
                            scope.launch {
                                runCatching { repo.markRoomRead(conversation.id) }
                                onOpenRoom(conversation)
                            }
                        },
                        onArchive={
                            scope.launch {
                                runCatching {
                                    repo.updateRoomPreferences(
                                        roomId=conversation.id,
                                        archived=!conversation.archived
                                    )
                                }.onSuccess {
                                    refresh++
                                }.onFailure {
                                    error=it.message
                                }
                            }
                        },
                        onMuteToggle={
                            scope.launch {
                                val next=if(conversation.notificationLevel=="off")"all" else "off"
                                runCatching {
                                    repo.updateRoomPreferences(
                                        roomId=conversation.id,
                                        notificationLevel=next
                                    )
                                }.onSuccess {
                                    refresh++
                                }.onFailure {
                                    error=it.message
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
private fun InboxCard(
    item: InboxConversation,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onMuteToggle: () -> Unit
) {
    var menuOpen by remember(item.id) { mutableStateOf(false) }

    Surface(
        color=if(item.unread>0)FqSurface2 else FqSurface,
        shape=RoundedCornerShape(20.dp),
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            if(item.unread>0) Color.White.copy(alpha=.12f) else FqBorder
        ),
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
                            tint=Color.White
                        )
                    }
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
                        Text("@"+item.otherUsername,color=FqMuted,fontSize=11.sp)
                    }
                    if(item.notificationLevel=="off") {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.NotificationsOff,
                            null,
                            tint=FqMuted,
                            modifier=Modifier.size(13.dp)
                        )
                    } else if(item.notificationLevel=="mentions") {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.AlternateEmail,
                            null,
                            tint=FqMuted,
                            modifier=Modifier.size(13.dp)
                        )
                    }
                }

                Text(
                    item.lastMessage.ifBlank { item.topic.ifBlank { "مکالمه جدید" } },
                    color=if(item.unread>0)Color.White.copy(alpha=.85f) else FqMuted,
                    fontSize=11.sp,
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
                            color=FqMuted,
                            fontSize=6.sp,
                            modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                        )
                    }
                    if(item.members>2) {
                        Spacer(Modifier.width(6.dp))
                        Text(compactInboxCount(item.members)+" عضو",color=FqMuted,fontSize=11.sp)
                    }
                }
            }

            Column(
                horizontalAlignment=Alignment.End,
                verticalArrangement=Arrangement.spacedBy(6.dp)
            ) {
                item.lastMessageAt?.let { value ->
                    val relative=inboxRelativeTime(value)
                    if(relative.isNotBlank()) {
                        Text(
                            relative,
                            color=FqMuted,
                            fontSize=8.sp
                        )
                    }
                }
                if(item.unread>0) {
                    Surface(
                        color=Color.White,
                        contentColor=Color.Black,
                        shape=CircleShape
                    ) {
                        Text(
                            if(item.unread>99)"99+" else item.unread.toString(),
                            fontSize=11.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                        )
                    }
                }
            }

            Box {
                IconButton(onClick={menuOpen=true}) {
                    Icon(Icons.Default.MoreVert,null,tint=FqMuted)
                }
                DropdownMenu(
                    expanded=menuOpen,
                    onDismissRequest={menuOpen=false}
                ) {
                    DropdownMenuItem(
                        text={
                            Text(
                                if(item.notificationLevel=="off")
                                    "فعال کردن اعلان‌ها"
                                else
                                    "بی‌صدا کردن"
                            )
                        },
                        leadingIcon={
                            Icon(
                                if(item.notificationLevel=="off")
                                    Icons.Default.NotificationsActive
                                else
                                    Icons.Default.NotificationsOff,
                                null
                            )
                        },
                        onClick={
                            menuOpen=false
                            onMuteToggle()
                        }
                    )
                    DropdownMenuItem(
                        text={Text(if(item.archived)"خارج کردن از آرشیو" else "آرشیو")},
                        leadingIcon={
                            Icon(
                                if(item.archived)Icons.Default.Unarchive
                                else Icons.Default.Archive,
                                null
                            )
                        },
                        onClick={
                            menuOpen=false
                            onArchive()
                        }
                    )
                }
            }
        }
    }
}

private fun inboxRelativeTime(value:String):String {
    val instant=runCatching { Instant.parse(value) }.getOrNull() ?: return ""
    val minutes=Duration.between(instant,Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes<1 -> "الان"
        minutes<60 -> minutes.toString()+"د"
        minutes<1_440 -> (minutes/60).toString()+"س"
        minutes<10_080 -> (minutes/1_440).toString()+"روز"
        else -> (minutes/10_080).toString()+"هفته"
    }
}

@Composable
fun ConnectedNotificationsScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onOpenRoom: (String,String) -> Unit,
    onOpenCreator: (Creator) -> Unit,
    onOpenClip: (String) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onOpenCollection: (String) -> Unit,
    onOpenWatchParty: (String) -> Unit,
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
                    fontSize=11.sp
                )
            }
            if(unread>0) {
                TextButton(onClick={
                    scope.launch {
                        runCatching { repo.markAllNotificationsRead() }
                            .onSuccess { refresh++ }
                    }
                }) { Text("خواندن همه",fontSize=11.sp) }
            }
            IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
        }

        if(loading) LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        error?.let {
            Text(it,color=FqDanger,fontSize=11.sp,modifier=Modifier.padding(12.dp))
        }

        if(!loading && items.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.NotificationsNone,
                "اعلانی نداری",
                "Like، Comment، Follow، Story و پیام‌های جدید اینجا نمایش داده می‌شن."
            )
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
                                    item.entityType=="watch_party" && !item.entityId.isNullOrBlank() ->
                                        onOpenWatchParty(item.entityId)
                                    item.entityType=="room" && !item.entityId.isNullOrBlank() ->
                                        onOpenRoom(item.entityId,item.actor?.displayName ?: "پیام")
                                    item.entityType=="reel" && !item.entityId.isNullOrBlank() ->
                                        onOpenClip(item.entityId)
                                    item.media!=null ->
                                        onOpenMedia(item.media)
                                    item.entityType in setOf("user","post","review","story") &&
                                        item.actor!=null ->
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
                        fontSize=11.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=4.dp)
                    )
                }
                Text(notificationTypeLabel(item.type),color=FqGold,fontSize=11.sp,modifier=Modifier.padding(top=4.dp))
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
    "post_like" -> Icons.Default.Favorite
    "post_comment" -> Icons.Default.ChatBubble
    "reel_like" -> Icons.Default.Favorite
    "reel_comment" -> Icons.Default.ChatBubble
    "review_like" -> Icons.Default.Star
    "dm_message" -> Icons.Default.MarkChatUnread
    "room_message" -> Icons.Default.Forum
    "release_ready" -> Icons.Default.NewReleases
    "new_episode" -> Icons.Default.LiveTv
    "episode_stream_ready" -> Icons.Default.PlayCircle
    "availability_ready" -> Icons.Default.HighQuality
    "collection_update" -> Icons.Default.CollectionsBookmark
    "watch_party_reminder" -> Icons.Default.Groups
    "watch_party_invite" -> Icons.Default.GroupAdd
    "watch_party_join_request" -> Icons.Default.PersonAddAlt1
    "watch_party_join_approved" -> Icons.Default.HowToReg
    "watch_party_join_declined" -> Icons.Default.PersonOff
    else -> Icons.Default.Notifications
}

private fun notificationTypeLabel(type:String)=when(type) {
    "follow" -> "Follow"
    "follow_request" -> "درخواست Follow"
    "follow_accepted" -> "Follow پذیرفته شد"
    "story_reaction" -> "Story Reaction"
    "story_reply" -> "Story Reply"
    "post_like" -> "Post Like"
    "post_comment" -> "Post Comment"
    "reel_like" -> "Clip Like"
    "reel_comment" -> "Clip Comment"
    "review_like" -> "Review Like"
    "dm_message" -> "پیام خصوصی"
    "room_message" -> "پیام گروه"
    "release_ready" -> "انتشار"
    "new_episode" -> "قسمت جدید"
    "episode_stream_ready" -> "آماده تماشا"
    "availability_ready" -> "نسخه جدید"
    "collection_update" -> "Collection"
    "watch_party_reminder" -> "Watch Party"
    "watch_party_invite" -> "دعوت Watch Party"
    "watch_party_join_request" -> "درخواست ورود"
    "watch_party_join_approved" -> "ورود تأیید شد"
    "watch_party_join_declined" -> "درخواست رد شد"
    else -> "Filmiqoo"
}

private fun compactInboxCount(value:Long):String=when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
