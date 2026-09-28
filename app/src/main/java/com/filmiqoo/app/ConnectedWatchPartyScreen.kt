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
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
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
        onDispose { player.release() }
    }

    DisposableEffect(partyId,lobby?.myRole,privateJoinRequired) {
        val id=partyId
        var socket:WebSocket?=null
        if(
            id!=null &&
            backend.session.isLoggedIn &&
            lobby!=null &&
            !privateJoinRequired
        ) {
            socket=realtime.connect(
                partyId=id,
                onConnected={
                    scope.launch { realtimeConnected=true }
                },
                onEvent={raw->
                    scope.launch {
                        val event=runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
                        when(event.optString("type")) {
                            "watchparty.state" -> {
                                val position=event.optLong("positionMs")
                                val playing=event.optBoolean("isPlaying")
                                val state=event.optString("state").ifBlank { "live" }
                                party=party?.copy(
                                    positionMs=position,
                                    isPlaying=playing,
                                    state=state
                                )
                                val hostControl=
                                    party?.host?.id==meId || lobby?.myRole=="cohost"
                                if(!hostControl) {
                                    val drift=abs(player.currentPosition-position)
                                    if(drift>1200) player.seekTo(position)
                                    if(playing && !player.isPlaying) player.play()
                                    if(!playing && player.isPlaying) player.pause()
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
                                        party=fresh
                                        val version=fresh.media.mediaVersionId
                                        if(!version.isNullOrBlank() && loadedVersion!=version) {
                                            runCatching { backend.playbackUrl(version) }
                                                .onSuccess { url->
                                                    loadedVersion=version
                                                    player.setMediaItem(ExoMediaItem.fromUri(url))
                                                    player.prepare()
                                                    player.seekTo(fresh.positionMs)
                                                    if(fresh.isPlaying) player.play() else player.pause()
                                                }
                                        }
                                    }
                            }
                        }
                    }
                },
                onDisconnected={
                    scope.launch { realtimeConnected=false }
                }
            )
        } else {
            realtimeConnected=false
        }
        onDispose {
            socket?.close(1000,"watch party screen closed")
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

    LaunchedEffect(partyId) {
        val id=partyId ?: return@LaunchedEffect
        if(backend.session.isLoggedIn) {
            runCatching { partyRepo.join(id,initialInviteCode) }
                .onSuccess {
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
        LoadingPage("در حال اتصال به تماشای گروهی...")
        return
    }

    val isHost=p.host.id==meId
    val canHostControl=isHost || lobby?.myRole=="cohost"

    PremiumWatchPartyStage(
        party=p,
        player=player,
        lobby=lobby,
        myUserId=meId,
        canHostControl=canHostControl,
        realtimeConnected=realtimeConnected,
        syncing=syncing,
        privateJoinRequired=privateJoinRequired,
        joinRequestPending=joinRequestPending,
        lobbyBusy=lobbyBusy,
        reminderEnabled=reminderEnabled,
        reminderBusy=reminderBusy,
        reactions=reactions,
        messages=messages,
        messageText=text,
        sendingMessage=sendingMessage,
        error=error,
        listState=listState,
        onBack=onBack,
        onPrimaryControl={
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
    media: MediaItem?,
    repository: TmdbRepository,
    loggedIn: Boolean,
    creating: Boolean,
    resolvingCatalog: Boolean,
    catalogConnected: Boolean,
    playable: Boolean,
    episodes: List<WatchPartyEpisodeOption>,
    selectedEpisodeId: String?,
    error: String?,
    onBack: () -> Unit,
    onEpisodeSelected: (String) -> Unit,
    onStart: (String,String?,String?) -> Unit
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
                    listOf(
                        Color.Black.copy(alpha=.28f),
                        Color.Black.copy(alpha=.68f),
                        FqBg.copy(alpha=.94f),
                        FqBg
                    )
                )
            )
        )

        IconButton(
            onClick=onBack,
            modifier=Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha=.42f))
        ) {
            Icon(Icons.Default.Close,null)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal=18.dp,vertical=24.dp)
        ) {
            Surface(
                color=FqGold.copy(alpha=.16f),
                shape=RoundedCornerShape(99.dp)
            ) {
                Row(
                    Modifier.padding(horizontal=10.dp,vertical=6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Groups,null,tint=FqGold,modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("FILMIQOO TOGETHER",color=FqGold,fontSize=9.sp,fontWeight=FontWeight.Black)
                }
            }

            Text(
                "با هم ببینید، دقیقاً همزمان",
                fontSize=31.sp,
                lineHeight=35.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=14.dp)
            )
            Text(
                media?.title ?: "یک فیلم یا سریال انتخاب کن",
                color=Color.White.copy(alpha=.78f),
                fontSize=13.sp,
                modifier=Modifier.padding(top=6.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top=14.dp),
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                listOf(
                    Icons.Default.Sync to "Live Sync",
                    Icons.Default.Chat to "چت زنده",
                    Icons.Default.EmojiEmotions to "واکنش"
                ).forEach { feature->
                    Surface(
                        color=Color.White.copy(alpha=.075f),
                        shape=RoundedCornerShape(13.dp),
                        modifier=Modifier.weight(1f)
                    ) {
                        Row(
                            Modifier.padding(horizontal=8.dp,vertical=9.dp),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.Center
                        ) {
                            Icon(feature.first,null,tint=FqGold,modifier=Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(feature.second,fontSize=8.sp,fontWeight=FontWeight.Bold)
                        }
                    }
                }
            }

            Surface(
                color=FqSurface.copy(alpha=.94f),
                shape=RoundedCornerShape(24.dp),
                modifier=Modifier.fillMaxWidth().padding(top=16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Surface(
                        color=when {
                            resolvingCatalog -> FqGold.copy(alpha=.10f)
                            catalogConnected && playable -> FqGreen.copy(alpha=.10f)
                            else -> FqDanger.copy(alpha=.10f)
                        },
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal=10.dp,vertical=9.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            if(resolvingCatalog) {
                                CircularProgressIndicator(
                                    color=FqGold,
                                    strokeWidth=2.dp,
                                    modifier=Modifier.size(17.dp)
                                )
                            } else {
                                Icon(
                                    if(catalogConnected && playable)Icons.Default.CheckCircle
                                    else Icons.Default.CloudOff,
                                    null,
                                    tint=if(catalogConnected && playable)FqGreen else FqDanger,
                                    modifier=Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        resolvingCatalog -> "در حال اتصال به Catalog…"
                                        catalogConnected && playable -> "آماده برای Watch Party"
                                        !catalogConnected -> "این عنوان هنوز در Catalog پخش نیست"
                                        else -> "نسخه قابل پخش هنوز آماده نیست"
                                    },
                                    fontSize=9.sp,
                                    fontWeight=FontWeight.Black
                                )
                                Text(
                                    when {
                                        resolvingCatalog -> "نسخه واقعی Filmiqoo را پیدا می‌کنیم."
                                        catalogConnected && playable -> "پخش و Sync به نسخه واقعی سرور متصل است."
                                        !catalogConnected -> "فقط عناوین دارای فایل واقعی می‌توانند Party بسازند."
                                        else -> "بعد از آماده شدن فایل پخش، Party فعال می‌شود."
                                    },
                                    color=FqMuted,
                                    fontSize=7.sp,
                                    modifier=Modifier.padding(top=2.dp)
                                )
                            }
                        }
                    }

                    if(episodes.isNotEmpty()) {
                        Text(
                            "قسمت برای Watch Party",
                            fontSize=11.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(top=12.dp)
                        )
                        LazyRow(
                            modifier=Modifier.fillMaxWidth().padding(top=7.dp),
                            horizontalArrangement=Arrangement.spacedBy(6.dp)
                        ) {
                            items(episodes,key={it.id}) { option->
                                FilterChip(
                                    selected=selectedEpisodeId==option.id,
                                    onClick={onEpisodeSelected(option.id)},
                                    label={
                                        Text(
                                            "ف"+option.season+" • ق"+option.episode+
                                                option.quality?.takeIf(String::isNotBlank)?.let{" • "+it}.orEmpty(),
                                            fontSize=8.sp
                                        )
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color=FqSurface3,
                        modifier=Modifier.padding(vertical=13.dp)
                    )

                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("چه کسانی وارد شوند؟",fontSize=12.sp,fontWeight=FontWeight.Black)
                            Text(
                                when(visibility) {
                                    "public" -> "همه می‌توانند Party را پیدا کنند و وارد شوند."
                                    "invite" -> "ورود فقط با لینک و کد دعوت."
                                    else -> "فقط با تأیید میزبان وارد می‌شوند."
                                },
                                color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(top=3.dp)
                            )
                        }
                        Icon(
                            when(visibility) {
                                "public" -> Icons.Default.Public
                                "invite" -> Icons.Default.VpnKey
                                else -> Icons.Default.Lock
                            },
                            null,
                            tint=FqGold
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top=11.dp),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "public" to "عمومی",
                            "invite" to "با دعوت",
                            "private" to "خصوصی"
                        ).forEach { option->
                            FilterChip(
                                selected=visibility==option.first,
                                onClick={visibility=option.first},
                                label={Text(option.second,fontSize=9.sp)},
                                modifier=Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(
                        color=FqSurface3,
                        modifier=Modifier.padding(vertical=13.dp)
                    )

                    Text("زمان شروع",fontSize=12.sp,fontWeight=FontWeight.Black)
                    Row(
                        Modifier.fillMaxWidth().padding(top=8.dp),
                        horizontalArrangement=Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(
                            "now" to "الان",
                            "30m" to "۳۰ دقیقه",
                            "1h" to "۱ ساعت",
                            "tomorrow" to "فردا"
                        ).forEach { option->
                            FilterChip(
                                selected=schedule==option.first,
                                onClick={schedule=option.first},
                                label={Text(option.second,fontSize=8.sp)}
                            )
                        }
                    }

                    if(scheduledAt!=null) {
                        Row(
                            Modifier.fillMaxWidth().padding(top=8.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Event,null,tint=FqGold,modifier=Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "شروع "+formatPartySchedule(scheduledAt),
                                color=FqGold,
                                fontSize=9.sp,
                                fontWeight=FontWeight.Bold
                            )
                        }
                    }

                    Button(
                        onClick={onStart(visibility,scheduledAt,selectedEpisodeId)},
                        enabled=!creating && !resolvingCatalog && media!=null && catalogConnected && playable,
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        shape=RoundedCornerShape(16.dp),
                        contentPadding=PaddingValues(vertical=13.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=15.dp)
                    ) {
                        if(creating) {
                            CircularProgressIndicator(
                                color=Color.Black,
                                strokeWidth=2.dp,
                                modifier=Modifier.size(19.dp)
                            )
                        } else {
                            Icon(
                                if(scheduledAt==null)Icons.Default.PlayArrow else Icons.Default.Event,
                                null,
                                tint=Color.Black
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            when {
                                !loggedIn -> "ورود و ساخت Watch Party"
                                scheduledAt==null -> "شروع Watch Party"
                                else -> "زمان‌بندی Watch Party"
                            },
                            color=Color.Black,
                            fontSize=12.sp,
                            fontWeight=FontWeight.Black
                        )
                    }
                }
            }

            error?.let {
                Surface(
                    color=FqDanger.copy(alpha=.12f),
                    shape=RoundedCornerShape(13.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=9.dp)
                ) {
                    Text(it,color=FqDanger,fontSize=9.sp,modifier=Modifier.padding(10.dp))
                }
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
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick=onInviteFriends) {
                    Icon(Icons.Default.GroupAdd,null,modifier=Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("دعوت دوست‌ها",fontSize=8.sp)
                }
                Button(
                    onClick=onShare,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                ) {
                    Icon(Icons.Default.Share,null,tint=Color.Black)
                    Spacer(Modifier.width(5.dp))
                    Text("اشتراک لینک",color=Color.Black,fontSize=8.sp)
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
                            lobby.readyCount+" آماده",
                        color=FqMuted,fontSize=8.sp
                    )
                }
                if(lobby.myRole=="host" || lobby.myRole=="cohost") {
                    FilterChip(
                        selected=lobby.readyCheckEnabled,
                        onClick={onToggleReadyCheck(!lobby.readyCheckEnabled)},
                        label={Text("بررسی آمادگی",fontSize=7.sp)},
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
                                        "host" -> "میزبان"
                                        "cohost" -> "هم‌میزبان"
                                        "moderator" -> "مدیر"
                                        else -> "بیننده"
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
                                        if(member.role=="cohost")"بیننده" else "هم‌میزبان",
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
