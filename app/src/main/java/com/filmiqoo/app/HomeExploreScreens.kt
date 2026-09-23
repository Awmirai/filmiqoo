package com.filmiqoo.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private sealed interface HomeLoad {
    data object Loading : HomeLoad
    data class Ready(val data: HomeBundle) : HomeLoad
    data class Error(val message: String) : HomeLoad
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    repository: TmdbRepository,
    backend: BackendRepository,
    loggedIn: Boolean,
    onMedia: (MediaItem) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onStory: (MediaItem, Int) -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onWatchParty: (MediaItem?) -> Unit
) {
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<HomeLoad>(HomeLoad.Loading) }
    var continueItems by remember { mutableStateOf<List<ContinueWatchingItem>>(emptyList()) }

    LaunchedEffect(reload,loggedIn) {
        if(loggedIn) {
            continueItems=runCatching { backend.continueWatching() }.getOrDefault(emptyList())
        } else {
            continueItems=emptyList()
        }
        state = HomeLoad.Loading
        state = runCatching { HomeLoad.Ready(repository.home()) }
            .getOrElse { HomeLoad.Error(it.message ?: "خطای ناشناخته") }
    }

    when(val s = state) {
        HomeLoad.Loading -> LoadingPage()
        is HomeLoad.Error -> ErrorPage(s.message) { reload++ }
        is HomeLoad.Ready -> {
            val data = s.data
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    BrandTopBar(onSearch=onSearch,onNotifications=onNotifications)
                }
                if (data.trending.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding=PaddingValues(horizontal=16.dp),
                            horizontalArrangement=Arrangement.spacedBy(10.dp)
                        ) {
                            items(data.trending.take(9)) { m ->
                                StoryBubble(m,repository) { onStory(m,data.trending.indexOf(m)) }
                            }
                        }
                    }
                    item {
                        HeroCarousel(data.trending.take(6),repository,onMedia,onWatchParty)
                    }
                }
                item {
                    SectionHeader(
                        "ادامه تماشا",
                        if(continueItems.isNotEmpty()) "همگام با حساب Filmiqoo" else "جایی که رها کردی برگرد"
                    )
                }
                item {
                    if(continueItems.isNotEmpty()) {
                        ContinueWatchingRealRow(continueItems,repository,onPlay)
                    } else {
                        ContinueWatchingRow(data.popularTv.take(5),repository,onMedia)
                    }
                }
                item { SectionHeader("ترند امروز","محبوب‌ترین‌های همین حالا") }
                item { MediaRow(data.trending,repository,onMedia) }
                item { SectionHeader("فیلم‌های محبوب","انتخاب‌های پرطرفدار") }
                item { MediaRow(data.popularMovies,repository,onMedia) }
                item { SectionHeader("سریال‌های محبوب","برای یک شب طولانی") }
                item { MediaRow(data.popularTv,repository,onMedia) }
                item { SectionHeader("سینمای ایران","فیلم و سریال ایرانی") }
                item { MediaRow(data.iranian,repository,onMedia) }
                item { SectionHeader("K-Drama","سریال‌های کره‌ای") }
                item { MediaRow(data.korean,repository,onMedia) }
                item { SectionHeader("انیمه","محبوب‌ترین انیمه‌ها") }
                item { MediaRow(data.anime,repository,onMedia) }
                item { SectionHeader("بالیوود","سینمای هند") }
                item { MediaRow(data.bollywood,repository,onMedia) }
                item { Spacer(Modifier.height(36.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCarousel(
    list: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit,
    onWatchParty: (MediaItem?) -> Unit
) {
    val pager = rememberPagerState(pageCount={list.size})
    Column(Modifier.padding(top=12.dp)) {
        HorizontalPager(
            state=pager,
            contentPadding=PaddingValues(horizontal=16.dp),
            pageSpacing=10.dp,
            modifier=Modifier.height(390.dp)
        ) { page ->
            val media = list[page]
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))
                    .clickable { onMedia(media) }
            ) {
                RemoteImage(repository.backdrop(media.backdropPath ?: media.posterPath),Modifier.fillMaxSize(),ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha=.18f),
                                Color.Black.copy(alpha=.88f)
                            )
                        )
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(20.dp)
                ) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        MetricPill(Icons.Default.Star,"IMDb " + formatVote(media.vote))
                        Spacer(Modifier.width(7.dp))
                        MetricPill(Icons.Default.HighQuality,"HD / 4K")
                    }
                    Text(
                        media.title,
                        fontSize=28.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=10.dp)
                    )
                    Text(
                        listOf(media.year,if(media.type==MediaType.MOVIE)"فیلم" else "سریال").filter{it.isNotBlank()}.joinToString(" • "),
                        color=FqMuted,
                        fontSize=12.sp,
                        modifier=Modifier.padding(top=6.dp)
                    )
                    if (media.overview.isNotBlank()) {
                        Text(
                            media.overview,
                            color=Color.White.copy(alpha=.82f),
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            fontSize=11.sp,
                            lineHeight=19.sp,
                            modifier=Modifier.padding(top=8.dp)
                        )
                    }
                    Row(Modifier.padding(top=14.dp)) {
                        Button(
                            onClick={onMedia(media)},
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            shape=RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow,null)
                            Spacer(Modifier.width(5.dp))
                            Text("مشاهده")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick={onWatchParty(media)},
                            shape=RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Groups,null)
                            Spacer(Modifier.width(5.dp))
                            Text("Watch Party")
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top=8.dp),
            horizontalArrangement=Arrangement.Center
        ) {
            repeat(list.size) { i ->
                Box(
                    Modifier.padding(horizontal=3.dp)
                        .width(if(i==pager.currentPage) 20.dp else 6.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if(i==pager.currentPage) FqGold else FqMuted.copy(alpha=.35f))
                )
            }
        }
    }
}

@Composable
private fun MediaRow(
    list: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(list.take(20),key={it.key}) { m ->
            PosterCard(m,repository,{onMedia(m)})
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    list: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(list,key={it.key}) { m ->
            Column(Modifier.width(230.dp).clickable { onMedia(m) }) {
                Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(18.dp))) {
                    RemoteImage(repository.backdrop(m.backdropPath ?: m.posterPath),Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.78f)))))
                    Icon(Icons.Default.PlayCircle,null,tint=Color.White,modifier=Modifier.size(48.dp).align(Alignment.Center))
                    Text(m.title,Modifier.align(Alignment.BottomStart).padding(11.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                LinearProgressIndicator(
                    progress={.35f + ((m.id % 50) / 100f)},
                    color=FqGold,
                    trackColor=FqSurface2,
                    modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(3.dp)
                )
                Text("ادامه از " + (15 + m.id%35) + " دقیقه",color=FqMuted,fontSize=10.sp,modifier=Modifier.padding(top=5.dp))
            }
        }
    }
}

@Composable
private fun ContinueWatchingRealRow(
    list: List<ContinueWatchingItem>,
    repository: TmdbRepository,
    onPlay: (PlaybackTarget) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(list,key={it.target.mediaVersionId}) { item ->
            Column(Modifier.width(240.dp).clickable { onPlay(item.target) }) {
                Box(Modifier.fillMaxWidth().height(135.dp).clip(RoundedCornerShape(18.dp))) {
                    RemoteImage(
                        repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                        Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.82f)))
                        )
                    )
                    Icon(
                        Icons.Default.PlayCircle,null,tint=Color.White,
                        modifier=Modifier.size(50.dp).align(Alignment.Center)
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(
                            item.media.title,
                            maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp
                        )
                        if(item.episodeLabel.isNotBlank()) {
                            Text(
                                item.episodeLabel,
                                color=Color.White.copy(alpha=.7f),
                                maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=8.sp
                            )
                        }
                    }
                }
                LinearProgressIndicator(
                    progress={item.progress},
                    color=FqGold,
                    trackColor=FqSurface2,
                    modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(4.dp)
                )
                Text(
                    (item.progress*100).toInt().toString()+"% تماشا شده",
                    color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(top=4.dp)
                )
            }
        }
    }
}

private sealed interface ExploreLoad {
    data object Loading : ExploreLoad
    data class Ready(val items: List<MediaItem>) : ExploreLoad
    data class Error(val message: String) : ExploreLoad
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExploreScreen(
    repository: TmdbRepository,
    store: LocalStore,
    onMedia: (MediaItem) -> Unit,
    onChat: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit
) {
    var load by remember { mutableStateOf<ExploreLoad>(ExploreLoad.Loading) }
    var followingOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        load = runCatching { ExploreLoad.Ready(repository.trending()) }
            .getOrElse { ExploreLoad.Error(it.message ?: "خطا") }
    }

    when(val s=load) {
        ExploreLoad.Loading -> LoadingPage("در حال ساخت اکسپلور شخصی...")
        is ExploreLoad.Error -> ErrorPage(s.message) {
            load = ExploreLoad.Loading
        }
        is ExploreLoad.Ready -> {
            val source = if (followingOnly) s.items.filterIndexed { index, _ -> index % 2 == 0 } else s.items
            if (source.isEmpty()) {
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("محتوایی نیست") }
            } else {
                val pager = rememberPagerState(pageCount={source.size})
                Box(Modifier.fillMaxSize()) {
                    VerticalPager(state=pager,modifier=Modifier.fillMaxSize()) { page ->
                        ExploreReel(
                            media=source[page],
                            index=page,
                            repository=repository,
                            store=store,
                            onMedia=onMedia,
                            onChat=onChat,
                            onCreator=onCreator
                        )
                    }
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top=12.dp)
                            .background(Color.Black.copy(alpha=.52f),RoundedCornerShape(18.dp))
                            .padding(4.dp)
                    ) {
                        FilterChip(
                            selected=!followingOnly,
                            onClick={followingOnly=false},
                            label={Text("برای تو")},
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=FqGold,
                                selectedLabelColor=Color.Black,
                                containerColor=Color.Transparent
                            )
                        )
                        Spacer(Modifier.width(5.dp))
                        FilterChip(
                            selected=followingOnly,
                            onClick={followingOnly=true},
                            label={Text("دنبال‌شده‌ها")},
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=FqGold,
                                selectedLabelColor=Color.Black,
                                containerColor=Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreReel(
    media: MediaItem,
    index: Int,
    repository: TmdbRepository,
    store: LocalStore,
    onMedia: (MediaItem) -> Unit,
    onChat: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val creators = remember {
        listOf(
            Creator("CineVerse","@cineverse","1.3M","نقد، خبر و پیشنهاد فیلم و سریال"),
            Creator("AnimeHub","@animehub","842K","انیمه، مانگا و فرهنگ ژاپن"),
            Creator("K-Drama Land","@kdrama","520K","خانه طرفدارهای K-Drama"),
            Creator("Film News","@filmnews","430K","خبرهای تازه سینما و تلویزیون")
        )
    }
    val creator = creators[index % creators.size]
    var liked by remember(media.key) { mutableStateOf(store.contains("likes",media.key)) }
    var saved by remember(media.key) { mutableStateOf(store.contains("saved_reels",media.key)) }
    var followed by remember(creator.handle) { mutableStateOf(store.contains("follows",creator.handle)) }
    var trailerLoading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RemoteImage(
            repository.backdrop(media.backdropPath ?: media.posterPath),
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha=.12f),
                        Color.Transparent,
                        Color.Black.copy(alpha=.86f)
                    )
                )
            )
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(start=17.dp,end=82.dp,bottom=24.dp)
        ) {
            Row(
                verticalAlignment=Alignment.CenterVertically,
                modifier=Modifier.clickable { onCreator(creator) }
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(FqGold),
                    contentAlignment=Alignment.Center
                ) {
                    Text(creator.name.take(1),color=Color.Black,fontSize=18.sp)
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(creator.name,fontSize=14.sp)
                        if (creator.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(15.dp))
                        }
                    }
                    Text(creator.handle,color=Color.White.copy(alpha=.7f),fontSize=9.sp)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick={
                        followed=store.toggle("follows",creator.handle)
                    },
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=3.dp),
                    modifier=Modifier.height(32.dp),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=if(followed)FqGold else Color.White)
                ) {
                    Text(if(followed)"دنبال می‌کنی" else "دنبال کردن",fontSize=9.sp)
                }
            }
            Text(
                media.title,
                fontSize=23.sp,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.padding(top=13.dp)
            )
            Text(
                "گاهی یک سکانس کافی است تا کل نگاهت به یک فیلم عوض شود.  #فیلمیکو #سینما #پیشنهاد",
                color=Color.White.copy(alpha=.9f),
                fontSize=11.sp,
                lineHeight=19.sp,
                maxLines=3,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=7.dp)
            )
            Surface(
                color=Color.Black.copy(alpha=.5f),
                shape=RoundedCornerShape(14.dp),
                modifier=Modifier.padding(top=12.dp).clickable { onMedia(media) }
            ) {
                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Movie,null,tint=FqGold,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(media.title,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("IMDb " + formatVote(media.vote) + " • " + media.year,color=FqMuted,fontSize=9.sp)
                    }
                    Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
                }
            }
            Button(
                onClick={
                    trailerLoading=true
                    scope.launch {
                        runCatching { repository.detail(media).trailerKey }
                            .getOrNull()
                            ?.let { openYoutube(context,it) }
                        trailerLoading=false
                    }
                },
                modifier=Modifier.padding(top=10.dp),
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                shape=RoundedCornerShape(12.dp)
            ) {
                if (trailerLoading) {
                    CircularProgressIndicator(color=Color.Black,strokeWidth=2.dp,modifier=Modifier.size(16.dp))
                } else {
                    Icon(Icons.Default.PlayArrow,null)
                }
                Spacer(Modifier.width(5.dp))
                Text("پخش تریلر")
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end=13.dp,bottom=28.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            ReelAction(Icons.Default.Favorite,if(liked)FqDanger else Color.White,""+(82+index*7)+"K") {
                liked=store.toggle("likes",media.key)
            }
            ReelAction(Icons.Default.ChatBubble,Color.White,""+(1+index)+".2K") { onChat(media) }
            ReelAction(Icons.Default.Bookmark,if(saved)FqGold else Color.White,"ذخیره") {
                saved=store.toggle("saved_reels",media.key)
            }
            ReelAction(Icons.Default.Share,Color.White,"اشتراک") {
                shareText(context,"Filmiqoo • " + media.title)
            }
        }
    }
}

@Composable
private fun ReelAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment=Alignment.CenterHorizontally,
        modifier=Modifier.clickable { onClick() }.padding(vertical=8.dp)
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha=.42f)),
            contentAlignment=Alignment.Center
        ) {
            Icon(icon,null,tint=tint,modifier=Modifier.size(26.dp))
        }
        Text(label,fontSize=9.sp,modifier=Modifier.padding(top=4.dp))
    }
}
