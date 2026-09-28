package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import kotlinx.coroutines.delay

private enum class ExploreCatalogFilter { ALL, MOVIES, SERIES }
private enum class ExploreLane { NONE, HOLLYWOOD, IRANIAN, KOREAN, ANIME, BOLLYWOOD }

@Composable
fun StreamingExploreScreen(
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit,
    onSearchAll:()->Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(ExploreCatalogFilter.ALL.name) }
    val filter=runCatching { ExploreCatalogFilter.valueOf(filterName) }.getOrDefault(ExploreCatalogFilter.ALL)
    var loading by remember { mutableStateOf(true) }
    var home by remember { mutableStateOf(HomeBundle()) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var laneName by rememberSaveable { mutableStateOf(ExploreLane.NONE.name) }
    val lane=runCatching { ExploreLane.valueOf(laneName) }
        .getOrDefault(ExploreLane.NONE)

    LaunchedEffect(Unit) {
        loading=true
        runCatching { repository.home() }
            .onSuccess { home=it }
            .onFailure { error=it.message }
        loading=false
    }

    LaunchedEffect(query) {
        val q=query.trim()
        if(q.length<2) {
            results=emptyList()
            return@LaunchedEffect
        }
        delay(280)
        loading=true
        error=null
        runCatching { repository.search(q) }
            .onSuccess { results=it }
            .onFailure { error=it.message }
        loading=false
    }

    val filteredResults=remember(results,filter) {
        when(filter) {
            ExploreCatalogFilter.ALL -> results
            ExploreCatalogFilter.MOVIES -> results.filter { it.type==MediaType.MOVIE }
            ExploreCatalogFilter.SERIES -> results.filter { it.type==MediaType.TV }
        }
    }

    val laneItems=remember(home,lane,filter) {
        val base=when(lane) {
            ExploreLane.NONE -> emptyList()
            ExploreLane.HOLLYWOOD -> home.popularMovies
            ExploreLane.IRANIAN -> home.iranian
            ExploreLane.KOREAN -> home.korean
            ExploreLane.ANIME -> home.anime
            ExploreLane.BOLLYWOOD -> home.bollywood
        }
        when(filter) {
            ExploreCatalogFilter.ALL -> base
            ExploreCatalogFilter.MOVIES -> base.filter { it.type==MediaType.MOVIE }
            ExploreCatalogFilter.SERIES -> base.filter { it.type==MediaType.TV }
        }
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Column(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF190507),FqBg)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal=16.dp,vertical=12.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("کشف برای تماشا",fontSize=28.sp,fontWeight=FontWeight.Black)
                    Text(
                        "از حال‌وهوات شروع کن، نه از یک لیست بی‌پایان",
                        color=FqMuted,
                        fontSize=11.sp
                    )
                }
                FqIconButton(
                    icon=Icons.Default.Tune,
                    contentDescription="جستجوی کامل",
                    onClick=onSearchAll
                )
            }

            OutlinedTextField(
                value=query,
                onValueChange={query=it.take(100)},
                singleLine=true,
                placeholder={Text("چی می‌خوای ببینی؟ عنوان، بازیگر یا اسم اصلی...")},
                leadingIcon={Icon(Icons.Default.Search,null)},
                trailingIcon={
                    if(query.isNotBlank()) {
                        IconButton(onClick={query=""}) {
                            Icon(Icons.Default.Close,null)
                        }
                    }
                },
                shape=RoundedCornerShape(20.dp),
                colors=OutlinedTextFieldDefaults.colors(
                    focusedContainerColor=FqSurface,
                    unfocusedContainerColor=FqSurface,
                    focusedBorderColor=FqGold,
                    unfocusedBorderColor=Color.White.copy(alpha=.08f)
                ),
                modifier=Modifier.fillMaxWidth().padding(top=12.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top=10.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    ExploreCatalogFilter.ALL to "همه",
                    ExploreCatalogFilter.MOVIES to "فیلم",
                    ExploreCatalogFilter.SERIES to "سریال"
                ).forEach { (item,label) ->
                    FilterChip(
                        selected=filter==item,
                        onClick={filterName=item.name},
                        label={Text(label)}
                    )
                }
            }
        }

        if(loading) {
            LinearProgressIndicator(
                color=FqGold,
                trackColor=Color.Transparent,
                modifier=Modifier.fillMaxWidth().height(2.dp)
            )
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=10.sp,
                modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp)
            )
        }

        if(query.trim().length>=2) {
            if(!loading && filteredResults.isEmpty()) {
                PremiumEmptyState(
                    icon=Icons.Default.SearchOff,
                    title="چیزی پیدا نشد",
                    body="اسم اصلی عنوان یا عبارت کوتاه‌تری رو امتحان کن."
                )
            } else {
                LazyVerticalGrid(
                    columns=GridCells.Fixed(3),
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                    horizontalArrangement=Arrangement.spacedBy(9.dp),
                    verticalArrangement=Arrangement.spacedBy(14.dp),
                    modifier=Modifier.fillMaxSize()
                ) {
                    items(filteredResults,key={it.key}) { media ->
                        ExplorePosterCard(media,repository,onMedia)
                    }
                }
            }
        } else if(lane!=ExploreLane.NONE) {
            Column(Modifier.fillMaxSize()) {
                ExploreLaneHeader(
                    lane=lane,
                    count=laneItems.size,
                    onBack={laneName=ExploreLane.NONE.name}
                )

                if(laneItems.isEmpty() && !loading) {
                    PremiumEmptyState(
                        icon=Icons.Default.MovieFilter,
                        title="چیزی در این فیلتر پیدا نشد",
                        body="فیلتر فیلم/سریال رو عوض کن یا به همه نتایج برگرد."
                    )
                } else {
                    LazyVerticalGrid(
                        columns=GridCells.Fixed(3),
                        contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                        horizontalArrangement=Arrangement.spacedBy(9.dp),
                        verticalArrangement=Arrangement.spacedBy(14.dp),
                        modifier=Modifier.fillMaxSize()
                    ) {
                        items(laneItems,key={it.key}) { media ->
                            ExplorePosterCard(media,repository,onMedia)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding=PaddingValues(bottom=26.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                if(home.trending.isNotEmpty()) {
                    item {
                        ExploreHeroStrip(
                            items=home.trending.take(6),
                            repository=repository,
                            onMedia=onMedia
                        )
                    }
                }

                item {
                    ExploreMoodGrid(
                        onHollywood={laneName=ExploreLane.HOLLYWOOD.name},
                        onIranian={laneName=ExploreLane.IRANIAN.name},
                        onKorean={laneName=ExploreLane.KOREAN.name},
                        onAnime={laneName=ExploreLane.ANIME.name},
                        onBollywood={laneName=ExploreLane.BOLLYWOOD.name}
                    )
                }

                if(home.popularMovies.isNotEmpty()) {
                    item { ExploreSectionTitle("فیلم‌های محبوب","برای یک شب سینمایی") }
                    item { ExplorePosterRail(home.popularMovies,repository,onMedia) }
                }
                if(home.popularTv.isNotEmpty()) {
                    item { ExploreSectionTitle("سریال‌های محبوب","برای وقتی یک قسمت کافی نیست") }
                    item { ExplorePosterRail(home.popularTv,repository,onMedia) }
                }
                if(home.iranian.isNotEmpty()) {
                    item { ExploreSectionTitle("سینمای ایران","فیلم و سریال فارسی") }
                    item { ExplorePosterRail(home.iranian,repository,onMedia) }
                }
                if(home.korean.isNotEmpty()) {
                    item { ExploreSectionTitle("کره‌ای","درام، اکشن و عاشقانه") }
                    item { ExplorePosterRail(home.korean,repository,onMedia) }
                }
                if(home.anime.isNotEmpty()) {
                    item { ExploreSectionTitle("انیمه","محبوب و تازه") }
                    item { ExplorePosterRail(home.anime,repository,onMedia) }
                }
                if(home.bollywood.isNotEmpty()) {
                    item { ExploreSectionTitle("سینمای هند","بالیوود و فراتر از آن") }
                    item { ExplorePosterRail(home.bollywood,repository,onMedia) }
                }
            }
        }
    }
}

@Composable
private fun ExploreHeroStrip(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(items,key={it.key}) { media ->
            Box(
                Modifier.width(300.dp).height(170.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onMedia(media) }
            ) {
                RemoteImage(
                    repository.backdrop(media.backdropPath ?: media.posterPath),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f)))
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(14.dp)
                ) {
                    Text(media.title,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Black,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(
                        listOf(media.year,if(media.vote>0)"★ "+formatVote(media.vote) else "").filter(String::isNotBlank).joinToString(" • "),
                        color=Color.White.copy(alpha=.72f),
                        fontSize=9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ExploreLaneHeader(
    lane:ExploreLane,
    count:Int,
    onBack:()->Unit
) {
    val (title,subtitle)=when(lane) {
        ExploreLane.HOLLYWOOD -> "هالیوود" to "فیلم‌های محبوب و جریان اصلی"
        ExploreLane.IRANIAN -> "سینمای ایران" to "فیلم و سریال فارسی‌زبان"
        ExploreLane.KOREAN -> "کره‌ای" to "K-Drama، فیلم و سریال کره‌ای"
        ExploreLane.ANIME -> "انیمه" to "انیمه‌های محبوب و تازه"
        ExploreLane.BOLLYWOOD -> "سینمای هند" to "بالیوود و سینمای هند"
        ExploreLane.NONE -> "کشف" to ""
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Surface(
            color=FqSurface,
            contentColor=Color.White,
            shape=CircleShape,
            modifier=Modifier.size(42.dp).clickable(onClick=onBack)
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(Icons.Default.ArrowBack,null,modifier=Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=20.sp,fontWeight=FontWeight.Black)
            Text(
                subtitle+" • "+count+" عنوان",
                color=FqMuted,
                fontSize=9.sp,
                modifier=Modifier.padding(top=2.dp)
            )
        }
    }
}

@Composable
private fun ExploreMoodGrid(
    onHollywood:()->Unit,
    onIranian:()->Unit,
    onKorean:()->Unit,
    onAnime:()->Unit,
    onBollywood:()->Unit
) {
    Column(Modifier.padding(horizontal=16.dp,vertical=12.dp)) {
        Text("از یک دنیا شروع کن",fontSize=18.sp,fontWeight=FontWeight.Black)
        Text("یک مسیر سریع برای رسیدن به چیزی که همین الان حالش رو داری",color=FqMuted,fontSize=10.sp)
        Row(
            Modifier.fillMaxWidth().padding(top=12.dp),
            horizontalArrangement=Arrangement.spacedBy(9.dp)
        ) {
            ExploreMoodCard(
                "هالیوود",
                "فیلم‌های محبوب",
                Icons.Default.LocalMovies,
                onHollywood,
                Modifier.weight(1f)
            )
            ExploreMoodCard("ایرانی","فیلم و سریال فارسی",Icons.Default.Movie,onIranian,Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(top=9.dp),
            horizontalArrangement=Arrangement.spacedBy(9.dp)
        ) {
            ExploreMoodCard("کره‌ای","K-Drama و بیشتر",Icons.Default.LiveTv,onKorean,Modifier.weight(1f))
            ExploreMoodCard("انیمه","ژاپن و آسیا",Icons.Default.Animation,onAnime,Modifier.weight(1f))
        }
        ExploreMoodCard(
            "هندی",
            "بالیوود و سینمای هند",
            Icons.Default.Theaters,
            onBollywood,
            Modifier.fillMaxWidth().padding(top=9.dp)
        )
    }
}

@Composable
private fun ExploreMoodCard(
    title:String,
    subtitle:String,
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    onClick:()->Unit,
    modifier:Modifier=Modifier
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.07f)),
        modifier=modifier.height(96.dp).clickable(onClick=onClick)
    ) {
        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.Center) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(20.dp))
            Text(title,fontSize=12.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=8.dp))
            Text(subtitle,color=FqMuted,fontSize=8.sp)
        }
    }
}

@Composable
private fun ExploreSectionTitle(title:String,subtitle:String) {
    Column(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=24.dp,bottom=10.dp)) {
        Text(title,fontSize=18.sp,fontWeight=FontWeight.Black)
        Text(subtitle,color=FqMuted,fontSize=10.sp)
    }
}

@Composable
private fun ExplorePosterRail(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(items.take(20),key={it.key}) { media ->
            ExplorePosterCard(media,repository,onMedia)
        }
    }
}

@Composable
private fun ExplorePosterCard(
    media:MediaItem,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    Column(
        Modifier.width(124.dp).clickable { onMedia(media) }
    ) {
        Box(
            Modifier.fillMaxWidth().height(184.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            RemoteImage(
                repository.poster(media.posterPath),
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            if(media.streamReady) {
                Surface(
                    color=FqGold,
                    shape=RoundedCornerShape(7.dp),
                    modifier=Modifier.align(Alignment.TopStart).padding(6.dp)
                ) {
                    Text(
                        media.quality.ifBlank{"PLAY"},
                        color=Color.White,
                        fontSize=7.sp,
                        fontWeight=FontWeight.Black,
                        modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp)
                    )
                }
            }
        }
        Text(media.title,fontSize=10.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp))
        Text(
            listOf(media.year,if(media.vote>0)"★ "+formatVote(media.vote) else "").filter(String::isNotBlank).joinToString(" • "),
            color=FqMuted,
            fontSize=8.sp,
            maxLines=1
        )
    }
}
