package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject
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
    val realtime=remember(backend) { WatchPartyRealtimeClient(backend.session) }
    val listState=rememberLazyListState()

    var partyId by rememberSaveable(initialPartyId) { mutableStateOf(initialPartyId) }
    var party by remember { mutableStateOf<WatchPartyInfo?>(null) }
    var meId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<RoomMessageItem>>(emptyList()) }
    var text by rememberSaveable(partyId) { mutableStateOf("") }
    var sendingMessage by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var loadedVersion by remember { mutableStateOf<String?>(null) }
    var inviteInfo by remember { mutableStateOf<WatchPartyInviteInfo?>(null) }
    var reminderEnabled by remember { mutableStateOf(false) }
    var reminderBusy by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showFriendsInvite by remember { mutableStateOf(false) }
    var showLobby by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var lobby by remember { mutableStateOf<WatchPartyLobby?>(null) }
    var reactions by remember { mutableStateOf<List<WatchPartyReaction>>(emptyList()) }
    var privateJoinRequired by remember { mutableStateOf(false) }
    var joinRequestPending by remember { mutableStateOf(false) }
    var lobbyBusy by remember { mutableStateOf(false) }
    var realtimeConnected by remember { mutableStateOf(false) }
    var partyReceivedAt by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var joinSucceeded by remember(partyId) { mutableStateOf(false) }
    var retryConnection by remember { mutableIntStateOf(0) }
    var stateWriteBusy by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var resolvedStartMedia by remember(media?.key) {
        mutableStateOf(media?.takeIf { !it.backendId.isNullOrBlank() })
    }
    var startPlatformDetail by remember(media?.key) { mutableStateOf<PlatformDetail?>(null) }
    var resolvingStartMedia by remember(media?.key) {
        mutableStateOf(media!=null && media.backendId.isNullOrBlank())
    }
    var selectedEpisodeId by rememberSaveable(media?.key) { mutableStateOf<String?>(null) }

    val player=remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode=Player.REPEAT_MODE_OFF
            playWhenReady=false
        }
    }

    DisposableEffect(Unit) {
        val listener=object:Player.Listener {
            override fun onPlayerError(playerError:PlaybackException) { playbackError="پخش ویدیو قطع شد؛ دوباره اتصال پخش را امتحان کن." }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener);player.release() }
    }

    LaunchedEffect(partyId,lobby!=null,privateJoinRequired,backend.session.isLoggedIn,party?.state,party?.serverTimed) {
        val id=partyId ?: return@LaunchedEffect
        if(!backend.session.isLoggedIn || lobby==null || privateJoinRequired || party?.serverTimed!=true || party?.state in setOf("ended","cancelled"))return@LaunchedEffect
        var retryMs=1000L
        var accepting=true
        var socket:WebSocket?=null
        try { while(isActive) {
            val disconnected=CompletableDeferred<Unit>()
            socket=realtime.connect(
                partyId=id,
                onConnected={
                    scope.launch { if(accepting && !disconnected.isCompleted){realtimeConnected=true;retryMs=1000L} }
                },
                onEvent={raw->
                    scope.launch {
                        if(!accepting || disconnected.isCompleted)return@launch
                        val event=runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
                        when(event.optString("type")) {
                            "watchparty.state" -> {
                                val position=event.optLong("positionMs")
                                val playing=event.optBoolean("isPlaying")
                                val state=event.optString("state").ifBlank { "live" }
                                val revision=event.optLong("revision")
                                if(revision>0 && revision<(party?.revision ?: 0))return@launch
                                val controller=event.optString("controllerUserId").takeIf{it.isNotBlank() && it!="null"}
                                partyReceivedAt=android.os.SystemClock.elapsedRealtime()
                                party=party?.copy(
                                    positionMs=position,
                                    isPlaying=playing,
                                    state=state,
                                    revision=revision,
                                    controllerUserId=controller,
                                    serverTimed=event.has("serverTime")
                                )
                                if(controller!=meId || state=="ended" || state=="cancelled") {
                                    val drift=abs(player.currentPosition-position)
                                    if(drift>1200) player.seekTo(position)
                                    player.playWhenReady=playing && state=="live"
                                }
                            }
                            "watchparty.reaction" -> {
                                reactions=runCatching {
                                    partyRepo.reactions(id)
                                }.getOrDefault(reactions)
                            }
                            "watchparty.queue.play" -> {
                                runCatching { partyRepo.detail(id) }
                                    .onSuccess { fresh->
                                        party=fresh;partyReceivedAt=android.os.SystemClock.elapsedRealtime()
                                        val version=fresh.media.mediaVersionId
                                        if(!version.isNullOrBlank() && loadedVersion!=version) {
                                            runCatching { backend.playbackUrl(version) }
                                                .onSuccess { url->
                                                    loadedVersion=version
                                                    player.setMediaItem(ExoMediaItem.fromUri(url))
                                                    player.prepare()
                                                    player.seekTo(fresh.positionMs)
                                                    player.playWhenReady=fresh.isPlaying && fresh.state=="live"
                                                }
                                        }
                                    }
                            }
                        }
                    }
                },
                onDisconnected={
                    disconnected.complete(Unit)
                }
            )
            if(socket==null)break
            disconnected.await()
            realtimeConnected=false
            socket?.cancel();socket=null
            delay(retryMs);retryMs=(retryMs*2).coerceAtMost(15_000L)
        } } finally {
            accepting=false
            socket?.cancel()
            realtimeConnected=false
        }
    }

    BackHandler { onBack() }

    LaunchedEffect(media?.key,partyId) {
        if(partyId!=null) return@LaunchedEffect
        val source=media
        if(source==null) {
            resolvingStartMedia=false
            resolvedStartMedia=null
            startPlatformDetail=null
            selectedEpisodeId=null
            return@LaunchedEffect
        }

        resolvingStartMedia=true
        val resolved=runCatching { repository.resolveCatalogMedia(source) }.getOrNull()
        resolvedStartMedia=resolved
        startPlatformDetail=null
        selectedEpisodeId=null

        val backendId=resolved?.backendId
        if(!backendId.isNullOrBlank()) {
            val detail=runCatching { backend.detail(backendId) }.getOrNull()
            startPlatformDetail=detail
            if(detail!=null) {
                resolvedStartMedia=detail.asMediaItem()
                if(resolved?.type==MediaType.TV) {
                    selectedEpisodeId=detail.seasons
                        .asSequence()
                        .flatMap { it.episodes.asSequence() }
                        .firstOrNull { it.streamReady && !it.mediaVersionId.isNullOrBlank() }
                        ?.id
                }
            }
        }
        resolvingStartMedia=false
    }

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

    LaunchedEffect(partyId,retryConnection) {
        val id=partyId ?: return@LaunchedEffect
        if(backend.session.isLoggedIn) {
            runCatching { partyRepo.join(id,initialInviteCode) }
                .onSuccess {
                    joinSucceeded=true
                    privateJoinRequired=false
                    joinRequestPending=false
                }
                .onFailure {
                    val message=it.message ?: "ورود به تماشای گروهی ناموفق بود"
                    privateJoinRequired=message.contains("private",ignoreCase=true)
                    error=if(privateJoinRequired) null else message
                }
        }
        while(isActive && partyId==id) {
            val fresh=runCatching { partyRepo.detail(id) }
                .onFailure { error=it.message }
                .getOrNull()
            if(fresh!=null) {
                if(fresh.revision<(party?.revision ?: 0)) { delay(2000);continue }
                party=fresh
                partyReceivedAt=android.os.SystemClock.elapsedRealtime()
                if(joinSucceeded)error=null
                val version=fresh.media.mediaVersionId
                if(joinSucceeded && fresh.serverTimed && !version.isNullOrBlank() && loadedVersion!=version) {
                    runCatching { backend.playbackUrl(version) }
                        .onSuccess { url ->
                            loadedVersion=version
                            playbackError=null
                            player.setMediaItem(ExoMediaItem.fromUri(url))
                            player.prepare()
                            player.seekTo(watchPartyTargetPosition(fresh,partyReceivedAt,android.os.SystemClock.elapsedRealtime()))
                            player.playWhenReady=fresh.isPlaying && fresh.state=="live"
                        }
                        .onFailure { error=it.message }
                }

                val controller=(fresh.controllerUserId ?: fresh.host.id)==meId
                if(joinSucceeded && fresh.serverTimed && !controller && loadedVersion==version) {
                    val drift=abs(player.currentPosition-fresh.positionMs)
                    if(drift>1500) player.seekTo(fresh.positionMs)
                    player.playWhenReady=fresh.isPlaying && fresh.state=="live"
                }

                if(fresh.state=="ended" || fresh.state=="cancelled")player.pause()
                if(joinSucceeded && fresh.roomId.isNotBlank()) {
                    runCatching { social.roomMessages(fresh.roomId) }
                        .onSuccess {
                            val changed=it.size!=messages.size && messages.isNotEmpty() &&
                                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)>=messages.size
                            messages=it
                            if(changed && it.isNotEmpty()) {
                                scope.launch { listState.animateScrollToItem(it.size) }
                            }
                        }
                }
            }
            delay(if(realtimeConnected)6000 else 2000)
        }
    }

    LaunchedEffect(partyId,backend.session.isLoggedIn) {
        val id=partyId ?: return@LaunchedEffect
        if(!backend.session.isLoggedIn) return@LaunchedEffect
        while(isActive && partyId==id) {
            runCatching { partyRepo.lobby(id) }
                .onSuccess {
                    lobby=it
                    joinSucceeded=true
                    privateJoinRequired=false
                }
            reactions=runCatching { partyRepo.reactions(id) }.getOrDefault(emptyList())
            delay(2000)
        }
    }

    LaunchedEffect(partyId,party?.host?.id,meId,lobby?.myRole) {
        val id=partyId ?: return@LaunchedEffect
        while(isActive && partyId==id) {
            delay(1000)
            val p=party ?: continue
            if(p.serverTimed && (p.controllerUserId ?: p.host.id)==meId && p.state=="live" && player.duration>0 && !stateWriteBusy) {
                syncing=true
                runCatching {
                    val event=partyRepo.updateState(
                        id=id,
                        positionMs=player.currentPosition.coerceAtLeast(0),
                        isPlaying=player.playWhenReady,
                        state="live",
                        expectedRevision=p.revision.takeIf{p.serverTimed}
                    )
                    partyReceivedAt=android.os.SystemClock.elapsedRealtime()
                    party=party?.copy(revision=event.optLong("revision",p.revision),positionMs=player.currentPosition,
                        isPlaying=player.playWhenReady,controllerUserId=meId)
                }.onFailure { error=it.message }
                syncing=false
            }
        }
    }

    if(partyId==null) {
        val playableEpisodes=startPlatformDetail
            ?.seasons
            .orEmpty()
            .flatMap { season->
                season.episodes
                    .filter { it.streamReady && !it.mediaVersionId.isNullOrBlank() }
                    .map { episode->
                        WatchPartyEpisodeOption(
                            id=episode.id,
                            season=season.number,
                            episode=episode.number,
                            name=episode.name,
                            quality=episode.quality
                        )
                    }
            }
        val resolved=resolvedStartMedia
        val catalogConnected=!resolved?.backendId.isNullOrBlank()
        val startPlayable=when(resolved?.type) {
            MediaType.MOVIE -> resolved.streamReady && !resolved.mediaVersionId.isNullOrBlank()
            MediaType.TV -> selectedEpisodeId!=null
            null -> false
        }

        WatchPartyStartScreen(
            media=media,
            repository=repository,
            loggedIn=backend.session.isLoggedIn,
            creating=creating,
            resolvingCatalog=resolvingStartMedia,
            catalogConnected=catalogConnected,
            playable=startPlayable,
            episodes=playableEpisodes,
            selectedEpisodeId=selectedEpisodeId,
            error=error,
            onBack=onBack,
            onEpisodeSelected={selectedEpisodeId=it},
            onStart={visibility,scheduledAt,episodeId->
                val backendId=resolved?.backendId
                if(!backend.session.isLoggedIn) {
                    onRequireAuth()
                } else if(backendId.isNullOrBlank()) {
                    error="این عنوان در Catalog پخش Filmiqoo موجود نیست."
                } else if(!startPlayable) {
                    error=if(resolved?.type==MediaType.TV)
                        "برای این سریال هنوز قسمت قابل پخش آماده نیست."
                    else
                        "نسخه قابل پخش این فیلم هنوز آماده نشده."
                } else {
                    creating=true
                    scope.launch {
                        runCatching {
                            partyRepo.create(
                                mediaTitleId=backendId,
                                title="Watch Party • "+(resolved?.title ?: media?.title.orEmpty()),
                                visibility=visibility,
                                scheduledAt=scheduledAt,
                                episodeId=episodeId
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
        if(error==null)LoadingPage("در حال اتصال به تماشای گروهی...")
        else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.Center) {
            Text("اتصال به اتاق انجام نشد",style=MaterialTheme.typography.headlineSmall)
            Text(error.orEmpty(),color=FqMuted,modifier=Modifier.padding(vertical=16.dp))
            Button(onClick={error=null;retryConnection++}){Text("تلاش دوباره")}
            TextButton(onClick=onBack){Text("بازگشت")}
        }
        return
    }

    val isHost=p.host.id==meId
    val canHostControl=isHost || lobby?.myRole in setOf("host","cohost")
    fun publishControl(position:Long,playing:Boolean,state:String="live") {
        if(!p.serverTimed || !canHostControl || stateWriteBusy || p.state in setOf("ended","cancelled"))return
        val previousPosition=player.currentPosition
        val previousPlaying=player.playWhenReady
        stateWriteBusy=true
        player.seekTo(position.coerceAtLeast(0));player.playWhenReady=playing
        scope.launch {
            try {
                val event=partyRepo.updateState(p.id,position.coerceAtLeast(0),playing,state,p.revision.takeIf{p.serverTimed})
                partyReceivedAt=android.os.SystemClock.elapsedRealtime()
                party=party?.copy(positionMs=position.coerceAtLeast(0),isPlaying=playing,state=state,
                    controllerUserId=meId,revision=event.optLong("revision",p.revision))
                error=null
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception) {
                player.seekTo(previousPosition);player.playWhenReady=previousPlaying
                error="کنترل پخش ثبت نشد؛ وضعیت اتاق دوباره دریافت می‌شود."
                try { party=partyRepo.detail(p.id);partyReceivedAt=android.os.SystemClock.elapsedRealtime() }
                catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception) { /* Keep previous snapshot and polling fallback. */ }
            } finally { stateWriteBusy=false }
        }
    }

    PremiumWatchPartyStage(
        party=p,
        player=player,
        lobby=lobby,
        myUserId=meId,
        canHostControl=canHostControl,
        realtimeConnected=realtimeConnected,
        syncing=syncing || stateWriteBusy,
        privateJoinRequired=privateJoinRequired,
        joinRequestPending=joinRequestPending,
        lobbyBusy=lobbyBusy,
        reminderEnabled=reminderEnabled,
        reminderBusy=reminderBusy,
        reactions=reactions,
        messages=messages,
        messageText=text,
        sendingMessage=sendingMessage,
        error=playbackError ?: error,
        listState=listState,
        onBack=onBack,
        onPrimaryControl={publishControl(player.currentPosition,if(p.state=="scheduled")true else !player.playWhenReady)},
        onSeek={publishControl(it,player.playWhenReady)},
        onRetryPlayback={playbackError=null;loadedVersion=null;error=null;retryConnection++},
        onInvite={showInviteDialog=true},
        onQueue={showQueue=true},
        onShare={
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
        onLobby={showLobby=true},
        onRequestJoin={
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
        onToggleReminder={
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
        onToggleReady={
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
        onReact={emoji->
            scope.launch {
                runCatching { partyRepo.react(p.id,emoji) }
                    .onFailure { error=it.message }
            }
        },
        onMessageTextChange={text=it.take(4000)},
        onSendMessage={
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
                sendingMessage=true
                scope.launch {
                    runCatching { social.sendMessage(p.roomId,sending,false) }
                        .onFailure {
                            error=it.message
                            if(text.isBlank()) text=sending
                        }
                    sendingMessage=false
                }
            }
        }
    )
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

    if(showFriendsInvite) {
        WatchPartyFriendsInviteSheet(
            partyId=p.id,
            partyRepo=partyRepo,
            onDismiss={showFriendsInvite=false}
        )
    }

    if(showQueue && lobby!=null) {
        WatchPartyQueueSheet(
            partyId=p.id,
            backend=backend,
            partyRepo=partyRepo,
            repository=repository,
            canHostControl=canHostControl,
            myUserId=meId,
            onDismiss={showQueue=false}
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
            onInviteFriends={
                showInviteDialog=false
                showFriendsInvite=true
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

data class WatchPartyEpisodeOption(
    val id:String,
    val season:Int,
    val episode:Int,
    val name:String,
    val quality:String?
)

@Composable
private fun WatchPartyStartScreen(
    media:MediaItem?,repository:TmdbRepository,loggedIn:Boolean,creating:Boolean,
    resolvingCatalog:Boolean,catalogConnected:Boolean,playable:Boolean,
    episodes:List<WatchPartyEpisodeOption>,selectedEpisodeId:String?,error:String?,
    onBack:()->Unit,onEpisodeSelected:(String)->Unit,onStart:(String,String?,String?)->Unit
) {
    var visibility by rememberSaveable { mutableStateOf("invite") }
    var schedule by rememberSaveable { mutableStateOf("now") }
    LazyColumn(Modifier.fillMaxSize().background(CinemaInk).safeDrawingPadding().testTag("party-creation"),
        contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { CinemaPageHeader("قرارِ تماشا","فیلم شما، دوست‌های شما",onBack) }
        item {
            Surface(color=CinemaSurface,shape=RoundedCornerShape(26.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
                Column {
                    RemoteImage(repository.backdrop(media?.backdropPath ?: media?.posterPath),
                        Modifier.fillMaxWidth().heightIn(min=120.dp,max=210.dp).aspectRatio(2f),ContentScale.Crop)
                    Column(Modifier.padding(20.dp)) {
                        Text("باهم ببینیم",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=CinemaPaper)
                        Text(media?.title ?: "یک عنوانِ قابل پخش انتخاب کن",style=MaterialTheme.typography.titleLarge,color=CinemaAccent,modifier=Modifier.padding(top=8.dp))
                        Text("پخش هماهنگ، گفتگوی اعضا و واکنش به لحظه‌های فیلم. کنترل پخش با میزبان و هم‌میزبان است.",
                            style=MaterialTheme.typography.bodyLarge,color=CinemaSoft,modifier=Modifier.padding(top=10.dp))
                        Text(when { resolvingCatalog->"در حال بررسی نسخهٔ پخش…";catalogConnected && playable->"نسخهٔ پخش آماده است";!catalogConnected->"این عنوان هنوز نسخهٔ پخش ندارد";else->"نسخهٔ پخش هنوز آماده نیست" },
                            style=MaterialTheme.typography.bodyMedium,color=if(catalogConnected && playable)FqGreen else CinemaSoft,modifier=Modifier.padding(top=14.dp))
                        if(resolvingCatalog)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=10.dp))
                    }
                }
            }
        }
        if(episodes.isNotEmpty()) item {
            Column(Modifier.padding(horizontal=20.dp)) {
                Text("کدام قسمت؟",style=MaterialTheme.typography.titleLarge,color=CinemaPaper)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=10.dp)) {
                    items(episodes,key={it.id}){episode->FilterChip(selected=selectedEpisodeId==episode.id,
                        onClick={onEpisodeSelected(episode.id)},label={Text("فصل ${episode.season} · قسمت ${episode.episode}")})}
                }
            }
        }
        item {
            Surface(color=CinemaSurface,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("چه کسانی وارد شوند؟",style=MaterialTheme.typography.titleLarge,color=CinemaPaper)
                    listOf("invite" to "با لینک دعوت","private" to "با تأیید میزبان","public" to "اتاق عمومی").forEach{(id,label)->
                        Row(Modifier.fillMaxWidth().clickable(enabled=!creating){visibility=id}.heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=visibility==id,onClick={visibility=id},enabled=!creating)
                            Text(label,style=MaterialTheme.typography.bodyLarge,color=CinemaPaper)
                        }
                    }
                    Text(when(visibility){"public"->"اتاق در فهرست عمومی دیده می‌شود.";"private"->"لینک را بفرست؛ ورود هر عضو باید تأیید شود.";else->"کد داخل لینک دعوت، کلید ورود دوست‌هاست."},
                        style=MaterialTheme.typography.bodyMedium,color=CinemaSoft,modifier=Modifier.padding(top=4.dp))
                    HorizontalDivider(color=CinemaLine,modifier=Modifier.padding(vertical=18.dp))
                    Text("چه زمانی؟",style=MaterialTheme.typography.titleLarge,color=CinemaPaper)
                    listOf("now" to "همین حالا","30m" to "۳۰ دقیقهٔ دیگر","1h" to "یک ساعت دیگر","tomorrow" to "فردا همین ساعت").forEach{(id,label)->
                        Row(Modifier.fillMaxWidth().clickable(enabled=!creating){schedule=id}.heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=schedule==id,onClick={schedule=id},enabled=!creating)
                            Text(label,style=MaterialTheme.typography.bodyLarge,color=CinemaPaper)
                        }
                    }
                    Button(onClick={
                        val seconds=when(schedule){"30m"->1800L;"1h"->3600L;"tomorrow"->86400L;else->0L}
                        val at=if(seconds==0L)null else java.time.Instant.now().plusSeconds(seconds).toString()
                        onStart(visibility,at,selectedEpisodeId)
                    },enabled=!creating && !resolvingCatalog && media!=null && catalogConnected && playable,
                        colors=ButtonDefaults.buttonColors(containerColor=CinemaAccent,contentColor=CinemaInk),
                        modifier=Modifier.fillMaxWidth().padding(top=20.dp).heightIn(min=52.dp).testTag("party-confirm-create")) {
                        if(creating)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        else Icon(if(schedule=="now")Icons.Default.Groups else Icons.Default.Event,null)
                        Spacer(Modifier.width(8.dp));Text(if(!loggedIn)"ورود و ساخت اتاق" else if(schedule=="now")"ساخت و ورود به اتاق" else "ثبتِ قرارِ تماشا")
                    }
                }
            }
        }
        error?.let{item { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=24.dp)) }}
    }
}
@Composable
private fun WatchPartyInviteDialog(
    party:WatchPartyInfo,
    info:WatchPartyInviteInfo,
    busy:Boolean,
    onShare:()->Unit,
    onInviteFriends:()->Unit,
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
                    fontSize=14.sp,
                    lineHeight=15.sp
                )
                if(info.inviteCode.isNotBlank()) {
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(13.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Invite Code",color=FqMuted,fontSize=12.sp)
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
                    fontSize=14.sp,
                    modifier=Modifier.padding(top=10.dp)
                )
            }
        },
        confirmButton={
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick=onInviteFriends) {
                    Icon(Icons.Default.GroupAdd,null,modifier=Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("دعوت دوست‌ها",fontSize=12.sp)
                }
                Button(
                    onClick=onShare,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                ) {
                    Icon(Icons.Default.Share,null,tint=Color.Black)
                    Spacer(Modifier.width(5.dp))
                    Text("اشتراک لینک",color=Color.Black,fontSize=12.sp)
                }
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
                        Text("کد جدید",fontSize=12.sp)
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
    var confirmLeaveOrEnd by remember { mutableStateOf(false) }
    val canEnd=lobby.myRole=="host" || lobby.myRole=="cohost"

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,bottom=28.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("اتاق انتظار",fontSize=20.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Black)
                    Text(
                        lobby.participantCount.toString()+" نفر • "+
                            lobby.readyCount.toString()+" آماده"+(if(lobby.presenceKnown)" • "+lobby.onlineCount+" آنلاین" else ""),
                        color=FqMuted,fontSize=12.sp
                    )
                }
                if(lobby.myRole=="host" || lobby.myRole=="cohost") {
                    FilterChip(
                        selected=lobby.readyCheckEnabled,
                        onClick={onToggleReadyCheck(!lobby.readyCheckEnabled)},
                        label={Text("بررسی آمادگی",fontSize=12.sp)},
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
                    Text(if(lobby.myReady)"آماده‌ام" else "اعلام آمادگی")
                }
            }

            if(lobby.requests.isNotEmpty() && (lobby.myRole=="host" || lobby.myRole=="cohost")) {
                Text(
                    "درخواست‌های ورود",
                    fontSize=16.sp,
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
                            Text(request.displayName,fontSize=14.sp)
                            Text("@"+request.username,color=FqMuted,fontSize=12.sp)
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
                fontSize=16.sp,
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
                                    Text(member.displayName,fontSize=14.sp)
                                    if(member.verified) {
                                        Spacer(Modifier.width(3.dp))
                                        Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(12.dp))
                                    }
                                }
                                Text(
                                    when(member.role) {
                                        "host" -> "میزبان"
                                        "cohost" -> "هم‌میزبان"
                                        "moderator" -> "مدیر"
                                        else -> "بیننده"
                                    }+(if(lobby.presenceKnown)" • "+(if(member.online)"آنلاین" else "آفلاین")else "")+" • @"+member.username,
                                    color=FqMuted,fontSize=12.sp
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
                                        if(member.role=="cohost")"بیننده" else "هم‌میزبان",
                                        fontSize=12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                enabled=!busy,
                onClick={confirmLeaveOrEnd=true},
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
                        "پایان تماشای گروهی"
                    else
                        "خروج از تماشای گروهی"
                )
            }
        }
    }

    if(confirmLeaveOrEnd) {
        AlertDialog(
            onDismissRequest={confirmLeaveOrEnd=false},
            icon={
                Icon(
                    if(canEnd)Icons.Default.StopCircle else Icons.Default.ExitToApp,
                    null,
                    tint=FqDanger
                )
            },
            title={
                Text(
                    if(canEnd)"تماشای گروهی پایان یابد؟"
                    else "از تماشای گروهی خارج شوی؟"
                )
            },
            text={
                Text(
                    if(canEnd)
                        "پخش برای همه متوقف می‌شود و این جلسه پایان می‌یابد."
                    else
                        "از جلسه خارج می‌شوی و برای برگشت باید دوباره وارد شوی."
                )
            },
            confirmButton={
                TextButton(
                    enabled=!busy,
                    onClick={
                        confirmLeaveOrEnd=false
                        onLeaveOrEnd()
                    }
                ) {
                    Text(if(canEnd)"پایان جلسه" else "خروج",color=FqDanger)
                }
            },
            dismissButton={
                TextButton(onClick={confirmLeaveOrEnd=false}) {
                    Text("لغو")
                }
            }
        )
    }
}

private fun partyStateLabel(state:String):String=when(state.lowercase()) {
    "scheduled" -> "زمان‌بندی‌شده"
    "ended" -> "پایان‌یافته"
    "paused" -> "متوقف"
    else -> state
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
