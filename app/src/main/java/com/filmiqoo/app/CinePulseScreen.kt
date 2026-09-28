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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private enum class CinePulseMode { NOW, REVIEWS, ROOMS, CLIPS }

@Composable
fun CinePulseScreen(
    social:SocialRepository,
    backend:BackendRepository,
    repository:TmdbRepository,
    loggedIn:Boolean,
    onMedia:(MediaItem)->Unit,
    onOpenPost:(String)->Unit,
    onOpenClip:(String)->Unit,
    onOpenRoom:(SocialRoom)->Unit,
    onCreator:(Creator)->Unit,
    onSearch:()->Unit,
    onInbox:()->Unit,
    onCreate:()->Unit,
    onRequireAuth:()->Unit,
    initialPostId:String?=null,
    onFocusedPostConsumed:()->Unit={}
) {
    val pulseRepo=remember { PulseRepository(backend) }
    val friendRepo=remember { FriendActivityRepository(backend) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var trending by remember { mutableStateOf<List<PulseTrendItem>>(emptyList()) }
    var watching by remember { mutableStateOf<List<FriendWatchingNow>>(emptyList()) }
    var posts by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<SocialRoom>>(emptyList()) }
    var clips by remember { mutableStateOf<List<ReelFeedItem>>(emptyList()) }
    var modeName by rememberSaveable {
        mutableStateOf(
            if(initialPostId.isNullOrBlank())
                CinePulseMode.NOW.name
            else
                CinePulseMode.REVIEWS.name
        )
    }
    val mode=runCatching { CinePulseMode.valueOf(modeName) }
        .getOrDefault(CinePulseMode.NOW)

    LaunchedEffect(initialPostId) {
        if(!initialPostId.isNullOrBlank()) {
            modeName=CinePulseMode.REVIEWS.name
        }
    }

    LaunchedEffect(refresh,loggedIn) {
        loading=true
        error=null
        runCatching {
            coroutineScope {
                val trendingReq=async { runCatching { pulseRepo.trending() }.getOrDefault(emptyList()) }
                val postsReq=async {
                    val base=runCatching { social.feedPage(limit=24).items }
                        .getOrDefault(emptyList())
                        .filter { it.media!=null }
                    val focused=initialPostId?.takeIf(String::isNotBlank)?.let { id ->
                        runCatching { social.post(id) }.getOrNull()
                    }
                    if(focused!=null) {
                        listOf(focused)+base.filterNot { it.id==focused.id }
                    } else base
                }
                val roomsReq=async {
                    runCatching { social.rooms() }
                        .getOrDefault(emptyList())
                        .filter { it.media!=null || it.topic.isNotBlank() }
                }
                val clipsReq=async {
                    runCatching { social.reelsPage(limit=18).items }
                        .getOrDefault(emptyList())
                        .filter { it.media!=null }
                }
                val watchingReq=async {
                    if(loggedIn) {
                        runCatching { friendRepo.followingWatching() }.getOrDefault(emptyList())
                    } else emptyList()
                }

                trending=trendingReq.await()
                posts=postsReq.await()
                rooms=roomsReq.await()
                clips=clipsReq.await()
                watching=watchingReq.await()
            }
        }.onFailure {
            error=it.message ?: "نبض در دسترس نیست"
        }
        loading=false
        if(!initialPostId.isNullOrBlank()) onFocusedPostConsumed()
    }

    LazyColumn(
        modifier=Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=28.dp)
    ) {
        item {
            PulseHeader(
                onSearch=onSearch,
                onInbox=onInbox,
                onCreate={
                    if(loggedIn) onCreate() else onRequireAuth()
                }
            )
        }

        item {
            PulseModeBar(
                selected=mode,
                onSelected={modeName=it.name}
            )
        }

        if(loading) {
            item {
                LinearProgressIndicator(
                    color=FqGold,
                    trackColor=Color.Transparent,
                    modifier=Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }

        error?.let { message ->
            item {
                Surface(
                    color=FqDanger.copy(alpha=.08f),
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudOff,null,tint=FqDanger)
                        Spacer(Modifier.width(8.dp))
                        Text(message,color=FqDanger,fontSize=10.sp,modifier=Modifier.weight(1f))
                        TextButton(onClick={refresh++}) { Text("دوباره") }
                    }
                }
            }
        }

        when(mode) {
            CinePulseMode.NOW -> {
                if(loggedIn && watching.isNotEmpty()) {
                    item {
                        PulseSectionTitle(
                            title="الان چی می‌بینن",
                            subtitle="فعالیت زنده آدم‌هایی که دنبال می‌کنی",
                            icon=Icons.Default.Visibility
                        )
                    }
                    item {
                        PulseWatchingRail(watching,repository,onMedia)
                    }
                }

                if(trending.isNotEmpty()) {
                    item {
                        PulseSectionTitle(
                            title="نبض داغ",
                            subtitle="عنوان‌هایی که همین حالا بیشترین تماشا و واکنش رو دارن",
                            icon=Icons.Default.Whatshot
                        )
                    }
                    item {
                        PulseTrendRail(trending,repository,onMedia)
                    }
                }

                if(posts.isNotEmpty()) {
                    item {
                        PulseSectionTitle(
                            title="تازه از فیلم‌بازها",
                            subtitle="نظرهای کوتاه و مرتبط با عنوان‌هایی که همین حالا دیده می‌شن",
                            icon=Icons.Default.RateReview
                        )
                    }
                    items(posts.take(4),key={it.id}) { post ->
                        PulsePostCard(
                            post=post,
                            repository=repository,
                            onMedia=onMedia,
                            onOpenPost=onOpenPost,
                            onCreator=onCreator
                        )
                    }
                }
            }

            CinePulseMode.REVIEWS -> {
                if(posts.isNotEmpty()) {
                    item {
                        PulseSectionTitle(
                            title="نقدهای فیلم‌بازها",
                            subtitle="عنوان‌محور، کوتاه و با محافظ اسپویل",
                            icon=Icons.Default.RateReview
                        )
                    }
                    items(posts,key={it.id}) { post ->
                        PulsePostCard(
                            post=post,
                            repository=repository,
                            onMedia=onMedia,
                            onOpenPost=onOpenPost,
                            onCreator=onCreator
                        )
                    }
                }
            }

            CinePulseMode.ROOMS -> {
                if(rooms.isNotEmpty()) {
                    item {
                        PulseSectionTitle(
                            title="اتاق‌های عنوان",
                            subtitle="هر گفتگو به یک فیلم، سریال یا قسمت مشخص وصل است",
                            icon=Icons.Default.Forum
                        )
                    }
                    items(rooms.take(30),key={it.id}) { room ->
                        PulseRoomCard(
                            room=room,
                            onOpenRoom=onOpenRoom
                        )
                    }
                }
            }

            CinePulseMode.CLIPS -> {
                if(clips.isNotEmpty()) {
                    item {
                        PulseSectionTitle(
                            title="کلیپ‌های مرتبط",
                            subtitle="کلیپ صحنه، واکنش و تحلیل؛ همیشه متصل به عنوان",
                            icon=Icons.Default.SmartDisplay
                        )
                    }
                    items(
                        clips.take(30).chunked(2),
                        key={ row -> row.joinToString(":") { it.id } }
                    ) { row ->
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal=16.dp,vertical=5.dp),
                            horizontalArrangement=Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { clip ->
                                PulseClipCard(
                                    clip=clip,
                                    repository=repository,
                                    onOpenClip=onOpenClip,
                                    modifier=Modifier.weight(1f)
                                )
                            }
                            if(row.size==1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        val modeEmpty=when(mode) {
            CinePulseMode.NOW ->
                trending.isEmpty() && watching.isEmpty() && posts.isEmpty()
            CinePulseMode.REVIEWS -> posts.isEmpty()
            CinePulseMode.ROOMS -> rooms.isEmpty()
            CinePulseMode.CLIPS -> clips.isEmpty()
        }
        if(!loading && modeEmpty) {
            item {
                PremiumEmptyState(
                    icon=when(mode) {
                        CinePulseMode.NOW -> Icons.Default.Whatshot
                        CinePulseMode.REVIEWS -> Icons.Default.RateReview
                        CinePulseMode.ROOMS -> Icons.Default.Forum
                        CinePulseMode.CLIPS -> Icons.Default.SmartDisplay
                    },
                    title=when(mode) {
                        CinePulseMode.NOW -> "نبض فعلاً آرومه"
                        CinePulseMode.REVIEWS -> "هنوز نقد تازه‌ای نیست"
                        CinePulseMode.ROOMS -> "گفتگوی بازی نیست"
                        CinePulseMode.CLIPS -> "کلیپ مرتبطی نیست"
                    },
                    body="اینجا فقط محتوایی میاد که مستقیم به فیلم، سریال یا قسمت مشخص وصل باشه؛ با محافظ اسپویل.",
                    action=if(loggedIn)"محتوا بساز" else "ورود به حساب",
                    onAction=if(loggedIn) onCreate else onRequireAuth
                )
            }
        }
    }
}

@Composable
private fun PulseModeBar(
    selected:CinePulseMode,
    onSelected:(CinePulseMode)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            Triple(CinePulseMode.NOW,Icons.Default.Whatshot,"الان"),
            Triple(CinePulseMode.REVIEWS,Icons.Default.RateReview,"نقدها"),
            Triple(CinePulseMode.ROOMS,Icons.Default.Forum,"گفتگوها"),
            Triple(CinePulseMode.CLIPS,Icons.Default.SmartDisplay,"کلیپ‌ها")
        ).forEach { (mode,icon,label) ->
            item {
                val active=selected==mode
                Surface(
                    color=if(active)FqGold else FqSurface,
                    contentColor=if(active)Color.White else FqMutedStrong,
                    shape=RoundedCornerShape(15.dp),
                    border=androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if(active)FqGold else Color.White.copy(alpha=.07f)
                    ),
                    modifier=Modifier.clickable { onSelected(mode) }
                ) {
                    Row(
                        Modifier.padding(horizontal=13.dp,vertical=9.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(icon,null,modifier=Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label,fontSize=10.sp,fontWeight=FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun PulseHeader(
    onSearch:()->Unit,
    onInbox:()->Unit,
    onCreate:()->Unit
) {
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                listOf(Color(0xFF160406),FqBg)
            )
        )
    ) {
        Column(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal=16.dp,vertical=14.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(FqGold,CircleShape),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(Icons.Default.Whatshot,null,tint=Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("نبض",fontSize=28.sp,fontWeight=FontWeight.Black)
                    Text(
                        "جایی برای واکنش، نقد و گفتگو؛ فقط درباره چیزی که دیدی",
                        color=FqMuted,
                        fontSize=10.sp,
                        maxLines=2
                    )
                }
                FqIconButton(Icons.Default.Search,"جستجو",onSearch)
                FqIconButton(Icons.Default.MarkChatUnread,"پیام‌ها",onInbox)
            }

            Row(
                Modifier.padding(top=10.dp),
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                Surface(
                    color=Color.White.copy(alpha=.06f),
                    shape=RoundedCornerShape(10.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal=8.dp,vertical=5.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.VisibilityOff,
                            null,
                            tint=FqGold,
                            modifier=Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("محافظ اسپویل",fontSize=7.sp,fontWeight=FontWeight.Bold)
                    }
                }
                Surface(
                    color=Color.White.copy(alpha=.06f),
                    shape=RoundedCornerShape(10.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal=8.dp,vertical=5.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.MovieFilter,
                            null,
                            tint=FqGold,
                            modifier=Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("همه‌چیز عنوان‌محور",fontSize=7.sp,fontWeight=FontWeight.Bold)
                    }
                }
            }

            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.07f)),
                modifier=Modifier.fillMaxWidth().padding(top=14.dp)
                    .clickable(onClick=onCreate)
            ) {
                Row(
                    Modifier.padding(13.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).background(FqGold.copy(alpha=.12f),CircleShape),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.Add,null,tint=FqGold)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("چی دیدی؟",fontSize=11.sp,fontWeight=FontWeight.Bold)
                        Text("نقد، نظر، واکنش یا کلیپ رو به همون عنوان وصل کن",color=FqMuted,fontSize=8.sp)
                    }
                    Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                }
            }
        }
    }
}

@Composable
private fun PulseSectionTitle(
    title:String,
    subtitle:String,
    icon:androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=24.dp,bottom=10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(FqGold.copy(alpha=.12f),RoundedCornerShape(11.dp)),
            contentAlignment=Alignment.Center
        ) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=17.sp,fontWeight=FontWeight.Black)
            Text(subtitle,color=FqMuted,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PulseWatchingRail(
    items:List<FriendWatchingNow>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(items.take(12),key={it.user.id+":"+it.media.key}) { item ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(20.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.07f)),
                modifier=Modifier.width(230.dp).clickable { onMedia(item.media) }
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(125.dp)) {
                        RemoteImage(
                            repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f)))
                            )
                        )
                        Surface(
                            color=FqGold,
                            shape=RoundedCornerShape(9.dp),
                            modifier=Modifier.align(Alignment.TopStart).padding(8.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal=7.dp,vertical=4.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(6.dp).background(Color.White,CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text("در حال تماشا",color=Color.White,fontSize=7.sp,fontWeight=FontWeight.Black)
                            }
                        }
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(9.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            RemoteImage(item.user.avatarUrl.takeIf(String::isNotBlank),Modifier.size(30.dp).clip(CircleShape))
                            Spacer(Modifier.width(7.dp))
                            Text(item.user.displayName,color=Color.White,fontSize=9.sp,fontWeight=FontWeight.Bold,maxLines=1)
                        }
                    }
                    Column(Modifier.padding(11.dp)) {
                        Text(item.media.title,fontSize=11.sp,fontWeight=FontWeight.Black,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(item.episodeLabel.ifBlank{"فیلم"},color=FqMuted,fontSize=8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PulseTrendRail(
    items:List<PulseTrendItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(items.take(12),key={it.media.key}) { item ->
            Box(
                Modifier.width(250.dp).height(145.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .clickable { onMedia(item.media) }
            ) {
                RemoteImage(
                    repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.86f)))
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text(item.media.title,color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Black,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Row(Modifier.padding(top=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility,null,tint=FqGold,modifier=Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(item.watchingNow.toString()+" نفر",color=Color.White.copy(alpha=.78f),fontSize=8.sp)
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Default.Favorite,null,tint=FqGold,modifier=Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(item.reactions.toString()+" واکنش",color=Color.White.copy(alpha=.78f),fontSize=8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsePostCard(
    post:SocialPost,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit,
    onOpenPost:(String)->Unit,
    onCreator:(Creator)->Unit
) {
    var revealed by remember(post.id) { mutableStateOf(!post.spoiler) }
    val media=post.media?.asMediaItem()
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.07f)),
        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=5.dp)
            .clickable { onOpenPost(post.id) }
    ) {
        Column {
            media?.let {
                Box(
                    Modifier.fillMaxWidth().height(170.dp).clickable { onMedia(it) }
                ) {
                    RemoteImage(
                        repository.backdrop(it.backdropPath ?: it.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.82f)))
                        )
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(it.title,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.Black,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(
                            if(post.type=="review")"ریویو" else "بحث",
                            color=FqGoldSoft,
                            fontSize=8.sp,
                            fontWeight=FontWeight.Bold
                        )
                    }
                }
            }
            Column(Modifier.padding(13.dp)) {
                Row(
                    verticalAlignment=Alignment.CenterVertically,
                    modifier=Modifier.clickable {
                        onCreator(
                            Creator(
                                name=post.author.displayName,
                                handle="@"+post.author.username,
                                followers="",
                                bio="فیلم‌باز در Filmiqoo",
                                verified=post.author.verified,
                                id=post.author.id,
                                entityType="user",
                                avatarUrl=post.author.avatarUrl
                            )
                        )
                    }
                ) {
                    RemoteImage(post.author.avatarUrl.takeIf(String::isNotBlank),Modifier.size(34.dp).clip(CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(post.author.displayName,fontSize=10.sp,fontWeight=FontWeight.Bold)
                        Text("@"+post.author.username,color=FqMuted,fontSize=7.sp)
                    }
                    post.publishedAt?.let {
                        Text(socialRelativeTime(it),color=FqMuted,fontSize=7.sp)
                    }
                }

                if(post.spoiler && !revealed) {
                    Surface(
                        color=FqDanger.copy(alpha=.08f),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                            .clickable { revealed=true }
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VisibilityOff,null,tint=FqDanger,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("اسپویلر مخفی شده • برای نمایش لمس کن",color=FqDanger,fontSize=9.sp)
                        }
                    }
                } else if(post.body.isNotBlank()) {
                    Text(
                        post.body,
                        fontSize=11.sp,
                        lineHeight=18.sp,
                        maxLines=5,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=10.dp)
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=9.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FavoriteBorder,null,tint=FqMuted,modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(post.likes.toString(),color=FqMuted,fontSize=8.sp)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.ChatBubbleOutline,null,tint=FqMuted,modifier=Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(post.comments.toString(),color=FqMuted,fontSize=8.sp)
                    Spacer(Modifier.weight(1f))
                    Text("باز کردن بحث",color=FqGold,fontSize=8.sp,fontWeight=FontWeight.Bold)
                    Icon(Icons.Default.ChevronLeft,null,tint=FqGold,modifier=Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PulseRoomCard(
    room:SocialRoom,
    onOpenRoom:(SocialRoom)->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha=.07f)
        ),
        modifier=Modifier.fillMaxWidth()
            .padding(horizontal=16.dp,vertical=5.dp)
            .clickable { onOpenRoom(room) }
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp)
                    .background(FqGold.copy(alpha=.12f),RoundedCornerShape(15.dp)),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    Icons.Default.Forum,
                    null,
                    tint=FqGold,
                    modifier=Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    room.media?.title ?: room.name,
                    fontSize=12.sp,
                    fontWeight=FontWeight.Black,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    if(room.media!=null) room.name else room.topic,
                    color=FqMuted,
                    fontSize=9.sp,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
                Text(
                    room.members.toString()+" نفر در گفتگو",
                    color=FqGoldSoft,
                    fontSize=8.sp,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(top=5.dp)
                )
            }
            Icon(
                Icons.Default.ChevronLeft,
                null,
                tint=FqMuted,
                modifier=Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun PulseClipCard(
    clip:ReelFeedItem,
    repository:TmdbRepository,
    onOpenClip:(String)->Unit,
    modifier:Modifier=Modifier
) {
    val media=clip.media?.asMediaItem()
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(FqSurface)
            .clickable { onOpenClip(clip.id) }
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(.67f)
        ) {
            RemoteImage(
                clip.coverUrl.takeIf(String::isNotBlank)
                    ?: media?.posterPath,
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha=.86f)
                        )
                    )
                )
            )
            Surface(
                color=Color.White.copy(alpha=.92f),
                contentColor=Color.Black,
                shape=CircleShape,
                modifier=Modifier.size(42.dp)
                    .align(Alignment.Center)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.PlayArrow,null)
                }
            }
            media?.title?.let { title ->
                Surface(
                    color=Color.Black.copy(alpha=.64f),
                    shape=RoundedCornerShape(9.dp),
                    modifier=Modifier.align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        title,
                        color=Color.White,
                        fontSize=8.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.widthIn(max=120.dp)
                            .padding(horizontal=7.dp,vertical=5.dp)
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal=10.dp,vertical=9.dp)) {
            Text(
                clip.caption.ifBlank { "کلیپ درباره "+(media?.title ?: "این عنوان") },
                fontSize=9.sp,
                lineHeight=13.sp,
                maxLines=2,
                overflow=TextOverflow.Ellipsis
            )
            Text(
                "@"+clip.author.username,
                color=FqMuted,
                fontSize=7.sp,
                maxLines=1,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=4.dp)
            )
        }
    }
}
