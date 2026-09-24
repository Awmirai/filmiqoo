package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val realtime=remember(backend) { RoomRealtimeClient(backend.session) }
    val messaging=remember(backend) { MessagingRepository(backend) }

    suspend fun refresh() {
        runCatching { social.roomMessages(roomId) }
            .onSuccess {
                val changed=it.size!=messages.size
                messages=it
                syncing=false
                if(loggedIn) {
                    runCatching { messaging.markRoomRead(roomId) }
                }
                if(changed && it.isNotEmpty()) {
                    scope.launch { listState.animateScrollToItem(it.lastIndex) }
                }
            }
            .onFailure { error=it.message }
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
                spoiler=false
                refresh()
            }.onFailure {
                error=it.message
            }
            uploading=false
        }
    }



    LaunchedEffect(loggedIn) {
        meId=if(loggedIn) runCatching { backend.me().id }.getOrNull() else null
    }

    LaunchedEffect(roomId) {
        refresh()
        while(isActive) {
            // Safety resync; live updates normally arrive over WebSocket.
            delay(30_000)
            refresh()
        }
    }

    DisposableEffect(roomId,loggedIn) {
        val socket=if(loggedIn) {
            realtime.connect(
                roomId=roomId,
                onConnected={
                    scope.launch {
                        realtimeConnected=true
                        error=null
                    }
                },
                onEvent={
                    scope.launch {
                        refresh()
                    }
                },
                onDisconnected={reason ->
                    scope.launch {
                        realtimeConnected=false
                        if(!reason.isNullOrBlank()) error=reason
                    }
                }
            )
        } else null
        onDispose {
            socket?.close(1000,"screen closed")
        }
    }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(FqSurface).padding(horizontal=8.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
            Box(Modifier.size(42.dp).background(FqGold,CircleShape),contentAlignment=Alignment.Center) {
                Icon(Icons.Default.Forum,null,tint=Color.Black)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title,fontSize=14.sp)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(
                        Modifier.size(6.dp).background(
                            if(realtimeConnected)FqGreen else if(error==null)FqGold else FqDanger,
                            CircleShape
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            realtimeConnected -> "Realtime • WebSocket"
                            syncing -> "در حال همگام‌سازی..."
                            loggedIn -> "اتصال Realtime در حال بازیابی"
                            else -> "حالت فقط مشاهده"
                        },
                        color=FqMuted,fontSize=8.sp
                    )
                }
            }
            IconButton(onClick={pinsOpen=true}) {
                Icon(Icons.Default.PushPin,null)
            }
            IconButton(onClick={
                if(!loggedIn) onRequireAuth() else searchOpen=true
            }) {
                Icon(Icons.Default.Search,null)
            }
            IconButton(
                onClick={
                    FilmiqooDeepLinks.share(
                        context,
                        title,
                        FilmiqooDeepLinks.room(roomId,title)
                    )
                }
            ) { Icon(Icons.Default.Share,null) }
            IconButton(onClick={scope.launch{refresh()}}) { Icon(Icons.Default.Refresh,null) }
        }

        error?.let {
            Text(it,color=FqDanger,fontSize=9.sp,modifier=Modifier.fillMaxWidth().background(FqDanger.copy(alpha=.08f)).padding(8.dp))
        }

        LazyColumn(
            state=listState,
            modifier=Modifier.weight(1f),
            contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            items(messages.size,key={messages[it].id}) { index ->
                val msg=messages[index]
                var reveal by remember(msg.id) { mutableStateOf(!msg.spoiler) }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                    RemoteImage(msg.author.avatarUrl.takeIf(String::isNotBlank),Modifier.size(34.dp).background(FqSurface2,CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Surface(color=FqSurface,shape=RoundedCornerShape(16.dp),modifier=Modifier.weight(1f)) {
                        Column(Modifier.padding(10.dp)) {
                            var menuOpen by remember(msg.id) { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Text(msg.author.displayName,color=FqGold,fontSize=9.sp)
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
                                            }
                                        )
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

                            if(!msg.replyPreview.isNullOrBlank()) {
                                Surface(
                                    color=FqSurface2,
                                    shape=RoundedCornerShape(10.dp),
                                    modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                                ) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(msg.replyAuthor ?: "Reply",color=FqGold,fontSize=7.sp)
                                        Text(
                                            msg.replyPreview,
                                            color=FqMuted,
                                            fontSize=7.sp,
                                            maxLines=2
                                        )
                                    }
                                }
                            }

                            if(msg.spoiler && !reveal) {
                                Text(
                                    "⚠ Spoiler Shield • نمایش پیام",
                                    color=FqDanger,fontSize=9.sp,
                                    modifier=Modifier.padding(top=5.dp).clickable { reveal=true }
                                )
                            } else {
                                if(!msg.attachmentUrl.isNullOrBlank()) {
                                    Box(
                                        Modifier.fillMaxWidth().height(190.dp)
                                            .padding(top=7.dp).clip(RoundedCornerShape(12.dp))
                                    ) {
                                        RemoteImage(
                                            msg.attachmentUrl,
                                            Modifier.fillMaxSize(),
                                            ContentScale.Crop
                                        )
                                        if(msg.type=="video") {
                                            Box(
                                                Modifier.size(46.dp).align(Alignment.Center)
                                                    .background(Color.Black.copy(alpha=.55f),CircleShape),
                                                contentAlignment=Alignment.Center
                                            ) {
                                                Icon(Icons.Default.PlayArrow,null,tint=Color.White)
                                            }
                                        }
                                    }
                                }
                                if(msg.body.isNotBlank()) {
                                    Text(
                                        msg.body,
                                        fontSize=10.sp,
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

                            Row(
                                Modifier.fillMaxWidth().padding(top=7.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick={replyTo=msg},
                                    contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)
                                ) {
                                    Icon(Icons.Default.Reply,null,modifier=Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("پاسخ",fontSize=7.sp)
                                }
                                listOf("❤️","🔥","😂","👍").forEach { reaction ->
                                    val count=msg.reactions[reaction] ?: 0L
                                    Text(
                                        reaction + if(count>0)" "+count else "",
                                        fontSize=8.sp,
                                        modifier=Modifier.padding(horizontal=3.dp)
                                            .clickable {
                                                if(!loggedIn) {
                                                    onRequireAuth()
                                                } else {
                                                    scope.launch {
                                                        runCatching {
                                                            social.toggleMessageReaction(roomId,msg.id,reaction)
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

        Column(Modifier.fillMaxWidth().background(FqSurface)) {
            replyTo?.let { reply ->
                Row(
                    Modifier.fillMaxWidth().background(FqSurface2).padding(horizontal=10.dp,vertical=7.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Reply,null,tint=FqGold,modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("پاسخ به "+reply.author.displayName,color=FqGold,fontSize=7.sp)
                        Text(reply.body.ifBlank{"رسانه"},color=FqMuted,fontSize=7.sp,maxLines=1)
                    }
                    IconButton(onClick={replyTo=null},modifier=Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close,null,modifier=Modifier.size(16.dp))
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(
                    enabled=!uploading,
                    onClick={
                        if(!loggedIn) onRequireAuth()
                        else mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                ) {
                    if(uploading) {
                        CircularProgressIndicator(color=FqGold,strokeWidth=2.dp,modifier=Modifier.size(19.dp))
                    } else {
                        Icon(Icons.Default.AddPhotoAlternate,null,tint=FqGold)
                    }
                }
                FilterChip(
                    selected=spoiler,
                    onClick={spoiler=!spoiler},
                    label={Text("Spoiler",fontSize=8.sp)}
                )
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value=text,onValueChange={text=it},
                    placeholder={Text("پیام...")},
                    shape=RoundedCornerShape(20.dp),
                    modifier=Modifier.weight(1f),
                    maxLines=4
                )
                IconButton(onClick={
                    if(!loggedIn) {
                        onRequireAuth()
                    } else if(text.isNotBlank()) {
                        val sending=text.trim()
                        text=""
                        val replyId=replyTo?.id
                        replyTo=null
                        scope.launch {
                            runCatching { social.sendMessage(roomId,sending,spoiler,replyId) }
                                .onSuccess { spoiler=false;refresh() }
                                .onFailure { error=it.message }
                        }
                    }
                }) {
                    Icon(Icons.Default.Send,null,tint=if(text.isBlank())FqMuted else FqGold)
                }
            }
        }
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
                    Text("داخل همین Room",color=FqMuted,fontSize=8.sp)
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
                Text(it,color=FqDanger,fontSize=8.sp,modifier=Modifier.padding(top=7.dp))
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
                    Text("حداکثر ۵۰ پیام",color=FqMuted,fontSize=8.sp)
                }
                IconButton(onClick=onDismiss){Icon(Icons.Default.Close,null)}
            }

            if(loading) {
                LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
            }
            error?.let {
                Text(it,color=FqDanger,fontSize=8.sp,modifier=Modifier.padding(top=7.dp))
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
                    Text(msg.author.displayName,color=FqGold,fontSize=8.sp)
                    if(msg.pinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.PushPin,null,tint=FqGold,modifier=Modifier.size(11.dp))
                    }
                }
                Text(
                    msg.body.ifBlank { if(msg.type=="text")"پیام" else "رسانه" },
                    fontSize=9.sp,
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
