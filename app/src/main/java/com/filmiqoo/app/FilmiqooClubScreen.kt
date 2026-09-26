package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class ClubTab { FOR_YOU, ROOMS, CREATORS }

private data class ClubBundle(
    val stories: List<SocialStory> = emptyList(),
    val posts: List<SocialPost> = emptyList(),
    val reels: List<ReelFeedItem> = emptyList(),
    val rooms: List<SocialRoom> = emptyList(),
    val channels: List<SocialChannel> = emptyList()
)

@Composable
fun FilmiqooClubScreen(
    social: SocialRepository,
    loggedIn: Boolean,
    onOpenRoom: (SocialRoom) -> Unit,
    onCreator: (Creator) -> Unit,
    onStory: (List<SocialStory>, Int) -> Unit,
    onMedia: (MediaItem) -> Unit,
    onOpenClips: () -> Unit,
    onCreate: () -> Unit,
    onInbox: () -> Unit,
    onRequireAuth: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var tab by remember { mutableStateOf(ClubTab.FOR_YOU) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(ClubBundle()) }

    LaunchedEffect(refresh,loggedIn) {
        loading=true
        error=null
        val stories=runCatching { social.stories() }.getOrDefault(emptyList())
        val posts=runCatching { social.feed() }.getOrDefault(emptyList())
        val reels=runCatching { social.reels() }.getOrDefault(emptyList())
        val rooms=runCatching { social.rooms() }.getOrDefault(emptyList())
        val channels=runCatching { social.channels() }.getOrDefault(emptyList())
        data=ClubBundle(
            stories=stories,
            posts=posts,
            reels=reels,
            rooms=rooms,
            channels=channels
        )
        loading=false
        if(stories.isEmpty() && posts.isEmpty() && reels.isEmpty() && rooms.isEmpty() && channels.isEmpty()) {
            error="هنوز فعالیتی در Club نیست."
        }
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        ClubTopBar(
            onCreate={
                if(loggedIn) onCreate() else onRequireAuth()
            },
            onInbox={
                if(loggedIn) onInbox() else onRequireAuth()
            }
        )

        ClubTabs(
            selected=tab,
            onSelected={tab=it}
        )

        if(loading) {
            LinearProgressIndicator(
                color=FqGold,
                trackColor=FqSurface2,
                modifier=Modifier.fillMaxWidth()
            )
        }

        when(tab) {
            ClubTab.FOR_YOU -> ClubForYou(
                data=data,
                social=social,
                loggedIn=loggedIn,
                onStory=onStory,
                onCreator=onCreator,
                onMedia=onMedia,
                onOpenClips=onOpenClips,
                onOpenRoom=onOpenRoom,
                onRequireAuth=onRequireAuth,
                onRefresh={refresh++}
            )
            ClubTab.ROOMS -> ClubRooms(
                rooms=data.rooms,
                onOpenRoom=onOpenRoom
            )
            ClubTab.CREATORS -> ClubCreators(
                channels=data.channels,
                social=social,
                loggedIn=loggedIn,
                onCreator=onCreator,
                onRequireAuth=onRequireAuth
            )
        }

        error?.let {
            if(!loading && data==ClubBundle()) {
                Text(
                    it,
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ClubTopBar(
    onCreate:()->Unit,
    onInbox:()->Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal=16.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Club",
                fontSize=27.sp,
                fontWeight=FontWeight.Black,
                letterSpacing=(-.6).sp
            )
            Text(
                "آدم‌ها، فیلم‌ها و گفت‌وگوهای واقعی",
                color=FqMuted,
                fontSize=11.sp,
                modifier=Modifier.padding(top=2.dp)
            )
        }
        FqIconButton(
            icon=Icons.Default.Add,
            contentDescription="ساخت محتوا",
            onClick=onCreate
        )
        Spacer(Modifier.width(4.dp))
        FqIconButton(
            icon=Icons.Default.MarkChatUnread,
            contentDescription="پیام‌ها",
            onClick=onInbox
        )
    }
}

@Composable
private fun ClubTabs(
    selected:ClubTab,
    onSelected:(ClubTab)->Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,bottom=10.dp)
            .background(FqSurface,RoundedCornerShape(18.dp))
            .padding(4.dp),
        horizontalArrangement=Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            ClubTab.FOR_YOU to "برای تو",
            ClubTab.ROOMS to "Roomها",
            ClubTab.CREATORS to "Creatorها"
        ).forEach { item ->
            val active=selected==item.first
            Surface(
                color=if(active) FqGold else Color.Transparent,
                contentColor=if(active) Color.Black else FqMuted,
                shape=RoundedCornerShape(14.dp),
                modifier=Modifier.weight(1f)
                    .clickable { onSelected(item.first) }
            ) {
                Box(
                    Modifier.padding(vertical=10.dp),
                    contentAlignment=Alignment.Center
                ) {
                    Text(
                        item.second,
                        fontSize=11.sp,
                        fontWeight=if(active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ClubForYou(
    data:ClubBundle,
    social:SocialRepository,
    loggedIn:Boolean,
    onStory:(List<SocialStory>,Int)->Unit,
    onCreator:(Creator)->Unit,
    onMedia:(MediaItem)->Unit,
    onOpenClips:()->Unit,
    onOpenRoom:(SocialRoom)->Unit,
    onRequireAuth:()->Unit,
    onRefresh:()->Unit
) {
    val scope=rememberCoroutineScope()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding=PaddingValues(bottom=26.dp)
    ) {
        if(data.stories.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp,vertical=4.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp)
                ) {
                    items(data.stories.take(14),key={it.id}) { story ->
                        val index=data.stories.indexOf(story)
                        ClubStoryBubble(story) { onStory(data.stories,index) }
                    }
                }
            }
        }

        if(data.reels.isNotEmpty()) {
            item {
                ClubSectionHeader(
                    title="Clips",
                    subtitle="لحظه‌های کوتاه از چیزهایی که دوست داری",
                    action="همه",
                    onAction=onOpenClips
                )
            }
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    items(data.reels.take(6),key={it.id}) { reel ->
                        ClubClipCard(reel,onOpenClips)
                    }
                }
            }
        }

        if(data.rooms.isNotEmpty()) {
            item {
                ClubSectionHeader(
                    title="الان داغه",
                    subtitle="گفت‌وگوهایی که همین حالا جریان دارن"
                )
            }
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    items(data.rooms.take(5),key={it.id}) { room ->
                        ClubRoomCard(room) { onOpenRoom(room) }
                    }
                }
            }
        }

        if(data.posts.isNotEmpty()) {
            item {
                ClubSectionHeader(
                    title="برای تو",
                    subtitle="بر اساس فیلم‌ها، آدم‌ها و تعامل‌های تو"
                )
            }
            items(data.posts,key={it.id}) { post ->
                ClubPostCard(
                    post=post,
                    loggedIn=loggedIn,
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
                    onMedia={post.media?.asMediaItem()?.let(onMedia)},
                    onLike={
                        if(!loggedIn) onRequireAuth()
                        else scope.launch {
                            runCatching { social.togglePostLike(post.id) }
                                .onSuccess { onRefresh() }
                        }
                    },
                    onSave={
                        if(!loggedIn) onRequireAuth()
                        else scope.launch {
                            runCatching { social.togglePostSave(post.id) }
                                .onSuccess { onRefresh() }
                        }
                    }
                )
            }
        }

        if(data.posts.isEmpty() && data.reels.isEmpty() && data.rooms.isEmpty()) {
            item {
                PremiumEmptyState(
                    icon=Icons.Default.Groups,
                    title="Club تازه شروع شده",
                    body="با دنبال‌کردن آدم‌ها، دیدن فیلم‌ها و ساختن اولین پست، این فضا شخصی می‌شه."
                )
            }
        }
    }
}

@Composable
private fun ClubStoryBubble(
    story:SocialStory,
    onClick:()->Unit
) {
    Column(
        Modifier.width(72.dp).clickable { onClick() },
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp)
                .background(FqGold,CircleShape)
                .padding(2.dp)
        ) {
            RemoteImage(
                story.author.avatarUrl.takeIf(String::isNotBlank),
                Modifier.fillMaxSize().clip(CircleShape)
            )
        }
        Text(
            story.author.displayName.ifBlank { story.author.username },
            fontSize=10.sp,
            maxLines=1,
            overflow=TextOverflow.Ellipsis,
            modifier=Modifier.padding(top=5.dp)
        )
    }
}

@Composable
private fun ClubClipCard(
    reel:ReelFeedItem,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.width(150.dp).height(220.dp)
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxSize()) {
            RemoteImage(
                reel.coverUrl.ifBlank { reel.media?.posterUrl.orEmpty() },
                Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent,Color.Black.copy(alpha=.82f))
                    )
                )
            )
            Icon(
                Icons.Default.PlayArrow,
                null,
                tint=Color.White,
                modifier=Modifier.align(Alignment.Center).size(34.dp)
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(10.dp)
            ) {
                Text(
                    reel.media?.title ?: reel.caption,
                    color=Color.White,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    "@"+reel.author.username,
                    color=Color.White.copy(alpha=.72f),
                    fontSize=10.sp,
                    modifier=Modifier.padding(top=3.dp)
                )
            }
        }
    }
}

@Composable
private fun ClubRoomCard(
    room:SocialRoom,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=Modifier.width(235.dp).clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier.size(9.dp).background(Color(0xFFFF5268),CircleShape)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "LIVE ROOM",
                    color=Color(0xFFFF6E80),
                    fontSize=9.sp,
                    fontWeight=FontWeight.Bold
                )
            }
            Text(
                room.name,
                fontSize=14.sp,
                fontWeight=FontWeight.Bold,
                maxLines=2,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=10.dp)
            )
            if(room.topic.isNotBlank()) {
                Text(
                    room.topic,
                    color=FqMuted,
                    fontSize=11.sp,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=5.dp)
                )
            }
            Row(
                Modifier.padding(top=12.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Groups,null,tint=FqGold,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    room.members.toString()+" عضو",
                    color=FqMuted,
                    fontSize=10.sp
                )
            }
        }
    }
}

@Composable
private fun ClubPostCard(
    post:SocialPost,
    loggedIn:Boolean,
    onCreator:()->Unit,
    onMedia:()->Unit,
    onLike:()->Unit,
    onSave:()->Unit
) {
    var spoilerVisible by remember(post.id) { mutableStateOf(!post.spoiler) }
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(24.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=Modifier.fillMaxWidth()
            .padding(horizontal=16.dp,vertical=6.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(
                verticalAlignment=Alignment.CenterVertically,
                modifier=Modifier.clickable { onCreator() }
            ) {
                RemoteImage(
                    post.author.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(42.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(9.dp))
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
                                modifier=Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        "@"+post.author.username,
                        color=FqMuted,
                        fontSize=10.sp
                    )
                }
                Text(
                    when(post.type) {
                        "review" -> "Review"
                        "poll" -> "Poll"
                        else -> "Post"
                    },
                    color=FqGold,
                    fontSize=10.sp
                )
            }

            if(post.spoiler && !spoilerVisible) {
                Surface(
                    color=FqDanger.copy(alpha=.10f),
                    shape=RoundedCornerShape(15.dp),
                    modifier=Modifier.fillMaxWidth()
                        .padding(top=12.dp)
                        .clickable { spoilerVisible=true }
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment=Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.VisibilityOff,null,tint=FqDanger)
                        Text(
                            "Spoiler Shield",
                            color=FqDanger,
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.padding(top=6.dp)
                        )
                        Text("برای دیدن لمس کن",color=FqMuted,fontSize=10.sp)
                    }
                }
            } else if(post.body.isNotBlank()) {
                Text(
                    post.body,
                    fontSize=12.sp,
                    lineHeight=19.sp,
                    modifier=Modifier.padding(top=12.dp)
                )
            }

            post.media?.takeIf { !it.title.isNullOrBlank() }?.let { media ->
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(17.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                        .clickable { onMedia() }
                ) {
                    Row(
                        Modifier.padding(10.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        RemoteImage(
                            media.posterUrl,
                            Modifier.size(46.dp,64.dp).clip(RoundedCornerShape(10.dp))
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
                                "مشاهده عنوان",
                                color=FqGold,
                                fontSize=10.sp,
                                modifier=Modifier.padding(top=4.dp)
                            )
                        }
                        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top=9.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                TextButton(onClick=onLike) {
                    Icon(
                        if(post.likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint=if(post.likedByMe) FqDanger else LocalContentColor.current,
                        modifier=Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(post.likes.toString(),fontSize=10.sp)
                }
                TextButton(onClick={}) {
                    Icon(Icons.Default.ChatBubbleOutline,null,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(post.comments.toString(),fontSize=10.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick=onSave) {
                    Icon(
                        if(post.savedByMe) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        null,
                        tint=if(post.savedByMe) FqGold else LocalContentColor.current
                    )
                }
            }
        }
    }
}

@Composable
private fun ClubRooms(
    rooms:List<SocialRoom>,
    onOpenRoom:(SocialRoom)->Unit
) {
    if(rooms.isEmpty()) {
        PremiumEmptyState(
            icon=Icons.Default.Forum,
            title="Room فعالی نیست",
            body="Roomهای فیلم‌ها و سریال‌ها وقتی گفتگو شروع بشه اینجا ظاهر می‌شن."
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding=PaddingValues(horizontal=16.dp,bottom=24.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(rooms,key={it.id}) { room ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(22.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.fillMaxWidth().clickable { onOpenRoom(room) }
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    RemoteImage(
                        room.media?.posterUrl,
                        Modifier.size(58.dp).clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(room.name,fontSize=13.sp,fontWeight=FontWeight.Bold)
                        Text(
                            room.topic,
                            color=FqMuted,
                            fontSize=10.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=4.dp)
                        )
                        Text(
                            room.members.toString()+" عضو",
                            color=FqGold,
                            fontSize=10.sp,
                            modifier=Modifier.padding(top=6.dp)
                        )
                    }
                    Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                }
            }
        }
    }
}

@Composable
private fun ClubCreators(
    channels:List<SocialChannel>,
    social:SocialRepository,
    loggedIn:Boolean,
    onCreator:(Creator)->Unit,
    onRequireAuth:()->Unit
) {
    val scope=rememberCoroutineScope()
    val followed=remember { mutableStateMapOf<String,Boolean>() }
    if(channels.isEmpty()) {
        PremiumEmptyState(
            icon=Icons.Default.PersonSearch,
            title="Creator پیدا نشد",
            body="Creatorها و Channelهای فعال اینجا نمایش داده می‌شن."
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding=PaddingValues(horizontal=16.dp,bottom=24.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(channels,key={it.id}) { channel ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(22.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(13.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    RemoteImage(
                        channel.avatarUrl.takeIf(String::isNotBlank),
                        Modifier.size(54.dp).clip(CircleShape)
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
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(
                        Modifier.weight(1f).clickable {
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
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(channel.name,fontSize=13.sp,fontWeight=FontWeight.Bold)
                            if(channel.verified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(14.dp))
                            }
                        }
                        Text("@"+channel.slug,color=FqMuted,fontSize=10.sp)
                        if(channel.bio.isNotBlank()) {
                            Text(
                                channel.bio,
                                color=Color.White.copy(alpha=.76f),
                                fontSize=10.sp,
                                maxLines=2,
                                overflow=TextOverflow.Ellipsis,
                                modifier=Modifier.padding(top=4.dp)
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick={
                            if(!loggedIn) onRequireAuth()
                            else scope.launch {
                                runCatching { social.toggleChannelFollow(channel.id) }
                                    .onSuccess { followed[channel.id]=it }
                            }
                        },
                        shape=RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            if(followed[channel.id]==true) "دنبال‌شده" else "دنبال",
                            fontSize=10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClubSectionHeader(
    title:String,
    subtitle:String,
    action:String?=null,
    onAction:(()->Unit)?=null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,top=18.dp,bottom=9.dp),
        verticalAlignment=Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=16.sp,fontWeight=FontWeight.Black)
            Text(subtitle,color=FqMuted,fontSize=10.sp,modifier=Modifier.padding(top=2.dp))
        }
        if(action!=null && onAction!=null) {
            TextButton(onClick=onAction) {
                Text(action,color=FqGold,fontSize=10.sp)
            }
        }
    }
}
