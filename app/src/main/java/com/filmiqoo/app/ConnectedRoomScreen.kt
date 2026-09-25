package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectedRoomScreen(
    roomId: String,
    title: String,
    social: SocialRepository,
    backend: BackendRepository,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onBack: () -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val listState=rememberLazyListState()
    var messages by remember(roomId) { mutableStateOf<List<RoomMessageItem>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(true) }
    var realtimeConnected by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<RoomMessageItem?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var meId by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var pinsOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<RoomMessageItem?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var roomMembersOpen by remember { mutableStateOf(false) }
    var roomSettingsOpen by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var roomTitle by remember(roomId,title) { mutableStateOf(title) }
    var forwardTarget by remember { mutableStateOf<RoomMessageItem?>(null) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    var locationDialogOpen by remember { mutableStateOf(false) }
    var contactDialogOpen by remember { mutableStateOf(false) }
    var scheduledOpen by remember { mutableStateOf(false) }
    var selectedMessageIds by remember(roomId) { mutableStateOf<Set<String>>(emptySet()) }
    var bulkForwardOpen by remember { mutableStateOf(false) }
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var firstUnreadMessageId by remember(roomId) { mutableStateOf<String?>(null) }
    var initialUnreadCount by remember(roomId) { mutableLongStateOf(0L) }
    var readStateCaptured by remember(roomId) { mutableStateOf(false) }
    var initialPositioned by remember(roomId) { mutableStateOf(false) }
    var draftLoaded by remember(roomId) { mutableStateOf(false) }
    var draftReplyId by remember(roomId) { mutableStateOf<String?>(null) }
    var memberState by remember(roomId) { mutableStateOf<RoomMembersState?>(null) }
    var socket by remember(roomId) { mutableStateOf<WebSocket?>(null) }
    var typingUsers by remember(roomId) {
        mutableStateOf<Map<String,Pair<String,Long>>>(emptyMap())
    }
    val realtime=remember(backend) { RoomRealtimeClient(backend.session) }
    val messaging=remember(backend) { MessagingRepository(backend) }

    suspend fun refresh(
        markRead:Boolean=true,
        autoScroll:Boolean=true
    ) {
        runCatching { social.roomMessages(roomId) }
            .onSuccess { incoming ->
                val changed=incoming.size!=messages.size
                messages=incoming
                syncing=false

                if(draftReplyId!=null && replyTo==null) {
                    replyTo=incoming.firstOrNull { it.id==draftReplyId }
                }

                if(loggedIn && markRead && readStateCaptured) {
                    runCatching { messaging.markRoomRead(roomId) }
                }

                if(!initialPositioned && incoming.isNotEmpty()) {
                    val unreadIndex=incoming.indexOfFirst { it.id==firstUnreadMessageId }
                    initialPositioned=true
                    scope.launch {
                        listState.scrollToItem(
                            if(unreadIndex>=0) unreadIndex else incoming.lastIndex
                        )
                    }
                } else if(changed && autoScroll && incoming.isNotEmpty()) {
                    scope.launch { listState.animateScrollToItem(incoming.lastIndex) }
                }
            }
            .onFailure { error=it.message }
    }

    suspend fun refreshMembers() {
        if(!loggedIn) {
            memberState=null
            return
        }
        runCatching { social.roomMembers(roomId) }
            .onSuccess { memberState=it }
            .onFailure { if(error==null) error=it.message }
    }

    val mediaPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if(uri==null) return@rememberLauncherForActivityResult
        if(!loggedIn) {
            onRequireAuth()
            return@rememberLauncherForActivityResult
        }
        uploading=true
        scope.launch {
            runCatching {
                social.sendMediaMessage(
                    context=context,
                    roomId=roomId,
                    uri=uri,
                    caption="",
                    spoiler=spoiler,
                    replyToMessageId=replyTo?.id
                )
            }.onSuccess {
                replyTo=null
                draftReplyId=null
                spoiler=false
                runCatching { messaging.deleteRoomDraft(roomId) }
                refresh()
            }.onFailure {
                error=it.message
            }
            uploading=false
        }
    }



    val documentPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if(uri==null) return@rememberLauncherForActivityResult
        if(!loggedIn) {
            onRequireAuth()
            return@rememberLauncherForActivityResult
        }
        uploading=true
        val replyId=replyTo?.id
        scope.launch {
            runCatching {
                social.sendDocumentMessage(
                    context=context,
                    roomId=roomId,
                    uri=uri,
                    caption="",
                    spoiler=spoiler,
                    replyToMessageId=replyId
                )
            }.onSuccess {
                replyTo=null
                draftReplyId=null
                spoiler=false
                runCatching { messaging.deleteRoomDraft(roomId) }
                refresh()
            }.onFailure {
                error=it.message
            }
            uploading=false
        }
    }


    LaunchedEffect(roomId,loggedIn) {
        draftLoaded=false
        readStateCaptured=!loggedIn
        initialPositioned=false
        firstUnreadMessageId=null
        initialUnreadCount=0L
        draftReplyId=null
        replyTo=null

        meId=if(loggedIn) runCatching { backend.me().id }.getOrNull() else null

        if(loggedIn) {
            runCatching { messaging.roomReadState(roomId) }
                .onSuccess {
                    firstUnreadMessageId=it.firstUnreadMessageId
                    initialUnreadCount=it.unread
                }
                .onFailure { if(error==null) error=it.message }

            runCatching { messaging.roomDraft(roomId) }
                .onSuccess { draft ->
                    if(draft.exists) {
                        text=draft.body
                        spoiler=draft.spoiler
                        draftReplyId=draft.replyToMessageId
                    }
                }
                .onFailure { if(error==null) error=it.message }
        }

        readStateCaptured=true
        refresh(markRead=false,autoScroll=false)
        if(loggedIn) {
            runCatching { messaging.markRoomRead(roomId) }
            refreshMembers()
        }
        if(draftReplyId!=null) {
            replyTo=messages.firstOrNull { it.id==draftReplyId }
        }
        draftLoaded=true

        while(isActive) {
            delay(30_000)
            refresh()
            if(loggedIn) refreshMembers()
        }
    }

    LaunchedEffect(text,loggedIn,socket) {
        val ws=socket ?: return@LaunchedEffect
        if(!loggedIn) return@LaunchedEffect
        if(text.isNotBlank()) {
            ws.send(JSONObject().put("type","typing").put("active",true).toString())
            delay(1_400)
            ws.send(JSONObject().put("type","typing").put("active",false).toString())
        } else {
            ws.send(JSONObject().put("type","typing").put("active",false).toString())
        }
    }

    LaunchedEffect(text,spoiler,replyTo?.id,draftLoaded,loggedIn) {
        if(!loggedIn || !draftLoaded) return@LaunchedEffect
        delay(700)
        runCatching {
            messaging.saveRoomDraft(
                roomId=roomId,
                body=text,
                replyToMessageId=replyTo?.id ?: draftReplyId,
                spoiler=spoiler
            )
        }
    }

    LaunchedEffect(socket,loggedIn) {
        while(isActive && loggedIn && socket!=null) {
            socket?.send(JSONObject().put("type","presence").toString())
            delay(25_000)
        }
    }

    LaunchedEffect(roomId) {
        while(isActive) {
            delay(1_000)
            val cutoff=System.currentTimeMillis()-4_500L
            typingUsers=typingUsers.filterValues { it.second>=cutoff }
        }
    }

    DisposableEffect(roomId,loggedIn) {
        val ws=if(loggedIn) {
            realtime.connect(
                roomId=roomId,
                onConnected={
                    scope.launch {
                        realtimeConnected=true
                        error=null
                    }
                },
                onEvent={raw->
                    scope.launch {
                        val event=runCatching { JSONObject(raw) }.getOrNull()
                        when(event?.optString("type")) {
                            "typing.changed" -> {
                                val user=event.optJSONObject("user")
                                val userId=user?.optString("id").orEmpty()
                                if(userId.isNotBlank() && userId!=meId) {
                                    if(event.optBoolean("active")) {
                                        val name=user?.optString("displayName")
                                            ?.takeIf(String::isNotBlank)
                                            ?: "کاربر"
                                        typingUsers=typingUsers+
                                            (userId to (name to System.currentTimeMillis()))
                                    } else {
                                        typingUsers=typingUsers-userId
                                    }
                                }
                            }
                            "member.joined","member.removed","member.role_changed","member.owner_changed" -> {
                                refreshMembers()
                                refresh()
                            }
                            "room.settings_changed" -> {
                                event.optString("name")
                                    .takeIf(String::isNotBlank)
                                    ?.let { roomTitle=it }
                                refreshMembers()
                            }
                            "connected" -> Unit
                            else -> refresh()
                        }
                    }
                },
                onDisconnected={reason ->
                    scope.launch {
                        realtimeConnected=false
                        socket=null
                        if(!reason.isNullOrBlank()) error=reason
                    }
                }
            )
        } else null
        socket=ws
        onDispose {
            runCatching {
                ws?.send(JSONObject().put("type","typing").put("active",false).toString())
            }
            ws?.close(1000,"screen closed")
            if(socket===ws) socket=null
        }
    }

    BackHandler {
        if(selectedMessageIds.isNotEmpty()) {
            selectedMessageIds=emptySet()
        } else {
            onBack()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(
            color=FqGlass,
            border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
            shadowElevation=6.dp
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal=6.dp,vertical=6.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                FqIconButton(
                    icon=Icons.Default.ArrowBack,
                    contentDescription="بازگشت",
                    onClick=onBack
                )
                Surface(
                    color=FqGold.copy(alpha=.12f),
                    contentColor=FqGold,
                    shape=CircleShape,
                    modifier=Modifier.size(42.dp)
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(
                            if(memberState?.roomType=="dm")
                                Icons.Default.PersonOutline
                            else
                                Icons.Default.Forum,
                            contentDescription=null,
                            modifier=Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        roomTitle,
                        style=MaterialTheme.typography.titleMedium,
                        fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines=1
                    )
                    Row(
                        verticalAlignment=Alignment.CenterVertically,
                        modifier=Modifier.padding(top=2.dp)
                    ) {
                        Box(
                            Modifier.size(7.dp).background(
                                if(realtimeConnected) FqGreen
                                else if(error==null) FqGold
                                else FqDanger,
                                CircleShape
                            )
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when {
                                memberState?.roomType=="dm" -> {
                                    val other=memberState?.items?.firstOrNull { it.id!=meId }
                                    when(other?.presence) {
                                        "watching" -> "آنلاین • در حال تماشا"
                                        "online" -> "آنلاین"
                                        else -> if(realtimeConnected) "Realtime متصل" else "آفلاین"
                                    }
                                }
                                loggedIn && memberState!=null ->
                                    (memberState?.online ?: 0L).toString()+" آنلاین"
                                realtimeConnected -> "Realtime متصل"
                                syncing -> "در حال همگام‌سازی..."
                                loggedIn -> "در حال بازیابی اتصال..."
                                else -> "فقط مشاهده"
                            },
                            color=FqMuted,
                            style=MaterialTheme.typography.labelSmall,
                            maxLines=1
                        )
                    }
                }
                if(loggedIn) {
                    FqIconButton(
                        icon=Icons.Default.Group,
                        contentDescription="اعضای گفتگو",
                        onClick={roomMembersOpen=true}
                    )
                }
                FqIconButton(
                    icon=Icons.Default.Search,
                    contentDescription="جستجوی پیام",
                    onClick={
                        if(!loggedIn) onRequireAuth()
                        else searchOpen=true
                    }
                )
            Box {
                IconButton(onClick={headerMenuOpen=true}) {
                    Icon(Icons.Default.MoreVert,null)
                }
                DropdownMenu(
                    expanded=headerMenuOpen,
                    onDismissRequest={headerMenuOpen=false}
                ) {
                    if(loggedIn) {
                        DropdownMenuItem(
                            text={Text("تنظیمات گفتگو")},
                            leadingIcon={Icon(Icons.Default.Settings,null)},
                            onClick={
                                headerMenuOpen=false
                                roomSettingsOpen=true
                            }
                        )
                    }
                    DropdownMenuItem(
                        text={Text("پیام‌های Pin شده")},
                        leadingIcon={Icon(Icons.Default.PushPin,null)},
                        onClick={
                            headerMenuOpen=false
                            pinsOpen=true
                        }
                    )
                    if(loggedIn) {
                        DropdownMenuItem(
                            text={Text("پیام‌های زمان‌بندی‌شده")},
                            leadingIcon={Icon(Icons.Default.Schedule,null)},
                            onClick={
                                headerMenuOpen=false
                                scheduledOpen=true
                            }
                        )
                    }
                    firstUnreadMessageId?.let { unreadId ->
                        DropdownMenuItem(
                            text={Text("اولین پیام خوانده‌نشده")},
                            leadingIcon={Icon(Icons.Default.MarkChatUnread,null)},
                            onClick={
                                headerMenuOpen=false
                                val index=messages.indexOfFirst { it.id==unreadId }
                                if(index>=0) {
                                    scope.launch { listState.animateScrollToItem(index) }
                                } else {
                                    actionMessage="اولین پیام خوانده‌نشده خارج از ۱۰۰ پیام اخیر است."
                                }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text={Text("اشتراک گفتگو")},
                        leadingIcon={Icon(Icons.Default.Share,null)},
                        onClick={
                            headerMenuOpen=false
                            FilmiqooDeepLinks.share(
                                context,
                                roomTitle,
                                FilmiqooDeepLinks.room(roomId,roomTitle)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text={Text("همگام‌سازی")},
                        leadingIcon={Icon(Icons.Default.Refresh,null)},
                        onClick={
                            headerMenuOpen=false
                            scope.launch {
                                refresh()
                                if(loggedIn) refreshMembers()
                            }
                        }
                    )
                }
            }
            }
        }

        if(selectedMessageIds.isNotEmpty()) {
            BulkMessageSelectionBar(
                count=selectedMessageIds.size,
                onForward={bulkForwardOpen=true},
                onDelete={bulkDeleteConfirm=true},
                onClear={selectedMessageIds=emptySet()}
            )
        }

        error?.let {
            Text(it,color=FqDanger,fontSize=11.sp,modifier=Modifier.fillMaxWidth().background(FqDanger.copy(alpha=.08f)).padding(8.dp))
        }

        LazyColumn(
            state=listState,
            modifier=Modifier.weight(1f),
            contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            items(messages.size,key={messages[it].id}) { index ->
                val msg=messages[index]
                val selected=selectedMessageIds.contains(msg.id)
                var reveal by remember(msg.id) { mutableStateOf(!msg.spoiler) }

                if(msg.id==firstUnreadMessageId) {
                    UnreadMessagesDivider(initialUnreadCount)
                }

                SwipeToReplyMessage(
                    key=msg.id,
                    enabled=loggedIn && selectedMessageIds.isEmpty(),
                    onReply={
                        replyTo=msg
                        draftReplyId=msg.id
                    }
                ) {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick={
                                if(selectedMessageIds.isNotEmpty()) {
                                    selectedMessageIds=
                                        if(selected) selectedMessageIds-msg.id
                                        else selectedMessageIds+msg.id
                                }
                            },
                            onLongClick={
                                selectedMessageIds=
                                    if(selected) selectedMessageIds-msg.id
                                    else selectedMessageIds+msg.id
                            }
                        ),
                        verticalAlignment=Alignment.Top
                    ) {
                        if(selected) {
                            Box(
                                Modifier.size(28.dp)
                                    .background(FqGold,CircleShape),
                                contentAlignment=Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    tint=Color.Black,
                                    modifier=Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        } else {
                            RemoteImage(
                                msg.author.avatarUrl.takeIf(String::isNotBlank),
                                Modifier.size(34.dp).background(FqSurface2,CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                        }

                        Surface(
                            color=if(selected)FqGold.copy(alpha=.10f) else FqSurface,
                            shape=RoundedCornerShape(16.dp),
                            modifier=Modifier.weight(1f)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                var menuOpen by remember(msg.id) { mutableStateOf(false) }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    Text(msg.author.displayName,color=FqGold,fontSize=11.sp)
                                    if(msg.author.verified) {
                                        Spacer(Modifier.width(3.dp))
                                        Icon(
                                            Icons.Default.Verified,
                                            null,
                                            tint=Color(0xFF4AB7FF),
                                            modifier=Modifier.size(12.dp)
                                        )
                                    }
                                    if(msg.pinned) {
                                        Spacer(Modifier.width(5.dp))
                                        Icon(
                                            Icons.Default.PushPin,
                                            null,
                                            tint=FqGold,
                                            modifier=Modifier.size(12.dp)
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if(selectedMessageIds.isEmpty()) {
                                        Box {
                                            IconButton(
                                                onClick={menuOpen=true},
                                                modifier=Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    null,
                                                    tint=FqMuted,
                                                    modifier=Modifier.size(17.dp)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded=menuOpen,
                                                onDismissRequest={menuOpen=false}
                                            ) {
                                                DropdownMenuItem(
                                                    text={Text("پاسخ")},
                                                    leadingIcon={Icon(Icons.Default.Reply,null)},
                                                    onClick={
                                                        menuOpen=false
                                                        replyTo=msg
                                                        draftReplyId=msg.id
                                                    }
                                                )
                                                if(loggedIn) {
                                                    DropdownMenuItem(
                                                        text={Text("فوروارد")},
                                                        leadingIcon={Icon(Icons.Default.Forward,null)},
                                                        onClick={
                                                            menuOpen=false
                                                            forwardTarget=msg
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text={Text("انتخاب پیام")},
                                                        leadingIcon={Icon(Icons.Default.CheckCircle,null)},
                                                        onClick={
                                                            menuOpen=false
                                                            selectedMessageIds=setOf(msg.id)
                                                        }
                                                    )
                                                }
                                                if(meId==msg.author.id && msg.type=="text") {
                                                    DropdownMenuItem(
                                                        text={Text("ویرایش")},
                                                        leadingIcon={Icon(Icons.Default.Edit,null)},
                                                        onClick={
                                                            menuOpen=false
                                                            editTarget=msg
                                                        }
                                                    )
                                                }
                                                if(loggedIn) {
                                                    DropdownMenuItem(
                                                        text={Text(if(msg.pinned)"برداشتن Pin" else "Pin پیام")},
                                                        leadingIcon={Icon(Icons.Default.PushPin,null)},
                                                        onClick={
                                                            menuOpen=false
                                                            scope.launch {
                                                                runCatching {
                                                                    social.toggleMessagePin(roomId,msg.id)
                                                                }.onSuccess {
                                                                    actionMessage=if(it)"پیام Pin شد." else "Pin برداشته شد."
                                                                    refresh()
                                                                }.onFailure {
                                                                    error=it.message
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                                if(meId==msg.author.id) {
                                                    DropdownMenuItem(
                                                        text={Text("حذف",color=FqDanger)},
                                                        leadingIcon={Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)},
                                                        onClick={
                                                            menuOpen=false
                                                            scope.launch {
                                                                runCatching {
                                                                    social.deleteMessage(roomId,msg.id)
                                                                }.onSuccess {
                                                                    actionMessage="پیام حذف شد."
                                                                    refresh()
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

                                msg.forwardedFrom?.let { forwarded ->
                                    Surface(
                                        color=FqGold.copy(alpha=.08f),
                                        shape=RoundedCornerShape(10.dp),
                                        modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                                    ) {
                                        Row(
                                            Modifier.padding(8.dp),
                                            verticalAlignment=Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Forward,
                                                null,
                                                tint=FqGold,
                                                modifier=Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Column {
                                                Text(
                                                    "فوروارد شده از "+forwarded.author,
                                                    color=FqGold,
                                                    fontSize=11.sp
                                                )
                                                Text(
                                                    forwarded.body.ifBlank {
                                                        when(forwarded.type) {
                                                            "voice" -> "پیام صوتی"
                                                            "image" -> "تصویر"
                                                            "video" -> "ویدیو"
                                                            "document" -> "فایل"
                                                            "location" -> "موقعیت مکانی"
                                                            "contact" -> "مخاطب"
                                                            else -> "پیام"
                                                        }
                                                    },
                                                    color=FqMuted,
                                                    fontSize=11.sp,
                                                    maxLines=1
                                                )
                                            }
                                        }
                                    }
                                }

                                if(!msg.replyPreview.isNullOrBlank()) {
                                    Surface(
                                        color=FqSurface2,
                                        shape=RoundedCornerShape(10.dp),
                                        modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                                    ) {
                                        Column(Modifier.padding(8.dp)) {
                                            Text(msg.replyAuthor ?: "Reply",color=FqGold,fontSize=11.sp)
                                            Text(
                                                msg.replyPreview,
                                                color=FqMuted,
                                                fontSize=11.sp,
                                                maxLines=2
                                            )
                                        }
                                    }
                                }

                                if(msg.spoiler && !reveal) {
                                    Text(
                                        "⚠ Spoiler Shield • نمایش پیام",
                                        color=FqDanger,
                                        fontSize=11.sp,
                                        modifier=Modifier.padding(top=5.dp).clickable { reveal=true }
                                    )
                                } else {
                                    when(msg.type) {
                                        "voice" -> {
                                            msg.attachmentUrl?.let { url ->
                                                VoiceMessagePlayer(
                                                    url=url,
                                                    declaredDurationMs=msg.attachmentDurationMs,
                                                    waveform=msg.attachmentWaveform
                                                )
                                            }
                                        }
                                        "document","location","contact" -> {
                                            RichMessageAttachment(msg)
                                        }
                                        "image","video" -> {
                                            if(!msg.attachmentUrl.isNullOrBlank()) {
                                                Box(
                                                    Modifier.fillMaxWidth().height(190.dp)
                                                        .padding(top=7.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                ) {
                                                    RemoteImage(
                                                        msg.attachmentUrl,
                                                        Modifier.fillMaxSize(),
                                                        ContentScale.Crop
                                                    )
                                                    if(msg.type=="video") {
                                                        Box(
                                                            Modifier.size(46.dp).align(Alignment.Center)
                                                                .background(
                                                                    Color.Black.copy(alpha=.55f),
                                                                    CircleShape
                                                                ),
                                                            contentAlignment=Alignment.Center
                                                        ) {
                                                            Icon(
                                                                Icons.Default.PlayArrow,
                                                                null,
                                                                tint=Color.White
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if(
                                        msg.body.isNotBlank() &&
                                        !(msg.type=="contact" && msg.body==msg.contactName) &&
                                        !(msg.type=="location" && msg.body==msg.locationLabel)
                                    ) {
                                        Text(
                                            msg.body,
                                            fontSize=12.sp,
                                            lineHeight=17.sp,
                                            modifier=Modifier.padding(top=5.dp)
                                        )
                                    }
                                }

                                if(msg.editedAt!=null || (meId==msg.author.id && msg.seenBy>0)) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(top=4.dp),
                                        verticalAlignment=Alignment.CenterVertically
                                    ) {
                                        if(msg.editedAt!=null) {
                                            Text("ویرایش شده",color=FqMuted,fontSize=6.sp)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        if(meId==msg.author.id && msg.seenBy>0) {
                                            Icon(
                                                Icons.Default.DoneAll,
                                                null,
                                                tint=FqGold,
                                                modifier=Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                "دیده‌شده توسط "+msg.seenBy,
                                                color=FqMuted,
                                                fontSize=6.sp
                                            )
                                        }
                                    }
                                }

                                if(selectedMessageIds.isEmpty()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(top=7.dp),
                                        verticalAlignment=Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick={
                                                replyTo=msg
                                                draftReplyId=msg.id
                                            },
                                            contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)
                                        ) {
                                            Icon(Icons.Default.Reply,null,modifier=Modifier.size(14.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("پاسخ",fontSize=11.sp)
                                        }
                                        listOf("❤️","🔥","😂","👍").forEach { reaction ->
                                            val count=msg.reactions[reaction] ?: 0L
                                            Text(
                                                reaction + if(count>0)" "+count else "",
                                                fontSize=11.sp,
                                                modifier=Modifier.padding(horizontal=3.dp)
                                                    .clickable {
                                                        if(!loggedIn) {
                                                            onRequireAuth()
                                                        } else {
                                                            scope.launch {
                                                                runCatching {
                                                                    social.toggleMessageReaction(
                                                                        roomId,
                                                                        msg.id,
                                                                        reaction
                                                                    )
                                                                }.onSuccess { refresh() }
                                                            }
                                                        }
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color=FqGlass,
            border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
            shadowElevation=8.dp
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal=8.dp,vertical=6.dp)
            ) {
                if(selectedMessageIds.isEmpty()) {
                    replyTo?.let { reply ->
                        Surface(
                            color=FqGold.copy(alpha=.07f),
                            shape=RoundedCornerShape(14.dp),
                            border=androidx.compose.foundation.BorderStroke(
                                1.dp,
                                FqGold.copy(alpha=.16f)
                            ),
                            modifier=Modifier.fillMaxWidth()
                                .padding(bottom=5.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal=10.dp,vertical=8.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Reply,
                                    contentDescription=null,
                                    tint=FqGold,
                                    modifier=Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "پاسخ به "+reply.author.displayName,
                                        color=FqGold,
                                        style=MaterialTheme.typography.labelMedium,
                                        fontWeight=androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    Text(
                                        reply.body.ifBlank{"رسانه"},
                                        color=FqMuted,
                                        style=MaterialTheme.typography.bodySmall,
                                        maxLines=1
                                    )
                                }
                                FqIconButton(
                                    icon=Icons.Default.Close,
                                    contentDescription="لغو پاسخ",
                                    onClick={
                                        replyTo=null
                                        draftReplyId=null
                                    },
                                    modifier=Modifier.size(40.dp)
                                )
                            }
                        }
                    }

                    if(typingUsers.isNotEmpty()) {
                        val names=typingUsers.values
                            .map { it.first }
                            .distinct()
                            .take(2)
                        Text(
                            names.joinToString("، ")+" در حال نوشتن...",
                            color=FqGold,
                            style=MaterialTheme.typography.labelSmall,
                            modifier=Modifier.padding(
                                horizontal=8.dp,
                                vertical=3.dp
                            )
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment=Alignment.Bottom
                    ) {
                        if(uploading) {
                            Box(
                                Modifier.size(48.dp),
                                contentAlignment=Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color=FqGold,
                                    strokeWidth=2.dp,
                                    modifier=Modifier.size(20.dp)
                                )
                            }
                        } else {
                            FqIconButton(
                                icon=Icons.Default.AttachFile,
                                contentDescription="پیوست",
                                onClick={
                                    if(!loggedIn) onRequireAuth()
                                    else attachmentMenuOpen=true
                                }
                            )
                        }

                        Spacer(Modifier.width(5.dp))

                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(
                                value=text,
                                onValueChange={text=it.take(4000)},
                                placeholder={
                                    Text(
                                        if(loggedIn)
                                            "پیام بنویس..."
                                        else
                                            "برای پیام دادن وارد شو"
                                    )
                                },
                                shape=RoundedCornerShape(20.dp),
                                colors=OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor=FqGold.copy(alpha=.75f),
                                    unfocusedBorderColor=FqBorder,
                                    focusedContainerColor=FqSurface,
                                    unfocusedContainerColor=FqSurface
                                ),
                                modifier=Modifier.fillMaxWidth(),
                                maxLines=4
                            )

                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(top=4.dp),
                                verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(6.dp)
                            ) {
                                PremiumChip(
                                    icon=Icons.Default.VisibilityOff,
                                    label="Spoiler",
                                    active=spoiler,
                                    onClick={spoiler=!spoiler}
                                )
                                if(text.isNotBlank()) {
                                    PremiumChip(
                                        icon=Icons.Default.Schedule,
                                        label="زمان‌بندی",
                                        active=false,
                                        onClick={
                                            if(!loggedIn) onRequireAuth()
                                            else scheduledOpen=true
                                        }
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text.length.toString()+"/4000",
                                    color=if(text.length>3800)
                                        FqDanger
                                    else
                                        FqMuted,
                                    style=MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        Spacer(Modifier.width(5.dp))

                        if(text.isNotBlank()) {
                            FqIconButton(
                                icon=Icons.Default.Send,
                                contentDescription="ارسال پیام",
                                accent=true,
                                onClick={
                                    if(!loggedIn) {
                                        onRequireAuth()
                                    } else {
                                        val sending=text.trim()
                                        val replyId=replyTo?.id
                                        val sendingSpoiler=spoiler
                                        text=""
                                        replyTo=null
                                        draftReplyId=null
                                        scope.launch {
                                            runCatching {
                                                social.sendMessage(
                                                    roomId,
                                                    sending,
                                                    sendingSpoiler,
                                                    replyId
                                                )
                                            }.onSuccess {
                                                spoiler=false
                                                runCatching {
                                                    messaging.deleteRoomDraft(roomId)
                                                }
                                                refresh()
                                            }.onFailure {
                                                error=it.message
                                                if(text.isBlank()) text=sending
                                            }
                                        }
                                    }
                                }
                            )
                        } else if(!loggedIn) {
                            FqIconButton(
                                icon=Icons.Default.Mic,
                                contentDescription="پیام صوتی",
                                onClick=onRequireAuth
                            )
                        } else {
                            VoiceRecordButton(
                                enabled=!uploading,
                                onRecorded={file,durationMs,waveform->
                                    uploading=true
                                    val replyId=replyTo?.id
                                    val sendingSpoiler=spoiler
                                    replyTo=null
                                    draftReplyId=null
                                    scope.launch {
                                        runCatching {
                                            social.sendVoiceMessage(
                                                roomId=roomId,
                                                file=file,
                                                durationMs=durationMs,
                                                waveform=waveform,
                                                spoiler=sendingSpoiler,
                                                replyToMessageId=replyId
                                            )
                                        }.onSuccess {
                                            spoiler=false
                                            runCatching {
                                                messaging.deleteRoomDraft(roomId)
                                            }
                                            refresh()
                                        }.onFailure {
                                            error=it.message
                                        }
                                        file.delete()
                                        uploading=false
                                    }
                                },
                                onError={error=it}
                            )
                        }
                    }
                }
            }
        }
    }

    if(attachmentMenuOpen) {
        ChatAttachmentMenuSheet(
            onDismiss={attachmentMenuOpen=false},
            onMedia={
                mediaPicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    )
                )
            },
            onDocument={
                documentPicker.launch(
                    arrayOf(
                        "application/pdf",
                        "text/plain",
                        "text/csv",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/zip"
                    )
                )
            },
            onLocation={locationDialogOpen=true},
            onContact={contactDialogOpen=true}
        )
    }

    if(locationDialogOpen) {
        LocationMessageDialog(
            onDismiss={locationDialogOpen=false},
            onSend={lat,lng,label->
                locationDialogOpen=false
                uploading=true
                val replyId=replyTo?.id
                val sendingSpoiler=spoiler
                scope.launch {
                    runCatching {
                        social.sendLocationMessage(
                            roomId=roomId,
                            latitude=lat,
                            longitude=lng,
                            label=label,
                            spoiler=sendingSpoiler,
                            replyToMessageId=replyId
                        )
                    }.onSuccess {
                        replyTo=null
                        draftReplyId=null
                        spoiler=false
                        runCatching { messaging.deleteRoomDraft(roomId) }
                        refresh()
                    }.onFailure {
                        error=it.message
                    }
                    uploading=false
                }
            }
        )
    }

    if(contactDialogOpen) {
        ContactMessageDialog(
            onDismiss={contactDialogOpen=false},
            onSend={name,phone,email->
                contactDialogOpen=false
                uploading=true
                val replyId=replyTo?.id
                val sendingSpoiler=spoiler
                scope.launch {
                    runCatching {
                        social.sendContactMessage(
                            roomId=roomId,
                            name=name,
                            phone=phone,
                            email=email,
                            spoiler=sendingSpoiler,
                            replyToMessageId=replyId
                        )
                    }.onSuccess {
                        replyTo=null
                        draftReplyId=null
                        spoiler=false
                        runCatching { messaging.deleteRoomDraft(roomId) }
                        refresh()
                    }.onFailure {
                        error=it.message
                    }
                    uploading=false
                }
            }
        )
    }

    if(scheduledOpen) {
        ScheduledMessagesSheet(
            roomId=roomId,
            currentText=text,
            spoiler=spoiler,
            replyToMessageId=replyTo?.id,
            messaging=messaging,
            onDismiss={scheduledOpen=false},
            onScheduled={
                text=""
                replyTo=null
                draftReplyId=null
                spoiler=false
                actionMessage="پیام زمان‌بندی شد."
                scope.launch { runCatching { messaging.deleteRoomDraft(roomId) } }
            },
            onError={error=it}
        )
    }

    if(bulkForwardOpen) {
        ForwardMessageSheet(
            currentRoomId=roomId,
            messaging=messaging,
            onDismiss={bulkForwardOpen=false},
            onForward={targetRoomId->
                val count=social.bulkForwardMessages(
                    roomId=roomId,
                    messageIds=selectedMessageIds,
                    targetRoomId=targetRoomId
                )
                selectedMessageIds=emptySet()
                actionMessage=count.toString()+" پیام فوروارد شد."
            }
        )
    }

    if(bulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest={bulkDeleteConfirm=false},
            icon={Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)},
            title={Text("حذف پیام‌های انتخاب‌شده؟")},
            text={
                Text(
                    selectedMessageIds.size.toString()+
                        " پیام حذف می‌شود. برای پیام‌های دیگران دسترسی مدیریت لازم است."
                )
            },
            confirmButton={
                Button(
                    onClick={
                        bulkDeleteConfirm=false
                        val ids=selectedMessageIds
                        scope.launch {
                            runCatching {
                                social.bulkDeleteMessages(roomId,ids)
                            }.onSuccess { count ->
                                selectedMessageIds=emptySet()
                                actionMessage=count.toString()+" پیام حذف شد."
                                refresh()
                            }.onFailure {
                                error=it.message
                            }
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=FqDanger)
                ) { Text("حذف",color=Color.White) }
            },
            dismissButton={
                TextButton(onClick={bulkDeleteConfirm=false}){Text("لغو")}
            }
        )
    }

    if(roomSettingsOpen) {
        RoomConversationSettingsSheet(
            roomId=roomId,
            messaging=messaging,
            onDismiss={roomSettingsOpen=false},
            onTitleChanged={roomTitle=it},
            onLeave={
                roomSettingsOpen=false
                onBack()
            }
        )
    }

    if(roomMembersOpen) {
        RoomMembersSheet(
            roomId=roomId,
            social=social,
            messaging=messaging,
            meId=meId,
            onDismiss={roomMembersOpen=false},
            onChanged={scope.launch{refreshMembers()}}
        )
    }

    forwardTarget?.let { message ->
        ForwardMessageSheet(
            currentRoomId=roomId,
            messaging=messaging,
            onDismiss={forwardTarget=null},
            onForward={targetRoomId->
                social.forwardMessage(roomId,message.id,targetRoomId)
                actionMessage="پیام فوروارد شد."
            }
        )
    }

    if(searchOpen) {
        RoomMessageSearchSheet(
            roomId=roomId,
            social=social,
            onDismiss={searchOpen=false},
            onJump={messageId->
                searchOpen=false
                val index=messages.indexOfFirst { it.id==messageId }
                if(index>=0) {
                    scope.launch { listState.animateScrollToItem(index) }
                } else {
                    actionMessage="این پیام بیرون از ۱۰۰ پیام اخیر است."
                }
            }
        )
    }

    if(pinsOpen) {
        RoomPinnedMessagesSheet(
            roomId=roomId,
            social=social,
            onDismiss={pinsOpen=false},
            onJump={messageId->
                pinsOpen=false
                val index=messages.indexOfFirst { it.id==messageId }
                if(index>=0) {
                    scope.launch { listState.animateScrollToItem(index) }
                } else {
                    actionMessage="پیام Pin شده قدیمی‌تر از لیست فعلی است."
                }
            }
        )
    }

    editTarget?.let { message ->
        EditRoomMessageDialog(
            message=message,
            onDismiss={editTarget=null},
            onSave={body,editedSpoiler->
                scope.launch {
                    runCatching {
                        social.editMessage(
                            roomId=roomId,
                            messageId=message.id,
                            body=body,
                            spoiler=editedSpoiler
                        )
                    }.onSuccess {
                        editTarget=null
                        actionMessage="پیام ویرایش شد."
                        refresh()
                    }.onFailure {
                        error=it.message
                    }
                }
            }
        )
    }

    actionMessage?.let { message ->
        Snackbar(
            modifier=Modifier.padding(16.dp),
            action={TextButton(onClick={actionMessage=null}){Text("باشه")}}
        ) { Text(message) }
    }

}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomMessageSearchSheet(
    roomId:String,
    social:SocialRepository,
    onDismiss:()->Unit,
    onJump:(String)->Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RoomMessageItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val q=query.trim()
        if(q.length<2) {
            results=emptyList()
            error=null
            return@LaunchedEffect
        }
        delay(300)
        loading=true
        runCatching { social.searchRoomMessages(roomId,q) }
            .onSuccess {
                results=it
                error=null
            }
            .onFailure { error=it.message }
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start=14.dp,end=14.dp,bottom=20.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("جستجوی پیام",fontSize=19.sp)
                    Text("داخل همین Room",color=FqMuted,fontSize=11.sp)
                }
                IconButton(onClick=onDismiss){Icon(Icons.Default.Close,null)}
            }

            OutlinedTextField(
                value=query,
                onValueChange={query=it.take(120)},
                placeholder={Text("کلمه یا جمله...")},
                leadingIcon={Icon(Icons.Default.Search,null)},
                singleLine=true,
                modifier=Modifier.fillMaxWidth(),
                shape=RoundedCornerShape(16.dp)
            )

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                )
            }

            error?.let {
                Text(it,color=FqDanger,fontSize=11.sp,modifier=Modifier.padding(top=7.dp))
            }

            if(query.trim().length>=2 && !loading && results.isEmpty() && error==null) {
                PremiumEmptyState(
                    Icons.Default.SearchOff,
                    "پیامی پیدا نشد",
                    "عبارت دیگه‌ای رو امتحان کن."
                )
            } else {
                LazyColumn(
                    modifier=Modifier.heightIn(max=500.dp).padding(top=8.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    items(results.size,key={results[it].id}) { index ->
                        val msg=results[index]
                        RoomSearchResultCard(msg){onJump(msg.id)}
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomPinnedMessagesSheet(
    roomId:String,
    social:SocialRepository,
    onDismiss:()->Unit,
    onJump:(String)->Unit
) {
    var loading by remember { mutableStateOf(true) }
    var pinnedItems by remember { mutableStateOf<List<RoomMessageItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(roomId) {
        runCatching { social.pinnedRoomMessages(roomId) }
            .onSuccess { pinnedItems=it }
            .onFailure { error=it.message }
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start=14.dp,end=14.dp,bottom=20.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.PushPin,null,tint=FqGold)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text("پیام‌های Pin شده",fontSize=19.sp)
                    Text("حداکثر ۵۰ پیام",color=FqMuted,fontSize=11.sp)
                }
                IconButton(onClick=onDismiss){Icon(Icons.Default.Close,null)}
            }

            if(loading) {
                LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
            }
            error?.let {
                Text(it,color=FqDanger,fontSize=11.sp,modifier=Modifier.padding(top=7.dp))
            }

            if(!loading && pinnedItems.isEmpty() && error==null) {
                PremiumEmptyState(
                    Icons.Default.PushPin,
                    "پیام Pin شده‌ای نیست",
                    "پیام‌های مهم این Room رو Pin کن."
                )
            } else {
                LazyColumn(
                    modifier=Modifier.heightIn(max=500.dp).padding(top=8.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    items(pinnedItems.size,key={pinnedItems[it].id}) { index ->
                        val msg=pinnedItems[index]
                        RoomSearchResultCard(msg){onJump(msg.id)}
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomSearchResultCard(
    msg:RoomMessageItem,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface2,
        shape=RoundedCornerShape(15.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(11.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            RemoteImage(
                msg.author.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(38.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(msg.author.displayName,color=FqGold,fontSize=11.sp)
                    if(msg.pinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.PushPin,null,tint=FqGold,modifier=Modifier.size(11.dp))
                    }
                }
                Text(
                    msg.body.ifBlank { if(msg.type=="text")"پیام" else "رسانه" },
                    fontSize=11.sp,
                    maxLines=2,
                    color=Color.White.copy(alpha=.86f),
                    modifier=Modifier.padding(top=3.dp)
                )
                if(msg.editedAt!=null) {
                    Text(
                        "ویرایش شده",
                        color=FqMuted,
                        fontSize=6.sp,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
            }
            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
        }
    }
}

@Composable
private fun EditRoomMessageDialog(
    message:RoomMessageItem,
    onDismiss:()->Unit,
    onSave:(String,Boolean)->Unit
) {
    var body by remember(message.id) { mutableStateOf(message.body) }
    var spoiler by remember(message.id) { mutableStateOf(message.spoiler) }

    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.Edit,null,tint=FqGold)},
        title={Text("ویرایش پیام")},
        text={
            Column {
                OutlinedTextField(
                    value=body,
                    onValueChange={body=it.take(4000)},
                    minLines=3,
                    maxLines=8,
                    modifier=Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected=spoiler,
                    onClick={spoiler=!spoiler},
                    label={Text("Spoiler")},
                    leadingIcon={Icon(Icons.Default.VisibilityOff,null)},
                    modifier=Modifier.padding(top=8.dp)
                )
            }
        },
        confirmButton={
            Button(
                enabled=body.trim().isNotEmpty(),
                onClick={onSave(body.trim(),spoiler)},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ذخیره",color=Color.Black) }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}
