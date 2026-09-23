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

    LaunchedEffect(partyId) {
        val id=partyId ?: return@LaunchedEffect
        if(backend.session.isLoggedIn) {
            runCatching { partyRepo.join(id) }
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

    LaunchedEffect(partyId,party?.host?.id,meId) {
        val id=partyId ?: return@LaunchedEffect
        while(isActive && partyId==id) {
            delay(2000)
            val p=party ?: continue
            if(p.host.id==meId && p.state=="live" && player.duration>0) {
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
            onStart={
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
                                title="Watch Party • "+media.title
                            )
                        }.onSuccess {
                            partyId=it
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
                    if(isHost) {
                        Button(
                            onClick={
                                if(player.isPlaying) player.pause() else player.play()
                                scope.launch {
                                    runCatching {
                                        partyRepo.updateState(
                                            p.id,
                                            player.currentPosition.coerceAtLeast(0),
                                            player.isPlaying
                                        )
                                    }
                                }
                            },
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                        ) {
                            Icon(if(player.isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,null)
                            Spacer(Modifier.width(5.dp))
                            Text(if(player.isPlaying)"Pause for all" else "Play for all")
                        }
                    } else {
                        Surface(color=Color.Black.copy(alpha=.55f),shape=RoundedCornerShape(11.dp)) {
                            Text(
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
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Groups,null,tint=FqGold,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(p.participants.toString(),fontSize=8.sp)
            }
        }

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
                } else if(text.isNotBlank() && p.roomId.isNotBlank()) {
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
}

@Composable
private fun WatchPartyStartScreen(
    media: MediaItem?,
    repository: TmdbRepository,
    loggedIn: Boolean,
    creating: Boolean,
    error: String?,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
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
                "تماشای همزمان، کنترل میزبان، Sync موقعیت پخش و Chat واقعی.",
                color=Color.White.copy(alpha=.72f),
                fontSize=9.sp,
                lineHeight=16.sp,
                modifier=Modifier.padding(top=9.dp)
            )
            Button(
                onClick=onStart,
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
                    if(loggedIn)"شروع Watch Party" else "ورود و شروع",
                    color=Color.Black
                )
            }
            error?.let {
                Text(it,color=FqDanger,fontSize=8.sp,modifier=Modifier.padding(top=10.dp))
            }
        }
    }
}
