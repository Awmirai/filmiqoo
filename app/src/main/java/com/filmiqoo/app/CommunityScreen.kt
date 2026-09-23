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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun CommunityScreen(
    social: SocialRepository,
    loggedIn: Boolean,
    onOpenRoom: (SocialRoom) -> Unit,
    onCreator: (Creator) -> Unit,
    onRequireAuth: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feed by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    var stories by remember { mutableStateOf<List<SocialStory>>(emptyList()) }
    var channels by remember { mutableStateOf<List<SocialChannel>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<SocialRoom>>(emptyList()) }
    var commentsFor by remember { mutableStateOf<SocialPost?>(null) }
    var activeStory by remember { mutableStateOf<SocialStory?>(null) }

    LaunchedEffect(tab,refresh) {
        loading=true
        error=null
        runCatching {
            when(tab) {
                0 -> feed=social.feed()
                1 -> stories=social.stories()
                2 -> channels=social.channels()
                else -> rooms=social.rooms()
            }
        }.onFailure { error=it.message ?: "خطا در دریافت Community" }
        loading=false
    }

    Column(Modifier.fillMaxSize()) {
        BrandTopBar()
        Row(
            Modifier.fillMaxWidth().padding(horizontal=16.dp,bottom=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Community",fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("آدم‌ها، داستان‌ها و بحث‌های سینمایی",color=FqMuted,fontSize=10.sp)
            }
            IconButton(onClick={refresh++}) { Icon(Icons.Default.Refresh,null) }
        }

        ScrollableTabRow(
            selectedTabIndex=tab,
            containerColor=FqBg,
            contentColor=FqGold,
            edgePadding=12.dp
        ) {
            listOf("فید","استوری","کانال‌ها","روم‌ها").forEachIndexed { i,label ->
                Tab(selected=tab==i,onClick={tab=i},text={Text(label)})
            }
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Surface(
                color=FqDanger.copy(alpha=.12f),
                modifier=Modifier.fillMaxWidth().padding(12.dp),
                shape=RoundedCornerShape(12.dp)
            ) {
                Text(it,color=FqDanger,fontSize=10.sp,modifier=Modifier.padding(10.dp))
            }
        }

        when(tab) {
            0 -> {
                if(feed.isEmpty() && !loading) {
                    EmptyCommunityState(Icons.Default.DynamicFeed,"فید هنوز خالیه","اولین پست Community رو از دکمه + بساز.")
                } else {
                    LazyColumn(
                        contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)
                    ) {
                        items(feed,key={it.id}) { post ->
                            SocialPostCard(
                                post=post,
                                onLike={
                                    if(!loggedIn) {
                                        onRequireAuth()
                                    } else {
                                        scope.launch {
                                            runCatching { social.togglePostLike(post.id) }
                                                .onSuccess { liked ->
                                                    feed=feed.map {
                                                        if(it.id==post.id) it.copy(
                                                            likes=(it.likes + if(liked)1 else -1).coerceAtLeast(0)
                                                        ) else it
                                                    }
                                                }
                                        }
                                    }
                                },
                                onComments={commentsFor=post},
                                onCreator={
                                    onCreator(
                                        Creator(
                                            post.author.displayName,
                                            "@"+post.author.username,
                                            "",
                                            "عضو Community Filmiqoo",
                                            post.author.verified
                                        )
                                    )
                                }
                            )
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
            1 -> {
                if(stories.isEmpty() && !loading) {
                    EmptyCommunityState(Icons.Default.AutoStories,"استوری فعالی نیست","استوری‌های Filmiqoo بعد از ۲۴ ساعت منقضی می‌شن.")
                } else {
                    LazyColumn(contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        item {
                            LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                items(stories,key={it.id}) { story ->
                                    Column(
                                        horizontalAlignment=Alignment.CenterHorizontally,
                                        modifier=Modifier.width(82.dp).clickable {
                                            activeStory=story
                                            if(loggedIn) scope.launch { runCatching { social.markStoryViewed(story.id) } }
                                        }
                                    ) {
                                        Box(
                                            Modifier.size(70.dp).background(FqGold,CircleShape).padding(2.dp)
                                        ) {
                                            RemoteImage(
                                                story.author.avatarUrl.ifBlank {
                                                    story.thumbnailUrl.ifBlank { story.media?.posterUrl.orEmpty() }
                                                }.takeIf(String::isNotBlank),
                                                Modifier.fillMaxSize().clip(CircleShape),
                                                ContentScale.Crop
                                            )
                                        }
                                        Text(
                                            story.author.displayName,
                                            fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
                                            modifier=Modifier.padding(top=5.dp)
                                        )
                                    }
                                }
                            }
                        }
                        items(stories,key={it.id}) { story ->
                            Surface(
                                color=FqSurface,
                                shape=RoundedCornerShape(18.dp),
                                modifier=Modifier.fillMaxWidth().clickable { activeStory=story }
                            ) {
                                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    RemoteImage(
                                        story.thumbnailUrl.ifBlank { story.mediaUrl }.takeIf(String::isNotBlank),
                                        Modifier.size(86.dp).clip(RoundedCornerShape(14.dp)),
                                        ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(story.author.displayName,fontSize=13.sp)
                                        Text(story.caption.ifBlank { "Story" },fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                                        Text(story.views.toString()+" بازدید",color=FqMuted,fontSize=8.sp,modifier=Modifier.padding(top=5.dp))
                                    }
                                    if(story.spoiler) Icon(Icons.Default.Warning,null,tint=FqDanger)
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if(channels.isEmpty() && !loading) {
                    EmptyCommunityState(Icons.Default.Campaign,"کانالی ساخته نشده","Creatorها و رسانه‌ها اینجا کانال می‌سازن.")
                } else {
                    LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        items(channels,key={it.id}) { channel ->
                            var following by remember(channel.id) { mutableStateOf(false) }
                            Surface(
                                color=FqSurface,
                                shape=RoundedCornerShape(18.dp),
                                modifier=Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(13.dp).clickable {
                                        onCreator(Creator(channel.name,"@"+channel.slug,channel.followers.toString(),channel.bio,channel.verified))
                                    },
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    RemoteImage(
                                        channel.avatarUrl.takeIf(String::isNotBlank),
                                        Modifier.size(54.dp).clip(CircleShape)
                                    )
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            Text(channel.name,fontSize=14.sp)
                                            if(channel.verified) {
                                                Spacer(Modifier.width(4.dp))
                                                Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(15.dp))
                                            }
                                        }
                                        Text("@"+channel.slug,color=FqMuted,fontSize=9.sp)
                                        Text(channel.bio,color=Color.White.copy(alpha=.78f),fontSize=9.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                                    }
                                    FilledTonalButton(
                                        onClick={
                                            if(!loggedIn) onRequireAuth()
                                            else scope.launch {
                                                runCatching { social.toggleChannelFollow(channel.id) }
                                                    .onSuccess { following=it }
                                            }
                                        },
                                        contentPadding=PaddingValues(horizontal=10.dp,vertical=4.dp)
                                    ) {
                                        Text(if(following)"دنبال‌شده" else "دنبال",fontSize=9.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                if(rooms.isEmpty() && !loading) {
                    EmptyCommunityState(Icons.Default.Forum,"رومی موجود نیست","با ورود فیلم‌ها و سریال‌ها، روم رسمی آن‌ها خودکار ساخته می‌شود.")
                } else {
                    LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        items(rooms,key={it.id}) { room ->
                            Surface(
                                color=FqSurface,
                                shape=RoundedCornerShape(18.dp),
                                modifier=Modifier.fillMaxWidth().clickable { onOpenRoom(room) }
                            ) {
                                Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
                                    RemoteImage(
                                        room.media?.posterUrl?.takeIf(String::isNotBlank),
                                        Modifier.size(54.dp).clip(RoundedCornerShape(13.dp))
                                    )
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(room.name,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(room.topic,color=FqMuted,fontSize=9.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                                        Row(Modifier.padding(top=5.dp),verticalAlignment=Alignment.CenterVertically) {
                                            Icon(Icons.Default.Groups,null,tint=FqGold,modifier=Modifier.size(12.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(room.members.toString()+" عضو",fontSize=8.sp,color=FqMuted)
                                        }
                                    }
                                    Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    commentsFor?.let { post ->
        PostCommentsSheet(
            post=post,
            social=social,
            loggedIn=loggedIn,
            onRequireAuth=onRequireAuth,
            onDismiss={commentsFor=null}
        )
    }

    activeStory?.let { story ->
        AlertDialog(
            onDismissRequest={activeStory=null},
            confirmButton={TextButton(onClick={activeStory=null}){Text("بستن")}},
            title={Text(story.author.displayName)},
            text={
                Column {
                    if(story.mediaUrl.isNotBlank() || story.thumbnailUrl.isNotBlank()) {
                        RemoteImage(
                            story.mediaUrl.ifBlank { story.thumbnailUrl },
                            Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)),
                            ContentScale.Crop
                        )
                    }
                    Text(story.caption,modifier=Modifier.padding(top=10.dp))
                    story.media?.title?.let {
                        AssistChip(onClick={},label={Text("🎬 "+it)})
                    }
                }
            }
        )
    }
}

@Composable
private fun SocialPostCard(
    post: SocialPost,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onCreator: () -> Unit
) {
    var revealed by remember(post.id) { mutableStateOf(!post.spoiler) }
    Surface(color=FqSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.clickable { onCreator() }) {
                RemoteImage(post.author.avatarUrl.takeIf(String::isNotBlank),Modifier.size(42.dp).clip(CircleShape))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(post.author.displayName,fontSize=12.sp)
                        if(post.author.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(14.dp))
                        }
                    }
                    Text("@"+post.author.username,color=FqMuted,fontSize=8.sp)
                }
                Surface(color=FqSurface2,shape=RoundedCornerShape(9.dp)) {
                    Text(
                        when(post.type){"review"->"Review";"poll"->"Poll";"announcement"->"خبر";else->"Post"},
                        color=FqGold,fontSize=8.sp,modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                    )
                }
            }

            if(post.spoiler && !revealed) {
                Surface(
                    color=FqDanger.copy(alpha=.11f),
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp).clickable { revealed=true }
                ) {
                    Column(Modifier.padding(15.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(Icons.Default.VisibilityOff,null,tint=FqDanger)
                        Text("Spoiler Shield",color=FqDanger,fontSize=12.sp,modifier=Modifier.padding(top=5.dp))
                        Text("برای نمایش محتوا لمس کن",color=FqMuted,fontSize=9.sp)
                    }
                }
            } else {
                Text(post.body,fontSize=12.sp,lineHeight=21.sp,modifier=Modifier.padding(top=12.dp))
            }

            post.media?.takeIf { !it.title.isNullOrBlank() }?.let { media ->
                Surface(color=FqSurface2,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
                    Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(media.posterUrl,Modifier.size(42.dp,58.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(media.title.orEmpty(),fontSize=10.sp)
                            Text("متصل به Catalog Filmiqoo",color=FqMuted,fontSize=8.sp)
                        }
                        Icon(Icons.Default.Movie,null,tint=FqGold)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top=10.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onLike,contentPadding=PaddingValues(horizontal=8.dp)) {
                    Icon(Icons.Default.FavoriteBorder,null,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(post.likes.toString(),fontSize=9.sp)
                }
                TextButton(onClick=onComments,contentPadding=PaddingValues(horizontal=8.dp)) {
                    Icon(Icons.Default.ChatBubbleOutline,null,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(post.comments.toString(),fontSize=9.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={}) { Icon(Icons.Default.BookmarkBorder,null) }
                IconButton(onClick={}) { Icon(Icons.Default.Share,null) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostCommentsSheet(
    post: SocialPost,
    social: SocialRepository,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<SocialComment>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            loading=true
            comments=runCatching { social.comments(post.id) }.getOrDefault(emptyList())
            loading=false
        }
    }

    LaunchedEffect(post.id) { reload() }

    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=FqSurface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.78f).padding(horizontal=14.dp)) {
            Text("نظرها",fontSize=20.sp,fontWeight=FontWeight.Bold)
            Text(post.author.displayName+" • "+post.body.take(70),color=FqMuted,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis)

            if(loading) LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth().padding(top=8.dp))

            LazyColumn(
                Modifier.weight(1f).padding(top=8.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                items(comments,key={it.id}) { c ->
                    var reveal by remember(c.id) { mutableStateOf(!c.spoiler) }
                    Surface(color=FqSurface2,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(c.author.displayName,color=FqGold,fontSize=9.sp)
                            if(c.spoiler && !reveal) {
                                Text(
                                    "⚠ اسپویلر • برای نمایش لمس کن",
                                    color=FqDanger,fontSize=9.sp,
                                    modifier=Modifier.padding(top=5.dp).clickable { reveal=true }
                                )
                            } else {
                                Text(c.body,fontSize=10.sp,modifier=Modifier.padding(top=4.dp))
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(vertical=8.dp)) {
                FilterChip(selected=spoiler,onClick={spoiler=!spoiler},label={Text("اسپویلر",fontSize=8.sp)})
                Spacer(Modifier.width(7.dp))
                OutlinedTextField(
                    value=text,onValueChange={text=it},
                    placeholder={Text("نظر بنویس...")},
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.weight(1f),
                    maxLines=3
                )
                IconButton(onClick={
                    if(!loggedIn) {
                        onRequireAuth()
                    } else if(text.isNotBlank()) {
                        scope.launch {
                            runCatching { social.addComment(post.id,text.trim(),spoiler) }
                                .onSuccess { text="";spoiler=false;reload() }
                        }
                    }
                }) {
                    Icon(Icons.Default.Send,null,tint=if(text.isBlank())FqMuted else FqGold)
                }
            }
        }
    }
}

@Composable
private fun EmptyCommunityState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(28.dp)) {
            Icon(icon,null,tint=FqMuted,modifier=Modifier.size(54.dp))
            Text(title,fontSize=17.sp,modifier=Modifier.padding(top=12.dp))
            Text(body,color=FqMuted,fontSize=10.sp,modifier=Modifier.padding(top=6.dp))
        }
    }
}
