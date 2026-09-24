package com.filmiqoo.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ReelLoad {
    data object Loading : ReelLoad
    data class Ready(val reels: List<ReelFeedItem>) : ReelLoad
    data class Error(val message: String) : ReelLoad
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectedExploreScreen(
    social: SocialRepository,
    backend: BackendRepository,
    repository: TmdbRepository,
    store: LocalStore,
    loggedIn: Boolean,
    onMedia: (MediaItem) -> Unit,
    onChat: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit,
    onRequireAuth: () -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var state by remember { mutableStateOf<ReelLoad>(ReelLoad.Loading) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        state=ReelLoad.Loading
        state=runCatching { ReelLoad.Ready(social.reels()) }
            .getOrElse { ReelLoad.Error(it.message ?: "خطا در دریافت Reels") }
    }

    when(val s=state) {
        ReelLoad.Loading -> LoadingPage("در حال آماده‌سازی Explore...")
        is ReelLoad.Error -> {
            // Backend may be offline during design preview; preserve the rich mock/TMDB explore.
            ExploreScreen(
                repository=repository,
                store=store,
                onMedia=onMedia,
                onChat=onChat,
                onCreator=onCreator
            )
        }
        is ReelLoad.Ready -> {
            if(s.reels.isEmpty()) {
                ExploreScreen(
                    repository=repository,
                    store=store,
                    onMedia=onMedia,
                    onChat=onChat,
                    onCreator=onCreator
                )
            } else {
                RealReelsPager(
                    reels=s.reels,
                    social=social,
                    backend=backend,
                    loggedIn=loggedIn,
                    onMedia=onMedia,
                    onCreator=onCreator,
                    onRequireAuth=onRequireAuth,
                    onRefresh={refresh++}
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RealReelsPager(
    reels: List<ReelFeedItem>,
    social: SocialRepository,
    backend: BackendRepository,
    loggedIn: Boolean,
    onMedia: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit,
    onRequireAuth: () -> Unit,
    onRefresh: () -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val pager=rememberPagerState(pageCount={reels.size})
    val liked=remember { mutableStateMapOf<String,Boolean>() }
    val saved=remember { mutableStateMapOf<String,Boolean>() }
    val followed=remember { mutableStateMapOf<String,Boolean>() }
    val revealed=remember { mutableStateMapOf<String,Boolean>() }
    var commentsFor by remember { mutableStateOf<ReelFeedItem?>(null) }
    var safetyFor by remember { mutableStateOf<ReelFeedItem?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var followingTab by remember { mutableStateOf(false) }

    val player=remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode=Player.REPEAT_MODE_ONE
            playWhenReady=true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val current=reels.getOrNull(pager.currentPage)

    LaunchedEffect(pager.currentPage,reels) {
        val reel=reels.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        player.stop()
        player.clearMediaItems()
        if(reel.playbackUrl.isNotBlank()) {
            player.setMediaItem(ExoMediaItem.fromUri(reel.playbackUrl))
            player.prepare()
            player.playWhenReady=true
        }
        if(loggedIn) {
            delay(1800)
            runCatching { social.markReelViewed(reel.id) }
        }
    }

    LaunchedEffect(pager.currentPage,reels,loggedIn) {
        if(!loggedIn) return@LaunchedEffect
        val reel=reels.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        var watchMs=0L
        var lastSample=android.os.SystemClock.elapsedRealtime()

        try {
            while(true) {
                delay(500)
                val now=android.os.SystemClock.elapsedRealtime()
                val elapsed=(now-lastSample).coerceIn(0L,1500L)
                lastSample=now
                if(player.isPlaying) {
                    watchMs+=elapsed
                }
            }
        } finally {
            val playerDuration=player.duration.takeIf { it>0L }
            val knownDuration=playerDuration
                ?: reel.durationMs.toLong().takeIf { it>0L }
                ?: 0L
            if(watchMs>=500L) {
                val completed=knownDuration>0L && watchMs>=knownDuration*9L/10L
                val rewatched=knownDuration>0L && watchMs>=knownDuration*3L/2L
                withContext(NonCancellable) {
                    runCatching {
                        social.reportReelPlayback(
                            id=reel.id,
                            watchMs=watchMs,
                            durationMs=knownDuration,
                            completed=completed,
                            rewatched=rewatched
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(muted) {
        player.volume=if(muted)0f else 1f
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state=pager,
            modifier=Modifier.fillMaxSize(),
            beyondViewportPageCount=1
        ) { page ->
            val reel=reels[page]
            val isCurrent=page==pager.currentPage
            ReelVideoPage(
                reel=reel,
                active=isCurrent,
                player=if(isCurrent)player else null,
                revealed=revealed[reel.id] == true || !reel.spoiler,
                liked=liked[reel.id] == true,
                saved=saved[reel.id] == true,
                followed=followed[reel.author.id] == true,
                onReveal={
                    revealed[reel.id]=true
                    if(isCurrent) player.play()
                },
                onLike={
                    if(!loggedIn) onRequireAuth()
                    else scope.launch {
                        runCatching { social.toggleReelLike(reel.id) }
                            .onSuccess { liked[reel.id]=it }
                    }
                },
                onSave={
                    if(!loggedIn) onRequireAuth()
                    else scope.launch {
                        runCatching { social.toggleReelSave(reel.id) }
                            .onSuccess { saved[reel.id]=it }
                    }
                },
                onFollow={
                    if(!loggedIn) onRequireAuth()
                    else scope.launch {
                        runCatching { social.toggleUserFollow(reel.author.id) }
                            .onSuccess { followed[reel.author.id]=it }
                    }
                },
                onComment={commentsFor=reel},
                onMedia={
                    reel.media?.asMediaItem()?.let(onMedia)
                },
                onCreator={
                    onCreator(
                        Creator(
                            name=reel.author.displayName,
                            handle="@"+reel.author.username,
                            followers="",
                            bio="Creator در Filmiqoo",
                            verified=reel.author.verified,
                            id=reel.author.id,
                            entityType="user",
                            avatarUrl=reel.author.avatarUrl
                        )
                    )
                },
                onNotInterested={
                    if(!loggedIn) {
                        onRequireAuth()
                    } else {
                        scope.launch {
                            runCatching {
                                social.feedback("reel",reel.id,"not_interested")
                            }.onSuccess {
                                feedbackMessage="این نوع Reel کمتر نمایش داده می‌شه."
                                onRefresh()
                            }
                        }
                    }
                },
                onSafety={
                    if(!loggedIn) onRequireAuth() else safetyFor=reel
                },
                onShare={
                    shareText(
                        context,
                        "Filmiqoo Reel • "+(reel.media?.title ?: reel.caption.ifBlank{"Reel"})
                    )
                },
                onToggleMute={muted=!muted},
                muted=muted
            )
        }

        Row(
            Modifier.align(Alignment.TopCenter).padding(top=10.dp)
                .background(Color.Black.copy(alpha=.5f),RoundedCornerShape(22.dp))
                .padding(horizontal=4.dp,vertical=2.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            FilterChip(
                selected=!followingTab,
                onClick={followingTab=false},
                label={Text("برای تو")},
                colors=FilterChipDefaults.filterChipColors(
                    selectedContainerColor=Color.White,
                    selectedLabelColor=Color.Black,
                    containerColor=Color.Transparent
                )
            )
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected=followingTab,
                onClick={followingTab=true},
                label={Text("دنبال‌شده‌ها")},
                colors=FilterChipDefaults.filterChipColors(
                    selectedContainerColor=Color.White,
                    selectedLabelColor=Color.Black,
                    containerColor=Color.Transparent
                )
            )
        }

        IconButton(
            onClick=onRefresh,
            modifier=Modifier.align(Alignment.TopEnd).padding(top=12.dp,end=8.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha=.45f))
        ) {
            Icon(Icons.Default.Refresh,null,tint=Color.White)
        }
    }

    commentsFor?.let { reel ->
        ReelCommentsSheet(
            reel=reel,
            social=social,
            loggedIn=loggedIn,
            onRequireAuth=onRequireAuth,
            onDismiss={commentsFor=null}
        )
    }

    safetyFor?.let { reel ->
        SafetyActionSheet(
            backend=backend,
            targetType="reel",
            targetId=reel.id,
            targetLabel="Reel از "+reel.author.displayName,
            userTargetId=reel.author.id,
            onDismiss={safetyFor=null},
            onChanged={
                safetyFor=null
                onRefresh()
            }
        )
    }

    feedbackMessage?.let {
        Snackbar(
            modifier=Modifier.padding(16.dp),
            action={TextButton(onClick={feedbackMessage=null}){Text("باشه")}}
        ) { Text(it) }
    }
}

@Composable
private fun ReelVideoPage(
    reel: ReelFeedItem,
    active: Boolean,
    player: ExoPlayer?,
    revealed: Boolean,
    liked: Boolean,
    saved: Boolean,
    followed: Boolean,
    onReveal: () -> Unit,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onFollow: () -> Unit,
    onComment: () -> Unit,
    onMedia: () -> Unit,
    onCreator: () -> Unit,
    onNotInterested: () -> Unit,
    onSafety: () -> Unit,
    onShare: () -> Unit,
    onToggleMute: () -> Unit,
    muted: Boolean
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val background=reel.coverUrl.ifBlank {
            reel.media?.backdropUrl ?: reel.media?.posterUrl.orEmpty()
        }
        if(background.isNotBlank()) {
            AsyncImage(
                model=background,
                contentDescription=null,
                contentScale=ContentScale.Crop,
                modifier=Modifier.fillMaxSize()
            )
        }

        if(active && player!=null && revealed && reel.playbackUrl.isNotBlank()) {
            AndroidView(
                factory={ ctx ->
                    PlayerView(ctx).apply {
                        this.player=player
                        useController=false
                        resizeMode=AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    }
                },
                update={it.player=player},
                modifier=Modifier.fillMaxSize().clickable {
                    if(player.isPlaying) player.pause() else player.play()
                }
            )
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha=.18f),
                        Color.Transparent,
                        Color.Black.copy(alpha=.18f),
                        Color.Black.copy(alpha=.88f)
                    )
                )
            )
        )

        if(reel.spoiler && !revealed) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha=.88f))
                    .clickable { onReveal() },
                contentAlignment=Alignment.Center
            ) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(30.dp)) {
                    Icon(Icons.Default.VisibilityOff,null,tint=FqDanger,modifier=Modifier.size(52.dp))
                    Text("Spoiler Shield",color=FqDanger,fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                    Text("این Reel دارای اسپویل است.",color=Color.White,fontSize=12.sp,modifier=Modifier.padding(top=7.dp))
                    Text("برای نمایش لمس کن",color=FqMuted,fontSize=10.sp,modifier=Modifier.padding(top=3.dp))
                }
            }
            return
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(start=16.dp,end=78.dp,bottom=24.dp)
        ) {
            Row(
                verticalAlignment=Alignment.CenterVertically,
                modifier=Modifier.clickable { onCreator() }
            ) {
                RemoteImage(
                    reel.author.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(42.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(reel.author.displayName,fontSize=13.sp,fontWeight=FontWeight.Bold)
                        if(reel.author.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(15.dp))
                        }
                    }
                    Text("@"+reel.author.username,color=Color.White.copy(alpha=.7f),fontSize=9.sp)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick=onFollow,
                    contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp),
                    modifier=Modifier.height(30.dp),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=if(followed)FqGold else Color.White)
                ) {
                    Text(if(followed)"دنبال می‌کنی" else "دنبال",fontSize=8.sp)
                }
            }

            if(reel.caption.isNotBlank()) {
                Text(
                    reel.caption,
                    color=Color.White,
                    fontSize=11.sp,
                    lineHeight=18.sp,
                    maxLines=4,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=10.dp)
                )
            }

            reel.media?.asMediaItem()?.let { media ->
                Surface(
                    color=Color.Black.copy(alpha=.58f),
                    shape=RoundedCornerShape(15.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp).clickable { onMedia() }
                ) {
                    Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(
                            reel.media.posterUrl,
                            Modifier.size(40.dp,56.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(media.title,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(
                                listOf(
                                    media.year,
                                    if(media.vote>0) "★ "+formatVote(media.vote) else ""
                                ).filter(String::isNotBlank).joinToString(" • "),
                                color=FqMuted,fontSize=8.sp
                            )
                        }
                        Icon(Icons.Default.PlayArrow,null,tint=FqGold)
                        Text("صفحه فیلم",color=FqGold,fontSize=8.sp)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end=12.dp,bottom=26.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            ReelCircleAction(
                icon=Icons.Default.Favorite,
                tint=if(liked)FqDanger else Color.White,
                text=compactCount(reel.likes + if(liked)1 else 0),
                onClick=onLike
            )
            ReelCircleAction(
                icon=Icons.Default.ChatBubble,
                tint=Color.White,
                text=compactCount(reel.comments),
                onClick=onComment
            )
            ReelCircleAction(
                icon=Icons.Default.Bookmark,
                tint=if(saved)FqGold else Color.White,
                text=compactCount(reel.saves + if(saved)1 else 0),
                onClick=onSave
            )
            ReelCircleAction(
                icon=if(muted)Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                tint=Color.White,
                text=if(muted)"بی‌صدا" else "صدا",
                onClick=onToggleMute
            )
            ReelCircleAction(
                icon=Icons.Default.DoNotDisturbOn,
                tint=Color.White,
                text="علاقه ندارم",
                onClick=onNotInterested
            )
            ReelCircleAction(
                icon=Icons.Default.MoreVert,
                tint=Color.White,
                text="بیشتر",
                onClick=onSafety
            )
            ReelCircleAction(
                icon=Icons.Default.Share,
                tint=Color.White,
                text=compactCount(reel.shares),
                onClick=onShare
            )
        }
    }
}

@Composable
private fun ReelCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment=Alignment.CenterHorizontally,
        modifier=Modifier.clickable { onClick() }.padding(vertical=7.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha=.48f)),
            contentAlignment=Alignment.Center
        ) {
            Icon(icon,null,tint=tint,modifier=Modifier.size(25.dp))
        }
        Text(text,color=Color.White,fontSize=8.sp,modifier=Modifier.padding(top=3.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelCommentsSheet(
    reel: ReelFeedItem,
    social: SocialRepository,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var items by remember(reel.id) { mutableStateOf<List<SocialComment>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            loading=true
            items=runCatching { social.reelComments(reel.id) }.getOrDefault(emptyList())
            loading=false
        }
    }

    LaunchedEffect(reel.id) { reload() }

    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=FqSurface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal=14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("نظرها",fontSize=20.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(compactCount(reel.comments),color=FqMuted,fontSize=9.sp)
            }

            if(loading) LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth().padding(top=8.dp))

            androidx.compose.foundation.lazy.LazyColumn(
                modifier=Modifier.weight(1f).padding(top=8.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                items(items.size,key={items[it].id}) { index ->
                    val c=items[index]
                    var reveal by remember(c.id) { mutableStateOf(!c.spoiler) }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                        RemoteImage(c.author.avatarUrl.takeIf(String::isNotBlank),Modifier.size(34.dp).clip(CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.author.displayName,color=FqGold,fontSize=9.sp)
                            if(c.spoiler && !reveal) {
                                Text(
                                    "⚠ Spoiler Shield • نمایش",
                                    color=FqDanger,fontSize=9.sp,
                                    modifier=Modifier.padding(top=4.dp).clickable { reveal=true }
                                )
                            } else {
                                Text(c.body,fontSize=10.sp,lineHeight=17.sp,modifier=Modifier.padding(top=3.dp))
                            }
                        }
                    }
                }
            }

            Row(Modifier.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(selected=spoiler,onClick={spoiler=!spoiler},label={Text("Spoiler",fontSize=8.sp)})
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value=text,onValueChange={text=it},
                    placeholder={Text("نظر بنویس...")},
                    shape=RoundedCornerShape(20.dp),
                    modifier=Modifier.weight(1f),
                    maxLines=3
                )
                IconButton(onClick={
                    if(!loggedIn) onRequireAuth()
                    else if(text.isNotBlank()) {
                        val body=text.trim()
                        text=""
                        scope.launch {
                            runCatching { social.addReelComment(reel.id,body,spoiler) }
                                .onSuccess { spoiler=false;reload() }
                        }
                    }
                }) {
                    Icon(Icons.Default.Send,null,tint=if(text.isBlank())FqMuted else FqGold)
                }
            }
        }
    }
}

private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
