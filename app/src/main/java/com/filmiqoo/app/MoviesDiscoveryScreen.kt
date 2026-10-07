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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/** Loads a module only while its lazy item is composed. Errors never erase another section. */
internal class DiscoveryModuleState {
    var items by mutableStateOf<List<DiscoveryTitle>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var retry by mutableIntStateOf(0)
}

@Composable
internal fun rememberDiscoveryModule(loader: DiscoveryRepository, type: MediaType, section: DiscoverySection,
    filters: DiscoveryFilters = DiscoveryFilters()): DiscoveryModuleState {
    val state = remember(loader, type, section, filters) { DiscoveryModuleState() }
    LaunchedEffect(loader, type, section, filters, state.retry) {
        state.loading = true; state.error = null
        try {
            val items = loader.page(type, section, filters).items
            currentCoroutineContext().ensureActive(); state.items = items
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { state.error = failure.message ?: "این بخش دریافت نشد." }
        finally { currentCoroutineContext().ensureActive(); state.loading = false }
    }
    return state
}

@Composable
internal fun DiscoveryModuleStatus(state: DiscoveryModuleState, emptyTitle: String = "هنوز عنوانی برای این انتخاب نیست") {
    if (state.loading && state.items.isEmpty()) DiscoverySkeleton()
    state.error?.let { message -> Box(Modifier.padding(horizontal = 20.dp)) {
        CinemaNotice("این بخش به‌روز نشد", message, Icons.Outlined.CloudOff, "تلاش دوباره", { state.retry++ })
    } }
    if (!state.loading && state.items.isEmpty() && state.error == null) Text(emptyTitle, color = CinemaSoft,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
fun MoviesDiscoveryScreen(repository: TmdbRepository, backend: BackendRepository, onMedia: (MediaItem) -> Unit,
    onSearch: () -> Unit, onRequireAuth: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val loader = remember(backend) { DiscoveryRepository(context, backend) }
    val hero = rememberDiscoveryModule(loader, MediaType.MOVIE, DiscoverySection.TRENDING)
    var heroIndex by rememberSaveable { mutableIntStateOf(0) }
    var genres by remember { mutableStateOf<List<DiscoveryGenre>>(emptyList()) }
    var genreFailed by remember { mutableStateOf(false) }
    var genreRetry by remember { mutableIntStateOf(0) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var listSection by rememberSaveable { mutableStateOf<String?>(null) }
    var listTitle by rememberSaveable { mutableStateOf("") }
    var filterGenre by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterFrom by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterTo by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterRating by rememberSaveable { mutableStateOf(0.0) }
    var filterCountry by rememberSaveable { mutableStateOf<String?>(null) }
    var filterLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var filterRuntime by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterSort by rememberSaveable { mutableStateOf(DiscoverySort.POPULAR.name) }
    var filterDub by rememberSaveable { mutableStateOf(false) }
    var filterSub by rememberSaveable { mutableStateOf(false) }
    var surpriseOpen by rememberSaveable { mutableStateOf(false) }
    val filters = DiscoveryFilters(genreId = filterGenre, yearFrom = filterFrom, yearTo = filterTo, minRating = filterRating,
        country = filterCountry, language = filterLanguage, runtimeMax = filterRuntime, sort = DiscoverySort.valueOf(filterSort),
        persianDubbedOnly = filterDub, persianSubtitleOnly = filterSub)
    fun apply(value: DiscoveryFilters) {
        filterGenre = value.genreId; filterFrom = value.yearFrom; filterTo = value.yearTo; filterRating = value.minRating
        filterCountry = value.country; filterLanguage = value.language; filterRuntime = value.runtimeMax; filterSort = value.sort.name
        filterDub = value.persianDubbedOnly; filterSub = value.persianSubtitleOnly
    }
    fun open(section: DiscoverySection, title: String, value: DiscoveryFilters = DiscoveryFilters()) {
        apply(value); listTitle = title; listSection = section.name
    }
    LaunchedEffect(loader, genreRetry) {
        try { genres = loader.genres(MediaType.MOVIE); genreFailed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { genreFailed = true }
    }
    country?.let { code ->
        CountryDiscoveryPage(repository, backend, code, MediaType.MOVIE, { country = null }, onMedia, onSearch)
        return
    }
    listSection?.let { section ->
        DiscoveryFullListScreen(repository, backend, MediaType.MOVIE, DiscoverySection.valueOf(section), filters, listTitle, { listSection = null }, onMedia)
        return
    }
    DiscoveryActionScope(backend) {
        LazyColumn(Modifier.fillMaxSize().background(CinemaInk).testTag("movies-discovery"),
            contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("masthead") { DiscoveryDestinationHeader("فیلم", "CINEMA / کشف یک داستان تازه", onSearch, onBack) }
            item("hero") {
                if (hero.items.isNotEmpty()) {
                    val choices = hero.items.take(5)
                    val selected = choices[heroIndex.coerceIn(0, choices.lastIndex)]
                    CinematicDiscoveryHero(selected, backend, onMedia, "انتخاب این هفته")
                    if (choices.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(choices, key = { cinemaMediaKey(it.media) }) { value ->
                            FilterChip(selected.media.key == value.media.key, { heroIndex = choices.indexOf(value) },
                                label = { Text(value.media.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.widthIn(max = 220.dp).heightIn(min = 48.dp))
                        }
                    }
                }
                DiscoveryModuleStatus(hero)
            }
            item("tools") {
                Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ showFilters = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("movies-filters"), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("کشف دقیق‌تر")
                    }
                    Button({ surpriseOpen = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("movies-surprise"), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaSurface, contentColor = CinemaGold)) {
                        Icon(Icons.Outlined.Casino, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("چی ببینم؟")
                    }
                }
            }
            item("world") { WorldDiscoveryHub(repository, backend, MediaType.MOVIE, { country = it }) }
            item("trending-editorial") {
                CinemaHeading("روی موج این هفته", "عنوان‌های ترند فیلم در TMDB", onAction = { open(DiscoverySection.TRENDING, "ترندهای فیلم") })
                val picks = hero.items.drop(1).take(3)
                if (picks.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DiscoveryBackdropCard(picks.first(), backend, onMedia)
                    if (picks.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        picks.drop(1).forEach { value -> DiscoveryBackdropCard(value, backend, onMedia, Modifier.weight(1f)) }
                    }
                }
            }
            item("ranking") {
                val state = rememberDiscoveryModule(loader, MediaType.MOVIE, DiscoverySection.TOP_RATED)
                CinemaHeading("۲۵۰ انتخاب با بالاترین امتیاز", "امتیاز و حداقل تعداد رأی TMDB؛ رتبه‌بندی رسمی IMDb نیست", "فهرست کامل",
                    { open(DiscoverySection.TOP_RATED, "۲۵۰ فیلم با بالاترین امتیاز") })
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.items.take(3).forEachIndexed { index, title -> RankedDiscoveryCard(title, index + 1, backend, onMedia) }
                }
                DiscoveryModuleStatus(state)
            }
            item("dubbed") {
                val state = rememberDiscoveryModule(loader, MediaType.MOVIE, DiscoverySection.DUBBED)
                if (state.items.isNotEmpty() || state.error != null || state.loading) {
                    CinemaHeading("دوبله فارسی؛ انتخاب نسخه با تو", "فقط عنوان‌هایی با نسخهٔ دوبلهٔ تشخیص‌داده‌شده", onAction = { open(DiscoverySection.DUBBED, "فیلم‌های دوبله فارسی") })
                    if (state.items.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.items.take(8), key = { cinemaMediaKey(it.media) }) { title -> DiscoveryPosterCard(title, backend, onMedia, Modifier.width(158.dp)) }
                    }
                    DiscoveryModuleStatus(state)
                }
            }
            item("genres") {
                CinemaHeading("هر ژانر، یک حال‌وهوا", "مسیر کشف واقعی فیلم")
                if (genres.isNotEmpty()) DiscoveryGenreExplorer(genres, hero.items) { id -> open(DiscoverySection.POPULAR,
                    genres.firstOrNull { it.id == id }?.name ?: "کشف ژانر", DiscoveryFilters(genreId = id)) }
                if (genreFailed) Box(Modifier.padding(horizontal = 20.dp)) { CinemaNotice("ژانرها دریافت نشدند", "اتصال را بررسی کن.", actionLabel = "تلاش دوباره", onAction = { genreRetry++ }) }
            }
            item("recent") { MovieEditorialModule(loader, backend, DiscoverySection.NEW, "تازه روی پرده", "انتشارهای تازه، بر اساس تاریخ واقعی", onMedia) { open(DiscoverySection.NEW, "فیلم‌های تازه") } }
            item("popular") {
                val state = rememberDiscoveryModule(loader, MediaType.MOVIE, DiscoverySection.POPULAR)
                CinemaHeading("این روزها محبوب", "محبوبیت TMDB؛ متفاوت از موجود بودن فایل", onAction = { open(DiscoverySection.POPULAR, "فیلم‌های محبوب") })
                if (state.items.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.items.take(10), key = { cinemaMediaKey(it.media) }) { title -> DiscoveryPosterCard(title, backend, onMedia, Modifier.width(150.dp)) }
                }
                DiscoveryModuleStatus(state)
            }
            item("gems") { MovieEditorialModule(loader, backend, DiscoverySection.HIDDEN_GEMS, "خوب‌هایی که کمتر دیده شده‌اند", "امتیاز بالا با رأی کافی و محبوبیت کمتر", onMedia) { open(DiscoverySection.HIDDEN_GEMS, "کشف کمترشناخته‌ها") } }
            item("collections") { MovieCollectionsModule(loader, backend, hero.items.firstOrNull()?.media, onMedia) }
            item("personal") { MovieContextualModule(loader, backend, onMedia) }
            item("provenance") { Text("امتیازها و اطلاعات جهانی از TMDB هستند. پخش و دانلود فقط با فایل واقعیِ موجود در فیلمیکو فعال می‌شود. دوبله و زیرنویس از فرادادهٔ هر نسخهٔ تلگرام تشخیص داده می‌شوند.",
                color = CinemaSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
        }
        if (showFilters) DiscoveryFilterSheet(MediaType.MOVIE, filters, genres,
            onApply = { value -> showFilters = false; open(DiscoverySection.POPULAR, "فیلم‌های انتخاب تو", value) }, onDismiss = { showFilters = false })
        if (surpriseOpen) DiscoverySurpriseSheet(MediaType.MOVIE, loader, backend, genres, filters, onMedia, { surpriseOpen = false })
    }
}

@Composable
internal fun DiscoveryDestinationHeader(title: String, subtitle: String, onSearch: () -> Unit, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.Outlined.ArrowForward, "بازگشت", tint = CinemaPaper) }
        Column(Modifier.weight(1f)) {
            Text(title, color = CinemaPaper, style = MaterialTheme.typography.displaySmall)
            Text(subtitle, color = CinemaSoft, style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onSearch, modifier = Modifier.testTag("discovery-open-search")) { Icon(Icons.Outlined.Search, "جستجوی عنوان", tint = CinemaPaper) }
    }
}

@Composable
private fun MovieEditorialModule(loader: DiscoveryRepository, backend: BackendRepository, section: DiscoverySection,
    heading: String, subtitle: String, onMedia: (MediaItem) -> Unit, onMore: () -> Unit) {
    val state = rememberDiscoveryModule(loader, MediaType.MOVIE, section)
    CinemaHeading(heading, subtitle, onAction = onMore)
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.items.take(2).forEach { title ->
            Row(Modifier.fillMaxWidth().background(CinemaSurface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DiscoveryArtwork(title.media.posterPath, false, Modifier.width(92.dp).aspectRatio(2f / 3f).clickable(role = Role.Button) { onMedia(title.media) })
                Column(Modifier.weight(1f).padding(start = 14.dp).clickable(role = Role.Button) { onMedia(title.media) }) {
                    Text(title.media.title, color = CinemaPaper, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(title.media.year, color = CinemaSoft, style = MaterialTheme.typography.bodySmall)
                    DiscoveryRatingBadge(title.media, Modifier.padding(top = 8.dp))
                    Text(title.media.overview.ifBlank { "جزئیات عنوان و نسخه‌های موجود" }, color = CinemaSoft, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    DiscoveryWatchlistButton(title.media, backend)
                }
            }
        }
    }
    DiscoveryModuleStatus(state)
}

@Composable
private fun MovieCollectionsModule(loader: DiscoveryRepository, backend: BackendRepository, seed: MediaItem?, onMedia: (MediaItem) -> Unit) {
    var collections by remember(seed?.key) { mutableStateOf<List<FranchiseInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<FranchiseInfo?>(null) }
    LaunchedEffect(seed?.key) { if (seed != null) collections = cinemaUiOptional { loader.collections(seed) }.orEmpty() }
    if (collections.isEmpty()) return
    CinemaHeading("داستان‌های ادامه‌دار", "مجموعه‌ها و فیلم‌های مرتبط با انتخاب این هفته")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        collections.take(2).forEach { collection ->
            Surface(color = CinemaSurface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { selected = collection }) {
                Column {
                    DiscoveryArtwork(collection.backdropPath ?: collection.posterPath, true, Modifier.fillMaxWidth().aspectRatio(2.4f))
                    Text(collection.name, style = MaterialTheme.typography.titleLarge, color = CinemaPaper, modifier = Modifier.padding(16.dp))
                    Text("${collection.parts.size} فیلم در مجموعه", color = CinemaGold, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
                }
            }
        }
    }
    selected?.let { collection -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(collection.name) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=420.dp)) {
                items(collection.parts,key=::cinemaMediaKey) { media ->
                    TextButton({ selected = null; onMedia(media) }, Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(media.title) }
                }
            }
        },
        confirmButton = { TextButton({ selected = null }) { Text("بستن") } }) }
}

@Composable
private fun MovieContextualModule(loader: DiscoveryRepository, backend: BackendRepository, onMedia: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val profile = backend.viewerProfiles.activeId()
    val account = backend.session.localAccountScope
    val local = remember(profile, account) { CinemaPersonalStore(context, profile) }
    var seed by remember(profile, account) { mutableStateOf<MediaItem?>(null) }
    var recommendations by remember(profile, account) { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(profile, account, backend.session.isLoggedIn) {
        recommendations = emptyList()
        val completed = if (backend.session.isLoggedIn)
            cinemaUiOptional { UserViewingStatsRepository(backend).load() }?.completedMovieTmdbIds ?: return@LaunchedEffect
            else emptySet()
        seed = (local.favorites() + local.saved()).firstOrNull { it.type == MediaType.MOVIE }
        if (seed == null && backend.session.isLoggedIn) seed = cinemaUiOptional { LibraryRepository(backend).watchlist() }?.firstOrNull { it.type == MediaType.MOVIE }
        seed?.let { chosen -> recommendations = cinemaUiOptional { CinemaDataRepository(context, backend).title(chosen).detail.recommendations }.orEmpty().filter { it.type == MediaType.MOVIE && it.id != chosen.id && it.id !in completed && !local.seen(it) } }
    }
    if (seed == null || recommendations.isEmpty()) return
    CinemaHeading("نزدیک به ${seed!!.title}", "پیشنهادهای TMDB بر اساس یک عنوان در فهرست تو")
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(recommendations.take(8), key = ::cinemaMediaKey) { media -> DiscoveryPosterCard(DiscoveryTitle(media), backend, onMedia, Modifier.width(150.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverySurpriseSheet(type: MediaType, loader: DiscoveryRepository, backend: BackendRepository, genres: List<DiscoveryGenre>, initialFilters: DiscoveryFilters,
    onMedia: (MediaItem) -> Unit, onDismiss: () -> Unit) {
    var genre by rememberSaveable { mutableStateOf(initialFilters.genreId) }
    var rating by rememberSaveable { mutableStateOf(initialFilters.minRating.coerceAtLeast(6.0)) }
    var attempt by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<DiscoveryTitle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt,genre,rating) {
        candidates=emptyList();error=null
        if (attempt == 0) return@LaunchedEffect
        loading = true; error = null
        try {
            val loaded = loader.page(type, DiscoverySection.POPULAR, initialFilters.copy(genreId = genre, minRating = rating)).items
            currentCoroutineContext().ensureActive(); candidates = loaded.shuffled().take(3)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "انتخاب‌ها دریافت نشدند." }
        finally { currentCoroutineContext().ensureActive(); loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CinemaSurface) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("چی ببینم؟", color = CinemaPaper, style = MaterialTheme.typography.headlineMedium)
                Text("سه انتخاب از نتیجه‌های واقعی، با محدودیت‌های انتخاب تو", color = CinemaSoft, style = MaterialTheme.typography.bodySmall) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(genre == null, { genre = null }, { Text("همهٔ ژانرها") }, modifier = Modifier.heightIn(min = 48.dp)) }
                items(genres, key = { it.id }) { value -> FilterChip(genre == value.id, { genre = value.id }, { Text(value.name) }, modifier = Modifier.heightIn(min = 48.dp)) }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(6.0, 7.0, 8.0).forEach { value ->
                FilterChip(rating == value, { rating = value }, { Text("TMDB ${formatVote(value)}+") }, modifier = Modifier.heightIn(min = 48.dp))
            } } }
            item { Button({ attempt++ }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discovery-surprise-pick")) { Text(if (loading) "در حال انتخاب…" else "انتخاب کن") } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(candidates, key = { cinemaMediaKey(it.media) }) { value -> DiscoveryBackdropCard(value, backend, { media -> onDismiss(); onMedia(media) }) }
            error?.let { item { Text(it, color = FqDanger) } }
            if (attempt > 0 && !loading && candidates.isEmpty() && error == null) item { Text("با این ترکیب عنوانی پیدا نشد؛ ژانر یا حداقل امتیاز را تغییر بده.", color = CinemaSoft) }
        }
    }
}
