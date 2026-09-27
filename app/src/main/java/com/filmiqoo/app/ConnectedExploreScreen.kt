package com.filmiqoo.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
    data class Ready(
        val reels:List<ReelFeedItem>,
        val nextCursor:String?
    ) : ReelLoad
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
    initialReelId: String? = null,
    resumeReelId: String? = null,
    onInitialReelConsumed: () -> Unit = {},
    onVisibleReelChanged: (String) -> Unit = {},
    onMedia: (MediaItem) -> Unit,
    onChat: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit,
    onRequireAuth: () -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val networkOnline=rememberNetworkOnline()
    var state by remember { mutableStateOf<ReelLoad>(ReelLoad.Loading) }
    var refresh by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    var hadOffline by remember { mutableStateOf(false) }
    val startupResumeReelId=remember { resumeReelId }

    LaunchedEffect(networkOnline) {
        if(!networkOnline) {
            hadOffline=true
        } else if(hadOffline && state is ReelLoad.Error) {
            hadOffline=false
            refresh++
        }
    }

    LaunchedEffect(refresh,initialReelId) {
        state=ReelLoad.Loading
        state=runCatching {
            val page=social.reelsPage()
            val feed=page.items
            val requestedId=initialReelId
                ?.takeIf(String::isNotBlank)
                ?: startupResumeReelId?.takeIf(String::isNotBlank)
            val target=requestedId?.let { id ->
                    feed.firstOrNull { it.id==id }
                        ?: runCatching { social.reel(id) }.getOrNull()
                }
            val ordered=if(target==null) {
                feed
            } else {
                listOf(target)+feed.filterNot { it.id==target.id }
            }
            if(
                target!=null &&
                !initialReelId.isNullOrBlank()
            ) {
                onInitialReelConsumed()
            }
            ReelLoad.Ready(
                reels=ordered,
                nextCursor=page.nextCursor
            )
        }.getOrElse { ReelLoad.Error(it.message ?: "خطا در دریافت Clips") }
    }

    when(val s=state) {
        ReelLoad.Loading -> LoadingPage("در حال آماده‌سازی Clips...")
        is ReelLoad.Error -> {
            ClipsUnavailableState(
                title="Clips در دسترس نیست",
                body=s.message,
                action="تلاش دوباره",
                onAction={refresh++}
            )
        }
        is ReelLoad.Ready -> {
            if(s.reels.isEmpty()) {
                ClipsUnavailableState(
                    title="هنوز Clip واقعی منتشر نشده",
                    body="وقتی اولین Clip منتشر بشه، همین‌جا وارد فید عمودی Filmiqoo می‌شه.",
                    action="تازه‌سازی",
                    onAction={refresh++}
                )
            } else {
                RealReelsPager(
                    reels=s.reels,
                    nextCursor=s.nextCursor,
                    loadingMore=loadingMore,
                    social=social,
                    backend=backend,
                    store=store,
                    loggedIn=loggedIn,
                    onMedia=onMedia,
                    onCreator=onCreator,
                    onRequireAuth=onRequireAuth,
                    onVisibleReelChanged=onVisibleReelChanged,
                    onClipRemoved={ removedId ->
                        val currentState=state as? ReelLoad.Ready
                        if(currentState!=null) {
                            state=currentState.copy(
                                reels=currentState.reels.filterNot { it.id==removedId }
                            )
                        }
                    },
                    onLoadMore={ cursor ->
                        if(!loadingMore) {
                            loadingMore=true
                            scope.launch {
                                runCatching {
                                    social.reelsPage(cursor=cursor)
                                }.onSuccess { page ->
                                    val current=(state as? ReelLoad.Ready)
                                        ?: return@onSuccess
                                    state=ReelLoad.Ready(
                                        reels=(current.reels+page.items)
                                            .distinctBy { it.id },
                                        nextCursor=page.nextCursor
                                    )
                                }
                                loadingMore=false
                            }
                        }
                    },
                    onRefresh={refresh++}
                )
                if(!networkOnline) {
                    NetworkOfflineBanner(
                        modifier=Modifier.padding(horizontal=12.dp,vertical=12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RealReelsPager(
    reels:List<ReelFeedItem>,
    nextCursor:String?,
    loadingMore:Boolean,
    social:SocialRepository,
    backend: BackendRepository,
    store:LocalStore,
    loggedIn: Boolean,
    onMedia: (MediaItem) -> Unit,
    onCreator:(Creator)->Unit,
    onRequireAuth:()->Unit,
    onVisibleReelChanged:(String)->Unit,
    onClipRemoved:(String)->Unit,
    onLoadMore:(String)->Unit,
    onRefresh:()->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val pager=rememberPagerState(pageCount={reels.size})
    val liked=remember { mutableStateMapOf<String,Boolean>() }
    val saved=remember { mutableStateMapOf<String,Boolean>() }
    val followed=remember { mutableStateMapOf<String,Boolean>() }
    val followPending=remember { mutableStateMapOf<String,Boolean>() }
    val likeBusy=remember { mutableStateMapOf<String,Boolean>() }
    val saveBusy=remember { mutableStateMapOf<String,Boolean>() }
    val followBusy=remember { mutableStateMapOf<String,Boolean>() }
    val commentDelta=remember { mutableStateMapOf<String,Long>() }
    val shareCount=remember { mutableStateMapOf<String,Long>() }
    val revealed=remember { mutableStateMapOf<String,Boolean>() }
    var commentsFor by remember { mutableStateOf<ReelFeedItem?>(null) }
    var moreFor by remember { mutableStateOf<ReelFeedItem?>(null) }
    var safetyFor by remember { mutableStateOf<ReelFeedItem?>(null) }
    var deleteFor by remember { mutableStateOf<ReelFeedItem?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var currentUserId by remember(loggedIn) { mutableStateOf<String?>(null) }
    var muted by remember {
        mutableStateOf(store.getBoolean("clips_muted",false))
    }

    LaunchedEffect(loggedIn) {
        currentUserId=if(loggedIn) {
            runCatching { backend.me().id }.getOrNull()
        } else null
    }

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

    LaunchedEffect(current?.id) {
        current?.id
            ?.takeIf(String::isNotBlank)
            ?.let(onVisibleReelChanged)
    }

    LaunchedEffect(
        pager.currentPage,
        reels.size,
        nextCursor,
        loadingMore
    ) {
        val cursor=nextCursor
        if(
            !loadingMore &&
            !cursor.isNullOrBlank() &&
            reels.isNotEmpty() &&
            pager.currentPage>=reels.size-4
        ) {
            onLoadMore(cursor)
        }
    }

    LaunchedEffect(pager.currentPage,reels) {
        val reel=reels.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        val playable=reels.filter { it.playbackUrl.isNotBlank() }
        val expectedIds=playable.map { it.id }
        val existingIds=(0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaId
        }

        if(existingIds!=expectedIds) {
            player.stop()
            player.setMediaItems(
                playable.map { item ->
                    ExoMediaItem.Builder()
                        .setMediaId(item.id)
                        .setUri(item.playbackUrl)
                        .build()
                }
            )
            if(playable.isNotEmpty()) {
                player.prepare()
            }
        }

        val playerIndex=(0 until player.mediaItemCount)
            .firstOrNull {
                player.getMediaItemAt(it).mediaId==reel.id
            }

        if(playerIndex!=null) {
            if(player.currentMediaItemIndex!=playerIndex) {
                player.seekToDefaultPosition(playerIndex)
            }
            player.playWhenReady=true
        } else {
            player.pause()
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
        store.putBoolean("clips_muted",muted)
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
                liked=liked[reel.id] ?: reel.likedByMe,
                canInteract=loggedIn,
                saved=saved[reel.id] ?: reel.savedByMe,
                followed=followed[reel.author.id] ?: reel.followingAuthor,
                followPending=followPending[reel.author.id] ?: reel.followPending,
                ownClip=currentUserId!=null && currentUserId==reel.author.id,
                commentCount=(reel.comments+(commentDelta[reel.id] ?: 0L)).coerceAtLeast(0L),
                shareCount=shareCount[reel.id] ?: reel.shares,
                onReveal={
                    revealed[reel.id]=true
                    if(isCurrent) player.play()
                },
                onLike={
                    if(!loggedIn) {
                        onRequireAuth()
                    } else if(likeBusy[reel.id]!=true) {
                        val previous=liked[reel.id] ?: reel.likedByMe
                        liked[reel.id]=!previous
                        likeBusy[reel.id]=true
                        scope.launch {
                            runCatching { social.toggleReelLike(reel.id) }
                                .onSuccess { liked[reel.id]=it }
                                .onFailure { liked[reel.id]=previous }
                            likeBusy.remove(reel.id)
                        }
                    }
                },
                onSave={
                    if(!loggedIn) {
                        onRequireAuth()
                    } else if(saveBusy[reel.id]!=true) {
                        val previous=saved[reel.id] ?: reel.savedByMe
                        saved[reel.id]=!previous
                        saveBusy[reel.id]=true
                        scope.launch {
                            runCatching { social.toggleReelSave(reel.id) }
                                .onSuccess { saved[reel.id]=it }
                                .onFailure { saved[reel.id]=previous }
                            saveBusy.remove(reel.id)
                        }
                    }
                },
                onFollow={
                    if(!loggedIn) {
                        onRequireAuth()
                    } else if(followBusy[reel.author.id]!=true) {
                        followBusy[reel.author.id]=true
                        scope.launch {
                            runCatching { social.toggleUserFollowState(reel.author.id) }
                                .onSuccess {
                                    followed[reel.author.id]=it.following
                                    followPending[reel.author.id]=it.pending
                                }
                            followBusy.remove(reel.author.id)
                        }
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
                            bio="سازنده در Filmiqoo",
                            verified=reel.author.verified,
                            id=reel.author.id,
                            entityType="user",
                            avatarUrl=reel.author.avatarUrl
                        )
                    )
                },
                onShare={
                    FilmiqooDeepLinks.share(
                        context,
                        "Filmiqoo Clip • "+
                            (reel.media?.title ?: reel.caption.ifBlank{"Clip"}),
                        FilmiqooDeepLinks.reel(reel.id)
                    )
                    if(loggedIn) {
                        scope.launch {
                            runCatching {
                                social.shareReel(reel.id,"system")
                            }.onSuccess { count ->
                                shareCount[reel.id]=count
                            }
                        }
                    }
                },
                onMore={moreFor=reel}
            )
        }

        Surface(
            color=Color.Black.copy(alpha=.56f),
            contentColor=Color.White,
            shape=RoundedCornerShape(22.dp),
            border=androidx.compose.foundation.BorderStroke(
                1.dp,
                Color.White.copy(alpha=.10f)
            ),
            modifier=Modifier.align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top=8.dp)
        ) {
            Row(
                Modifier.padding(horizontal=14.dp,vertical=9.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SmartDisplay,
                    null,
                    modifier=Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Clips",
                    fontWeight=FontWeight.Black,
                    fontSize=12.sp
                )
                Text(
                    "  •  برای تو",
                    color=Color.White.copy(alpha=.65f),
                    fontSize=10.sp
                )
            }
        }

        Surface(
            color=Color.Black.copy(alpha=.48f),
            contentColor=Color.White,
            shape=CircleShape,
            border=androidx.compose.foundation.BorderStroke(
                1.dp,
                Color.White.copy(alpha=.10f)
            ),
            modifier=Modifier.align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top=8.dp,end=10.dp)
                .size(44.dp)
                .clickable { muted=!muted }
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(
                    if(muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription=if(muted)"فعال کردن صدا" else "بی‌صدا کردن",
                    modifier=Modifier.size(21.dp)
                )
            }
        }

        AnimatedVisibility(
            visible=loadingMore && pager.currentPage>=reels.size-3,
            enter=fadeIn(tween(120)),
            exit=fadeOut(tween(160)),
            modifier=Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom=18.dp)
        ) {
            Surface(
                color=Color.Black.copy(alpha=.62f),
                contentColor=Color.White,
                shape=CircleShape,
                border=androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha=.10f)
                )
            ) {
                Row(
                    Modifier.padding(horizontal=12.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color=Color.White,
                        strokeWidth=2.dp,
                        modifier=Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "در حال آوردن Clipهای بعدی",
                        fontSize=9.sp,
                        color=Color.White.copy(alpha=.82f)
                    )
                }
            }
        }
    }

    commentsFor?.let { reel ->
        ReelCommentsSheet(
            reel=reel,
            social=social,
            loggedIn=loggedIn,
            onRequireAuth=onRequireAuth,
            onCommentAdded={
                commentDelta[reel.id]=(commentDelta[reel.id] ?: 0L)+1L
            },
            onDismiss={commentsFor=null}
        )
    }

    moreFor?.let { reel ->
        val ownClip=currentUserId!=null && currentUserId==reel.author.id
        ModalBottomSheet(
            onDismissRequest={moreFor=null},
            containerColor=FqSurface,
            dragHandle={BottomSheetDefaults.DragHandle(color=FqMuted)}
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal=16.dp)
                    .padding(bottom=24.dp)
            ) {
                Text(
                    if(ownClip)"مدیریت Clip" else "گزینه‌های Clip",
                    fontSize=18.sp,
                    fontWeight=FontWeight.Black,
                    modifier=Modifier.padding(bottom=8.dp)
                )

                if(ownClip) {
                    ReelMoreAction(
                        icon=Icons.Default.DeleteOutline,
                        title="حذف Clip",
                        subtitle="Clip از پروفایل و فید Filmiqoo حذف می‌شه."
                    ) {
                        moreFor=null
                        deleteFor=reel
                    }
                } else {
                    ReelMoreAction(
                        icon=Icons.Default.DoNotDisturbOn,
                        title="علاقه ندارم",
                        subtitle="Clipهای مشابه کمتر نمایش داده می‌شن."
                    ) {
                        moreFor=null
                        if(!loggedIn) {
                            onRequireAuth()
                        } else {
                            scope.launch {
                                runCatching {
                                    social.feedback("reel",reel.id,"not_interested")
                                }.onSuccess {
                                    feedbackMessage="این نوع Clip کمتر نمایش داده می‌شه."
                                    onRefresh()
                                }
                            }
                        }
                    }
                    ReelMoreAction(
                        icon=Icons.Default.Shield,
                        title="ایمنی و گزارش",
                        subtitle="گزارش، Block یا Mute کردن این حساب"
                    ) {
                        moreFor=null
                        if(!loggedIn) onRequireAuth() else safetyFor=reel
                    }
                }
            }
        }
    }

    deleteFor?.let { reel ->
        AlertDialog(
            onDismissRequest={
                if(!deleteBusy) deleteFor=null
            },
            icon={
                Icon(
                    Icons.Default.DeleteOutline,
                    null,
                    tint=FqDanger
                )
            },
            title={Text("حذف Clip؟")},
            text={
                Text(
                    "این Clip دیگه در فید و پروفایل نمایش داده نمی‌شه. این کار رو فقط برای Clipهای خودت می‌تونی انجام بدی."
                )
            },
            confirmButton={
                Button(
                    onClick={
                        if(!deleteBusy) {
                            deleteBusy=true
                            scope.launch {
                                runCatching {
                                    social.removeReel(reel.id)
                                }.onSuccess { removed ->
                                    if(removed) {
                                        onClipRemoved(reel.id)
                                        feedbackMessage="Clip حذف شد."
                                        deleteFor=null
                                    }
                                }.onFailure {
                                    feedbackMessage=it.message ?: "حذف Clip ناموفق بود."
                                }
                                deleteBusy=false
                            }
                        }
                    },
                    enabled=!deleteBusy,
                    colors=ButtonDefaults.buttonColors(
                        containerColor=FqDanger,
                        contentColor=Color.White
                    )
                ) {
                    if(deleteBusy) {
                        CircularProgressIndicator(
                            strokeWidth=2.dp,
                            modifier=Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("حذف")
                }
            },
            dismissButton={
                TextButton(
                    onClick={deleteFor=null},
                    enabled=!deleteBusy
                ) {
                    Text("انصراف")
                }
            }
        )
    }

    safetyFor?.let { reel ->
        SafetyActionSheet(
            backend=backend,
            targetType="reel",
            targetId=reel.id,
            targetLabel="Clip از "+reel.author.displayName,
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
    canInteract: Boolean,
    saved: Boolean,
    followed: Boolean,
    followPending: Boolean,
    ownClip: Boolean,
    commentCount: Long,
    shareCount: Long,
    onReveal: () -> Unit,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onFollow: () -> Unit,
    onComment: () -> Unit,
    onMedia: () -> Unit,
    onCreator: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit
) {
    val haptic=LocalHapticFeedback.current
    var heartBurst by remember(reel.id) { mutableStateOf(false) }

    LaunchedEffect(heartBurst) {
        if(heartBurst) {
            delay(430)
            heartBurst=false
        }
    }

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
                modifier=Modifier.fillMaxSize()
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

        if(!reel.spoiler || revealed) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(reel.id,liked,canInteract,player) {
                        detectTapGestures(
                            onTap={
                                if(active && player!=null) {
                                    if(player.isPlaying) player.pause()
                                    else player.play()
                                }
                            },
                            onDoubleTap={
                                if(canInteract) {
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove
                                    )
                                    if(!liked) onLike()
                                    heartBurst=true
                                } else {
                                    onLike()
                                }
                            }
                        )
                    }
            )
        }

        AnimatedVisibility(
            visible=heartBurst,
            enter=fadeIn(tween(70))+
                scaleIn(
                    initialScale=.38f,
                    animationSpec=tween(160)
                ),
            exit=fadeOut(tween(180))+
                scaleOut(
                    targetScale=1.35f,
                    animationSpec=tween(200)
                ),
            modifier=Modifier.align(Alignment.Center)
        ) {
            Icon(
                Icons.Default.Favorite,
                null,
                tint=Color.White,
                modifier=Modifier.size(92.dp)
            )
        }

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
                    Text("این Clip دارای اسپویل است.",color=Color.White,fontSize=12.sp,modifier=Modifier.padding(top=7.dp))
                    Text("برای نمایش لمس کن",color=FqMuted,fontSize=12.sp,modifier=Modifier.padding(top=3.dp))
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
                    Text("@"+reel.author.username,color=Color.White.copy(alpha=.7f),fontSize=11.sp)
                }
                Spacer(Modifier.width(10.dp))
                if(!ownClip) {
                    OutlinedButton(
                        onClick=onFollow,
                        contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp),
                        modifier=Modifier.height(30.dp),
                        colors=ButtonDefaults.outlinedButtonColors(
                            contentColor=when {
                                followed -> FqGold
                                followPending -> FqGoldSoft
                                else -> Color.White
                            }
                        )
                    ) {
                        Text(
                            when {
                                followed -> "دنبال می‌کنی"
                                followPending -> "درخواست شد"
                                else -> "دنبال"
                            },
                            fontSize=11.sp
                        )
                    }
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
                            Text(media.title,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(
                                listOf(
                                    media.year,
                                    if(media.vote>0) "★ "+formatVote(media.vote) else ""
                                ).filter(String::isNotBlank).joinToString(" • "),
                                color=FqMuted,fontSize=11.sp
                            )
                        }
                        Icon(Icons.Default.PlayArrow,null,tint=FqGold)
                        Text("صفحه فیلم",color=FqGold,fontSize=11.sp)
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
                text=compactCount(
                    (
                        reel.likes+
                            when {
                                liked && !reel.likedByMe -> 1
                                !liked && reel.likedByMe -> -1
                                else -> 0
                            }
                    ).coerceAtLeast(0)
                ),
                onClick=onLike
            )
            ReelCircleAction(
                icon=Icons.Default.ChatBubble,
                tint=Color.White,
                text=compactCount(commentCount),
                onClick=onComment
            )
            ReelCircleAction(
                icon=Icons.Default.Bookmark,
                tint=if(saved)FqGold else Color.White,
                text=compactCount(
                    (
                        reel.saves+
                            when {
                                saved && !reel.savedByMe -> 1
                                !saved && reel.savedByMe -> -1
                                else -> 0
                            }
                    ).coerceAtLeast(0)
                ),
                onClick=onSave
            )
            ReelCircleAction(
                icon=Icons.Default.Share,
                tint=Color.White,
                text=compactCount(shareCount),
                onClick=onShare
            )
            ReelCircleAction(
                icon=Icons.Default.MoreHoriz,
                tint=Color.White,
                text="بیشتر",
                onClick=onMore
            )
        }
    }
}

@Composable
private fun ReelMoreAction(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    subtitle:String,
    onClick:()->Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal=12.dp,vertical=13.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Surface(
            color=FqSurface2,
            contentColor=Color.White,
            shape=CircleShape,
            modifier=Modifier.size(42.dp)
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(icon,null,modifier=Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text(
                subtitle,
                color=FqMuted,
                fontSize=10.sp,
                modifier=Modifier.padding(top=2.dp)
            )
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
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
        Text(text,color=Color.White,fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelCommentsSheet(
    reel: ReelFeedItem,
    social: SocialRepository,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onCommentAdded: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var items by remember(reel.id) { mutableStateOf<List<SocialComment>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<SocialComment?>(null) }
    var loading by remember { mutableStateOf(true) }
    val displayComments=remember(items) {
        threadedSocialComments(items)
    }

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
                Text(
                    compactCount(if(loading)reel.comments else items.size.toLong()),
                    color=FqMuted,
                    fontSize=11.sp
                )
            }

            if(loading) LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth().padding(top=8.dp))

            androidx.compose.foundation.lazy.LazyColumn(
                modifier=Modifier.weight(1f).padding(top=8.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                items(
                    displayComments.size,
                    key={displayComments[it].id}
                ) { index ->
                    val c=displayComments[index]
                    var reveal by remember(c.id) { mutableStateOf(!c.spoiler) }
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start=if(c.parentCommentId!=null)26.dp else 0.dp),
                        verticalAlignment=Alignment.Top
                    ) {
                        RemoteImage(c.author.avatarUrl.takeIf(String::isNotBlank),Modifier.size(34.dp).clip(CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Text(
                                    c.author.displayName,
                                    color=FqGold,
                                    fontSize=11.sp
                                )
                                val relative=socialRelativeTime(c.createdAt)
                                if(relative.isNotBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "• "+relative,
                                        color=FqMuted,
                                        fontSize=8.sp
                                    )
                                }
                            }
                            if(c.spoiler && !reveal) {
                                Text(
                                    "⚠ Spoiler Shield • نمایش",
                                    color=FqDanger,fontSize=11.sp,
                                    modifier=Modifier.padding(top=4.dp).clickable { reveal=true }
                                )
                            } else {
                                Text(
                                    c.body,
                                    fontSize=12.sp,
                                    lineHeight=17.sp,
                                    modifier=Modifier.padding(top=3.dp)
                                )
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                TextButton(
                                    onClick={
                                        if(loggedIn) replyTo=c
                                        else onRequireAuth()
                                    },
                                    contentPadding=PaddingValues(
                                        horizontal=0.dp,
                                        vertical=2.dp
                                    )
                                ) {
                                    Text("پاسخ",color=FqMuted,fontSize=9.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick={
                                        if(!loggedIn) {
                                            onRequireAuth()
                                        } else {
                                            val before=c.likedByMe
                                            val optimistic=!before
                                            items=items.map {
                                                if(it.id==c.id) {
                                                    it.copy(
                                                        likedByMe=optimistic,
                                                        likes=(
                                                            it.likes+
                                                                if(optimistic)1 else -1
                                                        ).coerceAtLeast(0)
                                                    )
                                                } else it
                                            }
                                            scope.launch {
                                                runCatching {
                                                    social.toggleCommentLike(c.id)
                                                }.onSuccess { result ->
                                                    items=items.map {
                                                        if(it.id==c.id) {
                                                            it.copy(
                                                                likedByMe=result.first,
                                                                likes=result.second
                                                            )
                                                        } else it
                                                    }
                                                }.onFailure {
                                                    reload()
                                                }
                                            }
                                        }
                                    },
                                    modifier=Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        if(c.likedByMe)
                                            Icons.Default.Favorite
                                        else
                                            Icons.Default.FavoriteBorder,
                                        null,
                                        tint=if(c.likedByMe)FqDanger else FqMuted,
                                        modifier=Modifier.size(14.dp)
                                    )
                                }
                                if(c.likes>0) {
                                    Text(
                                        compactCount(c.likes),
                                        color=if(c.likedByMe)FqDanger else FqMuted,
                                        fontSize=8.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(vertical=8.dp)) {
                replyTo?.let { target ->
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(12.dp),
                        modifier=Modifier.fillMaxWidth().padding(bottom=7.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal=10.dp,vertical=7.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Reply,
                                null,
                                tint=FqGold,
                                modifier=Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "پاسخ به "+target.author.displayName,
                                fontSize=9.sp,
                                fontWeight=FontWeight.Bold,
                                modifier=Modifier.weight(1f)
                            )
                            IconButton(
                                onClick={replyTo=null},
                                modifier=Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    null,
                                    tint=FqMuted,
                                    modifier=Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment=Alignment.CenterVertically) {
                    FilterChip(
                        selected=spoiler,
                        onClick={spoiler=!spoiler},
                        label={Text("Spoiler",fontSize=11.sp)}
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value=text,
                        onValueChange={text=it},
                        placeholder={
                            Text(
                                if(replyTo!=null)"پاسخت رو بنویس..."
                                else "نظر بنویس..."
                            )
                        },
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
                                runCatching {
                                    social.addReelComment(
                                        reelId=reel.id,
                                        body=body,
                                        spoiler=spoiler,
                                        parentCommentId=replyTo?.id
                                    )
                                }.onSuccess {
                                    spoiler=false
                                    replyTo=null
                                    onCommentAdded()
                                    reload()
                                }
                            }
                        }
                    }) {
                        Icon(
                            Icons.Default.Send,
                            null,
                            tint=if(text.isBlank())FqMuted else FqGold
                        )
                    }
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


@Composable
private fun ClipsUnavailableState(
    title:String,
    body:String,
    action:String,
    onAction:()->Unit
) {
    Box(
        Modifier.fillMaxSize().background(FqBg),
        contentAlignment=Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal=30.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Surface(
                color=FqSurface,
                shape=CircleShape,
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.size(76.dp)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(
                        Icons.Default.SmartDisplay,
                        null,
                        tint=Color.White,
                        modifier=Modifier.size(34.dp)
                    )
                }
            }
            Text(
                title,
                fontSize=20.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=16.dp)
            )
            Text(
                body,
                color=FqMuted,
                fontSize=11.sp,
                lineHeight=18.sp,
                modifier=Modifier.padding(top=7.dp)
            )
            OutlinedButton(
                onClick=onAction,
                shape=RoundedCornerShape(16.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.padding(top=16.dp)
            ) {
                Icon(Icons.Default.Refresh,null,modifier=Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(action)
            }
        }
    }
}
