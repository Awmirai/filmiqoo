package com.filmiqoo.app

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf<String>("همه") }

    LaunchedEffect(Unit) {
        suggestions = runCatching { repository.trending() }.getOrDefault(emptyList())
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            loading=false
            return@LaunchedEffect
        }
        delay(450)
        loading=true
        results = runCatching { repository.search(query) }.getOrDefault(emptyList())
        loading=false
    }

    Column(Modifier.fillMaxSize()) {
        BrandTopBar()
        OutlinedTextField(
            value=query,
            onValueChange={query=it},
            singleLine=true,
            placeholder={Text("فیلم، سریال، بازیگر...")},
            leadingIcon={Icon(Icons.Default.Search,null)},
            trailingIcon={
                if(query.isNotBlank()) {
                    IconButton({query=""}) { Icon(Icons.Default.Close,null) }
                }
            },
            shape=RoundedCornerShape(18.dp),
            modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)
        )
        LazyRow(
            contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            items(listOf("همه","فیلم","سریال")) { label ->
                FilterChip(
                    selected=type==label,
                    onClick={type=label},
                    label={Text(label)}
                )
            }
        }
        if (loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }
        val source = if(query.isBlank()) suggestions else results
        val filtered = source.filter {
            type=="همه" || (type=="فیلم" && it.type==MediaType.MOVIE) || (type=="سریال" && it.type==MediaType.TV)
        }
        if (source.isEmpty() && query.isNotBlank() && !loading) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Text("نتیجه‌ای پیدا نشد",color=FqMuted)
            }
        } else {
            LazyVerticalGrid(
                columns=GridCells.Fixed(3),
                contentPadding=PaddingValues(horizontal=12.dp,bottom=30.dp),
                horizontalArrangement=Arrangement.spacedBy(9.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                items(filtered,key={it.key}) { m ->
                    Column(Modifier.clickable { onMedia(m) }) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(14.dp))
                        ) {
                            RemoteImage(repository.poster(m.posterPath),Modifier.fillMaxSize())
                            Surface(
                                color=Color.Black.copy(alpha=.6f),
                                shape=RoundedCornerShape(7.dp),
                                modifier=Modifier.align(Alignment.TopStart).padding(6.dp)
                            ) {
                                Text(formatVote(m.vote),fontSize=8.sp,color=FqGold,modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp))
                            }
                        }
                        Text(m.title,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                        Text(m.year,color=FqMuted,fontSize=9.sp)
                    }
                }
            }
        }
    }
}

private sealed interface DetailLoad {
    data object Loading : DetailLoad
    data class Ready(val detail: MediaDetail) : DetailLoad
    data class Error(val message: String) : DetailLoad
}

@Composable
fun DetailScreen(
    media: MediaItem,
    repository: TmdbRepository,
    store: LocalStore,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onChat: (MediaItem) -> Unit,
    onWatchParty: (MediaItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(media.key) { mutableStateOf<DetailLoad>(DetailLoad.Loading) }
    var favorite by remember(media.key) { mutableStateOf(store.contains("favorites",media.key)) }
    var downloaded by remember(media.key) { mutableStateOf(store.contains("downloads",media.key)) }
    var tab by remember(media.key) { mutableIntStateOf(0) }

    LaunchedEffect(media.key) {
        state = runCatching { DetailLoad.Ready(repository.detail(media)) }
            .getOrElse { DetailLoad.Error(it.message ?: "خطا") }
    }

    when(val s=state) {
        DetailLoad.Loading -> {
            Box(Modifier.fillMaxSize()) {
                LoadingPage()
                IconButton(onClick=onBack,modifier=Modifier.padding(10.dp)) { Icon(Icons.Default.ArrowBack,null) }
            }
        }
        is DetailLoad.Error -> {
            Box(Modifier.fillMaxSize()) {
                ErrorPage(s.message) {
                    state=DetailLoad.Loading
                    scope.launch {
                        state=runCatching { DetailLoad.Ready(repository.detail(media)) }
                            .getOrElse { DetailLoad.Error(it.message?:"خطا") }
                    }
                }
                IconButton(onClick=onBack,modifier=Modifier.padding(10.dp)) { Icon(Icons.Default.ArrowBack,null) }
            }
        }
        is DetailLoad.Ready -> {
            val d=s.detail
            val tabs = if(d.media.type==MediaType.TV) listOf("درباره","فصل‌ها","بازیگران","جامعه") else listOf("درباره","بازیگران","جامعه","مشابه")
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Box(Modifier.fillMaxWidth().height(520.dp)) {
                        RemoteImage(
                            repository.backdrop(d.media.backdropPath ?: d.media.posterPath),
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha=.08f),Color.Transparent,Color.Black.copy(alpha=.95f))
                                )
                            )
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick=onBack,
                                modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.45f))
                            ) { Icon(Icons.Default.ArrowBack,null) }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick={shareText(context,"Filmiqoo • "+d.media.title)},
                                modifier=Modifier.clip(CircleShape).background(Color.Black.copy(alpha=.45f))
                            ) { Icon(Icons.Default.Share,null) }
                        }
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            Text(d.media.title,fontSize=30.sp,fontWeight=FontWeight.Bold)
                            if(d.tagline.isNotBlank()) {
                                Text(d.tagline,color=FqGoldSoft,fontSize=11.sp,modifier=Modifier.padding(top=4.dp))
                            }
                            Row(
                                Modifier.padding(top=9.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                MetricPill(Icons.Default.Star,"IMDb "+formatVote(d.media.vote))
                                Spacer(Modifier.width(6.dp))
                                if(d.media.year.isNotBlank()) MetricPill(Icons.Default.CalendarMonth,d.media.year)
                                Spacer(Modifier.width(6.dp))
                                if(d.runtime>0) MetricPill(Icons.Default.Schedule,d.runtime.toString()+" دقیقه")
                            }
                            LazyRow(
                                horizontalArrangement=Arrangement.spacedBy(6.dp),
                                modifier=Modifier.padding(top=10.dp)
                            ) {
                                items(d.genres.take(5)) { g ->
                                    Surface(color=FqSurface2.copy(alpha=.82f),shape=RoundedCornerShape(10.dp)) {
                                        Text(g,fontSize=9.sp,modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp))
                                    }
                                }
                            }
                            Row(Modifier.padding(top=15.dp)) {
                                Button(
                                    onClick={
                                        d.trailerKey?.let { openYoutube(context,it) }
                                    },
                                    enabled=d.trailerKey!=null,
                                    colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                                    shape=RoundedCornerShape(13.dp),
                                    modifier=Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow,null)
                                    Spacer(Modifier.width(5.dp))
                                    Text(if(d.trailerKey!=null)"پخش تریلر" else "تریلر موجود نیست")
                                }
                                Spacer(Modifier.width(8.dp))
                                FilledTonalIconButton(
                                    onClick={favorite=store.toggle("favorites",media.key)},
                                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=FqSurface2)
                                ) {
                                    Icon(if(favorite)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,null,tint=if(favorite)FqGold else Color.White)
                                }
                            }
                            Row(Modifier.padding(top=8.dp)) {
                                OutlinedButton(
                                    onClick={downloaded=store.toggle("downloads",media.key)},
                                    modifier=Modifier.weight(1f),
                                    shape=RoundedCornerShape(12.dp)
                                ) {
                                    Icon(if(downloaded)Icons.Default.DownloadDone else Icons.Default.Download,null)
                                    Spacer(Modifier.width(5.dp))
                                    Text(if(downloaded)"برای دانلود ذخیره شد" else "دانلود")
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick={onWatchParty(d.media)},
                                    modifier=Modifier.weight(1f),
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
                item {
                    ScrollableTabRow(
                        selectedTabIndex=tab,
                        containerColor=FqBg,
                        contentColor=FqGold,
                        edgePadding=12.dp
                    ) {
                        tabs.forEachIndexed { index,label ->
                            Tab(
                                selected=tab==index,
                                onClick={tab=index},
                                text={Text(label)}
                            )
                        }
                    }
                }

                when {
                    tab==0 -> {
                        item {
                            Column(Modifier.padding(18.dp)) {
                                Text("داستان",fontSize=20.sp)
                                Text(
                                    d.media.overview.ifBlank { "توضیح فارسی برای این عنوان در TMDB ثبت نشده است." },
                                    color=Color.White.copy(alpha=.82f),
                                    fontSize=12.sp,
                                    lineHeight=22.sp,
                                    modifier=Modifier.padding(top=9.dp)
                                )
                                Row(Modifier.padding(top=18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    MetricPill(Icons.Default.HighQuality,"4K")
                                    MetricPill(Icons.Default.HdrOn,"HDR")
                                    MetricPill(Icons.Default.Subtitles,"FA / EN")
                                    MetricPill(Icons.Default.SurroundSound,"5.1")
                                }
                            }
                        }
                        item { SectionHeader("بازیگران","چهره‌های اصلی") }
                        item { CastRow(d.cast,repository) }
                        if(d.recommendations.isNotEmpty()) {
                            item { SectionHeader("پیشنهادهای مشابه") }
                            item {
                                LazyRow(
                                    contentPadding=PaddingValues(horizontal=16.dp),
                                    horizontalArrangement=Arrangement.spacedBy(12.dp)
                                ) {
                                    items(d.recommendations.take(14),key={it.key}) { rec ->
                                        PosterCard(rec,repository,{onMedia(rec)})
                                    }
                                }
                            }
                        }
                    }
                    d.media.type==MediaType.TV && tab==1 -> {
                        item { SectionHeader("فصل‌ها",d.seasons.size.toString()+" فصل ثبت شده") }
                        items(d.seasons,key={it.number}) { season ->
                            SeasonRow(season,repository)
                        }
                    }
                    (d.media.type==MediaType.TV && tab==2) || (d.media.type==MediaType.MOVIE && tab==1) -> {
                        item { SectionHeader("بازیگران","اطلاعات واقعی TMDB") }
                        items(d.cast,key={it.id}) { actor ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=7.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                RemoteImage(repository.profile(actor.profilePath),Modifier.size(62.dp).clip(CircleShape))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(actor.name,fontSize=14.sp)
                                    Text(actor.character,color=FqMuted,fontSize=10.sp)
                                }
                            }
                        }
                    }
                    (d.media.type==MediaType.TV && tab==3) || (d.media.type==MediaType.MOVIE && tab==2) -> {
                        item {
                            CommunityPanel(d.media,onChat)
                        }
                    }
                    else -> {
                        item { SectionHeader("مشابه") }
                        item {
                            LazyVerticalGrid(
                                columns=GridCells.Fixed(3),
                                contentPadding=PaddingValues(12.dp),
                                horizontalArrangement=Arrangement.spacedBy(9.dp),
                                verticalArrangement=Arrangement.spacedBy(12.dp),
                                modifier=Modifier.heightIn(max=900.dp)
                            ) {
                                items(d.recommendations.take(18),key={it.key}) { rec ->
                                    PosterCard(rec,repository,{onMedia(rec)},Modifier.width(120.dp))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun CastRow(cast: List<CastMember>, repository: TmdbRepository) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(cast,key={it.id}) { actor ->
            Column(Modifier.width(82.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                RemoteImage(repository.profile(actor.profilePath),Modifier.size(76.dp).clip(CircleShape))
                Text(actor.name,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp))
                Text(actor.character,color=FqMuted,fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SeasonRow(season: SeasonInfo, repository: TmdbRepository) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=7.dp)
            .clip(RoundedCornerShape(18.dp)).background(FqSurface).padding(10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        RemoteImage(repository.poster(season.posterPath),Modifier.width(78.dp).height(110.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(season.name,fontSize=16.sp)
            Text(season.episodes.toString()+" قسمت",color=FqGold,fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
            if(season.airDate.isNotBlank()) Text(season.airDate,color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(top=4.dp))
            Text("برای نسخه نهایی، اپیزودها و کیفیت‌های تلگرام زیر همین فصل قرار می‌گیرند.",color=FqMuted,fontSize=9.sp,lineHeight=15.sp,modifier=Modifier.padding(top=7.dp))
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
    }
}

@Composable
private fun CommunityPanel(media: MediaItem,onChat:(MediaItem)->Unit) {
    Column(Modifier.padding(16.dp)) {
        Surface(color=FqSurface,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(FqGold),contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.Forum,null,tint=Color.Black)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("روم رسمی "+media.title,fontSize=15.sp)
                        Text("12.8K عضو • 846 آنلاین",color=FqMuted,fontSize=9.sp)
                    }
                    Button(
                        onClick={onChat(media)},
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)
                    ) { Text("ورود",fontSize=10.sp) }
                }
                HorizontalDivider(color=FqSurface3,modifier=Modifier.padding(vertical=14.dp))
                Text("Nima",color=FqGold,fontSize=10.sp)
                Text("این قسمت رو دیدین؟ پایانش خیلی بحث‌برانگیز بود 😳",fontSize=11.sp,modifier=Modifier.padding(top=4.dp))
                Text("Sara",color=FqGold,fontSize=10.sp,modifier=Modifier.padding(top=12.dp))
                Surface(color=FqDanger.copy(alpha=.12f),shape=RoundedCornerShape(10.dp),modifier=Modifier.padding(top=4.dp)) {
                    Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning,null,tint=FqDanger,modifier=Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("این پیام دارای اسپویل است",fontSize=10.sp)
                    }
                }
            }
        }
        SectionHeader("نقدهای کاربران")
        repeat(3) { i ->
            Surface(color=FqSurface,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                Column(Modifier.padding(13.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(listOf("Armin","Saba","Nima")[i],fontSize=11.sp)
                        Spacer(Modifier.weight(1f))
                        Text("★ "+listOf("9.0","8.5","8.0")[i],color=FqGold,fontSize=10.sp)
                    }
                    Text("یکی از بهترین تجربه‌های این ژانر؛ فضاسازی و بازی‌ها خیلی خوب بود.",fontSize=10.sp,color=Color.White.copy(alpha=.82f),modifier=Modifier.padding(top=6.dp))
                }
            }
        }
    }
}

@Composable
fun StoryViewer(
    media: MediaItem,
    repository: TmdbRepository,
    onClose: () -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RemoteImage(
            repository.backdrop(media.backdropPath ?: media.posterPath),
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha=.5f),Color.Transparent,Color.Black.copy(alpha=.78f)))
            )
        )
        LinearProgressIndicator(
            progress={.72f},
            color=Color.White,
            trackColor=Color.White.copy(alpha=.2f),
            modifier=Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp).height(3.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal=13.dp,vertical=18.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(FqGold),contentAlignment=Alignment.Center) {
                Text("C",color=Color.Black)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("CineVerse",fontSize=12.sp)
                Text("2 دقیقه پیش",color=Color.White.copy(alpha=.65f),fontSize=8.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick=onClose) { Icon(Icons.Default.Close,null) }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text("پیشنهاد امروز",color=FqGold,fontSize=12.sp)
            Text(media.title,fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=5.dp))
            Text("اگر هنوز ندیدیش، امشب وقتشه 🍿",fontSize=12.sp,modifier=Modifier.padding(top=7.dp))
            Button(
                onClick={onMedia(media)},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                shape=RoundedCornerShape(14.dp),
                modifier=Modifier.padding(top=14.dp)
            ) {
                Icon(Icons.Default.Movie,null)
                Spacer(Modifier.width(6.dp))
                Text("مشاهده صفحه فیلم")
            }
            OutlinedTextField(
                value="",
                onValueChange={},
                readOnly=true,
                placeholder={Text("پاسخ به استوری...")},
                trailingIcon={Icon(Icons.Default.FavoriteBorder,null)},
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.fillMaxWidth().padding(top=16.dp)
            )
        }
    }
}

@Composable
fun NotificationsScreen(onBack:()->Unit) {
    val rows = listOf(
        Triple(Icons.Default.Favorite,"CineVerse پست شما را پسندید.","2 دقیقه"),
        Triple(Icons.Default.PersonAdd,"Nima شما را دنبال کرد.","12 دقیقه"),
        Triple(Icons.Default.Movie,"قسمت جدید یک سریال دنبال‌شده اضافه شد.","1 ساعت"),
        Triple(Icons.Default.Groups,"Watch Party تا 15 دقیقه دیگر شروع می‌شود.","امروز"),
        Triple(Icons.Default.ChatBubble,"Sara به کامنت شما پاسخ داد.","امروز"),
        Triple(Icons.Default.TrendingUp,"Reel شما از 10K بازدید عبور کرد.","دیروز")
    )
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
                Text("اعلان‌ها",fontSize=22.sp)
            }
        }
        items(rows) { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp)
                    .clip(RoundedCornerShape(16.dp)).background(FqSurface).padding(13.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(FqSurface2),contentAlignment=Alignment.Center) {
                    Icon(row.first,null,tint=FqGold)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.second,fontSize=11.sp)
                    Text(row.third,color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(top=4.dp))
                }
            }
        }
    }
}
