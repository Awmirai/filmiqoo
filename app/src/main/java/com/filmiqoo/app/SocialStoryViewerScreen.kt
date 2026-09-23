package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SocialStoryViewerScreen(
    stories: List<SocialStory>,
    startIndex: Int,
    social: SocialRepository,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onClose: () -> Unit
) {
    if(stories.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0,stories.lastIndex)) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var revealedSpoiler by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val story=stories[index]

    val player=remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode=Player.REPEAT_MODE_OFF
            playWhenReady=true
        }
    }

    fun next() {
        if(index<stories.lastIndex) {
            index++
        } else {
            onClose()
        }
    }

    fun previous() {
        if(index>0) index-- else progress=0f
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(index) {
        progress=0f
        revealedSpoiler=!story.spoiler
        reply=""
        if(loggedIn) {
            runCatching { social.markStoryViewed(story.id) }
        }

        if(story.type=="video" && story.mediaUrl.isNotBlank()) {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(ExoMediaItem.fromUri(story.mediaUrl))
            player.prepare()
            player.playWhenReady=!story.spoiler
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }

    LaunchedEffect(paused,revealedSpoiler,index,story.type) {
        if(story.type=="video") {
            player.playWhenReady=!paused && revealedSpoiler
        }
    }

    LaunchedEffect(index,story.type,revealedSpoiler,paused) {
        if(!revealedSpoiler) return@LaunchedEffect

        if(story.type=="video") {
            while(true) {
                delay(80)
                if(paused) continue
                val duration=player.duration
                if(duration>0) {
                    progress=(player.currentPosition.toFloat()/duration.toFloat()).coerceIn(0f,1f)
                    if(player.playbackState==Player.STATE_ENDED || progress>=.999f) {
                        next()
                        break
                    }
                }
            }
        } else {
            val durationMs=6_000L
            val step=60L
            var elapsed=(progress*durationMs).toLong()
            while(elapsed<durationMs) {
                delay(step)
                if(paused) continue
                elapsed+=step
                progress=(elapsed.toFloat()/durationMs.toFloat()).coerceIn(0f,1f)
            }
            if(index<=stories.lastIndex) next()
        }
    }

    BackHandler { onClose() }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .pointerInput(index,revealedSpoiler) {
                detectTapGestures(
                    onTap={ offset ->
                        if(!revealedSpoiler) {
                            revealedSpoiler=true
                            return@detectTapGestures
                        }
                        when {
                            offset.x < size.width*.32f -> previous()
                            offset.x > size.width*.68f -> next()
                            else -> paused=!paused
                        }
                    }
                )
            }
    ) {
        when(story.type) {
            "video" -> {
                AndroidView(
                    factory={ ctx ->
                        PlayerView(ctx).apply {
                            this.player=player
                            useController=false
                            resizeMode=AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                    },
                    update={it.player=player},
                    modifier=Modifier.fillMaxSize()
                )
            }
            "image" -> {
                RemoteImage(
                    story.mediaUrl.ifBlank { story.thumbnailUrl },
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
            }
            else -> {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(Color(0xFF151927),Color(0xFF34270A),Color(0xFF090A0E))
                        )
                    ),
                    contentAlignment=Alignment.Center
                ) {
                    Text(
                        story.caption,
                        color=Color.White,
                        fontSize=26.sp,
                        lineHeight=38.sp,
                        fontWeight=FontWeight.Bold,
                        modifier=Modifier.padding(30.dp)
                    )
                }
            }
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha=.6f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha=.76f)
                    )
                )
            )
        )

        if(story.spoiler && !revealedSpoiler) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha=.9f))
                    .clickable { revealedSpoiler=true },
                contentAlignment=Alignment.Center
            ) {
                Column(
                    horizontalAlignment=Alignment.CenterHorizontally,
                    modifier=Modifier.padding(28.dp)
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        null,
                        tint=FqDanger,
                        modifier=Modifier.size(54.dp)
                    )
                    Text(
                        "Spoiler Shield",
                        color=FqDanger,
                        fontSize=24.sp,
                        fontWeight=FontWeight.Black,
                        modifier=Modifier.padding(top=12.dp)
                    )
                    Text(
                        "این Story ممکنه بخش مهمی از داستان رو لو بده.",
                        color=Color.White,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=7.dp)
                    )
                    Button(
                        onClick={revealedSpoiler=true},
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        modifier=Modifier.padding(top=15.dp)
                    ) {
                        Text("نمایش Story",color=Color.Black)
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .padding(start=10.dp,end=10.dp,top=8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.spacedBy(4.dp)
            ) {
                stories.forEachIndexed { i,_ ->
                    LinearProgressIndicator(
                        progress={
                            when {
                                i<index -> 1f
                                i>index -> 0f
                                else -> progress
                            }
                        },
                        color=Color.White,
                        trackColor=Color.White.copy(alpha=.25f),
                        modifier=Modifier.weight(1f).height(3.dp).clip(CircleShape)
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top=10.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                RemoteImage(
                    story.author.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(40.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(
                            story.author.displayName,
                            color=Color.White,
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold
                        )
                        if(story.author.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Verified,
                                null,
                                tint=Color(0xFF4AB7FF),
                                modifier=Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        "@"+story.author.username+" • Story",
                        color=Color.White.copy(alpha=.65f),
                        fontSize=7.sp
                    )
                }
                IconButton(onClick={paused=!paused}) {
                    Icon(
                        if(paused)Icons.Default.PlayArrow else Icons.Default.Pause,
                        null,
                        tint=Color.White
                    )
                }
                IconButton(onClick={onClose}) {
                    Icon(Icons.Default.Close,null,tint=Color.White)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .padding(start=14.dp,end=14.dp,bottom=14.dp)
        ) {
            if(story.type!="text" && story.caption.isNotBlank()) {
                Text(
                    story.caption,
                    color=Color.White,
                    fontSize=11.sp,
                    lineHeight=18.sp,
                    maxLines=4,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(bottom=10.dp)
                )
            }

            story.media?.asMediaItem()?.let { media ->
                Surface(
                    color=Color.Black.copy(alpha=.58f),
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth().padding(bottom=10.dp)
                        .clickable { onMedia(media) }
                ) {
                    Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(
                            story.media.posterUrl,
                            Modifier.width(38.dp).height(52.dp).clip(RoundedCornerShape(8.dp)),
                            ContentScale.Crop
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                media.title,
                                color=Color.White,
                                fontSize=9.sp,
                                fontWeight=FontWeight.Bold,
                                maxLines=1,
                                overflow=TextOverflow.Ellipsis
                            )
                            Text(
                                "مشاهده صفحه فیلم / سریال",
                                color=FqMuted,
                                fontSize=7.sp
                            )
                        }
                        Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
                    }
                }
            }

            LazyRow(
                horizontalArrangement=Arrangement.spacedBy(7.dp),
                modifier=Modifier.padding(bottom=9.dp)
            ) {
                items(listOf("❤️","😂","😮","😢","🔥","👏")) { reaction ->
                    Surface(
                        color=Color.Black.copy(alpha=.48f),
                        shape=CircleShape,
                        modifier=Modifier.clickable {
                            if(!loggedIn) {
                                onRequireAuth()
                            } else {
                                scope.launch {
                                    runCatching {
                                        social.reactToStory(story.id,reaction)
                                    }.onSuccess {
                                        toast="واکنش ارسال شد "+reaction
                                    }.onFailure {
                                        toast=it.message
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            reaction,
                            fontSize=20.sp,
                            modifier=Modifier.padding(8.dp)
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value=reply,
                    onValueChange={reply=it},
                    placeholder={Text("پاسخ به Story...")},
                    singleLine=true,
                    shape=RoundedCornerShape(22.dp),
                    colors=OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor=Color.Black.copy(alpha=.35f),
                        focusedContainerColor=Color.Black.copy(alpha=.45f)
                    ),
                    modifier=Modifier.weight(1f)
                )
                Spacer(Modifier.width(7.dp))
                FilledIconButton(
                    enabled=reply.isNotBlank() && !sending,
                    onClick={
                        if(!loggedIn) {
                            onRequireAuth()
                        } else {
                            val text=reply.trim()
                            if(text.isBlank()) return@FilledIconButton
                            sending=true
                            scope.launch {
                                runCatching {
                                    social.replyToStory(story.id,text)
                                }.onSuccess {
                                    reply=""
                                    toast="پاسخ ارسال شد"
                                }.onFailure {
                                    toast=it.message
                                }
                                sending=false
                            }
                        }
                    },
                    colors=IconButtonDefaults.filledIconButtonColors(
                        containerColor=FqGold,
                        contentColor=Color.Black
                    )
                ) {
                    if(sending) {
                        CircularProgressIndicator(
                            color=Color.Black,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(17.dp)
                        )
                    } else {
                        Icon(Icons.Default.Send,null)
                    }
                }
            }
        }

        toast?.let {
            Snackbar(
                modifier=Modifier.align(Alignment.Center).padding(24.dp),
                action={TextButton(onClick={toast=null}){Text("باشه")}}
            ) {
                Text(it)
            }
        }
    }
}
