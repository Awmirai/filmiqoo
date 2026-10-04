package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal data class CinemaDetailActions(
    val back: () -> Unit = {}, val share: () -> Unit = {}, val save: () -> Unit = {},
    val favorite: () -> Unit = {}, val notes: () -> Unit = {}, val seen: () -> Unit = {},
    val collection: () -> Unit = {}, val availability: () -> Unit = {}, val follow: () -> Unit = {},
    val trailer: () -> Unit = {}, val spoiler: (Boolean) -> Unit = {},
    val play: (String) -> Unit = {}, val download: (String) -> Unit = {},
    val episodeSeen: (PlatformEpisode, Boolean) -> Unit = { _, _ -> },
    val person: (CastMember) -> Unit = {}, val media: (MediaItem) -> Unit = {}
)

/** Stateless media data + UI-only state. This screen can be exercised with deterministic fixtures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CinemaDetailContent(
    data: CinemaTitleData,
    saved: Boolean = false,
    favorite: Boolean = false,
    seen: Boolean = false,
    hideSpoilers: Boolean = true,
    hasNote: Boolean = false,
    following: Boolean = false,
    busy: Boolean = false,
    progress: SeriesWatchProgress? = null,
    actions: CinemaDetailActions = CinemaDetailActions(),
    community: @Composable () -> Unit = {}
) {
    val d = data.detail
    val media = d.media
    val isSeries = media.type == MediaType.TV
    val listState = rememberLazyListState()
    val showToolbarTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var tab by rememberSaveable(media.key) { mutableStateOf("about") }
    var selectedSeason by rememberSaveable(media.key) { mutableIntStateOf(data.platform?.seasons?.firstOrNull { it.number > 0 }?.number ?: 1) }
    var episodeFilter by rememberSaveable(media.key) { mutableIntStateOf(0) }
    var episodeSearch by rememberSaveable(media.key) { mutableStateOf("") }
    var versionsOpen by rememberSaveable(media.key) { mutableStateOf(false) }
    var selectedVersion by rememberSaveable(media.key) { mutableStateOf(data.playableMovies.firstOrNull { it.preferred }?.id ?: data.playableMovies.firstOrNull()?.id) }
    var nightsPace by rememberSaveable(media.key) { mutableIntStateOf(2) }
    val selectedMovie = data.playableMovies.firstOrNull { it.id == selectedVersion } ?: data.playableMovies.firstOrNull()
    val regularEpisodes = data.playableEpisodes.filter { it.first > 0 }.ifEmpty { data.playableEpisodes }
    val resumable = regularEpisodes.firstOrNull { (_, ep) -> progress?.episodes?.get(ep.id)?.let { !it.completed && it.positionMs > 0 } == true }
        ?: regularEpisodes.firstOrNull { (_, ep) -> progress?.episodes?.get(ep.id)?.completed != true }
        ?: regularEpisodes.firstOrNull()
    val primaryId = if (isSeries) resumable?.second?.mediaVersionId else selectedMovie?.id
    val season = data.platform?.seasons?.firstOrNull { it.number == selectedSeason } ?: data.platform?.seasons?.firstOrNull()
    val episodes = season?.episodes.orEmpty().sortedBy { it.number }.filter { ep ->
        (episodeSearch.isBlank() || ep.number.toString() == cinemaSearchKey(episodeSearch) || cinemaSearchKey(ep.name).contains(cinemaSearchKey(episodeSearch))) &&
            when (episodeFilter) { 1 -> ep.streamReady && !ep.mediaVersionId.isNullOrBlank(); 2 -> progress?.episodes?.get(ep.id)?.completed != true; else -> true }
    }
    val tabs = buildList { add("about" to "درباره"); if (isSeries) add("episodes" to "قسمت‌ها"); add("cast" to "بازیگران"); add("club" to "کلاب") }
    BackHandler { if (versionsOpen) versionsOpen = false else actions.back() }

    Scaffold(
        modifier = Modifier.testTag("cinema-detail").windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        containerColor = CinemaInk,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().background(CinemaInk).statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(actions.back) { Icon(Icons.Default.ArrowForward, "بازگشت", tint = CinemaPaper) }
                Text(if (showToolbarTitle) media.title else "FILMIQOO", color = CinemaPaper,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(actions.share) { Icon(Icons.Default.Share, "اشتراک‌گذاری عنوان", tint = CinemaPaper) }
            }
        },
        bottomBar = {
            Surface(color = CinemaInk, tonalElevation = 0.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = CinemaLine)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CinemaAction(if (busy) Icons.Default.HourglassTop else Icons.Default.PlayArrow,
                            when { busy -> "در حال آماده‌سازی…"; primaryId == null -> "هنوز قابل پخش نیست"; isSeries && progress?.episodes?.values?.any { it.positionMs > 0 || it.completed } == true -> "ادامهٔ سریال"; isSeries -> "شروع تماشا"; else -> "پخش فیلم" },
                            { primaryId?.let(actions.play) }, Modifier.weight(1f).testTag("detail-primary"), primary = true, enabled = !busy && primaryId != null)
                        if (isSeries) {
                            OutlinedIconButton({ tab = "episodes" }, modifier = Modifier.size(52.dp).testTag("detail-episodes-shortcut"), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Default.FormatListNumbered, "انتخاب قسمت", tint = CinemaPaper)
                            }
                        } else {
                            OutlinedIconButton({ versionsOpen = true }, enabled = !busy && data.playableMovies.isNotEmpty(), modifier = Modifier.size(52.dp).testTag("detail-versions"), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Default.Download, "کیفیت و دانلود", tint = CinemaPaper)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).testTag("detail-scroll"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("hero") { CinemaTitleHero(data) }
            item("quick-actions") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    item { CinemaTag(if (saved) "✓ در فهرست من" else "+ فهرست من", saved, actions.save) }
                    item { CinemaTag(if (favorite) "♥ محبوب من" else "♡ پسندیدم", favorite, actions.favorite) }
                    item { CinemaTag(if (seen) "✓ دیده‌ام" else "دیده‌ام", seen, actions.seen) }
                    item { CinemaTag(if (hasNote) "یادداشت من •" else "یادداشت من", hasNote, actions.notes) }
                    if (isSeries && data.platform != null) item { CinemaTag(if (following) "✓ دنبال می‌کنم" else "خبر قسمت جدید", following, actions.follow) }
                }
            }
            if (!data.metadataAvailable) item("metadata-warning") {
                Text("اطلاعات تکمیلی فعلاً دریافت نشد؛ فایل‌های موجود همچنان در دسترس‌اند.", color = CinemaSoft, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item("availability") {
                CinemaCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (primaryId != null) Icons.Default.Verified else Icons.Default.Info, null, tint = if (primaryId != null) FqGreen else CinemaSoft)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (primaryId != null) "آمادهٔ تماشا در فیلمیکو" else "فعلاً فقط اطلاعات عنوان موجود است", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CinemaPaper)
                            Text(if (isSeries) "${data.playableEpisodes.size} قسمت دارای فایل پخش" else if (selectedMovie != null) listOf(selectedMovie.quality, selectedMovie.codec, cinemaBytes(selectedMovie.fileSizeBytes)).filter(String::isNotBlank).joinToString(" · ") else "ثبت در فهرست من، به معنی موجود بودن فایل فیلم نیست.",
                                fontSize = 12.sp, lineHeight = 19.sp, color = CinemaSoft, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        if (!isSeries && data.playableMovies.isNotEmpty()) TextButton({ versionsOpen = true }) { Text("انتخاب کیفیت و دانلود", color = CinemaAccent) }
                        if (data.platform != null) TextButton(actions.availability) { Text("خبر موجود شدن نسخه", color = CinemaAccent) }
                    }
                }
            }
            item("tabs") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tabs, key = { it.first }) { (key, label) ->
                        Box(Modifier.testTag("detail-tab-$key")) { CinemaTag(label, tab == key) { tab = key } }
                    }
                }
            }
            when (tab) {
                "about" -> {
                    item("overview") {
                        CinemaCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("داستان، بدون اسپویل", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CinemaPaper, modifier = Modifier.weight(1f))
                                Switch(hideSpoilers, actions.spoiler, modifier = Modifier.testTag("spoiler-switch"))
                            }
                            Text(if (hideSpoilers) "خلاصهٔ داستان پنهان است. برای خواندن، محافظ اسپویل را خاموش کن." else media.overview.ifBlank { "هنوز خلاصه‌ای برای این عنوان ثبت نشده است." },
                                fontSize = 14.sp, lineHeight = 25.sp, color = CinemaSoft, modifier = Modifier.padding(top = 8.dp))
                            if (d.trailerKey != null) TextButton(actions.trailer) { Icon(Icons.Default.PlayCircle, null); Spacer(Modifier.width(7.dp)); Text("تماشای تریلر") }
                        }
                    }
                    item("planning") {
                        CinemaHeading("برنامهٔ تماشای من", "برآورد ساده، بر اساس اطلاعات موجود؛ نه زمان قطعی")
                        CinemaCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                            if (isSeries) {
                                val total = maxOf(data.platform?.seasons?.filter { it.number > 0 }?.sumOf { it.episodes.size } ?: 0,
                                    d.seasons.filter { it.number > 0 }.sumOf { it.episodes }).coerceAtLeast(0)
                                val remaining = (total - (progress?.watchedCount?.toInt() ?: 0)).coerceAtLeast(0)
                                Text("$remaining قسمت باقی‌مانده", color = CinemaPaper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(1, 2, 3).forEach { pace -> CinemaTag("شبی $pace", nightsPace == pace) { nightsPace = pace } }
                                }
                                Text(if (remaining > 0) "حدود ${CinemaPlanning.remainingNights(remaining, nightsPace)} شب تا پایان قسمت‌های ثبت‌شده" else "تعداد قسمت‌ها هنوز مشخص نیست یا همه را دیده‌ای.", color = CinemaSoft, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 12.dp))
                            } else {
                                Text(cinemaDuration(d.runtime), color = CinemaPaper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                if (d.runtime > 0) Text("با سرعت ۱٫۲۵ برابر: حدود ${CinemaPlanning.playbackMinutes(d.runtime, 1.25f)} دقیقه", color = CinemaSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                            TextButton(actions.notes) { Text("${if (hasNote) "ویرایش" else "افزودن"} یادداشت خصوصی", color = CinemaAccent) }
                        }
                    }
                    if (d.franchise != null && d.franchise.parts.isNotEmpty()) item("collection-order") {
                        CinemaShelf("ترتیب تماشای مجموعه", d.franchise.name, d.franchise.parts, actions.media)
                    }
                    if (d.cast.isNotEmpty()) item("cast-preview") { CinemaPeopleRow("چهره‌های این عنوان", d.cast.take(10), actions.person) }
                    item("facts") {
                        CinemaHeading("شناسنامهٔ عنوان")
                        CinemaCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                            CinemaFact("نام اصلی", media.originalTitle.ifBlank { media.title })
                            if (data.englishTitle.isNotBlank() && data.englishTitle != media.title) CinemaFact("نام انگلیسی", data.englishTitle)
                            CinemaFact("محصول", data.originCountries.joinToString(" · ").ifBlank { "ثبت نشده" })
                            CinemaFact("زبان‌ها", data.languages.joinToString(" · ").ifBlank { data.originalLanguage.ifBlank { "ثبت نشده" } })
                            CinemaFact("انتشار", media.date.ifBlank { "ثبت نشده" })
                            CinemaFact("وضعیت", when (d.status) { "Ended" -> "پایان‌یافته"; "Returning Series" -> "در حال پخش"; "Released" -> "منتشرشده"; "Canceled" -> "لغوشده"; else -> d.status.ifBlank { "ثبت نشده" } })
                            if (data.platform != null) TextButton(actions.collection) { Text("افزودن به کالکشن", color = CinemaAccent) }
                        }
                    }
                    if (d.recommendations.isNotEmpty()) item("related") { CinemaShelf("بعد از این چه ببینم؟", "پیشنهادهای مرتبط با این عنوان", d.recommendations, actions.media) }
                }
                "episodes" -> {
                    if (data.platform?.seasons.isNullOrEmpty()) {
                        item("metadata-seasons") {
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                CinemaNotice("قسمت قابل پخش هنوز ثبت نشده", "اطلاعات فصل‌ها ممکن است موجود باشد، اما فیلمیکو برای این عنوان هنوز فایل قسمت ندارد.")
                                d.seasons.forEach { s -> Text("فصل ${s.number} · ${s.episodes} قسمت", color = CinemaSoft, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp)) }
                            }
                        }
                    } else {
                        item("season-selector") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(data.platform.seasons, key = { it.id }) { s -> CinemaTag(if (s.number == 0) "ویژه‌ها" else "فصل ${s.number}", season?.id == s.id) { selectedSeason = s.number; episodeSearch = "" } }
                            }
                        }
                        item("episode-filters") {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                                OutlinedTextField(episodeSearch, { episodeSearch = it.take(80) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text("شماره یا نام قسمت") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(16.dp))
                                LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    items(listOf(0 to "همه", 1 to "قابل پخش", 2 to "ندیده")) { (index, name) -> CinemaTag(name, episodeFilter == index) { episodeFilter = index } }
                                }
                                progress?.let { Text("${it.watchedCount} از ${it.totalCount} قسمت دیده شده", color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)) }
                            }
                        }
                        if (episodes.isEmpty()) item("empty-episodes") {
                            Box(Modifier.padding(20.dp)) { CinemaNotice("قسمتی با این فیلتر پیدا نشد", "فیلتر یا عبارت جستجو را تغییر بده.") }
                        }
                        items(episodes, key = { "episode:${it.id}" }) { ep ->
                            CinemaEpisodeRow(ep, season?.number ?: 1, progress?.episodes?.get(ep.id), hideSpoilers, busy, actions)
                        }
                    }
                }
                "cast" -> {
                    if (d.cast.isNotEmpty()) item("people") { CinemaPeopleRow("بازیگران", d.cast, actions.person) }
                    if (d.directors.isNotEmpty()) item("creators") { CinemaPeopleRow(if (isSeries) "سازندگان و کارگردانان" else "کارگردان", d.directors, actions.person) }
                    if (d.cast.isEmpty() && d.directors.isEmpty()) item("empty-cast") { Box(Modifier.padding(20.dp)) { CinemaNotice("اطلاعات عوامل موجود نیست", "این بخش پس از دریافت اطلاعات معتبر نمایش داده می‌شود.") } }
                }
                "club" -> item("community") { community() }
            }
        }
    }
    if (versionsOpen) {
        ModalBottomSheet(onDismissRequest = { versionsOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CinemaInk) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("نسخه‌ای که مناسب توست", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CinemaPaper) }
                item { Text("کیفیت و حجم واقعی فایل‌های موجود؛ دانلود به صف اضافه می‌شود.", color = CinemaSoft, fontSize = 13.sp, lineHeight = 21.sp) }
                items(data.playableMovies, key = { it.id }) { version ->
                    CinemaCard(Modifier.fillMaxWidth(), accent = selectedMovie?.id == version.id) {
                        Text(version.quality.ifBlank { "نسخهٔ اصلی" }, color = CinemaPaper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(listOf(cinemaBytes(version.fileSizeBytes), version.codec, version.hdr).filter(String::isNotBlank).joinToString(" · "),
                            color = CinemaSoft, fontSize = 13.sp, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                        if (version.audioTracks.isNotEmpty()) Text("صدا: " + version.audioTracks.joinToString(" · "), color = CinemaPaper, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        if (version.subtitleTracks.isNotEmpty()) Text("زیرنویس: " + version.subtitleTracks.joinToString(" · "), color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CinemaAction(Icons.Default.PlayArrow, "پخش", { selectedVersion = version.id; versionsOpen = false; actions.play(version.id) }, Modifier.weight(1f), primary = true, enabled = !busy)
                            CinemaAction(Icons.Default.Download, "دانلود", { selectedVersion = version.id; versionsOpen = false; actions.download(version.id) }, Modifier.weight(1f).testTag("download-${version.id}"), enabled = !busy)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaTitleHero(data: CinemaTitleData) {
    val media = data.detail.media
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val heroHeight = (maxWidth * .88f).coerceIn(310.dp, 420.dp) * LocalDensity.current.fontScale.coerceIn(1f, 1.7f)
        Box(Modifier.fillMaxWidth().height(heroHeight)) {
            CinemaImage(media.backdropPath ?: media.posterPath, Modifier.fillMaxSize(), backdrop = true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .04f), CinemaInk.copy(alpha = .42f), CinemaInk))))
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.Bottom) {
                Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .2f)), shadowElevation = 10.dp) {
                    CinemaImage(media.posterPath, Modifier.width(104.dp).aspectRatio(2f / 3f))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (media.type == MediaType.MOVIE) "فیلم سینمایی" else "سریال", color = CinemaAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(media.title, color = CinemaPaper, fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Black,
                        maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                    Text(listOf(media.year, if (data.detail.runtime > 0) cinemaDuration(data.detail.runtime) else "").filter(String::isNotBlank).joinToString(" · "),
                        color = CinemaSoft, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 9.dp))
                    if (media.vote > 0) Text("★ " + String.format(Locale.US, "%.1f", media.vote) + "  TMDB", color = CinemaGold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
    }
    if (data.detail.genres.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(data.detail.genres.distinct()) { CinemaTag(it) }
    }
}

@Composable
private fun CinemaEpisodeRow(ep: PlatformEpisode, season: Int, progress: EpisodeWatchState?, hideSpoilers: Boolean, busy: Boolean, actions: CinemaDetailActions) {
    val ready = ep.streamReady && !ep.mediaVersionId.isNullOrBlank()
    val completed = progress?.completed == true
    Surface(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().testTag("episode-${ep.id}"), color = CinemaSurface,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, if (completed) FqGreen.copy(alpha = .25f) else CinemaLine)) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(92.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(11.dp)).clickable(enabled = ready && !busy) { ep.mediaVersionId?.let(actions.play) }) {
                    if (hideSpoilers) {
                        Box(Modifier.fillMaxSize().background(CinemaLine), contentAlignment = Alignment.Center) {
                            if (!ready) Icon(Icons.Default.VisibilityOff, "تصویر قسمت برای جلوگیری از اسپویل پنهان است", tint = CinemaSoft)
                        }
                    } else CinemaImage(ep.stillUrl, Modifier.fillMaxSize(), backdrop = true)
                    if (ready) Icon(Icons.Default.PlayCircle, "پخش قسمت ${ep.number}", modifier = Modifier.align(Alignment.Center).size(29.dp), tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(cinemaEpisodeLabel(season, ep.number), color = CinemaAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(if (hideSpoilers) "قسمت ${ep.number}" else ep.name.ifBlank { "قسمت ${ep.number}" }, color = CinemaPaper, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    Text(if (ready) listOf(ep.quality.orEmpty(), if (ep.runtimeMinutes > 0) "${ep.runtimeMinutes} دقیقه" else "").filter(String::isNotBlank).joinToString(" · ") else "فایل موجود نیست", color = CinemaSoft, fontSize = 12.sp)
                }
                IconButton({ ep.mediaVersionId?.let(actions.download) }, enabled = ready && !busy) { Icon(Icons.Default.Download, "دانلود قسمت ${ep.number}", tint = if (ready) CinemaPaper else CinemaSoft.copy(alpha = .4f)) }
            }
            if (!hideSpoilers && ep.overview.isNotBlank()) Text(ep.overview, color = CinemaSoft, fontSize = 13.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if ((progress?.progress ?: 0f) > 0f) LinearProgressIndicator(progress = { progress?.progress?.coerceIn(0f, 1f) ?: 0f }, color = if (completed) FqGreen else CinemaAccent,
                trackColor = CinemaLine, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            TextButton({ actions.episodeSeen(ep, !completed) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(if (completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (completed) FqGreen else CinemaSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (completed) "دیده شده" else "علامت‌گذاری به‌عنوان دیده‌شده", color = CinemaSoft, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CinemaPeopleRow(title: String, people: List<CastMember>, onPerson: (CastMember) -> Unit) {
    Column {
        CinemaHeading(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            items(people.distinctBy { it.id }, key = { it.id }) { person ->
                Column(Modifier.width(110.dp).clickable { onPerson(person) }) {
                    CinemaImage(person.profilePath, Modifier.fillMaxWidth().aspectRatio(.8f).clip(RoundedCornerShape(17.dp)))
                    Text(person.name, color = CinemaPaper, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, minLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(person.character, color = CinemaSoft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CinemaFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = CinemaSoft, fontSize = 12.sp)
        Text(value, color = CinemaPaper, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 3.dp))
    }
}
