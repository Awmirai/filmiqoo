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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

private enum class MeTab { ACTIVITY, CLIPS, LIBRARY }

private sealed interface MeLoad {
    data object Loading:MeLoad
    data class Ready(
        val profile:AccountProfile,
        val stats:LibraryStats,
        val watching:List<ContinueWatchingItem>,
        val favorites:List<MediaItem>,
        val posts:List<SocialPost>,
        val clips:List<ReelFeedItem>
    ):MeLoad
    data class Error(val message:String):MeLoad
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(
    backend:BackendRepository,
    repository:TmdbRepository,
    kidsMode:Boolean=false,
    onMedia:(MediaItem)->Unit,
    onPlay:(PlaybackTarget)->Unit,
    onClips:()->Unit,
    onCommunity:()->Unit,
    onDownloads:()->Unit,
    onLibrary:()->Unit,
    onSocialSaves:()->Unit,
    onHistory:()->Unit,
    onCreatorStudio:()->Unit,
    onInbox:()->Unit,
    onSettings:()->Unit,
    onViewerProfiles:()->Unit,
    onParentalControls:()->Unit,
    onSecurity:()->Unit,
    onSafety:()->Unit,
    onFollowRequests:()->Unit,
    onCloseFriends:()->Unit,
    onEditProfile:()->Unit,
    onFilmDna:()->Unit,
    onReputation:(String)->Unit,
    onSeriesCalendar:()->Unit,
    onSocialCollections:()->Unit,
    onLoggedOut:()->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val creatorRepo=remember { CreatorChannelRepository(backend) }
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<MeLoad>(MeLoad.Loading) }
    var tab by remember { mutableStateOf(MeTab.ACTIVITY) }
    var showMore by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        state=MeLoad.Loading
        state=runCatching {
            val profile=backend.me()
            MeLoad.Ready(
                profile=profile,
                stats=backend.libraryStats(),
                watching=backend.continueWatching(),
                favorites=backend.favorites(),
                posts=runCatching { creatorRepo.userPosts(profile.id) }.getOrDefault(emptyList()),
                clips=runCatching { creatorRepo.userReels(profile.id) }.getOrDefault(emptyList())
            )
        }.getOrElse { MeLoad.Error(it.message ?: "خطا در دریافت پروفایل") }
    }

    when(val s=state) {
        MeLoad.Loading -> LoadingPage("در حال آماده‌سازی پروفایل...")
        is MeLoad.Error -> ErrorPage(s.message) { refresh++ }
        is MeLoad.Ready -> {
            LazyColumn(
                Modifier.fillMaxSize().background(FqBg),
                contentPadding=PaddingValues(bottom=30.dp)
            ) {
                item {
                    MeHero(
                        profile=s.profile,
                        stats=s.stats,
                        kidsMode=kidsMode,
                        onEdit=onEditProfile,
                        onShare={
                            FilmiqooDeepLinks.share(
                                context,
                                s.profile.displayName+" • @"+s.profile.username,
                                FilmiqooDeepLinks.creator(s.profile.id)
                            )
                        },
                        onMore={showMore=true}
                    )
                }

                if(!kidsMode) {
                    item {
                        MePrimaryActions(
                            onEdit=onEditProfile,
                            onFilmDna=onFilmDna,
                            onInbox=onInbox
                        )
                    }

                    item {
                        MeTabs(
                            selected=tab,
                            onSelected={tab=it}
                        )
                    }
                }

                when(if(kidsMode) MeTab.LIBRARY else tab) {
                    MeTab.ACTIVITY -> {
                        if(s.posts.isEmpty()) {
                            item {
                                MeEmptyCard(
                                    icon=Icons.Default.RateReview,
                                    title="هنوز چیزی منتشر نکردی",
                                    body="Review، نظر و پیشنهادهای تو اینجا تبدیل به هویت سینمایی‌ات می‌شن.",
                                    action="برو به Club",
                                    onAction=onCommunity
                                )
                            }
                        } else {
                            items(s.posts.take(20),key={it.id}) { post ->
                                MePostCard(
                                    post=post,
                                    onMedia={
                                        post.media?.asMediaItem()?.let(onMedia)
                                    }
                                )
                            }
                        }
                    }

                    MeTab.CLIPS -> {
                        if(s.clips.isEmpty()) {
                            item {
                                MeEmptyCard(
                                    icon=Icons.Default.SmartDisplay,
                                    title="هنوز Clip نداری",
                                    body="کلیپ‌های کوتاهت اینجا یک ویترین تمیز و شخصی می‌سازن.",
                                    action="دیدن Clips",
                                    onAction=onClips
                                )
                            }
                        } else {
                            item {
                                LazyRow(
                                    contentPadding=PaddingValues(horizontal=16.dp,vertical=6.dp),
                                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                                ) {
                                    items(s.clips,key={it.id}) { clip ->
                                        MeClipCard(clip) {
                                            clip.media?.asMediaItem()?.let(onMedia) ?: onClips()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    MeTab.LIBRARY -> {
                        if(s.watching.isNotEmpty()) {
                            item {
                                MeSectionTitle(
                                    "ادامه تماشا",
                                    "همون جایی که رهاش کردی"
                                )
                            }
                            item {
                                LazyRow(
                                    contentPadding=PaddingValues(horizontal=16.dp),
                                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                                ) {
                                    items(s.watching,key={it.target.mediaVersionId}) { item ->
                                        MeContinueCard(item,repository) { onPlay(item.target) }
                                    }
                                }
                            }
                        }

                        item {
                            MeSectionTitle(
                                "ذخیره‌شده برای بعد",
                                if(s.favorites.isEmpty()) "هنوز چیزی اضافه نکردی" else "Favorites تو"
                            )
                        }
                        if(s.favorites.isEmpty()) {
                            item {
                                MeEmptyCard(
                                    icon=Icons.Default.BookmarkBorder,
                                    title="کتابخانه‌ات هنوز خالیه",
                                    body="هر فیلم یا سریالی که دوست داری ذخیره کن تا اینجا جمع بشه.",
                                    action="باز کردن Library",
                                    onAction=onLibrary
                                )
                            }
                        } else {
                            item {
                                LazyRow(
                                    contentPadding=PaddingValues(horizontal=16.dp),
                                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                                ) {
                                    items(s.favorites.take(18),key={it.key}) { media ->
                                        PosterCard(media,repository,{onMedia(media)})
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    MeQuickTools(
                        kidsMode=kidsMode,
                        onInbox=onInbox,
                        onDownloads=onDownloads,
                        onHistory=onHistory,
                        onSettings=onSettings,
                        onViewerProfiles=onViewerProfiles
                    )
                }
            }

            if(showMore) {
                ModalBottomSheet(
                    onDismissRequest={showMore=false},
                    containerColor=FqSurface,
                    dragHandle={BottomSheetDefaults.DragHandle(color=FqMuted)}
                ) {
                    MeMoreSheet(
                        profile=s.profile,
                        onDismiss={showMore=false},
                        onCreatorStudio=onCreatorStudio,
                        onSocialSaves=onSocialSaves,
                        onViewerProfiles=onViewerProfiles,
                        onParentalControls=onParentalControls,
                        onSecurity=onSecurity,
                        onSafety=onSafety,
                        onFollowRequests=onFollowRequests,
                        onCloseFriends=onCloseFriends,
                        onReputation={onReputation(s.profile.id)},
                        onSeriesCalendar=onSeriesCalendar,
                        onSocialCollections=onSocialCollections,
                        onSettings=onSettings,
                        onLogout={
                            scope.launch {
                                backend.logout()
                                showMore=false
                                onLoggedOut()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MeHero(
    profile:AccountProfile,
    stats:LibraryStats,
    kidsMode:Boolean,
    onEdit:()->Unit,
    onShare:()->Unit,
    onMore:()->Unit
) {
    Box(
        Modifier.fillMaxWidth().height(350.dp)
    ) {
        if(profile.coverUrl.isNotBlank()) {
            RemoteImage(
                profile.coverUrl,
                Modifier.fillMaxWidth().height(205.dp),
                ContentScale.Crop
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(205.dp).background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF18212D),
                            Color(0xFF241B0E),
                            Color(0xFF090B10)
                        )
                    )
                )
            )
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha=.08f),
                        Color.Transparent,
                        FqBg
                    )
                )
            )
        )

        Row(
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            MeCircleAction(Icons.Default.Share,onShare)
            Spacer(Modifier.width(6.dp))
            MeCircleAction(Icons.Default.MoreHoriz,onMore)
        }

        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal=18.dp)
        ) {
            Row(
                verticalAlignment=Alignment.Bottom
            ) {
                Box(
                    Modifier.size(92.dp)
                        .background(FqBg,CircleShape)
                        .padding(3.dp)
                ) {
                    if(profile.avatarUrl.isNotBlank()) {
                        RemoteImage(
                            profile.avatarUrl,
                            Modifier.fillMaxSize().clip(CircleShape),
                            ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape).background(FqSurface2),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Person,
                                null,
                                tint=Color.White,
                                modifier=Modifier.size(44.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f).padding(bottom=5.dp)
                ) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(
                            profile.displayName.ifBlank { profile.username },
                            fontSize=24.sp,
                            fontWeight=FontWeight.Black,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis
                        )
                        if(profile.verified) {
                            Spacer(Modifier.width(5.dp))
                            Icon(
                                Icons.Default.Verified,
                                null,
                                tint=Color(0xFF4DA3FF),
                                modifier=Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        "@"+profile.username,
                        color=FqMuted,
                        fontSize=12.sp
                    )
                }
            }

            if(profile.bio.isNotBlank()) {
                Text(
                    profile.bio,
                    fontSize=12.sp,
                    lineHeight=18.sp,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=9.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top=15.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                MeMetric(
                    value=compactMeCount(profile.followers),
                    label="دنبال‌کننده",
                    modifier=Modifier.weight(1f)
                )
                MeMetric(
                    value=compactMeCount(profile.following),
                    label="دنبال‌شده",
                    modifier=Modifier.weight(1f)
                )
                MeMetric(
                    value=stats.distinctTitles.toString(),
                    label="عنوان دیده‌شده",
                    modifier=Modifier.weight(1f)
                )
            }

            if(kidsMode) {
                Surface(
                    color=Color(0xFF1A2635),
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                ) {
                    Row(
                        Modifier.padding(13.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ChildCare,null,tint=Color(0xFF70C7FF))
                        Spacer(Modifier.width(9.dp))
                        Text("Kids Mode فعال است",fontSize=12.sp,fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MeCircleAction(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    onClick:()->Unit
) {
    Surface(
        color=Color.Black.copy(alpha=.42f),
        contentColor=Color.White,
        shape=CircleShape,
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha=.10f)
        ),
        modifier=Modifier.size(42.dp).clickable { onClick() }
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(icon,null,modifier=Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MeMetric(
    value:String,
    label:String,
    modifier:Modifier=Modifier
) {
    Column(
        modifier,
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Text(value,fontSize=15.sp,fontWeight=FontWeight.Black)
        Text(label,color=FqMuted,fontSize=10.sp,modifier=Modifier.padding(top=2.dp))
    }
}

@Composable
private fun MePrimaryActions(
    onEdit:()->Unit,
    onFilmDna:()->Unit,
    onInbox:()->Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=15.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick=onEdit,
            colors=ButtonDefaults.buttonColors(
                containerColor=Color.White,
                contentColor=Color.Black
            ),
            shape=RoundedCornerShape(15.dp),
            modifier=Modifier.weight(1f).height(48.dp)
        ) {
            Icon(Icons.Default.Edit,null,modifier=Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("ویرایش",fontWeight=FontWeight.Bold)
        }

        OutlinedButton(
            onClick=onFilmDna,
            border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
            shape=RoundedCornerShape(15.dp),
            modifier=Modifier.weight(1f).height(48.dp)
        ) {
            Icon(Icons.Default.AutoAwesome,null,tint=FqGold,modifier=Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Film DNA",color=Color.White,fontWeight=FontWeight.Bold)
        }

        Surface(
            color=FqSurface,
            shape=RoundedCornerShape(15.dp),
            border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
            modifier=Modifier.size(48.dp).clickable { onInbox() }
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(Icons.Default.MarkChatUnread,null,tint=Color.White)
            }
        }
    }
}

@Composable
private fun MeTabs(
    selected:MeTab,
    onSelected:(MeTab)->Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start=16.dp,end=16.dp,bottom=12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(FqSurface)
            .padding(4.dp),
        horizontalArrangement=Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            MeTab.ACTIVITY to "فعالیت",
            MeTab.CLIPS to "کلیپ‌ها",
            MeTab.LIBRARY to "کتابخانه"
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
                        fontWeight=if(active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun MePostCard(
    post:SocialPost,
    onMedia:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=Modifier.fillMaxWidth()
            .padding(horizontal=16.dp,vertical=5.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Surface(
                    color=Color.White.copy(alpha=.08f),
                    shape=CircleShape
                ) {
                    Icon(
                        when(post.type) {
                            "review" -> Icons.Default.StarRate
                            "poll" -> Icons.Default.Poll
                            else -> Icons.Default.Notes
                        },
                        null,
                        tint=if(post.type=="review")FqGold else Color.White,
                        modifier=Modifier.padding(8.dp).size(17.dp)
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    when(post.type) {
                        "review" -> "Review"
                        "poll" -> "Poll"
                        else -> "Post"
                    },
                    color=FqMuted,
                    fontSize=10.sp,
                    fontWeight=FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    compactMeCount(post.likes)+" پسند",
                    color=FqMuted,
                    fontSize=10.sp
                )
            }

            if(post.body.isNotBlank()) {
                Text(
                    post.body,
                    fontSize=13.sp,
                    lineHeight=20.sp,
                    maxLines=5,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=11.dp)
                )
            }

            post.media?.asMediaItem()?.let { media ->
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth()
                        .padding(top=12.dp)
                        .clickable { onMedia() }
                ) {
                    Row(
                        Modifier.padding(9.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        RemoteImage(
                            post.media.posterUrl,
                            Modifier.size(42.dp,58.dp).clip(RoundedCornerShape(9.dp)),
                            ContentScale.Crop
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                media.title,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                maxLines=2,
                                overflow=TextOverflow.Ellipsis
                            )
                            Text(
                                media.year,
                                color=FqMuted,
                                fontSize=10.sp,
                                modifier=Modifier.padding(top=3.dp)
                            )
                        }
                        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MeClipCard(
    clip:ReelFeedItem,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.width(158.dp).height(238.dp)
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxSize()) {
            RemoteImage(
                clip.coverUrl.ifBlank { clip.media?.posterUrl.orEmpty() },
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent,Color.Black.copy(alpha=.84f))
                    )
                )
            )
            Surface(
                color=Color.Black.copy(alpha=.45f),
                shape=CircleShape,
                modifier=Modifier.align(Alignment.Center).size(48.dp)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.PlayArrow,null,tint=Color.White)
                }
            }
            Column(
                Modifier.align(Alignment.BottomStart).padding(11.dp)
            ) {
                Text(
                    clip.media?.title ?: clip.caption,
                    color=Color.White,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    compactMeCount(clip.views)+" بازدید",
                    color=Color.White.copy(alpha=.68f),
                    fontSize=9.sp,
                    modifier=Modifier.padding(top=4.dp)
                )
            }
        }
    }
}

@Composable
private fun MeContinueCard(
    item:ContinueWatchingItem,
    repository:TmdbRepository,
    onClick:()->Unit
) {
    Column(
        Modifier.width(238.dp).clickable { onClick() }
    ) {
        Box(
            Modifier.fillMaxWidth().height(134.dp)
                .clip(RoundedCornerShape(18.dp))
        ) {
            RemoteImage(
                repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent,Color.Black.copy(alpha=.82f))
                    )
                )
            )
            Icon(
                Icons.Default.PlayCircle,
                null,
                tint=Color.White,
                modifier=Modifier.align(Alignment.Center).size(44.dp)
            )
            LinearProgressIndicator(
                progress={item.progress.coerceIn(0f,1f)},
                color=Color.White,
                trackColor=Color.White.copy(alpha=.22f),
                modifier=Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().height(3.dp)
            )
        }
        Text(
            item.media.title,
            fontSize=11.sp,
            fontWeight=FontWeight.Bold,
            maxLines=1,
            overflow=TextOverflow.Ellipsis,
            modifier=Modifier.padding(top=7.dp)
        )
        if(item.episodeLabel.isNotBlank()) {
            Text(item.episodeLabel,color=FqMuted,fontSize=9.sp)
        }
    }
}

@Composable
private fun MeSectionTitle(
    title:String,
    subtitle:String
) {
    Column(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=20.dp,bottom=10.dp)
    ) {
        Text(title,fontSize=18.sp,fontWeight=FontWeight.Black)
        Text(subtitle,color=FqMuted,fontSize=10.sp,modifier=Modifier.padding(top=2.dp))
    }
}

@Composable
private fun MeEmptyCard(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    body:String,
    action:String,
    onAction:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Icon(icon,null,tint=FqMuted,modifier=Modifier.size(30.dp))
            Text(
                title,
                fontSize=15.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=10.dp)
            )
            Text(
                body,
                color=FqMuted,
                fontSize=11.sp,
                lineHeight=17.sp,
                modifier=Modifier.padding(top=5.dp)
            )
            TextButton(onClick=onAction) {
                Text(action,color=Color.White,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MeQuickTools(
    kidsMode:Boolean,
    onInbox:()->Unit,
    onDownloads:()->Unit,
    onHistory:()->Unit,
    onSettings:()->Unit,
    onViewerProfiles:()->Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=22.dp)
    ) {
        Text("دسترسی سریع",fontSize=16.sp,fontWeight=FontWeight.Black)
        Row(
            Modifier.fillMaxWidth().padding(top=10.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            if(!kidsMode) {
                MeTool(Icons.Default.MarkChatUnread,"پیام‌ها",Modifier.weight(1f),onInbox)
            }
            MeTool(Icons.Default.Download,"دانلود",Modifier.weight(1f),onDownloads)
            MeTool(Icons.Default.History,"تاریخچه",Modifier.weight(1f),onHistory)
            MeTool(
                if(kidsMode) Icons.Default.SwitchAccount else Icons.Default.Settings,
                if(kidsMode)"پروفایل" else "تنظیمات",
                Modifier.weight(1f),
                if(kidsMode) onViewerProfiles else onSettings
            )
        }
    }
}

@Composable
private fun MeTool(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    label:String,
    modifier:Modifier,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=modifier.height(82.dp).clickable { onClick() }
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center
        ) {
            Icon(icon,null,tint=Color.White,modifier=Modifier.size(20.dp))
            Text(label,fontSize=9.sp,color=FqMuted,modifier=Modifier.padding(top=7.dp))
        }
    }
}

@Composable
private fun MeMoreSheet(
    profile:AccountProfile,
    onDismiss:()->Unit,
    onCreatorStudio:()->Unit,
    onSocialSaves:()->Unit,
    onViewerProfiles:()->Unit,
    onParentalControls:()->Unit,
    onSecurity:()->Unit,
    onSafety:()->Unit,
    onFollowRequests:()->Unit,
    onCloseFriends:()->Unit,
    onReputation:()->Unit,
    onSeriesCalendar:()->Unit,
    onSocialCollections:()->Unit,
    onSettings:()->Unit,
    onLogout:()->Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=28.dp)
    ) {
        Text(
            "حساب و ابزارها",
            fontSize=21.sp,
            fontWeight=FontWeight.Black,
            modifier=Modifier.padding(vertical=8.dp)
        )
        Text(
            "@"+profile.username,
            color=FqMuted,
            fontSize=11.sp,
            modifier=Modifier.padding(bottom=10.dp)
        )

        MeSheetRow(Icons.Default.Analytics,"Creator Studio",onCreatorStudio)
        MeSheetRow(Icons.Default.CollectionsBookmark,"ذخیره‌های اجتماعی",onSocialSaves)
        MeSheetRow(Icons.Default.SwitchAccount,"پروفایل‌های تماشا",onViewerProfiles)
        MeSheetRow(Icons.Default.AdminPanelSettings,"کنترل والدین",onParentalControls)
        MeSheetRow(Icons.Default.PersonAddAlt1,"درخواست‌های Follow",onFollowRequests)
        MeSheetRow(Icons.Default.Star,"Close Friends",onCloseFriends)
        MeSheetRow(Icons.Default.MilitaryTech,"Reputation و Badgeها",onReputation)
        MeSheetRow(Icons.Default.EventAvailable,"تقویم سریال‌ها",onSeriesCalendar)
        MeSheetRow(Icons.Default.CollectionsBookmark,"Club Lists",onSocialCollections)
        MeSheetRow(Icons.Default.Shield,"مرکز ایمنی",onSafety)
        MeSheetRow(Icons.Default.Security,"امنیت و دستگاه‌ها",onSecurity)
        MeSheetRow(Icons.Default.Settings,"همه تنظیمات",onSettings)

        HorizontalDivider(
            color=FqBorder,
            modifier=Modifier.padding(vertical=8.dp)
        )

        Surface(
            color=Color.Transparent,
            shape=RoundedCornerShape(16.dp),
            modifier=Modifier.fillMaxWidth().clickable { onLogout() }
        ) {
            Row(
                Modifier.padding(horizontal=12.dp,vertical=14.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Logout,null,tint=FqDanger)
                Spacer(Modifier.width(10.dp))
                Text("خروج از حساب",color=FqDanger,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MeSheetRow(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    onClick:()->Unit
) {
    Surface(
        color=Color.Transparent,
        shape=RoundedCornerShape(16.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal=12.dp,vertical=13.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(FqSurface2),
                contentAlignment=Alignment.Center
            ) {
                Icon(icon,null,tint=Color.White,modifier=Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            Text(title,fontSize=12.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted,modifier=Modifier.size(18.dp))
        }
    }
}

private fun compactMeCount(value:Long):String = when {
    value>=1_000_000 -> String.format(Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}


@Composable
fun MeSignedOutScreen(
    onLogin:()->Unit,
    onClub:()->Unit,
    onClips:()->Unit
) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF111722),
                    FqBg,
                    FqBg
                )
            )
        )
    ) {
        Column(
            Modifier.align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal=28.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(92.dp)
                    .background(Color.White,CircleShape),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    null,
                    tint=Color.Black,
                    modifier=Modifier.size(46.dp)
                )
            }

            Text(
                "پروفایل Filmiqoo تو",
                fontSize=25.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=20.dp)
            )
            Text(
                "فیلم‌هایی که می‌بینی، Reviewها، Clips، Listها و آدم‌هایی که دنبال می‌کنی همه در یک هویت سینمایی جمع می‌شن.",
                color=FqMuted,
                fontSize=12.sp,
                lineHeight=19.sp,
                modifier=Modifier.padding(top=9.dp)
            )

            Button(
                onClick=onLogin,
                colors=ButtonDefaults.buttonColors(
                    containerColor=Color.White,
                    contentColor=Color.Black
                ),
                shape=RoundedCornerShape(17.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(top=22.dp)
                    .height(52.dp)
            ) {
                Text("ورود یا ساخت حساب",fontWeight=FontWeight.Black)
            }

            Row(
                Modifier.fillMaxWidth().padding(top=10.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick=onClub,
                    shape=RoundedCornerShape(16.dp),
                    border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                    modifier=Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.Groups,null,modifier=Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Club")
                }
                OutlinedButton(
                    onClick=onClips,
                    shape=RoundedCornerShape(16.dp),
                    border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                    modifier=Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.SmartDisplay,null,modifier=Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clips")
                }
            }
        }
    }
}
