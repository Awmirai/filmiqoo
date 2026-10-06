package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    var archivedView by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<InboxConversation>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }

    val unreadCount=remember(items) { items.sumOf { it.unread } }
    val filteredItems=remember(items,query,unreadOnly) {
        val q=query.trim()
        items.filter { item ->
            val matchesUnread=!unreadOnly || item.unread>0
            val matchesQuery=q.isBlank() ||
                item.title.contains(q,ignoreCase=true) ||
                item.otherUsername.contains(q,ignoreCase=true) ||
                item.lastMessage.contains(q,ignoreCase=true) ||
                item.topic.contains(q,ignoreCase=true)
            matchesUnread && matchesQuery
        }
    }

    BackHandler { onBack() }

    LaunchedEffect(refresh,archivedView) {
        loading=true
        error=null
        try { items=repo.inbox(archived=archivedView) }
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.message ?: "خطا در دریافت پیام‌ها"}
        finally{loading=false}
    }

    Column(Modifier.fillMaxSize().background(CinemaInk).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding().imePadding()) {
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
                        "پیام‌های خصوصی",
                        fontSize=26.sp,
                        fontWeight=FontWeight.Black
                    )
                    Text(
                        when {
                            archivedView -> "آرشیو گفتگوها"
                            unreadCount>0L -> "گفتگوهای تو • "+unreadCount+" خوانده‌نشده"
                            else -> "گفتگوهای تو"
                        },
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
                            .heightIn(min=48.dp)
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
                            Icon(Icons.Default.Close,"پاک‌کردن جستجو",modifier=Modifier.size(18.dp))
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

            Row(
                Modifier.fillMaxWidth().padding(top=7.dp),
                horizontalArrangement=Arrangement.spacedBy(7.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                FilterChip(
                    selected=unreadOnly,
                    onClick={unreadOnly=!unreadOnly},
                    leadingIcon={
                        Icon(
                            if(unreadOnly)Icons.Default.MarkChatRead
                            else Icons.Default.MarkChatUnread,
                            null,
                            modifier=Modifier.size(16.dp)
                        )
                    },
                    label={
                        Text(
                            if(unreadOnly)"خوانده‌نشده‌ها" else "فقط خوانده‌نشده",
                            fontSize=10.sp
                        )
                    }
                )
                if(unreadCount>0L) {
                    Text(
                        unreadCount.toString()+" پیام خوانده‌نشده",
                        color=FqMuted,
                        fontSize=10.sp
                    )
                }
            }
        }

        val initialLoading=loading && items.isEmpty()
        if(loading && !initialLoading) {
            LinearProgressIndicator(
                color=Color.White,
                trackColor=Color.Transparent,
                modifier=Modifier.fillMaxWidth().height(2.dp)
            )
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

        if(initialLoading) {
            InboxLoadingState()
        } else if(filteredItems.isEmpty()) {
            val searching=query.isNotBlank()
            val filteringUnread=unreadOnly && !searching
            PremiumEmptyState(
                icon=when {
                    searching -> Icons.Default.SearchOff
                    archivedView -> Icons.Default.Archive
                    else -> Icons.Default.MarkChatUnread
                },
                title=when {
                    searching -> "گفتگویی پیدا نشد"
                    filteringUnread -> "پیام خوانده‌نشده‌ای نداری"
                    archivedView -> "آرشیو خالیه"
                    else -> "هنوز مکالمه‌ای نداری"
                },
                body=when {
                    searching -> "اسم، نام کاربری یا متن گفتگو رو با عبارت دیگه‌ای جستجو کن."
                    filteringUnread -> "همه گفتگوهای این بخش رو دیدی."
                    archivedView -> "گفتگوهایی که آرشیو می‌کنی اینجا می‌مونن."
                    else -> "از پروفایل یک نفر روی «پیام» بزن یا وارد گفت‌وگوهای کلاب شو."
                }
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
                            onOpenRoom(conversation)
                            scope.launch {
                                runCatching { repo.markRoomRead(conversation.id) }
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
private fun InboxLoadingState() {
    val transition=rememberInfiniteTransition(label="inboxSkeleton")
    val alpha by transition.animateFloat(
        initialValue=.30f,
        targetValue=.68f,
        animationSpec=infiniteRepeatable(
            animation=tween(850),
            repeatMode=RepeatMode.Reverse
        ),
        label="inboxSkeletonAlpha"
    )

    LazyColumn(
        contentPadding=PaddingValues(
            start=12.dp,
            end=12.dp,
            top=10.dp,
            bottom=24.dp
        ),
        verticalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        items(6) {
            Surface(
                color=Color.White.copy(alpha=alpha*.045f),
                shape=RoundedCornerShape(20.dp),
                border=androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha=alpha*.06f)
                ),
                modifier=Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha=alpha*.16f))
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Box(
                            Modifier.width(132.dp)
                                .height(10.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha=alpha*.15f))
                        )
                        Spacer(Modifier.height(7.dp))
                        Box(
                            Modifier.fillMaxWidth(.82f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha=alpha*.09f))
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.width(30.dp)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha=alpha*.08f))
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
                                "dm" -> "پیام خصوصی"
                                "group" -> "گروه"
                                "watch_party" -> "تماشای گروهی"
                                "episode" -> "گفتگوی قسمت"
                                else -> "گفتگو"
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

private enum class NotificationFilter {
    ALL, SOCIAL, MESSAGES, RELEASES
}

@Composable
fun ConnectedNotificationsScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onOpenRoom: (String,String) -> Unit,
    onOpenCreator: (Creator) -> Unit,
    onOpenClip: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onOpenCollection: (String) -> Unit,
    onOpenWatchParty: (String) -> Unit,
    onOpenLive: (String) -> Unit,
    onFollowRequests: () -> Unit,
    onOpenDiscussion:(MediaItem,String)->Unit={media,_->onOpenMedia(media)}
) {
    val lifecycleOwner=LocalLifecycleOwner.current
    val repo=remember { MessagingRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var unread by remember { mutableLongStateOf(0L) }
    var items by remember { mutableStateOf<List<FilmiqooNotification>>(emptyList()) }
    var filterName by rememberSaveable { mutableStateOf(NotificationFilter.ALL.name) }
    var unreadOnlyNotifications by rememberSaveable { mutableStateOf(false) }
    var unavailableTarget by remember { mutableStateOf(false) }
    val filter=runCatching { NotificationFilter.valueOf(filterName) }
        .getOrDefault(NotificationFilter.ALL)

    BackHandler { onBack() }

    LaunchedEffect(refresh,lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading=items.isEmpty()
            while(true) {
                try { val result=repo.notifications();items=result.first;unread=result.second;error=null }
                catch(cancelled:CancellationException){throw cancelled}
                catch(failure:Exception){error=failure.message ?: "خطا در دریافت اعلان‌ها"}
                finally{loading=false}
                delay(20_000)
            }
        }
    }

    if(unavailableTarget)AlertDialog(onDismissRequest={unavailableTarget=false},
        title={Text("محتوا در دسترس نیست")},
        text={Text("محتوای این اعلان حذف شده یا دسترسی به آن تغییر کرده است.")},
        confirmButton={TextButton({unavailableTarget=false}){Text("متوجه شدم")}})

    Column(Modifier.fillMaxSize().background(CinemaInk).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding().imePadding()) {
        CinemaPageHeader("اعلان‌ها",if(loading)"در حال دریافت…" else if(error!=null)"دریافت اعلان‌ها کامل نشد" else if(unread>0)"$unread اعلان خوانده‌نشده" else "اعلان‌های حساب و انتشار قسمت‌ها",onBack) {
            IconButton({refresh++},enabled=!loading){Icon(Icons.Default.Refresh,"تازه‌سازی")}
        }
        if(unread>0)TextButton({scope.launch {
            try{repo.markAllNotificationsRead();refresh++}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){error="علامت خوانده‌شده ثبت نشد؛ دوباره تلاش کن."}
        }},modifier=Modifier.padding(horizontal=20.dp)){Text("علامت‌زدن همه به‌عنوان خوانده‌شده")}

        LazyRow(
            contentPadding=PaddingValues(horizontal=12.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            item {
                FilterChip(
                    selected=filter==NotificationFilter.ALL,
                    onClick={filterName=NotificationFilter.ALL.name},
                    label={Text("همه",fontSize=10.sp)}
                )
            }
            item {
                FilterChip(
                    selected=filter==NotificationFilter.SOCIAL,
                    onClick={filterName=NotificationFilter.SOCIAL.name},
                    label={Text("اجتماعی",fontSize=12.sp)}
                )
            }
            item {
                FilterChip(
                    selected=filter==NotificationFilter.MESSAGES,
                    onClick={filterName=NotificationFilter.MESSAGES.name},
                    label={Text("پیام‌ها",fontSize=10.sp)}
                )
            }
            item {
                FilterChip(
                    selected=filter==NotificationFilter.RELEASES,
                    onClick={filterName=NotificationFilter.RELEASES.name},
                    label={Text("انتشارها",fontSize=10.sp)}
                )
            }
            item {
                FilterChip(
                    selected=unreadOnlyNotifications,
                    onClick={unreadOnlyNotifications=!unreadOnlyNotifications},
                    leadingIcon={
                        Icon(
                            if(unreadOnlyNotifications)Icons.Default.MarkEmailRead
                            else Icons.Default.MarkEmailUnread,
                            null,
                            modifier=Modifier.size(15.dp)
                        )
                    },
                    label={Text("خوانده‌نشده",fontSize=10.sp)}
                )
            }
        }

        val visibleItems=remember(items,filter,unreadOnlyNotifications) {
            items.filter {
                notificationMatchesFilter(it.type,filter) &&
                    (!unreadOnlyNotifications || !it.read)
            }
        }

        if(loading) LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        error?.let {
            Text(it,color=FqDanger,fontSize=11.sp,modifier=Modifier.padding(12.dp))
        }

        if(!loading && error==null && visibleItems.isEmpty()) {
            PremiumEmptyState(
                if(unreadOnlyNotifications)Icons.Default.MarkEmailRead
                else Icons.Default.NotificationsNone,
                if(unreadOnlyNotifications) {
                    "اعلان خوانده‌نشده‌ای نیست"
                } else {
                    when(filter) {
                        NotificationFilter.ALL -> "اعلانی نداری"
                        NotificationFilter.SOCIAL -> "اعلان نبض نداری"
                        NotificationFilter.MESSAGES -> "اعلان پیام نداری"
                        NotificationFilter.RELEASES -> "اعلان انتشار نداری"
                    }
                },
                if(unreadOnlyNotifications) {
                    "همه اعلان‌های این فیلتر رو دیدی."
                } else {
                    when(filter) {
                        NotificationFilter.ALL ->
                            "لایک، کامنت، دنبال‌کردن، استوری و پیام‌های جدید اینجا نمایش داده می‌شن."
                        NotificationFilter.SOCIAL ->
                            "نقدها، کلیپ‌ها، واکنش‌ها و دنبال‌کردن‌های Pulse اینجا میاد."
                        NotificationFilter.MESSAGES ->
                            "پیام خصوصی، گفتگو و دعوت‌های تماشای گروهی اینجا میاد."
                        NotificationFilter.RELEASES ->
                            "قسمت جدید، آماده‌شدن پخش و کیفیت‌های تازه اینجا میاد."
                    }
                }
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                items(visibleItems,key={it.id}) { item ->
                    NotificationCard(
                        item=item,
                        onClick={
                            val wasUnread=!item.read
                            if(wasUnread) {
                                items=items.map {
                                    if(it.id==item.id) it.copy(read=true) else it
                                }
                                unread=(unread-1L).coerceAtLeast(0L)
                            }

                            when {
                                item.type=="follow_request" -> onFollowRequests()
                                item.entityType=="title_comment" && item.media!=null && !item.discussionScope.isNullOrBlank() ->
                                    onOpenDiscussion(item.media,item.discussionScope)
                                item.entityType=="collection" && !item.entityId.isNullOrBlank() ->
                                    onOpenCollection(item.entityId)
                                item.entityType=="watch_party" && !item.entityId.isNullOrBlank() ->
                                    onOpenWatchParty(item.entityId)
                                item.entityType=="live" && !item.entityId.isNullOrBlank() ->
                                    onOpenLive(item.entityId)
                                item.entityType=="room" && !item.entityId.isNullOrBlank() ->
                                    onOpenRoom(item.entityId,item.actor?.displayName ?: "پیام")
                                item.entityType=="reel" && !item.entityId.isNullOrBlank() ->
                                    onOpenClip(item.entityId)
                                item.entityType=="post" && !item.entityId.isNullOrBlank() ->
                                    onOpenPost(item.entityId)
                                item.media!=null ->
                                    onOpenMedia(item.media)
                                item.entityType in setOf("user","review","story") &&
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
                                else -> unavailableTarget=true
                            }

                            if(wasUnread) {
                                scope.launch {
                                    runCatching { repo.markNotificationRead(item.id) }
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
            Column(horizontalAlignment=Alignment.End) {
                val relative=inboxRelativeTime(item.createdAt)
                if(relative.isNotBlank()) {
                    Text(
                        relative,
                        color=FqMuted,
                        fontSize=8.sp
                    )
                    Spacer(Modifier.height(5.dp))
                }
                Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
            }
        }
    }
}

private fun notificationMatchesFilter(
    type:String,
    filter:NotificationFilter
):Boolean = when(filter) {
    NotificationFilter.ALL -> true
    NotificationFilter.SOCIAL ->
        type.startsWith("follow") ||
        type.startsWith("story_") ||
        type.startsWith("post_") ||
        type.startsWith("reel_") ||
        type.startsWith("review_") ||
        type.startsWith("discussion_") ||
        type.startsWith("live_") ||
        type=="comment_like" ||
        type=="collection_update"
    NotificationFilter.MESSAGES ->
        type in setOf(
            "dm_message","room_message","watch_party_invite",
            "watch_party_reminder","watch_party_join_request",
            "watch_party_join_approved","watch_party_join_declined"
        )
    NotificationFilter.RELEASES ->
        type in setOf(
            "release_ready","new_episode",
            "episode_stream_ready","availability_ready"
        )
}

private fun notificationIcon(type:String)=when(type) {
    "post_published","reel_published" -> Icons.Default.NewReleases
    "discussion_reply" -> Icons.Default.Reply
    "discussion_like" -> Icons.Default.Favorite
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
    "comment_like" -> Icons.Default.Favorite
    "dm_message" -> Icons.Default.MarkChatUnread
    "room_message" -> Icons.Default.Forum
    "live_scheduled","live_started" -> Icons.Default.LiveTv
    "live_cancelled" -> Icons.Default.EventBusy
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
    "post_published" -> "پست تازه"
    "reel_published" -> "کلیپ تازه"
    "discussion_reply" -> "پاسخ به دیدگاه"
    "discussion_like" -> "پسندیدن دیدگاه"
    "follow" -> "دنبال‌کردن"
    "follow_request" -> "درخواست دنبال‌کردن"
    "follow_accepted" -> "درخواست پذیرفته شد"
    "story_reaction" -> "واکنش به استوری"
    "story_reply" -> "پاسخ به استوری"
    "post_like" -> "لایک پست"
    "post_comment" -> "کامنت پست"
    "reel_like" -> "لایک کلیپ"
    "reel_comment" -> "کامنت کلیپ"
    "review_like" -> "لایک ریویو"
    "comment_like" -> "لایک کامنت"
    "dm_message" -> "پیام خصوصی"
    "room_message" -> "پیام گروه"
    "live_scheduled" -> "رویداد زنده زمان‌بندی‌شده"
    "live_started" -> "پخش زنده شروع شد"
    "live_cancelled" -> "رویداد زنده لغو شد"
    "release_ready" -> "انتشار"
    "new_episode" -> "قسمت جدید"
    "episode_stream_ready" -> "آماده تماشا"
    "availability_ready" -> "نسخه جدید"
    "collection_update" -> "لیست کلاب"
    "watch_party_reminder" -> "تماشای گروهی"
    "watch_party_invite" -> "دعوت تماشای گروهی"
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
