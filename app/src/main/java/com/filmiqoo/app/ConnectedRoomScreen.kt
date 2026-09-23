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
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(msg.author.displayName,color=FqGold,fontSize=9.sp)
                                if(msg.author.verified) {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(12.dp))
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
                                    Text(msg.body,fontSize=10.sp,lineHeight=17.sp,modifier=Modifier.padding(top=5.dp))
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
}
