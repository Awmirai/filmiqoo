package com.filmiqoo.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SeriesDiscoveryScreen(repository: TmdbRepository, backend: BackendRepository,
    onMedia: (MediaItem) -> Unit, onSearch: () -> Unit, onCalendar: () -> Unit,
    onRequireAuth: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val loader = remember(backend) { DiscoveryRepository(context, backend) }
    val hero = rememberDiscoveryModule(loader, MediaType.TV, DiscoverySection.TRENDING)
    var heroIndex by rememberSaveable { mutableIntStateOf(0) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var listSection by rememberSaveable { mutableStateOf<String?>(null) }
    var listTitle by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = CountryDiscoveryFilterSaver) { mutableStateOf(DiscoveryFilters()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var surprise by rememberSaveable { mutableStateOf(false) }
    var genres by remember(loader) { mutableStateOf<List<DiscoveryGenre>>(emptyList()) }
    var genreError by remember(loader) { mutableStateOf<String?>(null) }
    var genreRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(loader, genreRetry) {
        genreError = null
        try { genres = loader.genres(MediaType.TV) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { genreError = failure.message ?: "ژانرها دریافت نشدند." }
    }
    fun open(section: DiscoverySection, title: String, value: DiscoveryFilters = DiscoveryFilters()) {
        filters = value; listTitle = title; listSection = section.name
    }
    country?.let {
        CountryDiscoveryPage(repository, backend, it, MediaType.TV, { country = null }, onMedia, onSearch)
        return
    }
    listSection?.let {
        DiscoveryFullListScreen(repository, backend, MediaType.TV, DiscoverySection.valueOf(it), filters,
            listTitle, { listSection = null }, onMedia)
        return
    }
    DiscoveryActionScope(backend) {
        LazyColumn(Modifier.fillMaxSize().background(CinemaInk).testTag("series-discovery"),
            contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("header") { DiscoveryDestinationHeader("سریال", "SERIES / هر داستان، یک قسمت دیگر", onSearch, onBack) }
            item("hero") {
                if (hero.items.isNotEmpty()) {
                    val choices = hero.items.filter { it.media.type == MediaType.TV }.take(5)
                    choices.getOrNull(heroIndex.coerceIn(0, (choices.size - 1).coerceAtLeast(0)))?.let {
                        CinematicDiscoveryHero(it, backend, onMedia, "داستان بعدی تو", series = true)
                    }
                    if (choices.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(choices, key = { cinemaMediaKey(it.media) }) { title ->
                            FilterChip(choices.indexOf(title) == heroIndex, { heroIndex = choices.indexOf(title) },
                                label = { Text(title.media.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.widthIn(max = 220.dp).heightIn(min = 48.dp))
                        }
                    }
                }
                DiscoveryModuleStatus(hero)
            }
            item("tools") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ showFilters = true }, Modifier.weight(1f).heightIn(min = 52.dp).testTag("series-filters")) { Text("کشف پیشرفته") }
                    OutlinedButton({ surprise = true }, Modifier.weight(1f).heightIn(min = 52.dp).testTag("series-surprise")) { Text("چه سریالی ببینم؟") }
                }
            }
            item("world") { WorldDiscoveryHub(repository, backend, MediaType.TV, { country = it }) }
            item("popular") {
                SeriesEditorialModule(loader, backend, DiscoverySection.POPULAR, "سریال‌های محبوب", "بر اساس محبوبیت در TMDB", onMedia,
                    { open(DiscoverySection.POPULAR, "سریال‌های محبوب") }, "series-popular", 4)
            }
            item("new") {
                SeriesEditorialModule(loader, backend, DiscoverySection.NEW, "داستان‌های تازه", "سریال‌های تازه بر اساس تاریخ شروع ثبت‌شده", onMedia,
                    { open(DiscoverySection.NEW, "سریال‌های تازه") }, "series-new", 2)
            }
            item("airing") {
                SeriesEditorialModule(loader, backend, DiscoverySection.AIRING, "هنوز ادامه دارد",
                    "قسمت تازه یا پیشِ رو در اطلاعات TMDB؛ زمان پخش از برنامهٔ ثبت‌شده", onMedia,
                    { open(DiscoverySection.AIRING, "سریال‌های در حال پخش") }, "series-airing", 4)
            }
            item("calendar") { SeriesFollowingCalendar(backend, onMedia, onCalendar, onRequireAuth) }
            item("dubbed") {
                SeriesEditorialModule(loader, backend, DiscoverySection.DUBBED, "سریال با دوبلهٔ فارسی",
                    "فایل‌های با دوبلهٔ فارسی تأییدشده؛ موجودی قسمت‌ها جدا از تعداد کل داستان", onMedia,
                    { open(DiscoverySection.DUBBED, "سریال‌های دوبلهٔ فارسی") }, "series-dubbed", 4)
            }
            item("completed") {
                val state = rememberDiscoveryModule(loader, MediaType.TV, DiscoverySection.COMPLETED)
                Column {
                    CinemaHeading("تمامِ داستان، یک‌جا", "پایان‌یافته یا لغوشده در TMDB؛ وضعیت داستان، نه کامل‌بودن فایل‌ها", "همه", { open(DiscoverySection.COMPLETED, "سریال‌های پایان‌یافته") })
                    DiscoveryModuleStatus(state)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.items.take(6), key = { cinemaMediaKey(it.media) }) { title -> DiscoveryPosterCard(title, backend, onMedia, Modifier.width(152.dp)) }
                    }
                }
            }
            item("mini") {
                SeriesEditorialModule(loader, backend, DiscoverySection.MINISERIES, "داستان‌های کوتاه، ماندگار",
                    "آثاری که نوع آن‌ها در TMDB مینی‌سریال ثبت شده است", onMedia,
                    { open(DiscoverySection.MINISERIES, "مینی‌سریال‌ها") }, "series-miniseries", 2)
            }
            item("ranked") {
                val state = rememberDiscoveryModule(loader, MediaType.TV, DiscoverySection.TOP_RATED)
                Column {
                    CinemaHeading("سریال‌های برتر · TMDB", "تا ۲۵۰ عنوان با بالاترین امتیاز TMDB و حداقل ۲۰۰ رأی", "فهرست کامل", { open(DiscoverySection.TOP_RATED, "سریال‌های برتر بر اساس امتیاز TMDB") })
                    DiscoveryModuleStatus(state)
                    state.items.take(3).forEachIndexed { index, title -> RankedDiscoveryCard(title, index + 1, backend, onMedia, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) }
                }
            }
            item("genres") {
                Column(Modifier.testTag("series-genres")) {
                    CinemaHeading("حال‌وهوای داستانت", "ژانری که دوست داری، مسیر کشف بعدی تو")
                    genreError?.let { CinemaNotice("ژانرها دریافت نشدند", it, Icons.Outlined.CloudOff, "تلاش دوباره", { genreRetry++ }) }
                    if (genres.isNotEmpty()) DiscoveryGenreExplorer(genres, hero.items) { id -> open(DiscoverySection.POPULAR, genres.firstOrNull { it.id == id }?.name ?: "ژانر سریال", DiscoveryFilters(genreId = id)) }
                }
            }
            item("continue") { SeriesContinueExploring(backend, onMedia, onRequireAuth) }
        }
        if (showFilters) DiscoveryFilterSheet(MediaType.TV, filters, genres,
            onApply = { showFilters = false; open(DiscoverySection.POPULAR, "سریال‌های انتخاب تو", it) }, onDismiss = { showFilters = false })
        if (surprise) DiscoverySurpriseSheet(MediaType.TV, loader, backend, genres, filters, onMedia, { surprise = false })
    }
}

@Composable
private fun SeriesEditorialModule(loader: DiscoveryRepository, backend: BackendRepository, section: DiscoverySection,
    title: String, subtitle: String, onMedia: (MediaItem) -> Unit, onMore: () -> Unit, tag: String, limit: Int) {
    val state = rememberDiscoveryModule(loader, MediaType.TV, section)
    Column(Modifier.testTag(tag)) {
        CinemaHeading(title, subtitle, "همه", onMore)
        DiscoveryModuleStatus(state)
        if (state.items.isNotEmpty()) CountryBackdropGrid(state.items.take(limit), backend, onMedia)
    }
}

@Composable
private fun SeriesFollowingCalendar(backend: BackendRepository, onMedia: (MediaItem) -> Unit, onCalendar: () -> Unit, onRequireAuth: () -> Unit) {
    val logged = backend.session.isLoggedIn
    val profile = backend.viewerProfiles.activeId()
    val account = backend.session.localAccountScope
    var episodes by remember(backend, profile, account, logged) { mutableStateOf<List<SeriesCalendarItem>>(emptyList()) }
    var loading by remember(backend, profile, account, logged) { mutableStateOf(logged) }
    var error by remember(backend, profile, account, logged) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(backend, profile, account, logged, retry) {
        if (!logged) return@LaunchedEffect
        loading = true; error = null
        try {
            val values = SeriesAlertsRepository(backend).calendar(60).mapNotNull { item -> runCatching { LocalDate.parse(item.airDate) }.getOrNull()?.let { it to item } }
                .sortedWith(compareBy<Pair<LocalDate, SeriesCalendarItem>> { it.first }.thenBy { it.second.media.title }.thenBy { it.second.seasonNumber }.thenBy { it.second.episodeNumber })
                .map { it.second }.distinctBy { it.episodeId }.take(4)
            currentCoroutineContext().ensureActive(); episodes = values
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "تقویم حساب دریافت نشد." }
        finally { currentCoroutineContext().ensureActive(); loading = false }
    }
    Column(Modifier.testTag("series-following-calendar")) {
        CinemaHeading("قسمت‌های بعدیِ دنبال‌شده‌ها", "تقویم سریال‌هایی که این حساب دنبال می‌کند؛ تاریخ ثبت‌شدهٔ قسمت‌ها", "تقویم", if (logged) onCalendar else onRequireAuth)
        if (!logged) CinemaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("برنامهٔ تماشای خودت را بساز", style = MaterialTheme.typography.titleMedium)
            Text("وارد حساب شو و در صفحهٔ سریال آن را دنبال کن تا قسمت‌های آینده اینجا دیده شوند.", color = CinemaSoft, modifier = Modifier.padding(vertical = 10.dp))
            CinemaAction(Icons.Outlined.Login, "ورود برای تقویم من", onRequireAuth, Modifier.fillMaxWidth().testTag("series-calendar-login"))
        } else {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = CinemaAccent)
            error?.let { CinemaNotice("تقویم حساب در دسترس نیست", it, Icons.Outlined.CloudOff, "تلاش دوباره", { retry++ }) }
            if (!loading && error == null && episodes.isEmpty()) Text("هنوز قسمتی با تاریخ مشخص برای دنبال‌شده‌های این حساب ثبت نشده است.", color = CinemaSoft, modifier = Modifier.padding(horizontal = 20.dp))
            episodes.forEach { item ->
                Surface(color = CinemaSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CinemaLine), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
                    .clickable(role = Role.Button, onClickLabel = "اطلاعات ${item.media.title}", onClick = { onMedia(item.media) }).testTag("series-calendar-${item.episodeId}")) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.media.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(LocalDate.parse(item.airDate).format(DateTimeFormatter.ofPattern("d MMM", Locale("fa", "IR"))), color = CinemaGold, modifier = Modifier.padding(start = 12.dp))
                        }
                        Text(item.episodeLabel, color = CinemaGold, modifier = Modifier.padding(top = 6.dp))
                        Text(if (item.streamReady) "فایل این قسمت آمادهٔ پخش است" else "زمان قسمت ثبت شده؛ فایل هنوز آمادهٔ پخش نیست", style = MaterialTheme.typography.bodySmall, color = CinemaSoft, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesContinueExploring(backend: BackendRepository, onMedia: (MediaItem) -> Unit, onRequireAuth: () -> Unit) {
    val context = LocalContext.current
    val profile = backend.viewerProfiles.activeId()
    val logged = backend.session.isLoggedIn
    val account = backend.session.localAccountScope
    val personal = remember(profile, account) { CinemaPersonalStore(context, profile) }
    var saved by remember(personal) { mutableStateOf(personal.saved().filter { it.type == MediaType.TV }) }
    var continued by remember(backend, profile, account, logged) { mutableStateOf<List<ContinueWatchingItem>>(emptyList()) }
    var error by remember(backend, profile, account, logged) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    DisposableEffect(personal, context) {
        val prefs = context.applicationContext.getSharedPreferences("filmiqoo_cinema_library_v1", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> saved = personal.saved().filter { it.type == MediaType.TV } }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(backend, profile, account, logged, retry) {
        if (!logged) return@LaunchedEffect
        error = null
        try { val values = backend.continueWatching().filter { it.media.type == MediaType.TV }.take(4);currentCoroutineContext().ensureActive();continued = values }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "ادامهٔ پخش حساب دریافت نشد." }
    }
    Column(Modifier.testTag("series-continue-exploring")) {
        CinemaHeading("از جهان خودت ادامه بده", "ذخیره‌های این پروفایل روی دستگاه؛ ادامهٔ پخش از سابقهٔ حساب")
        continued.forEach { item ->
            CinemaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clickable(role = Role.Button, onClickLabel = "اطلاعات ${item.media.title}", onClick = { onMedia(item.media) }).testTag("series-continue-${cinemaMediaKey(item.media)}")) {
                Text(item.media.title, style = MaterialTheme.typography.titleMedium)
                if (item.episodeLabel.isNotBlank()) Text(item.episodeLabel, color = CinemaSoft, modifier = Modifier.padding(top = 6.dp))
                if (item.durationMs > 0 && item.positionMs > 0) LinearProgressIndicator(progress = { (item.positionMs.toDouble() / item.durationMs).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("series-account-progress"), color = CinemaAccent)
            }
        }
        error?.let { CinemaNotice("ادامهٔ پخش دریافت نشد", it, Icons.Outlined.CloudOff, "تلاش دوباره", { retry++ }) }
        if (saved.isNotEmpty()) CinemaShelf("سریال‌هایی که نشان کرده‌ای", "این پروفایل روی همین دستگاه", saved, onMedia)
        if (saved.isEmpty() && continued.isEmpty() && error == null) CinemaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("داستان بعدی را نشان کن", style = MaterialTheme.typography.titleMedium)
            Text("با نشان‌کردن سریال روی کارت یا صفحهٔ اطلاعات، فهرست این پروفایل همین‌جا شکل می‌گیرد.", color = CinemaSoft, modifier = Modifier.padding(top = 8.dp))
            if (!logged) TextButton(onRequireAuth) { Text("ورود برای سابقهٔ تماشای حساب") }
        }
    }
}
