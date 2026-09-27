package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

@Composable
fun SavedSocialScreen(
    social:SocialRepository,
    onBack:()->Unit,
    onCreator:(Creator)->Unit,
    onMedia:(MediaItem)->Unit,
    onOpenPost:(String)->Unit,
    onOpenClip:(String)->Unit
) {
    val scope=rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var posts by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    var reels by remember { mutableStateOf<List<ReelFeedItem>>(emptyList()) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching {
            posts=social.savedPosts()
            reels=social.savedReels()
        }.onFailure {
            error=it.message ?: "خطا در دریافت ذخیره‌های اجتماعی"
        }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(Color(0xFF151D2D),FqBg))
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                Column(Modifier.weight(1f)) {
                    Text("ذخیره‌های اجتماعی",fontSize=22.sp,fontWeight=FontWeight.Black)
                    Text("Postها و Clipهایی که برای بعد نگه داشتی",color=FqMuted,fontSize=11.sp)
                }
                IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
            }
        }

        TabRow(
            selectedTabIndex=tab,
            containerColor=FqBg,
            contentColor=FqGold
        ) {
            Tab(
                selected=tab==0,
                onClick={tab=0},
                text={Text("Postها ("+posts.size+")",fontSize=11.sp)}
            )
            Tab(
                selected=tab==1,
                onClick={tab=1},
                text={Text("Clipها ("+reels.size+")",fontSize=11.sp)}
            )
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=11.sp,
                modifier=Modifier.fillMaxWidth().padding(10.dp)
            )
        }

        if(tab==0) {
            if(!loading && posts.isEmpty()) {
                PremiumEmptyState(
                    Icons.Default.BookmarkBorder,
                    "Saved Post نداری",
                    "از Club پست‌ها رو Save کن تا اینجا جمع بشن."
                )
            } else {
                LazyColumn(
                    contentPadding=PaddingValues(12.dp),
                    verticalArrangement=Arrangement.spacedBy(9.dp),
                    modifier=Modifier.fillMaxSize()
                ) {
                    items(posts,key={it.id}) { post ->
                        SavedPostCard(
                            post=post,
                            onOpen={onOpenPost(post.id)},
                            onCreator={
                                onCreator(
                                    Creator(
                                        name=post.author.displayName,
                                        handle="@"+post.author.username,
                                        followers="",
                                        bio="عضو Community Filmiqoo",
                                        verified=post.author.verified,
                                        id=post.author.id,
                                        entityType="user",
                                        avatarUrl=post.author.avatarUrl
                                    )
                                )
                            },
                            onMedia={post.media?.asMediaItem()?.let(onMedia)},
                            onRemove={
                                scope.launch {
                                    runCatching { social.togglePostSave(post.id) }
                                        .onSuccess {
                                            posts=posts.filterNot { it.id==post.id }
                                        }
                                        .onFailure { error=it.message }
                                }
                            }
                        )
                    }
                }
            }
        } else {
            if(!loading && reels.isEmpty()) {
                PremiumEmptyState(
                    Icons.Default.VideoLibrary,
                    "Saved Clip نداری",
                    "Clipهایی که Save می‌کنی اینجا جمع می‌شن."
                )
            } else {
                LazyVerticalGrid(
                    columns=GridCells.Fixed(2),
                    contentPadding=PaddingValues(10.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp),
                    modifier=Modifier.fillMaxSize()
                ) {
                    items(reels,key={it.id}) { reel ->
                        SavedReelCard(
                            reel=reel,
                            onClick={onOpenClip(reel.id)},
                            onRemove={
                                scope.launch {
                                    runCatching { social.toggleReelSave(reel.id) }
                                        .onSuccess { reels=reels.filterNot { it.id==reel.id } }
                                        .onFailure { error=it.message }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPostCard(
    post:SocialPost,
    onOpen:()->Unit,
    onCreator:()->Unit,
    onMedia:()->Unit,
    onRemove:()->Unit
) {
    var revealed by remember(post.id) { mutableStateOf(!post.spoiler) }

    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                RemoteImage(
                    post.author.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(42.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    Modifier.weight(1f).clickable { onCreator() }
                ) {
                    Text(post.author.displayName,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    Text("@"+post.author.username,color=FqMuted,fontSize=11.sp)
                }
                IconButton(onClick=onRemove) {
                    Icon(Icons.Default.BookmarkRemove,null,tint=FqGold)
                }
            }

            if(post.spoiler && !revealed) {
                Surface(
                    color=FqDanger.copy(alpha=.09f),
                    shape=RoundedCornerShape(12.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=9.dp)
                        .clickable { revealed=true }
                ) {
                    Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.VisibilityOff,null,tint=FqDanger)
                        Spacer(Modifier.width(6.dp))
                        Text("Spoiler Shield • برای نمایش لمس کن",color=FqDanger,fontSize=11.sp)
                    }
                }
            } else {
                Text(
                    post.body,
                    fontSize=11.sp,
                    lineHeight=16.sp,
                    maxLines=7,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=9.dp)
                )
            }

            post.media?.takeIf { !it.title.isNullOrBlank() }?.let { media ->
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(13.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=9.dp)
                        .clickable { onMedia() }
                ) {
                    Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(
                            media.posterUrl,
                            Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(8.dp)),
                            ContentScale.Crop
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(media.title.orEmpty(),fontSize=11.sp,modifier=Modifier.weight(1f))
                        Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
                    }
                }
            }

            Row(Modifier.padding(top=7.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.FavoriteBorder,null,tint=FqMuted,modifier=Modifier.size(14.dp))
                Text(" "+post.likes,color=FqMuted,fontSize=11.sp)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.ChatBubbleOutline,null,tint=FqMuted,modifier=Modifier.size(14.dp))
                Text(" "+post.comments,color=FqMuted,fontSize=11.sp)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.Share,null,tint=FqMuted,modifier=Modifier.size(14.dp))
                Text(" "+post.shares,color=FqMuted,fontSize=11.sp)
            }
        }
    }
}

@Composable
private fun SavedReelCard(
    reel:ReelFeedItem,
    onClick:()->Unit,
    onRemove:()->Unit
) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(.64f)
            .clip(RoundedCornerShape(17.dp))
            .background(FqSurface)
            .clickable { onClick() }
    ) {
        RemoteImage(
            reel.coverUrl.takeIf(String::isNotBlank)
                ?: reel.media?.posterUrl,
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.75f)))
            )
        )
        Icon(
            Icons.Default.PlayCircle,
            null,
            tint=Color.White,
            modifier=Modifier.size(46.dp).align(Alignment.Center)
        )
        if(reel.spoiler) {
            Surface(
                color=FqDanger,
                shape=RoundedCornerShape(8.dp),
                modifier=Modifier.align(Alignment.TopStart).padding(7.dp)
            ) {
                Text("SPOILER",fontSize=6.sp,modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp))
            }
        }
        IconButton(
            onClick=onRemove,
            modifier=Modifier.align(Alignment.TopEnd)
        ) {
            Icon(Icons.Default.BookmarkRemove,null,tint=FqGold)
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(9.dp)
        ) {
            Text(
                reel.caption.ifBlank { reel.media?.title.orEmpty() },
                fontSize=11.sp,
                maxLines=2,
                overflow=TextOverflow.Ellipsis
            )
            Text(
                "@"+reel.author.username,
                color=FqMuted,
                fontSize=6.sp,
                modifier=Modifier.padding(top=3.dp)
            )
        }
    }
}
