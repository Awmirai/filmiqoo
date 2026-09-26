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
import androidx.compose.ui.platform.LocalContext
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
        val reels: List<ReelFeedItem>,
        val relationship: UserRelationship?
    ): CreatorEntityState
    data class Channel(
        val profile: PublicChannelProfile,
        val posts: List<SocialPost>,
        val reels: List<ReelFeedItem>,
        val stories: List<SocialStory>,
        val members: List<ChannelMember>,
        val rooms: List<SocialRoom>,
        val management: ChannelManageOverview?
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
    onOpenClip: (String) -> Unit,
    onStartDm: (String, String) -> Unit,
    onManageChannel: (String, String) -> Unit,
    onReputation: (String) -> Unit,
    onRequireAuth: () -> Unit
) {
    val context=LocalContext.current
    val repo=remember { CreatorChannelRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember(creator.id,creator.entityType) {
        mutableStateOf<CreatorEntityState>(CreatorEntityState.Loading)
    }
    var followed by remember(creator.id) { mutableStateOf(false) }
    var followPending by remember(creator.id) { mutableStateOf(false) }
    var followBusy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var safetyTargetType by remember { mutableStateOf<String?>(null) }
    var safetyTargetId by remember { mutableStateOf<String?>(null) }
    var safetyUserId by remember { mutableStateOf<String?>(null) }
    var safetyLabel by remember { mutableStateOf("") }

    BackHandler { onBack() }

    LaunchedEffect(creator.id,creator.entityType,refresh) {
        if(creator.id.isBlank()) {
            state=CreatorEntityState.Error("این پروفایل هنوز به حساب واقعی Filmiqoo متصل نشده.")
            return@LaunchedEffect
        }
        state=CreatorEntityState.Loading
        state=runCatching {
            if(creator.entityType=="channel") {
                val management=if(backend.session.isLoggedIn) {
                    runCatching { repo.manageOverview(creator.id) }.getOrNull()
                } else null
                CreatorEntityState.Channel(
                    profile=repo.channelProfile(creator.id),
                    posts=repo.channelPosts(creator.id),
                    reels=repo.channelReels(creator.id),
                    stories=repo.channelStories(creator.id),
                    members=repo.channelMembers(creator.id),
                    rooms=repo.channelRooms(creator.id),
                    management=management
                )
            } else {
                CreatorEntityState.User(
                    profile=repo.userProfile(creator.id),
                    posts=repo.userPosts(creator.id),
                    reels=repo.userReels(creator.id),
                    relationship=if(backend.session.isLoggedIn) {
                        runCatching { social.userRelationship(creator.id) }.getOrNull()
                    } else null
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
            LaunchedEffect(p.id,s.relationship) {
                followed=s.relationship?.following == true
                followPending=s.relationship?.pending == true
            }
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
                followPending=followPending,
                followBusy=followBusy,
                tab=tab,
                tabs=listOf("Clips","پست‌ها","درباره"),
                onBack=onBack,
                onRefresh={refresh++},
                onShare={
                    FilmiqooDeepLinks.share(
                        context,
                        p.displayName+" • @"+p.username,
                        FilmiqooDeepLinks.creator(p.id)
                    )
                },
                onTab={tab=it},
                onMore={
                    if(!backend.session.isLoggedIn) {
                        onRequireAuth()
                    } else {
                        safetyTargetType="user"
                        safetyTargetId=p.id
                        safetyUserId=p.id
                        safetyLabel=p.displayName
                    }
                },
                onMessage={onStartDm(p.id,p.displayName)},
                onReputation={onReputation(p.id)},
                onFollow={
                    if(!backend.session.isLoggedIn) {
                        onRequireAuth()
                    } else if(!followBusy) {
                        followBusy=true
                        scope.launch {
                            runCatching { social.toggleUserFollowState(p.id) }
                                .onSuccess {
                                    followed=it.following
                                    followPending=it.pending
                                }
                            followBusy=false
                        }
                    }
                }
            ) {
                when(tab) {
                    0 -> CreatorReelsGrid(s.reels,onOpenClip,onMedia)
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
                followPending=false,
                followBusy=followBusy,
                tab=tab,
                tabs=listOf("Clips","پست‌ها","Roomها","درباره"),
                onBack=onBack,
                onRefresh={refresh++},
                onShare={
                    FilmiqooDeepLinks.share(
                        context,
                        p.name+" • @"+p.slug,
                        FilmiqooDeepLinks.channel(p.id)
                    )
                },
                onTab={tab=it},
                onManage=s.management?.let { { onManageChannel(p.id,p.name) } },
                onMore={
                    if(!backend.session.isLoggedIn) {
                        onRequireAuth()
                    } else {
                        safetyTargetType="channel"
                        safetyTargetId=p.id
                        safetyUserId=null
                        safetyLabel=p.name
                    }
                },
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
                    0 -> CreatorReelsGrid(s.reels,onOpenClip,onMedia)
                    1 -> CreatorPostsList(s.posts)
                    2 -> ChannelRoomsList(s.rooms,onOpenRoom)
                    else -> CreatorAbout(
                        bio=p.bio,
                        verified=p.verified,
                        privacy=p.visibility,
                        members=s.members,
                        rooms=s.rooms,
                        onOpenRoom=onOpenRoom
                    )
                }
            }
        }
    }

    val targetType=safetyTargetType
    val targetId=safetyTargetId
    if(targetType!=null && targetId!=null) {
        SafetyActionSheet(
            backend=backend,
            targetType=targetType,
            targetId=targetId,
            targetLabel=safetyLabel,
            userTargetId=safetyUserId,
            onDismiss={
                safetyTargetType=null
                safetyTargetId=null
                safetyUserId=null
            },
            onChanged={refresh++}
        )
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
    followPending: Boolean,
    followBusy: Boolean,
    tab: Int,
    tabs: List<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onTab: (Int) -> Unit,
    onFollow: () -> Unit,
    onMore: () -> Unit,
    onManage: (() -> Unit)? = null,
    onMessage: (() -> Unit)? = null,
    onReputation: (() -> Unit)? = null,
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
                if(onManage!=null) {
                    IconButton(
                        onClick=onManage,
                        modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.4f))
                    ) { Icon(Icons.Default.AdminPanelSettings,null,tint=FqGold) }
                }
                if(onReputation!=null) {
                    IconButton(
                        onClick=onReputation,
                        modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.4f))
                    ) { Icon(Icons.Default.MilitaryTech,null,tint=FqGold) }
                }
                IconButton(
                    onClick=onShare,
                    modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.4f))
                ) { Icon(Icons.Default.Share,null) }
                IconButton(
                    onClick=onMore,
                    modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.4f))
                ) { Icon(Icons.Default.MoreVert,null) }
            }

            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start=18.dp,end=18.dp,bottom=10.dp)
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
                        Text(handle,color=FqMuted,fontSize=11.sp)
                        if(bio.isNotBlank()) {
                            Text(
                                bio,
                                fontSize=11.sp,
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
                    CreatorCountCard(compactCreatorCount(reelsCount),"Clip",Modifier.weight(1f))
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=11.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick=onFollow,
                        enabled=!followBusy,
                        colors=ButtonDefaults.buttonColors(
                            containerColor=when {
                                followed -> FqSurface2
                                followPending -> FqGold.copy(alpha=.2f)
                                else -> FqGold
                            },
                            contentColor=when {
                                followed -> Color.White
                                followPending -> FqGold
                                else -> Color.Black
                            }
                        ),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.weight(1f)
                    ) {
                        if(followBusy) {
                            CircularProgressIndicator(strokeWidth=2.dp,modifier=Modifier.size(18.dp))
                        } else {
                            Icon(
                                when {
                                    followed -> Icons.Default.Check
                                    followPending -> Icons.Default.Schedule
                                    else -> Icons.Default.PersonAdd
                                },
                                null,
                                modifier=Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                followed -> "دنبال می‌کنی"
                                followPending -> "درخواست ارسال شد"
                                else -> "دنبال کردن"
                            }
                        )
                    }

                    if(onMessage!=null) {
                        OutlinedButton(
                            onClick=onMessage,
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.weight(.72f)
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline,null,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("پیام")
                        }
                    }
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
                    text={Text(label,fontSize=11.sp)}
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
            Text(label,color=FqMuted,fontSize=11.sp)
        }
    }
}

@Composable
private fun CreatorReelsGrid(
    reels: List<ReelFeedItem>,
    onOpenClip: (String) -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    if(reels.isEmpty()) {
        PremiumEmptyState(Icons.Default.VideoLibrary,"هنوز Clip نداره","Clipهای منتشرشده اینجا نمایش داده می‌شن.")
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
                Modifier.aspectRatio(.68f).clickable { onOpenClip(reel.id) }
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
                    Text(compactCreatorCount(reel.views),color=Color.White,fontSize=11.sp)
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
                            Text("@"+post.author.username,color=FqMuted,fontSize=11.sp)
                        }
                        Surface(color=FqSurface2,shape=RoundedCornerShape(8.dp)) {
                            Text(post.type,fontSize=11.sp,color=FqGold,modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp))
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
                                Text("Spoiler Shield • برای نمایش لمس کن",fontSize=11.sp)
                            }
                        }
                    } else {
                        Text(post.body,fontSize=11.sp,lineHeight=19.sp,modifier=Modifier.padding(top=10.dp))
                    }

                    Row(Modifier.fillMaxWidth().padding(top=10.dp)) {
                        Text("♥ "+compactCreatorCount(post.likes),color=FqMuted,fontSize=11.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("💬 "+compactCreatorCount(post.comments),color=FqMuted,fontSize=11.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("🔖 "+compactCreatorCount(post.saves),color=FqMuted,fontSize=11.sp)
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
                        Text(story.caption.ifBlank{"Story"},fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(
                            compactCreatorCount(story.views)+" بازدید",
                            color=FqMuted,
                            fontSize=11.sp,
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
                        Text("@"+member.username,color=FqMuted,fontSize=11.sp)
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
                            fontSize=11.sp,
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
                        Text(room.topic,color=FqMuted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(compactCreatorCount(room.members)+" عضو",color=FqGold,fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
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
                    Text(bio.ifBlank{"Bio ثبت نشده."},color=Color.White.copy(alpha=.78f),fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=7.dp))
                    HorizontalDivider(color=FqSurface3,modifier=Modifier.padding(vertical=12.dp))
                    Row {
                        MetricPill(Icons.Default.Verified,if(verified)"تأییدشده" else "عادی")
                        Spacer(Modifier.width(7.dp))
                        MetricPill(Icons.Default.Public,privacy)
                    }
                    if(members.isNotEmpty() || rooms.isNotEmpty()) {
                        HorizontalDivider(color=FqSurface3,modifier=Modifier.padding(vertical=12.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(members.isNotEmpty()) {
                                MetricPill(Icons.Default.Groups,compactCreatorCount(members.size.toLong())+" تیم")
                            }
                            if(rooms.isNotEmpty()) {
                                MetricPill(Icons.Default.Forum,compactCreatorCount(rooms.size.toLong())+" Room")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreatorStudioScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onLive: () -> Unit
) {
    val repo=remember { CreatorChannelRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var analyticsDays by remember { mutableIntStateOf(30) }
    var data by remember { mutableStateOf<CreatorStudioAnalytics?>(null) }
    var analytics by remember { mutableStateOf<CreatorAnalyticsV3?>(null) }
    var scheduled by remember { mutableStateOf<List<ScheduledCreatorItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(refresh,analyticsDays) {
        error=null
        data=runCatching { repo.creatorStudio() }
            .onFailure { error=it.message }
            .getOrNull()
        analytics=runCatching { repo.creatorAnalytics(analyticsDays) }
            .onFailure { error=it.message }
            .getOrNull()
        scheduled=runCatching { repo.scheduledContent() }.getOrDefault(emptyList())
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
            PremiumEmptyState(
                Icons.Default.Analytics,
                "Creator Studio در دسترس نیست",
                error ?: "خطا",
                "تلاش دوباره"
            ){refresh++}
        }
        return
    }

    val d=data!!
    LazyColumn(
        Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=28.dp)
    ) {
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
                        RemoteImage(
                            d.avatarUrl.takeIf(String::isNotBlank),
                            Modifier.size(62.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Creator Studio",color=FqGold,fontSize=11.sp,fontWeight=FontWeight.Bold)
                            Text(d.displayName,fontSize=22.sp,fontWeight=FontWeight.Black)
                            Text("@"+d.username,color=FqMuted,fontSize=11.sp)
                        }
                    }
                }
            }
        }

        item {
            Surface(
                color=FqDanger.copy(alpha=.09f),
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(horizontal=14.dp,vertical=10.dp)
                    .clickable { onLive() }
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(48.dp)
                            .background(FqDanger.copy(alpha=.18f),RoundedCornerShape(15.dp)),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.LiveTv,null,tint=FqDanger)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Live & Premiere",fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Text(
                            "ساخت، زمان‌بندی و مدیریت پخش زنده و Premiere",
                            color=FqMuted,
                            fontSize=11.sp
                        )
                    }
                    Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
                }
            }
        }

        item {
            PremiumSectionHeader(
                "Scheduled",
                if(scheduled.isEmpty())"محتوای زمان‌بندی‌شده‌ای نداری"
                else scheduled.size.toString()+" محتوای در صف",
                Icons.Default.ScheduleSend
            )
        }

        if(scheduled.isEmpty()) {
            item {
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.EventAvailable,null,tint=FqMuted)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Post، Review، Poll و Clip رو برای زمان دقیق برنامه‌ریزی کن.",
                            color=FqMuted,
                            fontSize=11.sp,
                            lineHeight=14.sp
                        )
                    }
                }
            }
        } else {
            items(scheduled,key={it.kind+"_"+it.id}) { item ->
                ScheduledCreatorCard(
                    item=item,
                    onPublishNow={
                        scope.launch {
                            runCatching { repo.publishScheduledNow(item.kind,item.id) }
                                .onSuccess { refresh++ }
                                .onFailure { error=it.message }
                        }
                    },
                    onUnschedule={
                        scope.launch {
                            runCatching { repo.unschedule(item.kind,item.id) }
                                .onSuccess { refresh++ }
                                .onFailure { error=it.message }
                        }
                    }
                )
            }
        }

        item {
            PremiumSectionHeader(
                "Analytics V3",
                "Watch Time، Retention، Completion، Rewatch و Conversion واقعی",
                Icons.Default.Insights
            )
        }

        item {
            LazyRow(
                contentPadding=PaddingValues(horizontal=14.dp),
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                items(listOf(7,30,90)) { days ->
                    FilterChip(
                        selected=analyticsDays==days,
                        onClick={analyticsDays=days},
                        label={Text(days.toString()+" روز",fontSize=11.sp)}
                    )
                }
            }
        }

        analytics?.let { a3 ->
            item {
                CreatorRetentionMetrics(a3.summary)
            }

            item {
                PremiumSectionHeader(
                    "Watch Time روزانه",
                    "Sessionهای واقعی Clip در "+a3.days+" روز اخیر",
                    Icons.Default.ShowChart
                )
            }

            item {
                CreatorDailyChart(a3.daily)
            }

            if(a3.topReels.isNotEmpty()) {
                item {
                    PremiumSectionHeader(
                        "بهترین Clipها",
                        "بر اساس Session، Watch Time و Completion",
                        Icons.Default.Leaderboard
                    )
                }
                items(a3.topReels,key={it.id}) { reel ->
                    CreatorTopReelCard(reel)
                }
            }
        }

        item {
            PremiumSectionHeader("نمای کلی","آمار واقعی حساب و محتوای منتشرشده",Icons.Default.Analytics)
        }

        item {
            Column(Modifier.padding(horizontal=14.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    StudioMetric(
                        "بازدید Clipها",
                        compactCreatorCount(d.reelViews),
                        Icons.Default.Visibility,
                        Modifier.weight(1f)
                    )
                    StudioMetric(
                        "Story Views",
                        compactCreatorCount(d.storyViews),
                        Icons.Default.AutoStories,
                        Modifier.weight(1f)
                    )
                }
                Row(
                    horizontalArrangement=Arrangement.spacedBy(8.dp),
                    modifier=Modifier.padding(top=8.dp)
                ) {
                    StudioMetric(
                        "دنبال‌کننده",
                        compactCreatorCount(d.followers),
                        Icons.Default.Groups,
                        Modifier.weight(1f)
                    )
                    StudioMetric(
                        "Channel Followers",
                        compactCreatorCount(d.channelFollowers),
                        Icons.Default.Campaign,
                        Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            PremiumSectionHeader("Engagement","تعامل روی Clipها",Icons.Default.Bolt)
        }

        item {
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(22.dp),
                modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
            ) {
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

        error?.let {
            item {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=11.sp,
                    modifier=Modifier.fillMaxWidth().padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun CreatorRetentionMetrics(summary:CreatorAnalyticsSummary) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            StudioMetric(
                "Unique Viewers",
                compactCreatorCount(summary.uniqueViewers),
                Icons.Default.PersonSearch,
                Modifier.weight(1f)
            )
            StudioMetric(
                "Watch Time",
                formatCreatorWatchTime(summary.watchMs),
                Icons.Default.Timer,
                Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            StudioMetric(
                "Completion",
                formatCreatorPercent(summary.completionRate),
                Icons.Default.TaskAlt,
                Modifier.weight(1f)
            )
            StudioMetric(
                "Rewatch",
                formatCreatorPercent(summary.rewatchRate),
                Icons.Default.Replay,
                Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            StudioMetric(
                "Avg Watch",
                formatCreatorDuration(summary.averageWatchMs),
                Icons.Default.AccessTime,
                Modifier.weight(1f)
            )
            StudioMetric(
                "Follow Conversion",
                formatCreatorPercent(summary.followerConversion),
                Icons.Default.PersonAdd,
                Modifier.weight(1f)
            )
        }

        Surface(
            color=FqGold.copy(alpha=.08f),
            shape=RoundedCornerShape(17.dp),
            modifier=Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.TrendingUp,null,tint=FqGold)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Followerهای جدید",fontSize=11.sp,fontWeight=FontWeight.Bold)
                    Text(
                        "در بازه انتخاب‌شده",
                        color=FqMuted,
                        fontSize=11.sp
                    )
                }
                Text(
                    "+"+summary.followersGained,
                    color=FqGold,
                    fontSize=16.sp,
                    fontWeight=FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun CreatorDailyChart(items:List<CreatorDailyMetric>) {
    if(items.isEmpty()) {
        Surface(
            color=FqSurface,
            shape=RoundedCornerShape(18.dp),
            modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
        ) {
            Text(
                "هنوز داده Playback برای نمودار ثبت نشده.",
                color=FqMuted,
                fontSize=11.sp,
                modifier=Modifier.padding(14.dp)
            )
        }
        return
    }

    val visible=items.takeLast(30)
    val maxWatch=visible.maxOfOrNull { it.watchMs }?.coerceAtLeast(1L) ?: 1L

    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
    ) {
        Column(Modifier.padding(vertical=14.dp)) {
            LazyRow(
                contentPadding=PaddingValues(horizontal=12.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp),
                verticalAlignment=Alignment.Bottom
            ) {
                items(visible,key={it.date}) { day ->
                    val fraction=(day.watchMs.toFloat()/maxWatch.toFloat()).coerceIn(.04f,1f)
                    Column(
                        horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.Bottom,
                        modifier=Modifier.height(126.dp).width(24.dp)
                    ) {
                        Text(
                            compactCreatorCount(day.uniqueViewers),
                            color=FqMuted,
                            fontSize=5.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.width(13.dp)
                                .height((82f*fraction).dp)
                                .background(FqGold,RoundedCornerShape(6.dp))
                        )
                        Text(
                            day.date.takeLast(5),
                            color=FqMuted,
                            fontSize=5.sp,
                            modifier=Modifier.padding(top=5.dp)
                        )
                    }
                }
            }
            Text(
                "ارتفاع ستون = Watch Time • عدد بالا = Unique Viewer",
                color=FqMuted,
                fontSize=6.sp,
                modifier=Modifier.padding(horizontal=14.dp,vertical=4.dp)
            )
        }
    }
}

@Composable
private fun CreatorTopReelCard(item:CreatorTopReelMetric) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
    ) {
        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(
                Modifier.width(62.dp).height(86.dp).clip(RoundedCornerShape(12.dp))
            ) {
                RemoteImage(item.coverUrl.takeIf(String::isNotBlank),Modifier.fillMaxSize(),ContentScale.Crop)
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha=.72f),RoundedCornerShape(6.dp))
                        .padding(horizontal=5.dp,vertical=3.dp)
                ) {
                    Text(
                        formatCreatorPercent(item.completionRate),
                        color=FqGold,
                        fontSize=6.sp
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.caption.ifBlank{"Reel"},
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    compactCreatorCount(item.uniqueViewers)+" Viewer • "+
                        formatCreatorWatchTime(item.watchMs),
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=5.dp)
                )
                LinearProgressIndicator(
                    progress={item.completionRate.toFloat().coerceIn(0f,1f)},
                    color=FqGold,
                    trackColor=FqSurface3,
                    modifier=Modifier.fillMaxWidth().padding(top=7.dp).height(4.dp)
                )
                Text(
                    compactCreatorCount(item.sessions)+" Session",
                    color=FqMuted,
                    fontSize=6.sp,
                    modifier=Modifier.padding(top=4.dp)
                )
            }
        }
    }
}

private fun formatCreatorPercent(value:Double):String =
    String.format(java.util.Locale.US,"%.1f%%",value.coerceIn(0.0,1.0)*100.0)

private fun formatCreatorDuration(ms:Long):String {
    if(ms<=0L) return "0s"
    val seconds=ms/1000L
    return if(seconds<60L) seconds.toString()+"s"
    else (seconds/60L).toString()+"m "+(seconds%60L).toString()+"s"
}

private fun formatCreatorWatchTime(ms:Long):String {
    if(ms<=0L) return "0m"
    val minutes=ms/60_000L
    return when {
        minutes>=1440L -> String.format(java.util.Locale.US,"%.1fd",minutes/1440.0)
        minutes>=60L -> String.format(java.util.Locale.US,"%.1fh",minutes/60.0)
        else -> minutes.toString()+"m"
    }
}

@Composable
private fun ScheduledCreatorCard(
    item:ScheduledCreatorItem,
    onPublishNow:()->Unit,
    onUnschedule:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(
                        FqGold.copy(alpha=.12f),
                        RoundedCornerShape(13.dp)
                    ),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(
                        if(item.kind=="reel")Icons.Default.VideoLibrary else Icons.Default.PostAdd,
                        null,
                        tint=FqGold
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when(item.contentType) {
                            "review" -> "Review"
                            "poll" -> "Poll"
                            "announcement" -> "Announcement"
                            "reel" -> "Reel"
                            else -> "Post"
                        },
                        fontSize=12.sp,
                        fontWeight=FontWeight.Bold
                    )
                    Text(
                        item.scheduledAt.replace("T"," ").take(16),
                        color=FqGold,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
                if(item.spoiler) {
                    Surface(
                        color=FqDanger.copy(alpha=.1f),
                        shape=RoundedCornerShape(7.dp)
                    ) {
                        Text(
                            "Spoiler",
                            color=FqDanger,
                            fontSize=6.sp,
                            modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                        )
                    }
                }
            }

            if(item.preview.isNotBlank()) {
                Text(
                    item.preview,
                    fontSize=11.sp,
                    maxLines=3,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=9.dp)
                )
            }

            val meta=listOf(item.channelName,item.mediaTitle).filter(String::isNotBlank)
            if(meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" • "),
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=5.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top=9.dp),
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                Button(
                    onClick=onPublishNow,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                    shape=RoundedCornerShape(11.dp),
                    modifier=Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Publish,null,tint=Color.Black,modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Publish Now",color=Color.Black,fontSize=11.sp)
                }
                OutlinedButton(
                    onClick=onUnschedule,
                    shape=RoundedCornerShape(11.dp),
                    modifier=Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.EditCalendar,null,modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("برگردان به Draft",fontSize=11.sp)
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
            Text(label,color=FqMuted,fontSize=11.sp)
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
        Text(label,fontSize=12.sp,modifier=Modifier.weight(1f))
        Text(compactCreatorCount(value),fontSize=12.sp,fontWeight=FontWeight.Bold)
    }
}

private fun compactCreatorCount(value: Long): String = when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
