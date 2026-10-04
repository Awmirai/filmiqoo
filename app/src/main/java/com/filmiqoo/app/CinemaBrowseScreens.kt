package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

@Composable
fun CinemaDiscoverScreen(
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit,
    onSearchAll: () -> Unit,
    initialRegion: CinemaRegion = CinemaRegion.IRAN,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val catalog = remember { CinemaDataRepository(context) }
    var regionName by rememberSaveable(initialRegion) { mutableStateOf(initialRegion.name) }
    var typeName by rememberSaveable { mutableStateOf(MediaType.MOVIE.name) }
    var eraName by rememberSaveable { mutableStateOf(CinemaEra.ALL.name) }
    var sortName by rememberSaveable { mutableStateOf(CinemaSort.POPULAR.name) }
    var genre by rememberSaveable { mutableIntStateOf(0) }
    val region = CinemaRegion.valueOf(regionName)
    val type = MediaType.valueOf(typeName)
    val era = CinemaEra.valueOf(eraName)
    val sort = CinemaSort.valueOf(sortName)
    val key = "$regionName:$typeName:$eraName:$sortName:$genre"
    var page by remember(key) { mutableIntStateOf(1) }
    var media by remember(key) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var hasMore by remember(key) { mutableStateOf(false) }
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var retry by remember(key) { mutableIntStateOf(0) }
    var sortOpen by remember { mutableStateOf(false) }
    if (onBack != null) BackHandler(onBack = onBack)
    LaunchedEffect(key, page, retry) {
        loading = true; error = null
        try {
            val result = catalog.catalog(CinemaQuery(region, type, era, sort, page, genre.takeIf { it > 0 }))
            media = (if (page == 1) result.items else media + result.items).distinctBy(::cinemaMediaKey)
            hasMore = result.hasMore
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "دریافت فهرست ناموفق بود." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().background(CinemaInk).statusBarsPadding().testTag("cinema-discovery")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.Default.ArrowForward, "بازگشت", tint = CinemaPaper) }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("جهان فیلم و سریال", fontSize = 24.sp, fontWeight = FontWeight.Black, color = CinemaPaper)
                Text(region.caption, fontSize = 12.sp, lineHeight = 20.sp, color = CinemaSoft)
            }
            IconButton(onSearchAll) { Icon(Icons.Default.Search, "جستجوی نام، بازیگر و عنوان", tint = CinemaPaper) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CinemaRegion.entries) { entry -> CinemaTag(entry.label, region == entry) { regionName = entry.name; genre = 0; if (entry == CinemaRegion.BOLLYWOOD) typeName = MediaType.MOVIE.name } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CinemaTag("فیلم", type == MediaType.MOVIE) { typeName = MediaType.MOVIE.name }
            if (region != CinemaRegion.BOLLYWOOD) CinemaTag("سریال", type == MediaType.TV) { typeName = MediaType.TV.name }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton({ sortOpen = true }) { Icon(Icons.Default.Sort, "مرتب‌سازی: ${sort.label}", tint = CinemaPaper) }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    CinemaSort.entries.forEach { entry -> DropdownMenuItem(text = { Text(entry.label) }, onClick = { sortName = entry.name; sortOpen = false }) }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(CinemaEra.entries) { entry -> CinemaTag(entry.label, era == entry) { eraName = entry.name } }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(listOf(0 to "همهٔ ژانرها", 18 to "درام", 35 to "کمدی", 80 to "جنایی", 16 to "انیمیشن")) { (id, name) -> CinemaTag(name, genre == id) { genre = id } }
        }
        if (loading) LinearProgressIndicator(color = CinemaAccent, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(128.dp), modifier = Modifier.weight(1f).testTag("discovery-grid"),
            contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("${region.label} · ${sort.label} · اطلاعات عنوان‌ها؛ پخش فقط با وجود فایل", color = CinemaSoft, fontSize = 12.sp, lineHeight = 19.sp)
            }
            if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { CinemaNotice("فهرست کامل نشد", error.orEmpty(), Icons.Default.CloudOff, "تلاش دوباره", { retry++ }) }
            items(media, key = ::cinemaMediaKey) { item -> CinemaPoster(item, { onMedia(item) }, Modifier.fillMaxWidth()) }
            if (!loading && error == null && media.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                CinemaNotice("عنوانی با این فیلتر پیدا نشد", "ژانر یا بازهٔ زمانی را تغییر بده. برای یک نام مشخص از جستجو استفاده کن.", Icons.Default.SearchOff)
            }
            if (hasMore && error == null) item(span = { GridItemSpan(maxLineSpan) }) {
                CinemaAction(Icons.Default.ExpandMore, if (loading) "در حال دریافت…" else "عنوان‌های بیشتر", { page++ }, Modifier.fillMaxWidth(), enabled = !loading)
            }
        }
    }
}

@Composable
fun CinemaHomeScreen(
    repository: TmdbRepository, backend: BackendRepository, loggedIn: Boolean,
    onMedia: (MediaItem) -> Unit, onPlay: (PlaybackTarget) -> Unit, onStory: (MediaItem, Int) -> Unit,
    onSearch: () -> Unit, onNotifications: () -> Unit, onReleases: () -> Unit, onClips: () -> Unit,
    onClub: () -> Unit, onWatchParty: (MediaItem?) -> Unit, badgeRefreshKey: Int = 0
) {
    if (loggedIn && backend.viewerProfiles.active()?.kidsMode == true) {
        PremiumHomeScreen(repository, backend, loggedIn, onMedia, onPlay, onStory, onSearch, onNotifications, onReleases, onClips, onClub, onWatchParty, badgeRefreshKey)
        return
    }
    val context = LocalContext.current
    val catalog = remember(backend) { CinemaDataRepository(context, backend) }
    var data by remember { mutableStateOf<HomeBundle?>(null) }
    var continued by remember { mutableStateOf<List<ContinueWatchingItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(retry, loggedIn) {
        try { data = catalog.home(); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "دریافت خانه ناموفق بود." }
    }
    LaunchedEffect(loggedIn, badgeRefreshKey, retry) {
        continued = if (loggedIn) cinemaOptional { backend.continueWatching() }.orEmpty() else emptyList()
    }
    val opened = region
    if (opened != null) {
        CinemaDiscoverScreen(repository, onMedia, onSearch, CinemaRegion.valueOf(opened)) { region = null }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(CinemaInk).testTag("cinema-home"), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                FilmiqooBrandMark(38.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("FILMIQOO", color = CinemaPaper, fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text("خانهٔ فیلم‌بازها", color = CinemaSoft, fontSize = 12.sp)
                }
                IconButton(onSearch) { Icon(Icons.Default.Search, "جستجو", tint = CinemaPaper) }
                IconButton(onNotifications) { Icon(Icons.Default.NotificationsNone, "اعلان‌ها", tint = CinemaPaper) }
            }
        }
        if (data == null && error == null) item("loading") { LinearProgressIndicator(color = CinemaAccent, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
        error?.let { message -> item("error") { Box(Modifier.padding(horizontal = 20.dp)) { CinemaNotice("خانه آماده نشد", message, Icons.Default.CloudOff, "تلاش دوباره", { retry++ }) } } }
        data?.trending?.firstOrNull()?.let { hero -> item("hero") { CinemaHomeHero(hero) { onMedia(hero) } } }
        item("regions") {
            CinemaHeading("سینمای تو، بدون مرزِ سلیقه", "بخش‌های واقعی بر اساس کشور تولید، نه دسته‌بندی حدسی")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                items(CinemaRegion.entries) { entry ->
                    val code = when (entry) { CinemaRegion.IRAN -> "IR"; CinemaRegion.KOREA -> "KR"; CinemaRegion.INDIA -> "IN"; CinemaRegion.BOLLYWOOD -> "HI"; CinemaRegion.WORLD -> "WORLD" }
                    Surface(Modifier.width(118.dp).heightIn(min = 108.dp).clickable { region = entry.name }, color = CinemaSurface, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, CinemaLine)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(code, color = CinemaAccent, fontSize = if (code.length > 2) 17.sp else 25.sp, fontWeight = FontWeight.Black)
                            Text(entry.label, color = CinemaPaper, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
        if (continued.isNotEmpty()) item("continue") {
            CinemaHeading("از همان‌جا ادامه بده", "موقعیت ذخیره‌شدهٔ تماشای تو")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(continued.distinctBy { it.target.mediaVersionId }, key = { it.target.mediaVersionId }) { item ->
                    Column(Modifier.width(228.dp).clickable { onPlay(item.target) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(17.dp))) {
                            CinemaImage(item.media.backdropPath ?: item.media.posterPath, Modifier.fillMaxSize(), true)
                            Icon(Icons.Default.PlayCircle, "ادامهٔ ${item.media.title}", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp))
                        }
                        LinearProgressIndicator(progress = { item.progress.coerceIn(0f, 1f) }, color = CinemaAccent, trackColor = CinemaLine, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(item.media.title, color = CinemaPaper, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                        Text(item.episodeLabel, color = CinemaSoft, fontSize = 12.sp)
                    }
                }
            }
        }
        data?.let { bundle ->
            item("iran") { CinemaShelf("ایران؛ خاطره و امروز", "فیلم و سریال ایرانی، قدیمی و تازه", bundle.iranian, onMedia) { region = CinemaRegion.IRAN.name } }
            item("korea") { CinemaShelf("قرار بعدی با K-drama", "نام عنوان‌های کره‌ای به انگلیسی", bundle.korean, onMedia) { region = CinemaRegion.KOREA.name } }
            item("india") { CinemaShelf("رنگ‌های سینمای هند", "هند، بالیوود و سینمای زبان‌های دیگر", bundle.bollywood, onMedia) { region = CinemaRegion.INDIA.name } }
            item("world") { CinemaShelf("روی پردهٔ جهان", "انتخاب‌های محبوب سینما", bundle.popularMovies, onMedia) { region = CinemaRegion.WORLD.name } }
            item("series") { CinemaShelf("یک قسمت دیگر…", "سریال‌های محبوب جهان", bundle.popularTv, onMedia) { region = CinemaRegion.WORLD.name } }
        }
        item("fan-tools") {
            CinemaHeading("فراتر از تماشا", "برای کسی که با فیلم زندگی می‌کند")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CinemaAction(Icons.Default.CalendarMonth, "تقویم انتشار", onReleases, Modifier.weight(1f))
                    CinemaAction(Icons.Default.Forum, "کلاب فیلم‌بازها", onClub, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CinemaAction(Icons.Default.MovieFilter, "کلیپ‌ها", onClips, Modifier.weight(1f))
                    CinemaAction(Icons.Default.Groups, "تماشای گروهی", { onWatchParty(null) }, Modifier.weight(1f))
                }
            }
        }
        item("notice") { Text("اطلاعات عنوان‌ها از بانک‌های فراداده دریافت می‌شود. امکان پخش و دانلود به موجود بودن فایل مجاز در کاتالوگ فیلمیکو بستگی دارد.", color = CinemaSoft, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) }
    }
}

@Composable
private fun CinemaHomeHero(media: MediaItem, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 350.dp).clip(RoundedCornerShape(27.dp)).background(CinemaSurface)) {
        CinemaImage(media.backdropPath ?: media.posterPath, Modifier.matchParentSize(), true)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, CinemaInk.copy(alpha = .85f), CinemaInk))))
        Column(Modifier.fillMaxWidth().padding(22.dp).padding(top = 150.dp)) {
            Text("انتخاب امروز", color = CinemaAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(media.title, color = CinemaPaper, fontSize = 29.sp, lineHeight = 37.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
            Text(listOf(media.year, if (media.type == MediaType.MOVIE) "فیلم" else "سریال").filter(String::isNotBlank).joinToString(" · "), color = CinemaSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
            CinemaAction(Icons.Default.ArrowBack, "جزئیات و گزینه‌های تماشا", onClick, Modifier.padding(top = 18.dp), primary = true)
        }
    }
}
