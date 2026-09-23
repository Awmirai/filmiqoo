package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private sealed interface CreatorEntityState {
    data object Loading: CreatorEntityState
    data class User(
        val profile: PublicCreatorProfile,
        val posts: List<SocialPost>,
        val reels: List<ReelFeedItem>
    ): CreatorEntityState
    data class Channel(
        val profile: PublicChannelProfile,
        val posts: List<SocialPost>,
        val reels: List<ReelFeedItem>,
        val stories: List<SocialStory>,
        val members: List<ChannelMember>,
        val rooms: List<SocialRoom>
    ): CreatorEntityState
    data class Error(val message: String): CreatorEntityState
}

@Composable
fun PremiumCreatorChannelScreen(
    creator: Creator,
    backend: BackendRepository,
    social: SocialRepository,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onOpenRoom: (SocialRoom) -> Unit,
    onStory: (List<SocialStory>, Int) -> Unit,
    onOpenReels: () -> Unit,
    onRequireAuth: () -> Unit
) {
    val repo=remember { CreatorChannelRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember(creator.id,creator.entityType) {
        mutableStateOf<CreatorEntityState>(CreatorEntityState.Loading)
    }
    var followed by remember(creator.id) { mutableStateOf(false) }
    var followBusy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(creator.id,creator.entityType,refresh) {
        if(creator.id.isBlank()) {
            state=CreatorEntityState.Error("این پروفایل هنوز به حساب واقعی Filmiqoo متصل نشده.")
            return@LaunchedEffect
        }
        state=CreatorEntityState.Loading
        state=runCatching {
            if(creator.entityType=="channel") {
                CreatorEntityState.Channel(
                    profile=repo.channelProfile(creator.id),
                    posts=repo.channelPosts(creator.id),
                    reels=repo.channelReels(creator.id),
                    stories=repo.channelStories(creator.id),
                    members=repo.channelMembers(creator.id),
                    rooms=repo.channelRooms(creator.id)
                )
            } else {
                CreatorEntityState.User(
                    profile=repo.userProfile(creator.id),
                    posts=repo.userPosts(creator.id),
                    reels=repo.userReels(creator.id)
                )
            }
        }.getOrElse {
            CreatorEntityState.Error(it.message ?: "خطا در دریافت پروفایل")
        }
    }

    when(val s=state) {
        CreatorEntityState.Loading -> LoadingPage("در حال آماده‌سازی پروفایل...")
        is CreatorEntityState.Error -> {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                }
                PremiumEmptyState(
                    icon=Icons.Default.PersonOff,
                    title="پروفایل در دسترس نیست",
                    body=s.message,
                    action="تلاش دوباره",
                    onAction={refresh++}
                )
            }
        }
        is CreatorEntityState.User -> {
            val p=s.profile
            CreatorEntityScaffold(
                name=p.displayName,
                handle="@"+p.username,
                bio=p.bio,
                avatar=p.avatarUrl,
                cover=p.coverUrl,
                verified=p.verified,
                followers=p.followers,
                following=p.following,
                postsCount=p.posts,
                reelsCount=p.reels,
                isChannel=false,
                followed=followed,
                followBusy=followBusy,
                tab=tab,
                tabs=listOf("Reels","پست‌ها","درباره"),
                onBack=onBack,
                onRefresh={refresh++},
                onTab={tab=it},
                onFollow={
                    if(!backend.session.isLoggedIn) {
                        onRequireAuth()
                    } else if(!followBusy) {
                        followBusy=true
                        scope.launch {
                            runCatching { social.toggleUserFollow(p.id) }
                                .onSuccess { followed=it }
                            followBusy=false
                        }
                    }
                }
            ) {
                when(tab) {
                    0 -> CreatorReelsGrid(s.reels,onOpenReels,onMedia)
                    1 -> CreatorPostsList(s.posts)
                    else -> CreatorAbout(
                        bio=p.bio,
                        verified=p.verified,
                        privacy=if(p.privateAccount)"خصوصی" else "عمومی",
                        members=emptyList(),
                        rooms=emptyList(),
                        onOpenRoom=onOpenRoom
                    )
                }
            }
        }
        is CreatorEntityState.Channel -> {
            val p=s.profile
            CreatorEntityScaffold(
                name=p.name,
                handle="@"+p.slug,
                bio=p.bio,
                avatar=p.avatarUrl,
                cover=p.coverUrl,
                verified=p.verified,
                followers=p.followers,
                following=0,
                postsCount=p.posts,
                reelsCount=p.reels,
                isChannel=true,
                followed=followed,
                followBusy=followBusy,
                tab=tab,
                tabs=listOf("Reels","پست‌ها","Stories","اعضا","چت"),
                onBack=onBack,
                onRefresh={refresh++},
                onTab={tab=it},
                onFollow={
                    if(!backend.session.isLoggedIn) {
                        onRequireAuth()
                    } else if(!followBusy) {
                        followBusy=true
                        scope.launch {
                            runCatching { social.toggleChannelFollow(p.id) }
                                .onSuccess { followed=it }
                            followBusy=false
                        }
                    }
                }
            ) {
                when(tab) {
                    0 -> CreatorReelsGrid(s.reels,onOpenReels,onMedia)
                    1 -> CreatorPostsList(s.posts)
                    2 -> ChannelStoriesGrid(s.stories,onStory)
                    3 -> ChannelMembersList(s.members)
                    else -> ChannelRoomsList(s.rooms,onOpenRoom)
                }
            }
        }
    }
}

@Composable
private fun CreatorEntityScaffold(
    name: String,
    handle: String,
    bio: String,
    avatar: String,
    cover: String,
    verified: Boolean,
    followers: Long,
    following: Long,
    postsCount: Long,
    reelsCount: Long,
    isChannel: Boolean,
    followed: Boolean,
    followBusy: Boolean,
    tab: Int,
    tabs: List<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTab: (Int) -> Unit,
    onFollow: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(Modifier.fillMaxWidth().height(330.dp)) {
            if(cover.isNotBlank()) {
                RemoteImage(cover,Modifier.fillMaxSize(),ContentScale.Crop)
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(Color(0xFF171D2C),Color(0xFF3B2A08),FqBg)
                        )
                    )
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha=.25f),Color.Transparent,FqBg)
                    )
                )
            )

            Row(
                Modifier.fillMaxWidth().padding(9.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(
                    onClick=onBack,
                    modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.4f))
                ) { Icon(Icons.Default.ArrowBack,null) }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick=onRefresh,
                    modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.4f))
                ) { Icon(Icons.Default.Refresh,null) }
                IconButton(
                    onClick={},
                    modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.4f))
                ) { Icon(Icons.Default.MoreVert,null) }
            }

            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal=18.dp,bottom=10.dp)
            ) {
                Row(verticalAlignment=Alignment.Bottom) {
                    Box(
                        Modifier.size(92.dp).background(FqGold,CircleShape).padding(3.dp)
                    ) {
                        RemoteImage(
                            avatar.takeIf(String::isNotBlank),
                            Modifier.fillMaxSize().clip(CircleShape),
                            ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(name,fontSize=24.sp,fontWeight=FontWeight.Black)
                            if(verified) {
                                Spacer(Modifier.width(5.dp))
                                Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(18.dp))
                            }
                        }
                        Text(handle,color=FqMuted,fontSize=9.sp)
                        if(bio.isNotBlank()) {
                            Text(
                                bio,
                                fontSize=9.sp,
                                lineHeight=15.sp,
                                maxLines=2,
                                overflow=TextOverflow.Ellipsis,
                                modifier=Modifier.padding(top=5.dp)
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=13.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    CreatorCountCard(compactCreatorCount(followers),"دنبال‌کننده",Modifier.weight(1f))
                    if(!isChannel) {
                        CreatorCountCard(compactCreatorCount(following),"دنبال‌شده",Modifier.weight(1f))
                    }
                    CreatorCountCard(compactCreatorCount(postsCount),"پست",Modifier.weight(1f))
                    CreatorCountCard(compactCreatorCount(reelsCount),"Reel",Modifier.weight(1f))
                }

                Button(
                    onClick=onFollow,
                    enabled=!followBusy,
                    colors=ButtonDefaults.buttonColors(
                        containerColor=if(followed)FqSurface2 else FqGold,
                        contentColor=if(followed)Color.White else Color.Black
                    ),
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=11.dp)
                ) {
                    if(followBusy) {
                        CircularProgressIndicator(strokeWidth=2.dp,modifier=Modifier.size(18.dp))
                    } else {
                        Icon(
                            if(followed)Icons.Default.Check else Icons.Default.PersonAdd,
                            null,
                            modifier=Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if(followed)"دنبال می‌کنی" else "دنبال کردن")
                }
            }
        }

        ScrollableTabRow(
            selectedTabIndex=tab,
            containerColor=FqBg,
            contentColor=FqGold,
            edgePadding=10.dp,
            divider={}
        ) {
            tabs.forEachIndexed { i,label ->
                Tab(
                    selected=tab==i,
                    onClick={onTab(i)},
                    text={Text(label,fontSize=9.sp)}
                )
            }
        }

        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun CreatorCountCard(value:String,label:String,modifier:Modifier=Modifier) {
    Surface(color=Color.Black.copy(alpha=.36f),shape=RoundedCornerShape(14.dp),modifier=modifier) {
        Column(Modifier.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(value,fontSize=13.sp,fontWeight=FontWeight.Black)
            Text(label,color=FqMuted,fontSize=7.sp)
        }
    }
}

@Composable
private fun CreatorReelsGrid(
    reels: List<ReelFeedItem>,
    onOpenReels: () -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    if(reels.isEmpty()) {
        PremiumEmptyState(Icons.Default.VideoLibrary,"هنوز Reels نداره","Reelهای منتشرشده اینجا نمایش داده می‌شن.")
        return
    }

    LazyVerticalGrid(
        columns=GridCells.Fixed(3),
        contentPadding=PaddingValues(4.dp),
        horizontalArrangement=Arrangement.spacedBy(3.dp),
        verticalArrangement=Arrangement.spacedBy(3.dp)
    ) {
        items(reels,key={it.id}) { reel ->
            Box(
                Modifier.aspectRatio(.68f).clickable { onOpenReels() }
            ) {
                RemoteImage(reel.coverUrl.takeIf(String::isNotBlank),Modifier.fillMaxSize(),ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.72f)))
                    )
                )
                Row(
                    Modifier.align(Alignment.BottomStart).padding(7.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow,null,tint=Color.White,modifier=Modifier.size(14.dp))
                    Text(compactCreatorCount(reel.views),color=Color.White,fontSize=7.sp)
                }
                reel.media?.asMediaItem()?.let { media ->
                    IconButton(
                        onClick={onMedia(media)},
                        modifier=Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Default.Movie,null,tint=FqGold,modifier=Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorPostsList(posts: List<SocialPost>) {
    if(posts.isEmpty()) {
        PremiumEmptyState(Icons.Default.DynamicFeed,"هنوز پستی نیست","پست‌های منتشرشده اینجا نمایش داده می‌شن.")
        return
    }

    LazyColumn(
        contentPadding=PaddingValues(12.dp),
        verticalArrangement=Arrangement.spacedBy(9.dp)
    ) {
        items(posts,key={it.id}) { post ->
            var reveal by remember(post.id) { mutableStateOf(!post.spoiler) }
            Surface(color=FqSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(
                            post.author.avatarUrl.takeIf(String::isNotBlank),
                            Modifier.size(40.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(post.author.displayName,fontSize=11.sp,fontWeight=FontWeight.Bold)
                            Text("@"+post.author.username,color=FqMuted,fontSize=7.sp)
                        }
                        Surface(color=FqSurface2,shape=RoundedCornerShape(8.dp)) {
                            Text(post.type,fontSize=7.sp,color=FqGold,modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp))
                        }
                    }

                    if(post.spoiler && !reveal) {
                        Surface(
                            color=FqDanger.copy(alpha=.12f),
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=10.dp).clickable { reveal=true }
                        ) {
                            Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.VisibilityOff,null,tint=FqDanger)
                                Spacer(Modifier.width(7.dp))
                                Text("Spoiler Shield • برای نمایش لمس کن",fontSize=9.sp)
                            }
                        }
                    } else {
                        Text(post.body,fontSize=11.sp,lineHeight=19.sp,modifier=Modifier.padding(top=10.dp))
                    }

                    Row(Modifier.fillMaxWidth().padding(top=10.dp)) {
                        Text("♥ "+compactCreatorCount(post.likes),color=FqMuted,fontSize=8.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("💬 "+compactCreatorCount(post.comments),color=FqMuted,fontSize=8.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("🔖 "+compactCreatorCount(post.saves),color=FqMuted,fontSize=8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelStoriesGrid(
    stories: List<SocialStory>,
    onStory: (List<SocialStory>,Int) -> Unit
) {
    if(stories.isEmpty()) {
        PremiumEmptyState(Icons.Default.AutoStories,"Story فعالی نیست","Storyهای کانال بعد از ۲۴ ساعت از Feed خارج می‌شن.")
        return
    }

    LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        items(stories.size,key={stories[it].id}) { index ->
            val story=stories[index]
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth().clickable { onStory(stories,index) }
            ) {
                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    RemoteImage(
                        story.thumbnailUrl.ifBlank { story.mediaUrl }.takeIf(String::isNotBlank),
                        Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)),
                        ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(story.caption.ifBlank{"Story"},fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(
                            compactCreatorCount(story.views)+" بازدید",
                            color=FqMuted,
                            fontSize=7.sp,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }
                    if(story.spoiler) Icon(Icons.Default.VisibilityOff,null,tint=FqDanger)
                    Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                }
            }
        }
    }
}

@Composable
private fun ChannelMembersList(members: List<ChannelMember>) {
    if(members.isEmpty()) {
        PremiumEmptyState(Icons.Default.Groups,"عضوی نمایش داده نمی‌شه","اعضای مدیریتی و تیم کانال اینجا دیده می‌شن.")
        return
    }

    LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(members,key={it.id}) { member ->
            Surface(color=FqSurface,shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) {
                    RemoteImage(member.avatarUrl.takeIf(String::isNotBlank),Modifier.size(48.dp).clip(CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(member.displayName,fontSize=11.sp,fontWeight=FontWeight.Bold)
                            if(member.verified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(13.dp))
                            }
                        }
                        Text("@"+member.username,color=FqMuted,fontSize=7.sp)
                    }
                    Surface(
                        color=if(member.role=="owner")FqGold.copy(alpha=.15f) else FqSurface2,
                        shape=RoundedCornerShape(9.dp)
                    ) {
                        Text(
                            when(member.role) {
                                "owner" -> "Owner"
                                "admin" -> "Admin"
                                "moderator" -> "Moderator"
                                else -> "Member"
                            },
                            color=if(member.role=="owner")FqGold else Color.White,
                            fontSize=7.sp,
                            modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRoomsList(
    rooms: List<SocialRoom>,
    onOpenRoom: (SocialRoom) -> Unit
) {
    if(rooms.isEmpty()) {
        PremiumEmptyState(Icons.Default.Forum,"چت عمومی فعالی نیست","وقتی کانال Room عمومی بسازه، اینجا نمایش داده می‌شه.")
        return
    }

    LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(rooms,key={it.id}) { room ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth().clickable { onOpenRoom(room) }
            ) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(FqGold.copy(alpha=.12f)),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.Forum,null,tint=FqGold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(room.name,fontSize=11.sp,fontWeight=FontWeight.Bold)
                        Text(room.topic,color=FqMuted,fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(compactCreatorCount(room.members)+" عضو",color=FqGold,fontSize=7.sp,modifier=Modifier.padding(top=3.dp))
                    }
                    Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                }
            }
        }
    }
}

@Composable
private fun CreatorAbout(
    bio: String,
    verified: Boolean,
    privacy: String,
    members: List<ChannelMember>,
    rooms: List<SocialRoom>,
    onOpenRoom: (SocialRoom) -> Unit
) {
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Surface(color=FqSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    Text("درباره",fontSize=15.sp,fontWeight=FontWeight.Bold)
                    Text(bio.ifBlank{"Bio ثبت نشده."},color=Color.White.copy(alpha=.78f),fontSize=9.sp,lineHeight=16.sp,modifier=Modifier.padding(top=7.dp))
                    HorizontalDivider(color=FqSurface3,modifier=Modifier.padding(vertical=12.dp))
                    Row {
                        MetricPill(Icons.Default.Verified,if(verified)"تأییدشده" else "عادی")
                        Spacer(Modifier.width(7.dp))
                        MetricPill(Icons.Default.Public,privacy)
                    }
                }
            }
        }
    }
}

@Composable
fun CreatorStudioScreen(
    backend: BackendRepository,
    onBack: () -> Unit
) {
    val repo=remember { CreatorChannelRepository(backend) }
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<CreatorStudioAnalytics?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        error=null
        data=runCatching { repo.creatorStudio() }
            .onFailure { error=it.message }
            .getOrNull()
    }

    if(data==null && error==null) {
        LoadingPage("در حال جمع‌کردن Analytics...")
        return
    }

    if(data==null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            }
            PremiumEmptyState(Icons.Default.Analytics,"Creator Studio در دسترس نیست",error ?: "خطا", "تلاش دوباره"){refresh++}
        }
        return
    }

    val d=data!!
    LazyColumn(Modifier.fillMaxSize().background(FqBg),contentPadding=PaddingValues(bottom=28.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().height(230.dp).background(
                    Brush.linearGradient(listOf(Color(0xFF111827),Color(0xFF382908),FqBg))
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp)
                ) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(d.avatarUrl.takeIf(String::isNotBlank),Modifier.size(62.dp).clip(CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Creator Studio",color=FqGold,fontSize=11.sp,fontWeight=FontWeight.Bold)
                            Text(d.displayName,fontSize=22.sp,fontWeight=FontWeight.Black)
                            Text("@"+d.username,color=FqMuted,fontSize=8.sp)
                        }
                    }
                }
            }
        }

        item {
            PremiumSectionHeader("نمای کلی","آمار واقعی حساب و محتوای منتشرشده",Icons.Default.Analytics)
        }

        item {
            Column(Modifier.padding(horizontal=14.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    StudioMetric("بازدید Reels",compactCreatorCount(d.reelViews),Icons.Default.Visibility,Modifier.weight(1f))
                    StudioMetric("Story Views",compactCreatorCount(d.storyViews),Icons.Default.AutoStories,Modifier.weight(1f))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)) {
                    StudioMetric("دنبال‌کننده",compactCreatorCount(d.followers),Icons.Default.Groups,Modifier.weight(1f))
                    StudioMetric("Channel Followers",compactCreatorCount(d.channelFollowers),Icons.Default.Campaign,Modifier.weight(1f))
                }
            }
        }

        item {
            PremiumSectionHeader("Engagement","تعامل روی Reels",Icons.Default.Bolt)
        }

        item {
            Surface(color=FqSurface,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    StudioStatRow("Like",d.reelLikes,Icons.Default.Favorite)
                    StudioStatRow("Comment",d.reelComments,Icons.Default.ChatBubble)
                    StudioStatRow("Save",d.reelSaves,Icons.Default.Bookmark)
                    StudioStatRow("Share",d.reelShares,Icons.Default.Share)
                }
            }
        }

        item {
            PremiumSectionHeader("Content","موجودی Creator",Icons.Default.VideoLibrary)
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=14.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                PremiumStat(d.posts.toString(),"پست",Modifier.weight(1f))
                PremiumStat(d.reels.toString(),"Reel",Modifier.weight(1f))
                PremiumStat(d.channels.toString(),"کانال",Modifier.weight(1f))
            }
        }

        item {
            Surface(
                color=FqGold.copy(alpha=.1f),
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.fillMaxWidth().padding(14.dp)
            ) {
                Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Lightbulb,null,tint=FqGold)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("Analytics V2",fontSize=11.sp,fontWeight=FontWeight.Bold)
                        Text(
                            "مرحله بعد Retention، Completion Rate، Follower Conversion و نمودار روزانه به همین Studio اضافه می‌شه.",
                            color=FqMuted,
                            fontSize=8.sp,
                            lineHeight=14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioMetric(
    label:String,
    value:String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier:Modifier=Modifier
) {
    Surface(color=FqSurface,shape=RoundedCornerShape(18.dp),modifier=modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon,null,tint=FqGold)
            Text(value,fontSize=22.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=8.dp))
            Text(label,color=FqMuted,fontSize=8.sp)
        }
    }
}

@Composable
private fun StudioStatRow(
    label:String,
    value:Long,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=FqGold,modifier=Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(label,fontSize=10.sp,modifier=Modifier.weight(1f))
        Text(compactCreatorCount(value),fontSize=12.sp,fontWeight=FontWeight.Bold)
    }
}

private fun compactCreatorCount(value: Long): String = when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
