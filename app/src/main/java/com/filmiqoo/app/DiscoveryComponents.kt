package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.time.LocalDate

/** One action owner per discovery page, not one authenticated request per poster. */
internal class DiscoveryActions(val personal: CinemaPersonalStore) {
    val saved = mutableStateMapOf<String, Boolean>()
    val favorites = mutableStateMapOf<String, Boolean>()
    val busy = mutableStateMapOf<String, Boolean>()
    var error by mutableStateOf<String?>(null)
    var revision by mutableIntStateOf(0)
    fun isSaved(media: MediaItem): Boolean = saved[cinemaMediaKey(media)] ?: personal.contains("watchlist", media)
}
private val LocalDiscoveryActions = staticCompositionLocalOf<DiscoveryActions?> { null }

@Composable
internal fun DiscoveryActionScope(backend: BackendRepository, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val profile = backend.viewerProfiles.activeId()
    val account = backend.session.localAccountScope
    val actions = remember(backend, profile, account) { DiscoveryActions(CinemaPersonalStore(context, profile)) }
    LaunchedEffect(backend, profile, account, backend.session.isLoggedIn) {
        if (backend.session.isLoggedIn) {
            try {
                supervisorScope {
                    val library=LibraryRepository(backend)
                    val saved=async{cinemaUiOptional{library.watchlist()}.orEmpty()}
                    val favorites=async{cinemaUiOptional{library.favorites()}.orEmpty()}
                    saved.await().forEach { val key=cinemaMediaKey(it);if(!actions.saved.containsKey(key))actions.saved[key]=true }
                    favorites.await().forEach{val key=cinemaMediaKey(it);if(!actions.favorites.containsKey(key))actions.favorites[key]=true}
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Local lists remain usable; do not announce cloud synchronization. */ }
        }
    }
    CompositionLocalProvider(LocalDiscoveryActions provides actions) {
        Box(Modifier.fillMaxSize()) {
            content()
            actions.error?.let { message ->
                Surface(color = CinemaSurface, border = BorderStroke(1.dp, CinemaLine),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("discovery-action-error")) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = CinemaPaper, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton({ actions.error = null }) { Icon(Icons.Outlined.Close, "بستن پیام") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiscoveryWatchlistButton(media: MediaItem, backend: BackendRepository, modifier: Modifier = Modifier) {
    val actions = LocalDiscoveryActions.current
    val scope = key(actions) { rememberCoroutineScope() }
    val key = cinemaMediaKey(media)
    val saved = actions?.isSaved(media) == true
    val busy = actions?.busy?.get(key) == true
    IconButton(onClick = {
        if (actions == null || busy) return@IconButton
        actions.busy[key] = true
        scope.launch {
            try {
                val next = if (backend.session.isLoggedIn && !media.backendId.isNullOrBlank())
                    LibraryRepository(backend).toggleWatchlist(media.backendId) else !actions.isSaved(media)
                currentCoroutineContext().ensureActive()
                actions.personal.setSaved("watchlist", media, next)
                actions.saved[key] = next
                actions.revision++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actions.error = "ذخیره انجام نشد؛ دوباره تلاش کن." }
            finally { actions.busy[key] = false }
        }
    }, enabled = actions != null && !busy,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("discovery-save-$key")) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CinemaGold)
        else Icon(if (saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,
            if (saved) "حذف ${media.title} از فهرست من" else "ذخیرهٔ ${media.title} در فهرست من", tint = if (saved) CinemaGold else CinemaPaper)
    }
}

@Composable
private fun DiscoveryContextMenu(media: MediaItem, backend: BackendRepository) {
    val actions = LocalDiscoveryActions.current
    var opened by remember { mutableStateOf(false) }
    var ratingOpened by remember { mutableStateOf(false) }
    val scope = key(actions) { rememberCoroutineScope() }
    val key = cinemaMediaKey(media)
    Box {
        IconButton({ opened = true }, Modifier.size(48.dp).testTag("discovery-actions-$key")) {
            Icon(Icons.Outlined.MoreHoriz, "گزینه‌های ${media.title}", tint = CinemaPaper)
        }
        DropdownMenu(opened, { opened = false }) {
            val favorite = actions?.favorites?.get(key) ?: actions?.personal?.contains("favorites", media) ?: false
            DropdownMenuItem(text = { Text(if (favorite) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها") },
                leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) }, enabled = actions != null && actions.busy[key] != true,
                onClick = {
                    opened = false
                    if (actions != null) {
                        actions.busy[key] = true
                        scope.launch {
                            try {
                                val next = if (backend.session.isLoggedIn && !media.backendId.isNullOrBlank()) backend.toggleFavorite(media.backendId) else !favorite
                                currentCoroutineContext().ensureActive()
                                actions.personal.setSaved("favorites", media, next); actions.favorites[key] = next; actions.revision++
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { actions.error = "علاقه‌مندی ذخیره نشد؛ دوباره تلاش کن." }
                            finally { actions.busy[key] = false }
                        }
                    }
                })
            val seen = actions?.personal?.seen(media) == true
            DropdownMenuItem(text = { Text(if (seen) "هنوز ندیده‌ام" else "دیده‌ام") }, leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                enabled = actions != null, onClick = { opened = false; actions?.personal?.markSeen(media, !seen); actions?.revision = (actions?.revision ?: 0) + 1 })
            DropdownMenuItem(text = { Text("امتیاز من") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null) },
                enabled = actions != null, onClick = { opened = false; ratingOpened = true })
        }
    }
    if (ratingOpened && actions != null) CinemaRatingDialog(media, actions.personal,
        onDismiss = { ratingOpened = false }, onChanged = { actions.revision++ })
}

@Composable
internal fun CinemaRatingDialog(media: MediaItem, store: CinemaPersonalStore, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var selected by remember(media) { mutableIntStateOf(store.rating(media) ?: 8) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = CinemaSurface,
        title = { Text("امتیاز من به ${media.title}", color = CinemaPaper) },
        text = { Column {
            Text("${java.text.NumberFormat.getIntegerInstance(java.util.Locale("fa", "IR")).format(selected)} / ۱۰",
                color = CinemaGold, style = MaterialTheme.typography.headlineLarge)
            Slider(selected.toFloat(), { selected = it.toInt().coerceIn(1, 10) }, valueRange = 1f..10f, steps = 8)
            Text("روی همین دستگاه و برای همین پروفایل ذخیره می‌شود.", color = CinemaSoft, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ store.setRating(media, selected); onChanged(); onDismiss() }) { Text("ذخیره") } },
        dismissButton = { Row {
            if (store.rating(media) != null) TextButton({ store.setRating(media, null); onChanged(); onDismiss() }) { Text("حذف امتیاز") }
            TextButton(onDismiss) { Text("انصراف") }
        } })
}

/** Decode to the display's needs. No full-resolution artwork or transformed poster-wall layers. */
@Composable
internal fun DiscoveryArtwork(path: String?, backdrop: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = path?.takeIf { it.isNotBlank() && it != "null" }?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it
        else "https://image.tmdb.org/t/p/" + (if (backdrop) "w780" else "w342") + it
    }
    Box(modifier.background(CinemaSurface), contentAlignment = Alignment.Center) {
        Icon(if (backdrop) Icons.Outlined.Theaters else Icons.Outlined.Movie, null, tint = CinemaSoft.copy(alpha = .2f), modifier = Modifier.size(32.dp))
        if (url != null) AsyncImage(model = ImageRequest.Builder(context).data(url)
            .size(if (backdrop) 1000 else 342, if (backdrop) 600 else 513).crossfade(180).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun DiscoveryRatingBadge(media: MediaItem, modifier: Modifier = Modifier) {
    if (!media.vote.isFinite() || media.vote <= 0.0) return
    Surface(color = CinemaInk.copy(alpha = .9f), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text("★ ${formatVote(media.vote)} · TMDB", color = CinemaGold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
    }
}

@Composable
internal fun DiscoveryDubBadge(title: DiscoveryTitle, modifier: Modifier = Modifier) {
    if (!title.isPersianDubbed) return
    Surface(color = CinemaInk.copy(alpha = .92f), shape = RoundedCornerShape(6.dp), modifier = modifier.testTag("discovery-dub-${cinemaMediaKey(title.media)}")) {
        Text("دوبله فارسی", color = CinemaGold, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
internal fun DiscoveryPosterCard(title: DiscoveryTitle, backend: BackendRepository, onMedia: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    val media = title.media
    Column(modifier.testTag("discovery-poster-${cinemaMediaKey(media)}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
            DiscoveryArtwork(media.posterPath, false, Modifier.fillMaxSize().clickable(role = Role.Button,
                onClickLabel = "جزئیات ${media.title}") { onMedia(media) }.testTag("poster-${cinemaMediaKey(media)}"))
            Box(Modifier.fillMaxWidth().height(90.dp).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(listOf(Color.Transparent, CinemaInk.copy(alpha = .92f)))))
            DiscoveryRatingBadge(media, Modifier.align(Alignment.TopStart).padding(7.dp))
            DiscoveryDubBadge(title, Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 48.dp))
            Surface(color = CinemaInk.copy(alpha = .76f), shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.BottomEnd)) {
                DiscoveryWatchlistButton(media, backend)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp).clickable(role = Role.Button) { onMedia(media) }) {
                Text(media.title, color = CinemaPaper, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(media.year.ifBlank { if (media.type == MediaType.MOVIE) "فیلم" else "سریال" }, color = CinemaSoft, fontSize = 12.sp)
            }
            DiscoveryContextMenu(media, backend)
        }
    }
}

@Composable
internal fun DiscoveryBackdropCard(title: DiscoveryTitle, backend: BackendRepository, onMedia: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(CinemaSurface)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            DiscoveryArtwork(title.media.backdropPath ?: title.media.posterPath, true, Modifier.fillMaxSize().clickable(role = Role.Button) { onMedia(title.media) })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, CinemaSurface))))
            DiscoveryRatingBadge(title.media, Modifier.align(Alignment.TopStart).padding(8.dp))
            DiscoveryDubBadge(title, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp).clickable(role = Role.Button) { onMedia(title.media) }) {
                Text(title.media.title, color = CinemaPaper, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(title.media.year, title.runtimeMinutes?.let(::cinemaDuration), title.seasonCount?.let { "$it فصل" })
                    .filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = CinemaSoft, fontSize = 12.sp)
            }
            DiscoveryWatchlistButton(title.media, backend)
        }
    }
}

@Composable
internal fun RankedDiscoveryCard(title: DiscoveryTitle, rank: Int, backend: BackendRepository, onMedia: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(CinemaSurface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString().padStart(2, '0'), color = CinemaGold, fontSize = 33.sp, fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.displaySmall.copy(textDirection = TextDirection.Ltr), modifier = Modifier.widthIn(min = 54.dp).padding(end = 10.dp))
        DiscoveryArtwork(title.media.posterPath, false, Modifier.width(65.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(7.dp))
            .clickable(role = Role.Button) { onMedia(title.media) })
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable(role = Role.Button) { onMedia(title.media) }) {
            Text(title.media.title, color = CinemaPaper, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(title.media.year, color = CinemaSoft, fontSize = 12.sp)
            DiscoveryRatingBadge(title.media, Modifier.padding(top = 6.dp))
        }
        DiscoveryWatchlistButton(title.media, backend)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CinematicDiscoveryHero(title: DiscoveryTitle, backend: BackendRepository, onMedia: (MediaItem) -> Unit, eyebrow: String, series: Boolean = false) {
    val context = LocalContext.current
    val window = LocalConfiguration.current
    var detail by remember(title.media.key) { mutableStateOf<CinemaTitleData?>(null) }
    LaunchedEffect(title.media.key, backend) { detail = cinemaUiOptional { CinemaDataRepository(context, backend).title(title.media) } }
    val media = detail?.detail?.media ?: title.media
    val info = detail?.detail
    val accent = if (series) FqBlue else CinemaGold
    val artHeight = if (window.screenWidthDp >= 600) 390.dp else if (window.screenHeightDp < 480) 220.dp else 340.dp
    Box(Modifier.fillMaxWidth().testTag(if (series) "series-discovery-hero" else "movies-discovery-hero")) {
        DiscoveryArtwork(media.backdropPath ?: media.posterPath, true, Modifier.fillMaxWidth().height(artHeight))
        Box(Modifier.fillMaxWidth().height(artHeight + 70.dp).background(Brush.verticalGradient(
            0f to CinemaInk.copy(alpha = .15f), .25f to CinemaInk.copy(alpha = .2f), .7f to CinemaInk.copy(alpha = .86f), 1f to CinemaInk)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = if (window.screenHeightDp < 480) 100.dp else 165.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(eyebrow, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            info?.logoPath?.let{path->AsyncImage(model=ImageRequest.Builder(context).data("https://image.tmdb.org/t/p/w300$path")
                .size(450,150).crossfade(180).build(),contentDescription=null,contentScale=ContentScale.Fit,
                modifier=Modifier.widthIn(max=240.dp).heightIn(max=70.dp))}
            Text(media.title, color = CinemaPaper, style = MaterialTheme.typography.displayMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(role = Role.Button) { onMedia(media) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (media.year.isNotBlank()) Text(media.year, color = CinemaPaper, style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
                val runtime = info?.runtime?.takeIf { it > 0 } ?: title.runtimeMinutes
                if (runtime != null && runtime > 0) Text(cinemaDuration(runtime), color = CinemaSoft, style = MaterialTheme.typography.labelLarge)
                if (series) {
                    val seasons = info?.seasons?.count { it.number > 0 }?.takeIf { it > 0 } ?: title.seasonCount
                    if (seasons != null && seasons > 0) Text("$seasons فصل", color = CinemaSoft, style = MaterialTheme.typography.labelLarge)
                    val status = info?.status?.takeIf(String::isNotBlank) ?: title.status
                    status?.let { Text(discoveryStatusLabel(it), color = accent, style = MaterialTheme.typography.labelLarge) }
                }
                DiscoveryRatingBadge(media)
                DiscoveryDubBadge(title)
                info?.certification?.let{Text("US · $it",color=CinemaSoft,style=MaterialTheme.typography.labelMedium.copy(textDirection=TextDirection.Ltr))}
            }
            if (!info?.genres.isNullOrEmpty()) Text(info!!.genres.take(3).joinToString(" · "), color = CinemaSoft, style = MaterialTheme.typography.bodySmall)
            if (media.overview.isNotBlank()) Text(media.overview, color = CinemaPaper.copy(alpha = .9f),
                style = MaterialTheme.typography.bodyMedium, maxLines = if (window.fontScale >= 1.6f) 3 else 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ onMedia(media) }, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    .testTag(if (series) "series-hero-details" else "movies-hero-details"),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = CinemaInk)) {
                    Text(if (series) "فصل‌ها و جزئیات" else "جزئیات و نسخه‌ها", fontWeight = FontWeight.Bold)
                }
                Surface(color = CinemaSurface, shape = RoundedCornerShape(10.dp)) { DiscoveryWatchlistButton(media, backend) }
            }
            info?.trailerKey?.takeIf(String::isNotBlank)?.let { key ->
                TextButton({ openYoutube(context, key) }, Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("تماشای تریلر", color = CinemaPaper)
                }
            }
        }
    }
}

internal fun discoveryStatusLabel(value: String): String = when (value) {
    "Returning Series", "0" -> "در حال پخش"
    "Ended", "3" -> "پایان‌یافته"
    "Canceled", "Cancelled", "4" -> "متوقف‌شده"
    "In Production", "2" -> "در حال تولید"
    "Planned", "1" -> "در برنامهٔ تولید"
    else -> value
}

internal fun cinemaCountryLabel(code:String):String=when(code.uppercase(java.util.Locale.ROOT)){
    "IR"->"ایران";"TR"->"ترکیه";"KR"->"کرهٔ جنوبی";"IN"->"هند";"US"->"آمریکا";"GB"->"بریتانیا"
    "JP"->"ژاپن";"CN"->"چین";"ES"->"اسپانیا";"FR"->"فرانسه";"DE"->"آلمان";"EG"->"مصر";"SA"->"عربستان"
    else->code
}

@Composable
internal fun DiscoveryGenreExplorer(genres: List<DiscoveryGenre>, titles: List<DiscoveryTitle>, onGenre: (Int) -> Unit) {
    val window = LocalConfiguration.current
    val columns = if (window.screenWidthDp >= 600 && window.fontScale < 1.6f) 3 else 2
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        genres.take(12).chunked(columns).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { genre ->
                    val art = titles.firstOrNull { genre.id in it.genreIds }?.media
                    Box(Modifier.weight(1f).heightIn(min = if (window.fontScale >= 1.6f) 125.dp else 100.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(CinemaSurface, Color(0xFF25222B))))
                        .clickable(role = Role.Button) { onGenre(genre.id) }.testTag("discovery-genre-${genre.id}")) {
                        if (art != null) DiscoveryArtwork(art.backdropPath ?: art.posterPath, true, Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(CinemaInk.copy(alpha = .9f), CinemaInk.copy(alpha = .45f)))))
                        Column(Modifier.padding(14.dp).align(Alignment.BottomStart)) {
                            Text(genre.name, color = CinemaPaper, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("کشف عنوان‌ها", color = CinemaGold, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun DiscoverySkeleton(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(CinemaSurface, RoundedCornerShape(10.dp)))
            Box(Modifier.padding(top = 10.dp).fillMaxWidth(.8f).height(12.dp).background(CinemaLine))
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryFilterSheet(type: MediaType, filters: DiscoveryFilters, genres: List<DiscoveryGenre>, onApply: (DiscoveryFilters) -> Unit, onDismiss: () -> Unit) {
    var genre by rememberSaveable { mutableStateOf(filters.genreId) }
    var from by rememberSaveable { mutableStateOf(filters.yearFrom?.toString().orEmpty()) }
    var to by rememberSaveable { mutableStateOf(filters.yearTo?.toString().orEmpty()) }
    var rating by rememberSaveable { mutableStateOf(filters.minRating) }
    var country by rememberSaveable { mutableStateOf(filters.country) }
    var language by rememberSaveable { mutableStateOf(filters.language) }
    var runtime by rememberSaveable { mutableStateOf(filters.runtimeMax) }
    var status by rememberSaveable { mutableStateOf(filters.status) }
    var sort by rememberSaveable { mutableStateOf(filters.sort.name) }
    var dubbed by rememberSaveable { mutableStateOf(filters.persianDubbedOnly) }
    var subtitled by rememberSaveable { mutableStateOf(filters.persianSubtitleOnly) }
    var registryPicker by rememberSaveable { mutableStateOf<String?>(null) }
    val yearNow = LocalDate.now().year
    fun year(value: String) = cinemaSearchKey(value).toIntOrNull()
    val valid = (from.isBlank() || year(from) in 1900..yearNow) && (to.isBlank() || year(to) in 1900..yearNow) &&
        (from.isBlank() || to.isBlank() || year(from)!! <= year(to)!!)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CinemaSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("کشف دقیق‌تر", color = CinemaPaper, style = MaterialTheme.typography.headlineSmall)
            Text("فیلترها روی دادهٔ سرویس اعمال می‌شوند؛ امتیازها از TMDB هستند.", color = CinemaSoft, style = MaterialTheme.typography.bodySmall)
            Text("مرتب‌سازی", color = CinemaPaper, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(DiscoverySort.POPULAR to "محبوبیت", DiscoverySort.NEWEST to "تاریخ انتشار", DiscoverySort.RATING to "امتیاز", DiscoverySort.VOTES to "تعداد رأی").forEach { (value, label) ->
                    FilterChip(sort == value.name, { sort = value.name }, { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text("ژانر", color = CinemaPaper, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(genre == null, { genre = null }, { Text("همهٔ ژانرها") }, modifier = Modifier.heightIn(min = 48.dp))
                genres.forEach { item -> FilterChip(genre == item.id, { genre = item.id }, { Text(item.name) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(from, { from = it.take(4) }, label = { Text("از سال") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("discovery-year-from"))
                OutlinedTextField(to, { to = it.take(4) }, label = { Text("تا سال") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("discovery-year-to"))
            }
            if (!valid) Text("سال‌ها باید بین ۱۹۰۰ و $yearNow و به ترتیب باشند.", color = FqDanger, style = MaterialTheme.typography.bodySmall)
            Text("حداقل امتیاز TMDB", color = CinemaPaper, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(0.0, 6.0, 7.0, 8.0).forEach { value -> FilterChip(rating == value, { rating = value }, { Text(if (value == 0.0) "همه" else "${formatVote(value)}+") }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            Text("کشور سازنده", color = CinemaPaper, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(null to "جهان", "IR" to "ایران", "TR" to "ترکیه", "KR" to "کره", "IN" to "هند", "US" to "آمریکا", "GB" to "بریتانیا", "JP" to "ژاپن", "CN" to "چین", "ES" to "اسپانیا", "FR" to "فرانسه", "DE" to "آلمان", "EG" to "مصر", "SA" to "عربستان").forEach { (value, label) ->
                    FilterChip(country == value, { country = value }, { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            TextButton({registryPicker="countries"},Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text("همهٔ کشورها" + (country?.let{" · $it"} ?: ""))
            }
            Text("زبان اصلی", color = CinemaPaper, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(null to "همه", "fa" to "فارسی", "tr" to "Turkish", "en" to "English", "ko" to "Korean", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada", "bn" to "Bengali", "ja" to "Japanese", "zh" to "Chinese", "ar" to "Arabic", "es" to "Spanish", "fr" to "French", "de" to "German").forEach { (value, label) ->
                    FilterChip(language == value, { language = value }, { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            TextButton({registryPicker="languages"},Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text("همهٔ زبان‌ها" + (language?.let{" · $it"} ?: ""))
            }
            if (type == MediaType.MOVIE) {
                Text("حداکثر زمان فیلم", color = CinemaPaper, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf(null, 90, 120, 180).forEach { value ->
                    FilterChip(runtime == value, { runtime = value }, { Text(value?.let { "$it دقیقه" } ?: "هر مدت") }, modifier = Modifier.heightIn(min = 48.dp))
                } }
            } else {
                Text("وضعیت سریال", color = CinemaPaper, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf(null to "همه", "0" to "در حال پخش", "3" to "پایان‌یافته", "4" to "متوقف‌شده").forEach { (value, label) ->
                    FilterChip(status == value, { status = value }, { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                } }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("فقط دوبله فارسی", color = CinemaPaper, modifier = Modifier.weight(1f))
                Switch(dubbed, { dubbed = it }, modifier = Modifier.testTag("discovery-dub-filter"))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("فقط زیرنویس فارسی", color = CinemaPaper, modifier = Modifier.weight(1f))
                Switch(subtitled, { subtitled = it }, modifier = Modifier.testTag("discovery-subtitle-filter"))
            }
            Text("دوبله از نسخه‌های واردشدهٔ تلگرام تأیید می‌شود؛ زبان اصلی فیلم جای دوبله را نمی‌گیرد.", color = CinemaSoft, style = MaterialTheme.typography.bodySmall)
            Button({ onApply(DiscoveryFilters(genreId = genre, yearFrom = year(from), yearTo = year(to), minRating = rating,
                country = country, language = language, runtimeMax = if (type == MediaType.MOVIE) runtime else null,
                status = if (type == MediaType.TV) status else null, sort = DiscoverySort.valueOf(sort), persianDubbedOnly = dubbed, persianSubtitleOnly = subtitled)) },
                enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discovery-apply-filters")) { Text("نمایش عنوان‌ها") }
            TextButton({ genre = null; from = ""; to = ""; rating = 0.0; country = null; language = null; runtime = null; status = null; sort = DiscoverySort.POPULAR.name; dubbed = false; subtitled = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discovery-reset-filters")) { Text("پاک‌کردن فیلترها") }
        }
    }
    registryPicker?.let { kind -> DiscoveryRegistryPicker(kind,
        onSelected={value->if(kind=="countries")country=value else language=value;registryPicker=null},
        onDismiss={registryPicker=null}) }
}

/** The full registry is loaded only on demand; shortcuts are not the country/language architecture. */
@Composable
private fun DiscoveryRegistryPicker(kind:String,onSelected:(String)->Unit,onDismiss:()->Unit){
    val context=LocalContext.current
    val repository=remember{DiscoveryRepository(context,BackendRepository(context))}
    var values by remember(kind){mutableStateOf<List<Pair<String,String>>>(emptyList())}
    var query by rememberSaveable(kind){mutableStateOf("")}
    var error by remember(kind){mutableStateOf(false)}
    var loading by remember(kind){mutableStateOf(true)}
    var retry by remember{mutableIntStateOf(0)}
    LaunchedEffect(kind,retry){
        loading=true;error=false
        try{values=if(kind=="countries")repository.countries().map{it.code to it.name.ifBlank{it.englishName}}
            else repository.languages().map{it.code to it.name.ifBlank{it.englishName}}
        }catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{loading=false}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(kind=="countries")"کشور سازنده"else "زبان اصلی")},
        text={Column(Modifier.heightIn(max=460.dp)){
            OutlinedTextField(query,{query=it},label={Text("نام یا کد")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error)TextButton({retry++}){Text("فهرست دریافت نشد · تلاش دوباره")}
            val matches=values.filter{cinemaSearchKey(it.first+" "+it.second).contains(cinemaSearchKey(query))}
            if(!loading&&!error&&matches.isEmpty())Text("گزینه‌ای پیدا نشد",color=CinemaSoft)
            androidx.compose.foundation.lazy.LazyColumn {items(matches,key={it.first}){(code,label)->
                TextButton({onSelected(code)},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("$label · $code")}
            }}
        }},confirmButton={TextButton(onDismiss){Text("بستن")}})
}

@Composable
internal fun DiscoveryFullListScreen(repository: TmdbRepository, backend: BackendRepository, type: MediaType, section: DiscoverySection,
    initialFilters: DiscoveryFilters, title: String, onBack: () -> Unit, onMedia: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val loader = remember(backend) { DiscoveryRepository(context, backend) }
    // Reload page one on recreation; page N alone cannot restore request-backed items.
    var page by remember(type, section, initialFilters) { mutableIntStateOf(1) }
    var items by remember(type, section, initialFilters) { mutableStateOf<List<DiscoveryTitle>>(emptyList()) }
    var hasMore by remember(type, section, initialFilters) { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val ranked = section == DiscoverySection.TOP_RATED
    val window = LocalConfiguration.current
    BackHandler(onBack = onBack)
    LaunchedEffect(type, section, initialFilters, page, retry) {
        loading = true; error = null
        try {
            val result = loader.page(type, section, initialFilters, page)
            currentCoroutineContext().ensureActive()
            items = (if (page == 1) result.items else items + result.items).distinctBy { cinemaMediaKey(it.media) }.let { if (ranked) it.take(250) else it }
            hasMore = result.hasMore && (!ranked || items.size < 250)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "عنوان‌ها دریافت نشدند؛ دوباره تلاش کن." }
        finally { currentCoroutineContext().ensureActive(); loading = false }
    }
    DiscoveryActionScope(backend) {
        Column(Modifier.fillMaxSize().background(CinemaInk).testTag("discovery-full-list")) {
            CinemaPageHeader(title, if (ranked) "انتخاب بر اساس امتیاز و تعداد رأی TMDB؛ رتبه‌بندی IMDb نیست" else "فقط ${if (type == MediaType.MOVIE) "فیلم" else "سریال"} · دادهٔ واقعی سرویس", onBack)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CinemaAccent)
            LazyVerticalGrid(GridCells.Adaptive(if (ranked) if (window.fontScale >= 1.6f) 360.dp else 330.dp else if (window.fontScale >= 1.6f) 170.dp else 145.dp),
                modifier = Modifier.weight(1f).testTag("discovery-list-grid"), contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (items.isEmpty() && loading) item(span = { GridItemSpan(maxLineSpan) }) { DiscoverySkeleton() }
                if (items.isEmpty() && !loading && error == null) item(span = { GridItemSpan(maxLineSpan) }) {
                    CinemaNotice("این انتخاب فعلاً عنوانی ندارد", "فیلترها را تغییر بده یا یک ژانر دیگر را امتحان کن.", actionLabel = "بازگشت به کشف", onAction = onBack)
                }
                itemsIndexed(items, key = { _, value -> cinemaMediaKey(value.media) }) { index, value ->
                    if (ranked) RankedDiscoveryCard(value, index + 1, backend, onMedia)
                    else DiscoveryPosterCard(value, backend, onMedia)
                }
                error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                    CinemaNotice("این بخش به‌روز نشد", message, Icons.Outlined.CloudOff, "تلاش دوباره", { retry++ })
                } }
                if (hasMore && items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton({ page++ }, enabled = !loading && error == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discovery-load-more")) { Text("عنوان‌های بیشتر") }
                }
            }
        }
    }
}
