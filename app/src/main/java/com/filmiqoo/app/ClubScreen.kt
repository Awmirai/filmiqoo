package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Duration

private enum class ClubTab { FOR_YOU, FOLLOWING, ROOMS }

@Composable
fun ClubScreen(
    social:SocialRepository,
    backend:BackendRepository,
    loggedIn:Boolean,
    onMedia:(MediaItem)->Unit,
    onCreator:(Creator)->Unit,
    onOpenClip:(String)->Unit,
    onOpenRoom:(SocialRoom)->Unit,
    onStory:(List<SocialStory>,Int)->Unit,
    onInbox:()->Unit,
    onCreate:()->Unit,
    onRequireAuth:()->Unit
) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val friendRepo=remember { FriendActivityRepository(backend) }
    val pulseRepo=remember { PulseRepository(backend) }
    val messagingRepo=remember { MessagingRepository(backend) }

    var tab by remember { mutableStateOf(ClubTab.FOR_YOU) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var feed by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    var stories by remember { mutableStateOf<List<SocialStory>>(emptyList()) }
    var clips by remember { mutableStateOf<List<ReelFeedItem>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<SocialRoom>>(emptyList()) }
    var creators by remember { mutableStateOf<List<SocialChannel>>(emptyList()) }
    var following by remember { mutableStateOf<List<FriendActivityItem>>(emptyList()) }
    var pulse by remember { mutableStateOf<List<PulseTrendItem>>(emptyList()) }
    var commentsFor by remember { mutableStateOf<SocialPost?>(null) }
    var unreadMessages by remember { mutableLongStateOf(0L) }

    LaunchedEffect(loggedIn,refresh) {
        unreadMessages=if(loggedIn) {
            runCatching {
                messagingRepo.inbox().sumOf { it.unread }
            }.getOrDefault(0L)
        } else 0L
    }

    LaunchedEffect(tab,refresh,loggedIn) {
        loading=true
        error=null

        runCatching {
            when(tab) {
                ClubTab.FOR_YOU -> {
                    pulse=runCatching { pulseRepo.trending() }.getOrDefault(emptyList())
                    val results=listOf(
                        runCatching { feed=social.feed() },
                        runCatching { stories=social.stories() },
                        runCatching { clips=social.reels() },
                        runCatching { rooms=social.rooms() },
                        runCatching { creators=social.channels() }
                    )
                    results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
                }
                ClubTab.FOLLOWING -> {
                    if(!loggedIn) {
                        following=emptyList()
                    } else {
                        following=friendRepo.feed()
                    }
                }
                ClubTab.ROOMS -> rooms=social.rooms()
            }
        }.onFailure {
            error=it.message ?: "Club در دسترس نیست"
        }

        loading=false
    }

    Box(Modifier.fillMaxSize().background(FqBg)) {
        Column(Modifier.fillMaxSize()) {
            ClubHeader(
                selected=tab,
                unreadMessages=unreadMessages,
                onSelected={tab=it},
                onInbox=onInbox,
                onCreate={
                    if(loggedIn) onCreate() else onRequireAuth()
                }
            )

            if(loading) {
                LinearProgressIndicator(
                    modifier=Modifier.fillMaxWidth().height(2.dp),
                    color=FqGold,
                    trackColor=Color.Transparent
                )
            }

            error?.let {
                ClubInlineError(it) { refresh++ }
            }

            when(tab) {
                ClubTab.FOR_YOU -> {
                    ClubForYou(
                        pulse=pulse,
                        stories=stories,
                        clips=clips,
                        feed=feed,
                        rooms=rooms,
                        creators=creators,
                        social=social,
                        loggedIn=loggedIn,
                        onStory=onStory,
                        onMedia=onMedia,
                        onCreator=onCreator,
                        onOpenClip=onOpenClip,
                        onOpenRoom=onOpenRoom,
                        onRequireAuth=onRequireAuth,
                        onComments={commentsFor=it},
                        onFeedChange={feed=it},
                        onRefresh={refresh++}
                    )
                }

                ClubTab.FOLLOWING -> {
                    if(!loggedIn) {
                        ClubSignedOutState(
                            title="فید آدم‌هایی که دنبال می‌کنی",
                            body="بعد از ورود، Reviewها، Listها و فعالیت دوستانت اینجا جمع می‌شن.",
                            onLogin=onRequireAuth
                        )
                    } else {
                        ClubFollowingFeed(
                            items=following,
                            onMedia=onMedia,
                            onCreator=onCreator,
                            onOpenClip=onOpenClip,
                            onRefresh={refresh++}
                        )
                    }
                }

                ClubTab.ROOMS -> {
                    ClubRooms(
                        rooms=rooms,
                        onOpenRoom=onOpenRoom,
                        onMedia=onMedia,
                        onRefresh={refresh++}
                    )
                }
            }
        }
    }

    commentsFor?.let { post ->
        ClubCommentsSheet(
            post=post,
            social=social,
            loggedIn=loggedIn,
            onRequireAuth=onRequireAuth,
            onCommentAdded={
                feed=feed.map {
                    if(it.id==post.id) it.copy(comments=it.comments+1) else it
                }
            },
            onDismiss={commentsFor=null}
        )
    }
}

@Composable
private fun ClubHeader(
    selected:ClubTab,
    unreadMessages:Long,
    onSelected:(ClubTab)->Unit,
    onInbox:()->Unit,
    onCreate:()->Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF111722),
                        FqBg
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal=16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top=10.dp,bottom=14.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Club",
                    fontSize=30.sp,
                    fontWeight=FontWeight.Black,
                    letterSpacing=(-0.5).sp
                )
                Text(
                    "سینما وقتی جذابه که درباره‌ش حرف بزنی",
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=2.dp)
                )
            }

            Box {
                FqIconButton(
                    icon=Icons.Default.MarkChatUnread,
                    contentDescription="پیام‌ها",
                    onClick=onInbox
                )
                if(unreadMessages>0) {
                    Surface(
                        color=Color.White,
                        contentColor=Color.Black,
                        shape=CircleShape,
                        modifier=Modifier.align(Alignment.TopEnd)
                            .offset(x=2.dp,y=(-2).dp)
                    ) {
                        Text(
                            if(unreadMessages>99)"99+" else unreadMessages.toString(),
                            fontSize=7.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(horizontal=5.dp,vertical=2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(5.dp))
            Surface(
                color=Color.White,
                contentColor=Color.Black,
                shape=CircleShape,
                modifier=Modifier.size(42.dp).clickable { onCreate() }
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.Add,null,modifier=Modifier.size(24.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(FqSurface)
                .padding(4.dp),
            horizontalArrangement=Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                ClubTab.FOR_YOU to "برای تو",
                ClubTab.FOLLOWING to "دنبال‌شده‌ها",
                ClubTab.ROOMS to "روم‌ها"
            ).forEach { (tab,label) ->
                val active=selected==tab
                Surface(
                    color=if(active) Color.White else Color.Transparent,
                    contentColor=if(active) Color.Black else FqMuted,
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.weight(1f)
                        .height(38.dp)
                        .clickable { onSelected(tab) }
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Text(
                            label,
                            fontSize=11.sp,
                            fontWeight=if(active)FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ClubForYou(
    pulse:List<PulseTrendItem>,
    stories:List<SocialStory>,
    clips:List<ReelFeedItem>,
    feed:List<SocialPost>,
    rooms:List<SocialRoom>,
    creators:List<SocialChannel>,
    social:SocialRepository,
    loggedIn:Boolean,
    onStory:(List<SocialStory>,Int)->Unit,
    onMedia:(MediaItem)->Unit,
    onCreator:(Creator)->Unit,
    onOpenClip:(String)->Unit,
    onOpenRoom:(SocialRoom)->Unit,
    onRequireAuth:()->Unit,
    onComments:(SocialPost)->Unit,
    onFeedChange:(List<SocialPost>)->Unit,
    onRefresh:()->Unit
) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current

    if(
        pulse.isEmpty() &&
        stories.isEmpty() &&
        clips.isEmpty() &&
        feed.isEmpty() &&
        rooms.isEmpty() &&
        creators.isEmpty()
    ) {
        ClubEmptyState(
            icon=Icons.Default.MovieFilter,
            title="Club تازه داره شکل می‌گیره",
            body="وقتی اولین Post، Clip یا Room واقعی ساخته بشه، اینجا ظاهر می‌شه.",
            action="تازه‌سازی",
            onAction=onRefresh
        )
        return
    }

    LazyColumn(
        contentPadding=PaddingValues(bottom=28.dp),
        verticalArrangement=Arrangement.spacedBy(0.dp)
    ) {
        if(stories.isNotEmpty()) {
            item {
                ClubStories(
                    stories=stories,
                    onStory=onStory
                )
            }
        }

        if(pulse.isNotEmpty()) {
            item {
                ClubSectionTitle(
                    title="الان زنده",
                    subtitle="چیزهایی که همین لحظه بین فیلم‌بازها جریان دارن"
                )
            }
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    items(pulse,key={it.media.key}) { item ->
                        ClubPulseCard(item) { onMedia(item.media) }
                    }
                }
            }
        }

        if(rooms.isNotEmpty()) {
            item {
                ClubSectionTitle(
                    title="Roomهای Club",
                    subtitle="گفت‌وگوهای مرتبط با فیلم‌ها و سریال‌ها"
                )
            }
            item {
                ClubLiveRoomsRow(
                    rooms=rooms.take(8),
                    onOpenRoom=onOpenRoom
                )
            }
        }

        if(clips.isNotEmpty()) {
            item {
                ClubSectionTitle(
                    title="Clips",
                    subtitle="لحظه‌های کوتاه، مستقیم از فیلم‌بازها"
                )
            }
            item {
                ClubClipsRow(
                    clips=clips.take(8),
                    onOpenClip=onOpenClip
                )
            }
        }

        if(creators.isNotEmpty()) {
            item {
                ClubSectionTitle(
                    title="آدم‌ها و رسانه‌ها",
                    subtitle="چیزهایی که ارزش دنبال‌کردن دارن"
                )
            }
            item {
                ClubCreatorsRow(
                    channels=creators.take(10),
                    onCreator=onCreator
                )
            }
        }

        if(feed.isNotEmpty()) {
            item {
                ClubSectionTitle(
                    title="برای تو",
                    subtitle="Review، نظر و پیشنهاد؛ بدون شلوغ‌کاری"
                )
            }
            items(feed,key={it.id}) { post ->
                ClubPostCard(
                    post=post,
                    social=social,
                    loggedIn=loggedIn,
                    onRequireAuth=onRequireAuth,
                    onCreator={
                        onCreator(
                            Creator(
                                name=post.author.displayName,
                                handle="@"+post.author.username,
                                followers="",
                                bio="",
                                verified=post.author.verified,
                                id=post.author.id,
                                entityType="user",
                                avatarUrl=post.author.avatarUrl
                            )
                        )
                    },
                    onMedia={
                        post.media?.asMediaItem()?.let(onMedia)
                    },
                    onLike={
                        if(!loggedIn) {
                            onRequireAuth()
                        } else {
                            val previousLiked=post.likedByMe
                            val previousLikes=post.likes
                            val optimisticLiked=!previousLiked
                            onFeedChange(
                                feed.map {
                                    if(it.id==post.id) {
                                        it.copy(
                                            likedByMe=optimisticLiked,
                                            likes=(
                                                previousLikes+
                                                    if(optimisticLiked)1 else -1
                                            ).coerceAtLeast(0)
                                        )
                                    } else it
                                }
                            )
                            scope.launch {
                                runCatching { social.togglePostLike(post.id) }
                                    .onSuccess { liked->
                                        onFeedChange(
                                            feed.map {
                                                if(it.id==post.id) {
                                                    it.copy(
                                                        likedByMe=liked,
                                                        likes=(
                                                            previousLikes+
                                                                if(liked)1 else -1
                                                        ).coerceAtLeast(0)
                                                    )
                                                } else it
                                            }
                                        )
                                    }
                                    .onFailure {
                                        onFeedChange(
                                            feed.map {
                                                if(it.id==post.id) {
                                                    it.copy(
                                                        likedByMe=previousLiked,
                                                        likes=previousLikes
                                                    )
                                                } else it
                                            }
                                        )
                                    }
                            }
                        }
                    },
                    onSave={
                        if(!loggedIn) {
                            onRequireAuth()
                        } else {
                            val previousSaved=post.savedByMe
                            val previousSaves=post.saves
                            val optimisticSaved=!previousSaved
                            onFeedChange(
                                feed.map {
                                    if(it.id==post.id) {
                                        it.copy(
                                            savedByMe=optimisticSaved,
                                            saves=(
                                                previousSaves+
                                                    if(optimisticSaved)1 else -1
                                            ).coerceAtLeast(0)
                                        )
                                    } else it
                                }
                            )
                            scope.launch {
                                runCatching { social.togglePostSave(post.id) }
                                    .onSuccess { result->
                                        onFeedChange(
                                            feed.map {
                                                if(it.id==post.id) {
                                                    it.copy(
                                                        savedByMe=result.first,
                                                        saves=result.second
                                                    )
                                                } else it
                                            }
                                        )
                                    }
                                    .onFailure {
                                        onFeedChange(
                                            feed.map {
                                                if(it.id==post.id) {
                                                    it.copy(
                                                        savedByMe=previousSaved,
                                                        saves=previousSaves
                                                    )
                                                } else it
                                            }
                                        )
                                    }
                            }
                        }
                    },
                    onComments={onComments(post)},
                    onShare={
                        if(loggedIn) {
                            scope.launch {
                                runCatching { social.sharePost(post.id,"system") }
                            }
                        }
                        shareText(
                            context,
                            buildString {
                                append(post.author.displayName)
                                if(post.body.isNotBlank()) {
                                    append("\n")
                                    append(post.body.take(700))
                                }
                                post.media?.title?.takeIf(String::isNotBlank)?.let {
                                    append("\n🎬 ")
                                    append(it)
                                }
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ClubStories(
    stories:List<SocialStory>,
    onStory:(List<SocialStory>,Int)->Unit
) {
    Column(Modifier.fillMaxWidth().padding(top=3.dp,bottom=2.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=16.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Text(
                "Stories",
                fontSize=13.sp,
                fontWeight=FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "۲۴ ساعت",
                color=FqMuted,
                fontSize=10.sp
            )
        }
        LazyRow(
            contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(stories,key={_,story->story.id}) { index,story ->
                Column(
                    horizontalAlignment=Alignment.CenterHorizontally,
                    modifier=Modifier.width(64.dp)
                        .clickable { onStory(stories,index) }
                ) {
                    Box(
                        Modifier.size(58.dp)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFFFF5D75),
                                        FqGold,
                                        Color(0xFF7C5CFF),
                                        Color(0xFFFF5D75)
                                    )
                                ),
                                CircleShape
                            )
                            .padding(2.dp)
                    ) {
                        RemoteImage(
                            story.author.avatarUrl
                                .ifBlank { story.thumbnailUrl }
                                .ifBlank { story.media?.posterUrl.orEmpty() }
                                .takeIf(String::isNotBlank),
                            Modifier.fillMaxSize()
                                .clip(CircleShape)
                                .background(FqSurface2),
                            ContentScale.Crop
                        )
                    }
                    Text(
                        story.author.displayName,
                        fontSize=9.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClubSectionTitle(
    title:String,
    subtitle:String
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal=16.dp)
            .padding(top=18.dp,bottom=8.dp)
    ) {
        Text(
            title,
            fontSize=18.sp,
            fontWeight=FontWeight.Black
        )
        Text(
            subtitle,
            color=FqMuted,
            fontSize=10.sp,
            modifier=Modifier.padding(top=2.dp)
        )
    }
}

@Composable
private fun ClubLiveRoomsRow(
    rooms:List<SocialRoom>,
    onOpenRoom:(SocialRoom)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(rooms,key={it.id}) { room ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(20.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.width(230.dp)
                    .clickable { onOpenRoom(room) }
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(52.dp)
                            .clip(RoundedCornerShape(15.dp))
                    ) {
                        RemoteImage(
                            room.media?.posterUrl?.takeIf(String::isNotBlank),
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Surface(
                            color=Color.Black.copy(alpha=.72f),
                            shape=RoundedCornerShape(7.dp),
                            modifier=Modifier.align(Alignment.BottomStart)
                                .padding(4.dp)
                        ) {
                            Text(
                                roomTypeLabel(room.type),
                                color=Color.White.copy(alpha=.82f),
                                fontSize=7.sp,
                                modifier=Modifier.padding(horizontal=5.dp,vertical=2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            room.name,
                            fontSize=12.sp,
                            fontWeight=FontWeight.Bold,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis
                        )
                        Text(
                            room.topic,
                            color=FqMuted,
                            fontSize=9.sp,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=3.dp)
                        )
                        Text(
                            compactClubCount(room.members)+" عضو",
                            color=Color.White.copy(alpha=.72f),
                            fontSize=9.sp,
                            modifier=Modifier.padding(top=5.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClubClipsRow(
    clips:List<ReelFeedItem>,
    onOpenClip:(String)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(clips,key={it.id}) { clip ->
            Box(
                Modifier.width(132.dp)
                    .height(210.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(FqSurface2)
.clickable { onOpenClip(clip.id) }
            ) {
                RemoteImage(
                    clip.coverUrl.takeIf(String::isNotBlank)
                        ?: clip.media?.backdropUrl
                        ?: clip.media?.posterUrl,
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha=.08f),
                                Color.Black.copy(alpha=.88f)
                            )
                        )
                    )
                )
                Surface(
                    color=Color.Black.copy(alpha=.54f),
                    shape=CircleShape,
                    modifier=Modifier.align(Alignment.Center)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        null,
                        modifier=Modifier.padding(8.dp).size(19.dp)
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(10.dp)
                ) {
                    Text(
                        clip.caption.ifBlank {
                            clip.media?.title ?: clip.author.displayName
                        },
                        fontSize=10.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                    Text(
                        compactClubCount(clip.views)+" بازدید",
                        color=Color.White.copy(alpha=.65f),
                        fontSize=8.sp,
                        modifier=Modifier.padding(top=4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClubCreatorsRow(
    channels:List<SocialChannel>,
    onCreator:(Creator)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(9.dp)
    ) {
        items(channels,key={it.id}) { channel ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.width(176.dp)
                    .clickable {
                        onCreator(
                            Creator(
                                name=channel.name,
                                handle="@"+channel.slug,
                                followers=channel.followers.toString(),
                                bio=channel.bio,
                                verified=channel.verified,
                                id=channel.id,
                                entityType="channel",
                                avatarUrl=channel.avatarUrl,
                                coverUrl=channel.coverUrl
                            )
                        )
                    }
            ) {
                Row(
                    Modifier.padding(11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    RemoteImage(
                        channel.avatarUrl.takeIf(String::isNotBlank),
                        Modifier.size(42.dp).clip(CircleShape),
                        ContentScale.Crop
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(
                                channel.name,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                maxLines=1,
                                overflow=TextOverflow.Ellipsis
                            )
                            if(channel.verified) {
                                Spacer(Modifier.width(3.dp))
                                Icon(
                                    Icons.Default.Verified,
                                    null,
                                    tint=Color(0xFF4AB7FF),
                                    modifier=Modifier.size(12.dp)
                                )
                            }
                        }
                        Text(
                            compactClubCount(channel.followers)+" دنبال‌کننده",
                            color=FqMuted,
                            fontSize=8.sp,
                            modifier=Modifier.padding(top=3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClubPostCard(
    post:SocialPost,
    social:SocialRepository,
    loggedIn:Boolean,
    onRequireAuth:()->Unit,
    onCreator:()->Unit,
    onMedia:()->Unit,
    onLike:()->Unit,
    onSave:()->Unit,
    onComments:()->Unit,
    onShare:()->Unit
) {
    var revealed by remember(post.id) { mutableStateOf(!post.spoiler) }

    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal=12.dp,vertical=5.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(FqSurface)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment=Alignment.CenterVertically,
            modifier=Modifier.fillMaxWidth()
                .clickable { onCreator() }
        ) {
            RemoteImage(
                post.author.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(42.dp).clip(CircleShape),
                ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        post.author.displayName,
                        fontSize=12.sp,
                        fontWeight=FontWeight.Bold
                    )
                    if(post.author.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Verified,
                            null,
                            tint=Color(0xFF4AB7FF),
                            modifier=Modifier.size(13.dp)
                        )
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        "@"+post.author.username,
                        color=FqMuted,
                        fontSize=9.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when(post.type.lowercase()) {
                            "review" -> "Review"
                            "poll" -> "Poll"
                            "announcement" -> "Announcement"
                            else -> "Post"
                        },
                        color=when(post.type.lowercase()) {
                            "review" -> FqGold
                            "poll" -> FqBlue
                            else -> FqMuted
                        },
                        fontSize=8.sp,
                        fontWeight=FontWeight.Bold
                    )
                    post.publishedAt?.let { published ->
                        val relative=clubRelativeTime(published)
                        if(relative.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text("• "+relative,color=FqMuted,fontSize=8.sp)
                        }
                    }
                }
            }
        }

        if(post.spoiler && !revealed) {
            Surface(
                color=Color(0xFFFF4D67).copy(alpha=.08f),
                shape=RoundedCornerShape(16.dp),
                border=androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color(0xFFFF4D67).copy(alpha=.18f)
                ),
                modifier=Modifier.fillMaxWidth()
                    .padding(top=12.dp)
                    .clickable { revealed=true }
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        null,
                        tint=Color(0xFFFF6A7D)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Spoiler مخفی شده",
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold
                        )
                        Text(
                            "برای نمایش لمس کن",
                            color=FqMuted,
                            fontSize=9.sp
                        )
                    }
                }
            }
        } else if(post.body.isNotBlank()) {
            Text(
                post.body,
                fontSize=12.sp,
                lineHeight=20.sp,
                color=Color.White.copy(alpha=.92f),
                modifier=Modifier.padding(top=12.dp)
            )
        }

        if(
            post.type.equals("poll",ignoreCase=true) &&
            (!post.spoiler || revealed)
        ) {
            ClubPollInline(
                postId=post.id,
                social=social,
                loggedIn=loggedIn,
                onRequireAuth=onRequireAuth
            )
        }

        post.media?.let { media ->
            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(top=12.dp)
                    .clickable { onMedia() }
            ) {
                Row(
                    Modifier.padding(8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    RemoteImage(
                        media.posterUrl?.takeIf(String::isNotBlank),
                        Modifier.size(54.dp,72.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            media.title.orEmpty(),
                            fontSize=12.sp,
                            fontWeight=FontWeight.Bold,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis
                        )
                        Text(
                            "روی Filmiqoo",
                            color=FqMuted,
                            fontSize=9.sp,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }
                    Icon(
                        Icons.Default.ChevronLeft,
                        null,
                        tint=FqMuted
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top=11.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            ClubAction(
                icon=if(post.likedByMe)Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                value=post.likes,
                active=post.likedByMe,
                onClick=onLike
            )
            ClubAction(
                icon=Icons.Default.ChatBubbleOutline,
                value=post.comments,
                onClick=onComments
            )
            ClubAction(
                icon=Icons.Default.IosShare,
                value=post.shares,
                onClick=onShare
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick=onSave) {
                Icon(
                    if(post.savedByMe)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    null,
                    tint=if(post.savedByMe)Color.White else FqMuted
                )
            }
        }
    }
}

@Composable
private fun ClubPollInline(
    postId:String,
    social:SocialRepository,
    loggedIn:Boolean,
    onRequireAuth:()->Unit
) {
    val scope=rememberCoroutineScope()
    var data by remember(postId) { mutableStateOf<PollData?>(null) }
    var selectedId by remember(postId) { mutableStateOf<String?>(null) }
    var loading by remember(postId) { mutableStateOf(true) }
    var busyOption by remember(postId) { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading=true
            data=runCatching { social.poll(postId) }.getOrNull()
            selectedId=if(loggedIn) {
                runCatching { social.pollSelection(postId) }.getOrNull()
            } else null
            loading=false
        }
    }

    LaunchedEffect(postId,loggedIn) { reload() }

    Surface(
        color=FqSurface2,
        shape=RoundedCornerShape(18.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=Modifier.fillMaxWidth().padding(top=12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            if(loading && data==null) {
                LinearProgressIndicator(
                    color=FqBlue,
                    trackColor=Color.Transparent,
                    modifier=Modifier.fillMaxWidth().height(2.dp)
                )
                return@Column
            }

            val poll=data ?: return@Column
            poll.options.forEach { option ->
                val selected=selectedId==option.id
                val fraction=if(poll.totalVotes<=0) 0f
                else (option.votes.toFloat()/poll.totalVotes.toFloat()).coerceIn(0f,1f)

                Surface(
                    color=if(selected) FqBlue.copy(alpha=.12f) else FqSurface,
                    shape=RoundedCornerShape(14.dp),
                    border=androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if(selected) FqBlue.copy(alpha=.38f) else FqBorder
                    ),
                    modifier=Modifier.fillMaxWidth()
                        .padding(vertical=4.dp)
                        .clickable(enabled=busyOption==null) {
                            if(!loggedIn) {
                                onRequireAuth()
                            } else {
                                busyOption=option.id
                                scope.launch {
                                    runCatching {
                                        social.votePoll(postId,option.id)
                                    }.onSuccess { choice ->
                                        selectedId=choice.takeIf(String::isNotBlank)
                                        data=runCatching { social.poll(postId) }
                                            .getOrDefault(poll)
                                    }
                                    busyOption=null
                                }
                            }
                        }
                ) {
                    Column(Modifier.padding(horizontal=11.dp,vertical=9.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            if(selected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    tint=FqBlue,
                                    modifier=Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                option.label,
                                fontSize=10.sp,
                                fontWeight=if(selected) FontWeight.Bold else FontWeight.Medium,
                                modifier=Modifier.weight(1f)
                            )
                            Text(
                                ((fraction*100).toInt()).toString()+"٪",
                                color=if(selected) FqBlue else FqMuted,
                                fontSize=9.sp,
                                fontWeight=FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress={fraction},
                            color=if(selected) FqBlue else Color.White.copy(alpha=.30f),
                            trackColor=Color.White.copy(alpha=.06f),
                            modifier=Modifier.fillMaxWidth()
                                .height(3.dp)
                                .padding(top=7.dp)
                        )
                    }
                }
            }

            Text(
                compactClubCount(data?.totalVotes ?: 0L)+" رأی",
                color=FqMuted,
                fontSize=9.sp,
                modifier=Modifier.padding(top=6.dp,start=3.dp)
            )
        }
    }
}

@Composable
private fun ClubAction(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    value:Long,
    active:Boolean=false,
    onClick:()->Unit
) {
    Row(
        Modifier.clickable { onClick() }
            .padding(horizontal=7.dp,vertical=7.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint=if(active)Color(0xFFFF5D75) else FqMuted,
            modifier=Modifier.size(19.dp)
        )
        if(value>0) {
            Spacer(Modifier.width(4.dp))
            Text(
                compactClubCount(value),
                color=if(active)Color(0xFFFF8A9A) else FqMuted,
                fontSize=9.sp
            )
        }
    }
}

@Composable
private fun ClubFollowingFeed(
    items:List<FriendActivityItem>,
    onMedia:(MediaItem)->Unit,
    onCreator:(Creator)->Unit,
    onOpenClip:(String)->Unit,
    onRefresh:()->Unit
) {
    if(items.isEmpty()) {
        ClubEmptyState(
            icon=Icons.Default.PeopleOutline,
            title="اینجا هنوز آرومه",
            body="وقتی آدم‌های بیشتری رو Follow کنی، فعالیت واقعی‌شون اینجا میاد.",
            action="تازه‌سازی",
            onAction=onRefresh
        )
        return
    }

    LazyColumn(
        contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(items,key={it.type+"_"+it.entityId+"_"+it.createdAt}) { item ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(22.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment=Alignment.CenterVertically,
                        modifier=Modifier.clickable {
                            onCreator(
                                Creator(
                                    name=item.actor.displayName,
                                    handle="@"+item.actor.username,
                                    followers="",
                                    bio="",
                                    verified=item.actor.verified,
                                    id=item.actor.id,
                                    entityType="user",
                                    avatarUrl=item.actor.avatarUrl
                                )
                            )
                        }
                    ) {
                        RemoteImage(
                            item.actor.avatarUrl.takeIf(String::isNotBlank),
                            Modifier.size(40.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.actor.displayName,
                                fontSize=12.sp,
                                fontWeight=FontWeight.Bold
                            )
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(
                                    followingActionLabel(item.type),
                                    color=FqMuted,
                                    fontSize=9.sp
                                )
                                val relative=clubRelativeTime(item.createdAt)
                                if(relative.isNotBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "• "+relative,
                                        color=FqMuted,
                                        fontSize=9.sp
                                    )
                                }
                            }
                        }
                    }

                    var activityRevealed by remember(
                        item.type,
                        item.entityId,
                        item.createdAt
                    ) {
                        mutableStateOf(!item.spoiler)
                    }

                    if(item.body.isNotBlank()) {
                        if(item.spoiler && !activityRevealed) {
                            Surface(
                                color=FqDanger.copy(alpha=.08f),
                                shape=RoundedCornerShape(14.dp),
                                border=androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    FqDanger.copy(alpha=.16f)
                                ),
                                modifier=Modifier.fillMaxWidth()
                                    .padding(top=12.dp)
                                    .clickable { activityRevealed=true }
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        null,
                                        tint=FqDanger,
                                        modifier=Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        "Spoiler مخفی شده • برای نمایش لمس کن",
                                        color=FqDanger,
                                        fontSize=10.sp,
                                        fontWeight=FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            Text(
                                item.body,
                                fontSize=12.sp,
                                lineHeight=19.sp,
                                modifier=Modifier.padding(top=12.dp)
                            )
                        }
                    }

                    if(item.type.lowercase() in listOf("reel","clip")) {
                        Surface(
                            color=Color.White,
                            contentColor=Color.Black,
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.padding(top=12.dp)
                                .clickable { onOpenClip(item.entityId) }
                        ) {
                            Row(
                                Modifier.padding(horizontal=12.dp,vertical=9.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SmartDisplay,
                                    null,
                                    modifier=Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "دیدن Clip",
                                    fontSize=10.sp,
                                    fontWeight=FontWeight.Bold
                                )
                            }
                        }
                    }

                    item.media?.let { media ->
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(top=12.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(FqSurface2)
                                .clickable { onMedia(media) }
                                .padding(9.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            RemoteImage(
                                media.posterPath,
                                Modifier.size(44.dp,60.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                media.title,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                modifier=Modifier.weight(1f),
                                maxLines=2,
                                overflow=TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClubRooms(
    rooms:List<SocialRoom>,
    onOpenRoom:(SocialRoom)->Unit,
    onMedia:(MediaItem)->Unit,
    onRefresh:()->Unit
) {
    if(rooms.isEmpty()) {
        ClubEmptyState(
            icon=Icons.Default.Forum,
            title="هنوز Room فعالی نیست",
            body="Roomهای فیلم و قسمت‌ها وقتی Catalog پر بشه خودکار اینجا زنده می‌شن.",
            action="تازه‌سازی",
            onAction=onRefresh
        )
        return
    }

    var roomQuery by remember { mutableStateOf("") }
    val filteredRooms=remember(rooms,roomQuery) {
        val q=roomQuery.trim()
        if(q.isBlank()) rooms
        else rooms.filter { room ->
            room.name.contains(q,ignoreCase=true) ||
                room.topic.contains(q,ignoreCase=true) ||
                room.media?.title.orEmpty().contains(q,ignoreCase=true)
        }
    }

    LazyColumn(
        contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "Roomها",
                fontSize=22.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(start=4.dp,end=4.dp,bottom=4.dp)
            )
            Text(
                "بحث فیلم‌ها، سریال‌ها و قسمت‌ها",
                color=FqMuted,
                fontSize=10.sp,
                modifier=Modifier.padding(start=4.dp,end=4.dp,bottom=9.dp)
            )
            OutlinedTextField(
                value=roomQuery,
                onValueChange={roomQuery=it},
                singleLine=true,
                placeholder={Text("جستجو در Roomها...")},
                leadingIcon={
                    Icon(Icons.Default.Search,null,modifier=Modifier.size(18.dp))
                },
                trailingIcon={
                    if(roomQuery.isNotBlank()) {
                        IconButton(onClick={roomQuery=""}) {
                            Icon(Icons.Default.Close,null,modifier=Modifier.size(17.dp))
                        }
                    }
                },
                shape=RoundedCornerShape(16.dp),
                colors=OutlinedTextFieldDefaults.colors(
                    focusedBorderColor=Color.White.copy(alpha=.18f),
                    unfocusedBorderColor=FqBorder,
                    focusedContainerColor=FqSurface,
                    unfocusedContainerColor=FqSurface
                ),
                modifier=Modifier.fillMaxWidth()
            )
        }
        if(filteredRooms.isEmpty()) {
            item {
                PremiumEmptyState(
                    icon=Icons.Default.SearchOff,
                    title="Room پیدا نشد",
                    body="اسم فیلم، سریال یا موضوع گفتگو رو با عبارت دیگه‌ای جستجو کن."
                )
            }
        }
        items(filteredRooms,key={it.id}) { room ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(22.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.fillMaxWidth()
                    .clickable { onOpenRoom(room) }
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(68.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(FqSurface2)
                    ) {
                        RemoteImage(
                            room.media?.posterUrl?.takeIf(String::isNotBlank),
                            Modifier.fillMaxSize()
                                .clickable {
                                    room.media?.asMediaItem()?.let(onMedia)
                                        ?: onOpenRoom(room)
                                },
                            ContentScale.Crop
                        )
                        Surface(
                            color=Color.Black.copy(alpha=.72f),
                            shape=RoundedCornerShape(8.dp),
                            modifier=Modifier.align(Alignment.BottomStart)
                                .padding(6.dp)
                        ) {
                            Text(
                                roomTypeLabel(room.type),
                                color=Color.White.copy(alpha=.86f),
                                fontSize=8.sp,
                                fontWeight=FontWeight.Bold,
                                modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            room.name,
                            fontSize=13.sp,
                            fontWeight=FontWeight.Bold,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis
                        )
                        Text(
                            room.topic,
                            color=FqMuted,
                            fontSize=10.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=4.dp)
                        )
                        Text(
                            compactClubCount(room.members)+" عضو",
                            color=Color.White.copy(alpha=.72f),
                            fontSize=9.sp,
                            modifier=Modifier.padding(top=6.dp)
                        )
                    }
                    Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubCommentsSheet(
    post:SocialPost,
    social:SocialRepository,
    loggedIn:Boolean,
    onRequireAuth:()->Unit,
    onCommentAdded:()->Unit,
    onDismiss:()->Unit
) {
    val scope=rememberCoroutineScope()
    var comments by remember(post.id) { mutableStateOf<List<SocialComment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading=true
            comments=runCatching { social.comments(post.id) }.getOrDefault(emptyList())
            loading=false
        }
    }

    LaunchedEffect(post.id) { reload() }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface,
        dragHandle={
            BottomSheetDefaults.DragHandle(color=FqMuted)
        }
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal=16.dp)
        ) {
            Text(
                "گفت‌وگو",
                fontSize=20.sp,
                fontWeight=FontWeight.Black
            )
            Text(
                post.author.displayName+" • "+
                    compactClubCount(if(loading)post.comments else comments.size.toLong())+
                    " نظر",
                color=FqMuted,
                fontSize=10.sp,
                modifier=Modifier.padding(top=3.dp,bottom=12.dp)
            )

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth()
                )
            } else if(comments.isEmpty()) {
                Text(
                    "اولین نفر باش که چیزی می‌گه.",
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(vertical=22.dp)
                )
            } else {
                LazyColumn(
                    modifier=Modifier.heightIn(max=360.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)
                ) {
                    items(comments,key={it.id}) { comment ->
                        Row(
                            verticalAlignment=Alignment.Top
                        ) {
                            RemoteImage(
                                comment.author.avatarUrl.takeIf(String::isNotBlank),
                                Modifier.size(34.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    comment.author.displayName,
                                    fontSize=10.sp,
                                    fontWeight=FontWeight.Bold
                                )
                                var reveal by remember(comment.id) {
                                    mutableStateOf(!comment.spoiler)
                                }
                                if(comment.spoiler && !reveal) {
                                    Text(
                                        "⚠ Spoiler Shield • نمایش",
                                        color=FqDanger,
                                        fontSize=10.sp,
                                        fontWeight=FontWeight.Bold,
                                        modifier=Modifier.padding(top=4.dp)
                                            .clickable { reveal=true }
                                    )
                                } else {
                                    Text(
                                        comment.body,
                                        fontSize=11.sp,
                                        lineHeight=18.sp,
                                        modifier=Modifier.padding(top=2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(top=14.dp,bottom=10.dp)
            ) {
                FilterChip(
                    selected=spoiler,
                    onClick={spoiler=!spoiler},
                    label={Text("Spoiler",fontSize=10.sp)},
                    leadingIcon={
                        Icon(
                            Icons.Default.VisibilityOff,
                            null,
                            modifier=Modifier.size(14.dp)
                        )
                    }
                )
                Row(
                    Modifier.fillMaxWidth().padding(top=6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value=text,
                        onValueChange={text=it},
                        placeholder={Text("نظرت رو بنویس...")},
                        singleLine=false,
                        maxLines=4,
                        shape=RoundedCornerShape(18.dp),
                        modifier=Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick={
                            if(!loggedIn) {
                                onRequireAuth()
                            } else {
                                val clean=text.trim()
                                if(clean.isNotBlank() && !sending) {
                                    sending=true
                                    scope.launch {
                                        runCatching {
                                            social.addComment(
                                                postId=post.id,
                                                body=clean,
                                                spoiler=spoiler
                                            )
                                        }.onSuccess {
                                            text=""
                                            spoiler=false
                                            onCommentAdded()
                                            reload()
                                        }
                                        sending=false
                                    }
                                }
                            }
                        },
                        enabled=!sending,
                        colors=IconButtonDefaults.filledIconButtonColors(
                            containerColor=Color.White,
                            contentColor=Color.Black
                        )
                    ) {
                        Icon(Icons.Default.ArrowUpward,null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClubEmptyState(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    body:String,
    action:String,
    onAction:()->Unit
) {
    Box(
        Modifier.fillMaxSize().padding(28.dp),
        contentAlignment=Alignment.Center
    ) {
        Column(
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Surface(
                color=FqSurface,
                shape=CircleShape
            ) {
                Icon(
                    icon,
                    null,
                    tint=FqMuted,
                    modifier=Modifier.padding(18.dp).size(34.dp)
                )
            }
            Text(
                title,
                fontSize=18.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=14.dp)
            )
            Text(
                body,
                color=FqMuted,
                fontSize=11.sp,
                lineHeight=18.sp,
                modifier=Modifier.padding(top=6.dp),
            )
            TextButton(
                onClick=onAction,
                modifier=Modifier.padding(top=6.dp)
            ) {
                Text(action,color=Color.White)
            }
        }
    }
}

@Composable
private fun ClubSignedOutState(
    title:String,
    body:String,
    onLogin:()->Unit
) {
    Box(
        Modifier.fillMaxSize().padding(28.dp),
        contentAlignment=Alignment.Center
    ) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Lock,
                null,
                tint=FqMuted,
                modifier=Modifier.size(38.dp)
            )
            Text(
                title,
                fontSize=18.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=12.dp)
            )
            Text(
                body,
                color=FqMuted,
                fontSize=11.sp,
                lineHeight=18.sp,
                modifier=Modifier.padding(top=6.dp)
            )
            Button(
                onClick=onLogin,
                colors=ButtonDefaults.buttonColors(
                    containerColor=Color.White,
                    contentColor=Color.Black
                ),
                shape=RoundedCornerShape(16.dp),
                modifier=Modifier.padding(top=14.dp)
            ) {
                Text("ورود به Filmiqoo")
            }
        }
    }
}

@Composable
private fun ClubInlineError(
    message:String,
    onRetry:()->Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xFFFF4D67).copy(alpha=.08f))
            .padding(horizontal=16.dp,vertical=9.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Text(
            message,
            color=Color(0xFFFF8A9A),
            fontSize=10.sp,
            modifier=Modifier.weight(1f),
            maxLines=1,
            overflow=TextOverflow.Ellipsis
        )
        TextButton(onClick=onRetry) {
            Text("دوباره",fontSize=10.sp,color=Color.White)
        }
    }
}

private fun roomTypeLabel(type:String):String = when(type.lowercase()) {
    "episode" -> "EPISODE"
    "community" -> "TITLE"
    "group" -> "GROUP"
    "dm" -> "DM"
    else -> "ROOM"
}

private fun followingActionLabel(type:String):String = when(type.lowercase()) {
    "review" -> "یک Review نوشته"
    "post" -> "یک پست منتشر کرده"
    "collection" -> "یک List ساخته"
    "reel","clip" -> "یک Clip منتشر کرده"
    "watching" -> "الان در حال تماشاست"
    else -> "در Club فعال بوده"
}

private fun clubRelativeTime(value:String):String {
    val published=runCatching { Instant.parse(value) }.getOrNull() ?: return ""
    val minutes=Duration.between(published,Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes<1 -> "الان"
        minutes<60 -> minutes.toString()+"د"
        minutes<1_440 -> (minutes/60).toString()+"س"
        minutes<10_080 -> (minutes/1_440).toString()+"روز"
        else -> (minutes/10_080).toString()+"هفته"
    }
}

private fun compactClubCount(value:Long):String = when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}


@Composable
private fun ClubPulseCard(
    item:PulseTrendItem,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            if(item.live) Color(0xFFFF5D6C).copy(alpha=.28f) else FqBorder
        ),
        modifier=Modifier.width(238.dp)
            .height(138.dp)
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxSize()) {
            RemoteImage(
                item.media.backdropPath ?: item.media.posterPath,
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha=.90f),
                            Color.Black.copy(alpha=.48f),
                            Color.Transparent
                        )
                    )
                )
            )

            Column(
                Modifier.fillMaxSize().padding(13.dp),
                verticalArrangement=Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    if(item.live) {
                        Box(
                            Modifier.size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5D6C))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "LIVE",
                            color=Color(0xFFFF7180),
                            fontSize=9.sp,
                            fontWeight=FontWeight.Black
                        )
                    } else {
                        Text(
                            "PULSE",
                            color=FqGold,
                            fontSize=9.sp,
                            fontWeight=FontWeight.Black
                        )
                    }
                }

                Column {
                    Text(
                        item.media.title,
                        color=Color.White,
                        fontSize=14.sp,
                        fontWeight=FontWeight.Black,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.padding(top=6.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        if(item.watchingNow>0) {
                            Icon(
                                Icons.Default.Visibility,
                                null,
                                tint=Color.White.copy(alpha=.74f),
                                modifier=Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                item.watchingNow.toString()+" در حال تماشا",
                                color=Color.White.copy(alpha=.78f),
                                fontSize=9.sp
                            )
                        }
                        if(item.reactions>0) {
                            if(item.watchingNow>0) Spacer(Modifier.width(10.dp))
                            Text(
                                "🔥 "+compactClubCount(item.reactions),
                                color=Color.White.copy(alpha=.78f),
                                fontSize=9.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


