package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ConnectedWatchPartyScreen(
    media: MediaItem?,
    initialPartyId: String? = null,
    initialInviteCode: String? = null,
    backend: BackendRepository,
    social: SocialRepository,
    repository: TmdbRepository,
    onBack: () -> Unit,
    onRequireAuth: () -> Unit
) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    val partyRepo=remember { WatchPartyRepository(backend) }
    val listState=rememberLazyListState()

    var partyId by remember(initialPartyId) { mutableStateOf(initialPartyId) }
    var party by remember { mutableStateOf<WatchPartyInfo?>(null) }
    var meId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<RoomMessageItem>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var loadedVersion by remember { mutableStateOf<String?>(null) }
    var inviteInfo by remember { mutableStateOf<WatchPartyInviteInfo?>(null) }
    var reminderEnabled by remember { mutableStateOf(false) }
    var reminderBusy by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showLobby by remember { mutableStateOf(false) }
    var lobby by remember { mutableStateOf<WatchPartyLobby?>(null) }
    var reactions by remember { mutableStateOf<List<WatchPartyReaction>>(emptyList()) }
    var privateJoinRequired by remember { mutableStateOf(false) }
    var joinRequestPending by remember { mutableStateOf(false) }
    var lobbyBusy by remember { mutableStateOf(false) }

    val player=remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode=Player.REPEAT_MODE_OFF
            playWhenReady=false
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        if(backend.session.isLoggedIn) {
            meId=runCatching { backend.me().id }.getOrNull()
        }
    }


    LaunchedEffect(partyId,party?.state,party?.host?.id,meId,lobby?.myRole) {
        val id=partyId ?: return@LaunchedEffect
        val p=party ?: return@LaunchedEffect
        if(!backend.session.isLoggedIn) return@LaunchedEffect

        if(p.state=="scheduled") {
            reminderEnabled=runCatching {
                partyRepo.reminderEnabled(id)
            }.getOrDefault(false)
        } else {
            reminderEnabled=false
        }

        inviteInfo=if(p.host.id==meId || lobby?.myRole=="cohost") {
            runCatching { partyRepo.inviteInfo(id) }.getOrNull()
        } else null
    }

    LaunchedEffect(partyId) {
        val id=partyId ?: return@LaunchedEffect
        if(backend.session.isLoggedIn) {
            runCatching { partyRepo.join(id,initialInviteCode) }
                .onSuccess {
                    privateJoinRequired=false
                    joinRequestPending=false
                }
                .onFailure {
                    val message=it.message ?: "ورود به Watch Party ناموفق بود"
                    privateJoinRequired=message.contains("private",ignoreCase=true)
                    error=if(privateJoinRequired) null else message
                }
        }
        while(isActive && partyId==id) {
            val fresh=runCatching { partyRepo.detail(id) }
                .onFailure { error=it.message }
                .getOrNull()
            if(fresh!=null) {
                party=fresh
                error=null
                val version=fresh.media.mediaVersionId
                if(!version.isNullOrBlank() && loadedVersion!=version) {
                    runCatching { backend.playbackUrl(version) }
                        .onSuccess { url ->
                            loadedVersion=version
                            player.setMediaItem(ExoMediaItem.fromUri(url))
                            player.prepare()
                            player.seekTo(fresh.positionMs)
                            if(fresh.isPlaying) player.play() else player.pause()
                        }
                        .onFailure { error=it.message }
                }

                val host=fresh.host.id==meId
                if(!host && loadedVersion==version) {
                    val drift=abs(player.currentPosition-fresh.positionMs)
                    if(drift>1500) player.seekTo(fresh.positionMs)
                    if(fresh.isPlaying && !player.isPlaying) player.play()
                    if(!fresh.isPlaying && player.isPlaying) player.pause()
                }

                if(fresh.roomId.isNotBlank()) {
                    runCatching { social.roomMessages(fresh.roomId) }
                        .onSuccess {
                            val changed=it.size!=messages.size
                            messages=it
                            if(changed && it.isNotEmpty()) {
                                scope.launch { listState.animateScrollToItem(it.lastIndex) }
                            }
                        }
                }
            }
            delay(2000)
        }
    }

    LaunchedEffect(partyId,backend.session.isLoggedIn) {
        val id=partyId ?: return@LaunchedEffect
        if(!backend.session.isLoggedIn) return@LaunchedEffect
        while(isActive && partyId==id) {
            runCatching { partyRepo.lobby(id) }
                .onSuccess {
                    lobby=it
                    privateJoinRequired=false
                }
            reactions=runCatching { partyRepo.reactions(id) }.getOrDefault(emptyList())
            delay(2000)
        }
    }

    LaunchedEffect(partyId,party?.host?.id,meId,lobby?.myRole) {
        val id=partyId ?: return@LaunchedEffect
        while(isActive && partyId==id) {
            delay(2000)
            val p=party ?: continue
            if((p.host.id==meId || lobby?.myRole=="cohost") && p.state=="live" && player.duration>0) {
                syncing=true
                runCatching {
                    partyRepo.updateState(
                        id=id,
                        positionMs=player.currentPosition.coerceAtLeast(0),
                        isPlaying=player.isPlaying,
                        state="live"
                    )
                }.onFailure { error=it.message }
                syncing=false
            }
        }
    }

    if(partyId==null) {
        WatchPartyStartScreen(
            media=media,
            repository=repository,
            loggedIn=backend.session.isLoggedIn,
            creating=creating,
            error=error,
            onBack=onBack,
            onStart={visibility,scheduledAt->
                val backendId=media?.backendId
                if(!backend.session.isLoggedIn) {
                    onRequireAuth()
                } else if(backendId.isNullOrBlank()) {
                    error="این عنوان هنوز به Catalog واقعی Filmiqoo متصل نیست."
                } else {
                    creating=true
                    scope.launch {
                        runCatching {
                            partyRepo.create(
                                mediaTitleId=backendId,
                                title="Watch Party • "+media.title,
                                visibility=visibility,
                                scheduledAt=scheduledAt
                            )
                        }.onSuccess { created->
                            partyId=created.id
                            if(created.inviteCode.isNotBlank()) {
                                inviteInfo=WatchPartyInviteInfo(
                                    inviteCode=created.inviteCode,
                                    visibility=visibility,
                                    state=created.state,
                                    scheduledAt=created.scheduledAt
                                )
                            }
                            error=null
                        }.onFailure {
                            error=it.message
                        }
                        creating=false
                    }
                }
            }
        )
        return
    }

    val p=party
    if(p==null) {
        LoadingPage("در حال اتصال به Watch Party...")
        return
    }

    val isHost=p.host.id==meId
    val canHostControl=isHost || lobby?.myRole=="cohost"

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().weight(.47f).background(Color.Black)
        ) {
            AndroidView(
                factory={ctx->
                    PlayerView(ctx).apply {
                        this.player=player
                        useController=false
                        resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn=true
                    }
                },
                update={it.player=player},
                modifier=Modifier.fillMaxSize()
            )

            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha=.55f),Color.Transparent,Color.Black.copy(alpha=.65f))
                    )
                )
            )

            Row(
                Modifier.fillMaxWidth().padding(9.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(
                    onClick=onBack,
                    modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.45f))
                ) { Icon(Icons.Default.Close,null) }
                Spacer(Modifier.weight(1f))
                if(canHostControl && p.visibility in setOf("invite","private")) {
                    IconButton(
                        onClick={showInviteDialog=true},
                        modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.45f))
                    ) {
                        Icon(Icons.Default.Key,null)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                IconButton(
                    onClick={
                        val code=when {
                            p.visibility=="invite" -> inviteInfo?.inviteCode ?: initialInviteCode
                            else -> null
                        }
                        FilmiqooDeepLinks.share(
                            context,
                            p.title,
                            FilmiqooDeepLinks.watchParty(p.id,code)
                        )
                    },
                    modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.45f))
                ) {
                    Icon(Icons.Default.Share,null)
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    color=if(p.state=="live")FqDanger else FqSurface2,
                    shape=RoundedCornerShape(9.dp)
                ) {
                    Text(
                        if(p.state=="live")"● LIVE" else p.state.uppercase(),
                        fontSize=8.sp,
                        modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp)
                    )
                }
            }

            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)
            ) {
                Text(p.title,fontSize=18.sp)
                Text(
                    p.media.title+" • "+p.participants+" نفر • "+(p.media.quality ?: "Auto"),
                    color=Color.White.copy(alpha=.7f),
                    fontSize=8.sp
                )

                Row(
                    Modifier.fillMaxWidth().padding(top=10.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    if(canHostControl) {
                        Button(
                            onClick={
                                if(p.state=="scheduled") {
                                    player.play()
                                    scope.launch {
                                        runCatching {
                                            partyRepo.updateState(
                                                p.id,
                                                player.currentPosition.coerceAtLeast(0),
                                                true,
                                                state="live"
                                            )
                                        }.onFailure { error=it.message }
                                    }
                                } else {
                                    if(player.isPlaying) player.pause() else player.play()
                                    scope.launch {
                                        runCatching {
                                            partyRepo.updateState(
                                                p.id,
                                                player.currentPosition.coerceAtLeast(0),
                                                player.isPlaying
                                            )
                                        }.onFailure { error=it.message }
                                    }
                                }
                            },
                            enabled=!(
                                p.state=="scheduled" &&
                                lobby?.readyCheckEnabled==true &&
                                (lobby?.readyCount ?: 0L) < (lobby?.participantCount ?: 0L)
                            ),
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                        ) {
                            Icon(
                                if(p.state=="scheduled")Icons.Default.RocketLaunch
                                else if(player.isPlaying)Icons.Default.Pause
                                else Icons.Default.PlayArrow,
                                null
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if(p.state=="scheduled")"شروع الان"
                                else if(player.isPlaying)"Pause for all"
                                else "Play for all"
                            )
                        }
                    } else {
                        Surface(color=Color.Black.copy(alpha=.55f),shape=RoundedCornerShape(11.dp)) {
                            Text(
                                if(p.state=="scheduled")
                                    "Party زمان‌بندی شده • منتظر شروع میزبان"
                                else
                                    "کنترل پخش با میزبان • Sync فعال",
                                fontSize=8.sp,
                                modifier=Modifier.padding(horizontal=10.dp,vertical=7.dp)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if(syncing) CircularProgressIndicator(color=FqGold,strokeWidth=2.dp,modifier=Modifier.size(18.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=9.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            RemoteImage(p.host.avatarUrl.takeIf(String::isNotBlank),Modifier.size(38.dp).clip(CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(p.host.displayName,fontSize=10.sp)
                Text("میزبان • @"+p.host.username,color=FqMuted,fontSize=7.sp)
            }
            TextButton(
                onClick={showLobby=true},
                enabled=lobby!=null
            ) {
                Icon(Icons.Default.Groups,null,tint=FqGold,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    (lobby?.participantCount ?: p.participants).toString()+" نفر",
                    fontSize=8.sp
                )
            }
        }

        if(privateJoinRequired) {
            Surface(
                color=FqDanger.copy(alpha=.08f),
                shape=RoundedCornerShape(15.dp),
                modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,bottom=8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock,null,tint=FqGold)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Watch Party خصوصی",fontSize=9.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            if(joinRequestPending)"درخواست ورود ارسال شده؛ منتظر تأیید میزبان."
                            else "برای ورود باید میزبان درخواستت رو تأیید کنه.",
                            color=FqMuted,fontSize=7.sp,modifier=Modifier.padding(top=2.dp)
                        )
                    }
                    Button(
                        enabled=!joinRequestPending && !lobbyBusy,
                        onClick={
                            if(!backend.session.isLoggedIn) {
                                onRequireAuth()
                            } else {
                                lobbyBusy=true
                                scope.launch {
                                    runCatching { partyRepo.requestJoin(p.id) }
                                        .onSuccess { joinRequestPending=it=="pending" }
                                        .onFailure { error=it.message }
                                    lobbyBusy=false
                                }
                            }
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                    ) {
                        Text(if(joinRequestPending)"ارسال شد" else "درخواست ورود",color=Color.Black,fontSize=7.sp)
                    }
                }
            }
        }

        if(p.state=="scheduled") {
            Surface(
                color=FqGold.copy(alpha=.08f),
                shape=RoundedCornerShape(15.dp),
                modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,bottom=8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Event,null,tint=FqGold)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text("زمان‌بندی شده",fontSize=9.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            p.scheduledAt?.let(::formatPartySchedule) ?: "زمان شروع ثبت شده",
                            color=FqMuted,
                            fontSize=7.sp,
                            modifier=Modifier.padding(top=2.dp)
                        )
                    }
                    OutlinedButton(
                        enabled=!reminderBusy,
                        onClick={
                            if(!backend.session.isLoggedIn) {
                                onRequireAuth()
                            } else {
                                reminderBusy=true
                                scope.launch {
                                    runCatching { partyRepo.toggleReminder(p.id) }
                                        .onSuccess { reminderEnabled=it }
                                        .onFailure { error=it.message }
                                    reminderBusy=false
                                }
                            }
                        },
                        contentPadding=PaddingValues(horizontal=9.dp,vertical=4.dp)
                    ) {
                        Icon(
                            if(reminderEnabled)Icons.Default.NotificationsActive
                            else Icons.Default.NotificationsNone,
                            null,
                            tint=if(reminderEnabled)FqGold else FqMuted,
                            modifier=Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if(reminderEnabled)"Reminder روشن" else "یادم بنداز",fontSize=7.sp)
                    }
                }
            }
        }

        if(p.state=="scheduled" && lobby?.readyCheckEnabled==true && lobby!=null) {
            val currentLobby=lobby!!
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(15.dp),
                modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,bottom=8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.HowToReg,null,tint=FqGold)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ready Check",fontSize=9.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            currentLobby.readyCount.toString()+" از "+currentLobby.participantCount+" نفر آماده‌اند",
                            color=FqMuted,fontSize=7.sp,modifier=Modifier.padding(top=2.dp)
                        )
                    }
                    OutlinedButton(
                        enabled=!lobbyBusy,
                        onClick={
                            lobbyBusy=true
                            scope.launch {
                                runCatching { partyRepo.toggleReady(p.id) }
                                    .onSuccess { ready->
                                        lobby=lobby?.copy(
                                            myReady=ready,
                                            readyCount=(
                                                (lobby?.readyCount ?: 0L) +
                                                if(ready)1 else -1
                                            ).coerceAtLeast(0L)
                                        )
                                    }
                                    .onFailure { error=it.message }
                                lobbyBusy=false
                            }
                        }
                    ) {
                        Icon(
                            if(currentLobby.myReady)Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint=if(currentLobby.myReady)FqGreen else FqMuted,
                            modifier=Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if(currentLobby.myReady)"آماده‌ام" else "Ready",fontSize=7.sp)
                    }
                }
            }
        }

        WatchPartyReactionBar(
            reactions=reactions,
            enabled=lobby!=null && !privateJoinRequired,
            onReact={emoji->
                scope.launch {
                    runCatching { partyRepo.react(p.id,emoji) }
                        .onFailure { error=it.message }
                }
            }
        )

        HorizontalDivider(color=FqSurface3)

        LazyColumn(
            state=listState,
            modifier=Modifier.weight(.53f),
            contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            items(messages,key={it.id}) { msg ->
                Row(verticalAlignment=Alignment.Top) {
                    RemoteImage(
                        msg.author.avatarUrl.takeIf(String::isNotBlank),
                        Modifier.size(30.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text(msg.author.displayName,color=FqGold,fontSize=8.sp)
                        Text(msg.body,fontSize=9.sp,modifier=Modifier.padding(top=2.dp))
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(FqSurface).padding(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value=text,
                onValueChange={text=it},
                placeholder={Text("پیام Watch Party...")},
                singleLine=true,
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.weight(1f)
            )
            IconButton(onClick={
                if(!backend.session.isLoggedIn) {
                    onRequireAuth()
                } else if(
                    text.isNotBlank() &&
                    p.roomId.isNotBlank() &&
                    lobby!=null &&
                    !privateJoinRequired
                ) {
                    val sending=text.trim()
                    text=""
                    scope.launch {
                        runCatching { social.sendMessage(p.roomId,sending,false) }
                            .onFailure { error=it.message }
                    }
                }
            }) {
                Icon(Icons.Default.Send,null,tint=if(text.isBlank())FqMuted else FqGold)
            }
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=8.sp,
                modifier=Modifier.fillMaxWidth().background(FqDanger.copy(alpha=.08f)).padding(6.dp)
            )
        }
    }
    if(showLobby && lobby!=null) {
        WatchPartyLobbySheet(
            lobby=lobby!!,
            busy=lobbyBusy,
            onToggleReadyCheck={enabled->
                lobbyBusy=true
                scope.launch {
                    runCatching { partyRepo.setReadyCheck(p.id,enabled) }
                        .onSuccess {
                            lobby=lobby?.copy(readyCheckEnabled=it)
                        }
                        .onFailure { error=it.message }
                    lobbyBusy=false
                }
            },
            onReady={
                lobbyBusy=true
                scope.launch {
                    runCatching { partyRepo.toggleReady(p.id) }
                        .onSuccess { ready->
                            val before=lobby?.myReady ?: false
                            val delta=when {
                                ready && !before -> 1
                                !ready && before -> -1
                                else -> 0
                            }
                            lobby=lobby?.copy(
                                myReady=ready,
                                readyCount=((lobby?.readyCount ?: 0L)+delta).coerceAtLeast(0L)
                            )
                        }
                        .onFailure { error=it.message }
                    lobbyBusy=false
                }
            },
            onResolve={userId,accept->
                lobbyBusy=true
                scope.launch {
                    runCatching { partyRepo.resolveJoinRequest(p.id,userId,accept) }
                        .onSuccess {
                            lobby=runCatching { partyRepo.lobby(p.id) }.getOrNull() ?: lobby
                        }
                        .onFailure { error=it.message }
                    lobbyBusy=false
                }
            },
            onRole={userId,role->
                lobbyBusy=true
                scope.launch {
                    runCatching { partyRepo.setMemberRole(p.id,userId,role) }
                        .onSuccess {
                            lobby=runCatching { partyRepo.lobby(p.id) }.getOrNull() ?: lobby
                        }
                        .onFailure { error=it.message }
                    lobbyBusy=false
                }
            },
            onLeaveOrEnd={
                lobbyBusy=true
                scope.launch {
                    if(canHostControl) {
                        runCatching {
                            partyRepo.updateState(
                                p.id,
                                player.currentPosition.coerceAtLeast(0),
                                false,
                                "ended"
                            )
                        }.onSuccess {
                            showLobby=false
                            onBack()
                        }.onFailure { error=it.message }
                    } else {
                        runCatching { partyRepo.leave(p.id) }
                            .onSuccess {
                                showLobby=false
                                onBack()
                            }
                            .onFailure { error=it.message }
                    }
                    lobbyBusy=false
                }
            },
            onDismiss={showLobby=false}
        )
    }

    if(showInviteDialog && inviteInfo!=null) {
        WatchPartyInviteDialog(
            party=p,
            info=inviteInfo!!,
            busy=reminderBusy,
            onShare={
                FilmiqooDeepLinks.share(
                    context,
                    p.title,
                    FilmiqooDeepLinks.watchParty(p.id,inviteInfo?.inviteCode)
                )
            },
            onRegenerate={
                reminderBusy=true
                scope.launch {
                    runCatching { partyRepo.regenerateInvite(p.id) }
                        .onSuccess { code->
                            inviteInfo=inviteInfo?.copy(inviteCode=code)
                        }
                        .onFailure { error=it.message }
                    reminderBusy=false
                }
            },
            onDismiss={showInviteDialog=false}
        )
    }
}

@Composable
private fun WatchPartyStartScreen(
    media: MediaItem?,
    repository: TmdbRepository,
    loggedIn: Boolean,
    creating: Boolean,
    error: String?,
    onBack: () -> Unit,
    onStart: (String,String?) -> Unit
) {
    var visibility by remember { mutableStateOf("public") }
    var schedule by remember { mutableStateOf("now") }

    val scheduledAt=when(schedule) {
        "30m" -> java.time.Instant.now().plusSeconds(30*60L).toString()
        "1h" -> java.time.Instant.now().plusSeconds(60*60L).toString()
        "tomorrow" -> java.time.Instant.now().plusSeconds(24*60*60L).toString()
        else -> null
    }
    Box(Modifier.fillMaxSize().background(FqBg)) {
        RemoteImage(
            repository.backdrop(media?.backdropPath ?: media?.posterPath),
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha=.5f),Color.Black.copy(alpha=.72f),FqBg)
                )
            )
        )
        IconButton(
            onClick=onBack,
            modifier=Modifier.align(Alignment.TopStart).padding(10.dp)
        ) { Icon(Icons.Default.Close,null) }

        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(26.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(82.dp).clip(CircleShape).background(FqGold),
                contentAlignment=Alignment.Center
            ) {
                Icon(Icons.Default.Groups,null,tint=Color.Black,modifier=Modifier.size(45.dp))
            }
            Text("Watch Party",fontSize=30.sp,modifier=Modifier.padding(top=15.dp))
            Text(
                media?.title ?: "یک عنوان انتخاب کن",
                color=FqGold,
                fontSize=13.sp,
                modifier=Modifier.padding(top=5.dp)
            )
            Text(
                "تماشای همزمان، کنترل میزبان، Sync موقعیت پخش، دعوت خصوصی و Reminder.",
                color=Color.White.copy(alpha=.72f),
                fontSize=9.sp,
                lineHeight=16.sp,
                modifier=Modifier.padding(top=9.dp)
            )

            Text(
                "دسترسی",
                fontSize=10.sp,
                fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,
                modifier=Modifier.align(Alignment.Start).padding(top=18.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top=7.dp),
                horizontalArrangement=Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "public" to "Public",
                    "invite" to "Invite",
                    "private" to "Private"
                ).forEach { option ->
                    FilterChip(
                        selected=visibility==option.first,
                        onClick={visibility=option.first},
                        label={Text(option.second,fontSize=7.sp)}
                    )
                }
            }

            Text(
                "زمان شروع",
                fontSize=10.sp,
                fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,
                modifier=Modifier.align(Alignment.Start).padding(top=10.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top=7.dp),
                horizontalArrangement=Arrangement.spacedBy(5.dp)
            ) {
                listOf(
                    "now" to "الان",
                    "30m" to "۳۰ دقیقه",
                    "1h" to "۱ ساعت",
                    "tomorrow" to "فردا"
                ).forEach { option ->
                    FilterChip(
                        selected=schedule==option.first,
                        onClick={schedule=option.first},
                        label={Text(option.second,fontSize=7.sp)}
                    )
                }
            }

            if(scheduledAt!=null) {
                Text(
                    "شروع: "+formatPartySchedule(scheduledAt),
                    color=FqGold,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=6.dp)
                )
            }

            Button(
                onClick={onStart(visibility,scheduledAt)},
                enabled=!creating,
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                shape=RoundedCornerShape(15.dp),
                modifier=Modifier.fillMaxWidth().padding(top=18.dp)
            ) {
                if(creating) {
                    CircularProgressIndicator(color=Color.Black,strokeWidth=2.dp,modifier=Modifier.size(18.dp))
                } else {
                    Icon(Icons.Default.LiveTv,null,tint=Color.Black)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if(!loggedIn)"ورود و شروع"
                    else if(scheduledAt==null)"شروع Watch Party"
                    else "زمان‌بندی Watch Party",
                    color=Color.Black
                )
            }
            error?.let {
                Text(it,color=FqDanger,fontSize=8.sp,modifier=Modifier.padding(top=10.dp))
            }
        }
    }
}


@Composable
private fun WatchPartyInviteDialog(
    party:WatchPartyInfo,
    info:WatchPartyInviteInfo,
    busy:Boolean,
    onShare:()->Unit,
    onRegenerate:()->Unit,
    onDismiss:()->Unit
) {
    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.VpnKey,null,tint=FqGold)},
        title={Text("دعوت به Watch Party")},
        text={
            Column {
                Text(
                    when(info.visibility) {
                        "invite" -> "فقط افرادی که لینک دارای کد دعوت رو دارن می‌تونن وارد بشن."
                        "private" -> "این Party خصوصی است و ورود عمومی بسته است."
                        else -> "این Party عمومی است."
                    },
                    color=FqMuted,
                    fontSize=9.sp,
                    lineHeight=15.sp
                )
                if(info.inviteCode.isNotBlank()) {
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(13.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Invite Code",color=FqMuted,fontSize=7.sp)
                            Text(
                                info.inviteCode.uppercase(),
                                color=FqGold,
                                fontSize=20.sp,
                                fontWeight=androidx.compose.ui.text.font.FontWeight.Black,
                                modifier=Modifier.padding(top=4.dp)
                            )
                        }
                    }
                }
                Text(
                    party.title,
                    fontSize=9.sp,
                    modifier=Modifier.padding(top=10.dp)
                )
            }
        },
        confirmButton={
            Button(
                onClick=onShare,
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) {
                Icon(Icons.Default.Share,null,tint=Color.Black)
                Spacer(Modifier.width(5.dp))
                Text("اشتراک لینک",color=Color.Black)
            }
        },
        dismissButton={
            Row {
                if(info.visibility=="invite") {
                    TextButton(
                        enabled=!busy,
                        onClick=onRegenerate
                    ) {
                        Icon(Icons.Default.Refresh,null)
                        Spacer(Modifier.width(3.dp))
                        Text("کد جدید",fontSize=8.sp)
                    }
                }
                TextButton(onClick=onDismiss){Text("بستن")}
            }
        }
    )
}

@Composable
private fun WatchPartyReactionBar(
    reactions:List<WatchPartyReaction>,
    enabled:Boolean,
    onReact:(String)->Unit
) {
    val emojis=listOf("❤️","😂","😮","🔥","👏","😢","🤯")
    Column(
        Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp)
    ) {
        if(reactions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.spacedBy(5.dp)
            ) {
                reactions.take(7).forEach { reaction ->
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            reaction.emoji,
                            fontSize=14.sp,
                            modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top=if(reactions.isEmpty())0.dp else 5.dp),
            horizontalArrangement=Arrangement.SpaceEvenly
        ) {
            emojis.forEach { emoji ->
                Text(
                    emoji,
                    fontSize=20.sp,
                    modifier=Modifier
                        .clip(CircleShape)
                        .clickable(enabled=enabled) { onReact(emoji) }
                        .padding(5.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchPartyLobbySheet(
    lobby:WatchPartyLobby,
    busy:Boolean,
    onToggleReadyCheck:(Boolean)->Unit,
    onReady:()->Unit,
    onResolve:(String,Boolean)->Unit,
    onRole:(String,String)->Unit,
    onLeaveOrEnd:()->Unit,
    onDismiss:()->Unit
) {
    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,bottom=28.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Waiting Room",fontSize=20.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Black)
                    Text(
                        lobby.participantCount.toString()+" نفر • "+
                            lobby.readyCount+" Ready",
                        color=FqMuted,fontSize=8.sp
                    )
                }
                if(lobby.myRole=="host" || lobby.myRole=="cohost") {
                    FilterChip(
                        selected=lobby.readyCheckEnabled,
                        onClick={onToggleReadyCheck(!lobby.readyCheckEnabled)},
                        label={Text("Ready Check",fontSize=7.sp)},
                        leadingIcon={
                            Icon(Icons.Default.HowToReg,null,modifier=Modifier.size(15.dp))
                        }
                    )
                }
            }

            if(lobby.readyCheckEnabled) {
                Button(
                    onClick=onReady,
                    enabled=!busy,
                    colors=ButtonDefaults.buttonColors(
                        containerColor=if(lobby.myReady)FqGreen else FqGold,
                        contentColor=Color.Black
                    ),
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                ) {
                    Icon(
                        if(lobby.myReady)Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if(lobby.myReady)"Ready هستم" else "من آماده‌ام")
                }
            }

            if(lobby.requests.isNotEmpty() && (lobby.myRole=="host" || lobby.myRole=="cohost")) {
                Text(
                    "درخواست‌های ورود",
                    fontSize=11.sp,
                    fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier=Modifier.padding(top=14.dp,bottom=6.dp)
                )
                lobby.requests.forEach { request ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical=5.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        RemoteImage(
                            request.avatarUrl.takeIf(String::isNotBlank),
                            Modifier.size(38.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(request.displayName,fontSize=9.sp)
                            Text("@"+request.username,color=FqMuted,fontSize=7.sp)
                        }
                        IconButton(
                            enabled=!busy,
                            onClick={onResolve(request.id,false)}
                        ) { Icon(Icons.Default.Close,null,tint=FqDanger) }
                        IconButton(
                            enabled=!busy,
                            onClick={onResolve(request.id,true)}
                        ) { Icon(Icons.Default.Check,null,tint=FqGreen) }
                    }
                }
            }

            Text(
                "اعضا",
                fontSize=11.sp,
                fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,
                modifier=Modifier.padding(top=14.dp,bottom=5.dp)
            )

            LazyColumn(
                modifier=Modifier.heightIn(max=340.dp),
                verticalArrangement=Arrangement.spacedBy(5.dp)
            ) {
                items(lobby.members,key={it.id}) { member ->
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(9.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            RemoteImage(
                                member.avatarUrl.takeIf(String::isNotBlank),
                                Modifier.size(38.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(member.displayName,fontSize=9.sp)
                                    if(member.verified) {
                                        Spacer(Modifier.width(3.dp))
                                        Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(12.dp))
                                    }
                                }
                                Text(
                                    when(member.role) {
                                        "host" -> "Host"
                                        "cohost" -> "Co-host"
                                        "moderator" -> "Moderator"
                                        else -> "Viewer"
                                    }+" • @"+member.username,
                                    color=FqMuted,fontSize=7.sp
                                )
                            }
                            if(lobby.readyCheckEnabled) {
                                Icon(
                                    if(member.ready)Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    null,
                                    tint=if(member.ready)FqGreen else FqMuted,
                                    modifier=Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            if(lobby.myRole=="host" && member.role!="host") {
                                TextButton(
                                    enabled=!busy,
                                    onClick={
                                        onRole(
                                            member.id,
                                            if(member.role=="cohost")"viewer" else "cohost"
                                        )
                                    }
                                ) {
                                    Text(
                                        if(member.role=="cohost")"Viewer" else "Co-host",
                                        fontSize=7.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                enabled=!busy,
                onClick=onLeaveOrEnd,
                colors=ButtonDefaults.outlinedButtonColors(contentColor=FqDanger),
                modifier=Modifier.fillMaxWidth().padding(top=14.dp)
            ) {
                Icon(
                    if(lobby.myRole=="host" || lobby.myRole=="cohost")Icons.Default.StopCircle
                    else Icons.Default.ExitToApp,
                    null
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if(lobby.myRole=="host" || lobby.myRole=="cohost")
                        "پایان Watch Party"
                    else
                        "خروج از Watch Party"
                )
            }
        }
    }
}

private fun formatPartySchedule(value:String):String =
    runCatching {
        val instant=java.time.Instant.parse(value)
        val formatter=java.time.format.DateTimeFormatter.ofPattern(
            "yyyy/MM/dd • HH:mm",
            java.util.Locale.forLanguageTag("fa")
        ).withZone(java.time.ZoneId.systemDefault())
        formatter.format(instant)
    }.getOrDefault(value)
