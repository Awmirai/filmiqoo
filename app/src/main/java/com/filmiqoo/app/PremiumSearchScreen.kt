package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSearchScreen(repository: TmdbRepository, backend: BackendRepository, onBack: () -> Unit, onMedia: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit, onOpenPost: (String) -> Unit, onOpenClip: (String) -> Unit, onDiscover: (() -> Unit)? = null) {
    val context = LocalContext.current
    val search = remember(backend) { UniversalSearchRepository(context.applicationContext, backend) }
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalConfiguration.current
    val compact = window.screenHeightDp < 480 || window.fontScale >= 1.6f
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var playableOnly by rememberSaveable { mutableStateOf(false) }
    var orderName by rememberSaveable { mutableStateOf(CinematicSearchOrder.RELEVANCE.name) }
    val order = CinematicSearchOrder.entries.firstOrNull { it.name == orderName } ?: CinematicSearchOrder.RELEVANCE
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var catalogRetry by remember { mutableIntStateOf(0) }
    var titles by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var starters by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var history by remember { mutableStateOf(search.history()) }
    var loading by remember { mutableStateOf(false) }
    var catalogLoading by remember { mutableStateOf(true) }
    var catalogFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val normalized = remember(query) { cinemaSearchKey(query) }
    val gridState = rememberLazyGridState()
    val listKey = listOf(normalized, filter.toString(), playableOnly.toString(), orderName).joinToString("|")
    var lastListKey by rememberSaveable { mutableStateOf(listKey) }
    val hasFilters = filter != 0 || playableOnly || order != CinematicSearchOrder.RELEVANCE
    val visible = remember(titles, filter, playableOnly, order) { cinematicSearchTitles(titles, filter, playableOnly, order) }
    val starterTitles = remember(starters, filter, playableOnly, order) { cinematicSearchTitles(starters, filter, playableOnly, order).take(8) }
    val artwork = remember(starters) { cinematicSearchArtwork(starters) }
    fun resetFilters() { filter = 0; playableOnly = false; orderName = CinematicSearchOrder.RELEVANCE.name }
    fun openTitle(media: MediaItem) {
        if (normalized.length >= 2) { search.recordHistory(query.trim()); history = search.history() }
        keyboard?.hide(); onMedia(media)
    }
    BackHandler(onBack = onBack)

    LaunchedEffect(listKey) {
        // Reset for an explicit query/filter change, while preserving scroll on a restored tab.
        if (lastListKey != listKey) { gridState.scrollToItem(0); lastListKey = listKey }
    }

    // Real service artwork stays still while typing; no invented recommendations or automatic carousel.
    LaunchedEffect(repository, catalogRetry) {
        catalogLoading = true; catalogFailed = false
        try {
            val loaded = repository.trending()
            currentCoroutineContext().ensureActive()
            starters = loaded.distinctBy(::cinemaMediaKey).take(24)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { catalogFailed = true }
        finally { currentCoroutineContext().ensureActive(); catalogLoading = false }
    }
    LaunchedEffect(normalized, retry) {
        titles = emptyList(); error = null; loading = normalized.length >= 2
        if (normalized.length < 2) return@LaunchedEffect
        delay(350)
        try {
            val local = search.search(normalized).media
            val result = if (local.isNotEmpty()) local else repository.search(normalized)
            currentCoroutineContext().ensureActive()
            titles = result.distinctBy(::cinemaMediaKey)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "جست‌وجو انجام نشد. اتصال اینترنت را بررسی کن." }
        finally { currentCoroutineContext().ensureActive(); loading = false }
    }
    Box(Modifier.fillMaxSize().background(CinemaInk).imePadding().testTag("cinema-search")) {
        SearchPosterAtmosphere(artwork, compact, normalized.isNotEmpty())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Outlined.ArrowForward, "بازگشت", tint = CinemaPaper) }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("جست‌وجو", fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = CinemaPaper)
                    if (!compact) Text("از یک اسم، به یک دنیای تازه", fontSize = 12.sp, color = CinemaSoft)
                }
                IconButton({ keyboard?.hide(); showFilters = true }, modifier = Modifier.testTag("search-options")) {
                    BadgedBox(badge = { if (hasFilters) Badge(containerColor = CinemaAccent) }) {
                        Icon(Icons.Outlined.Tune, "تنظیم نتیجه‌ها", tint = CinemaPaper)
                    }
                }
            }
            OutlinedTextField(query, { query = it.take(120) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("search-input"),
                label = { Text("نام فیلم یا سریال") }, placeholder = { Text("فارسی یا English", color = CinemaSoft) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = CinemaAccent) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }, modifier = Modifier.testTag("search-clear")) { Icon(Icons.Outlined.Close, "پاک‌کردن جست‌وجو") } },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CinemaSurface.copy(alpha = .96f), unfocusedContainerColor = CinemaSurface.copy(alpha = .94f),
                    focusedBorderColor = CinemaAccent, unfocusedBorderColor = Color.White.copy(alpha = .19f), focusedTextColor = CinemaPaper, unfocusedTextColor = CinemaPaper, cursorColor = CinemaAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (normalized.length >= 2) { search.recordHistory(query.trim()); history = search.history() }
                    keyboard?.hide()
                }))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("search-type-filters")) {
                listOf("همه", "فیلم", "سریال").forEachIndexed { index, label -> item(key = index) {
                    FilterChip(filter == index, { filter = index }, label = { Text(label, fontWeight = if (filter == index) FontWeight.Bold else FontWeight.Medium) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("search-type-$index"),
                        colors = FilterChipDefaults.filterChipColors(containerColor = CinemaInk.copy(alpha = .76f), labelColor = CinemaSoft,
                            selectedContainerColor = CinemaAccent.copy(alpha = .2f), selectedLabelColor = CinemaPaper))
                } }
                if (playableOnly) item {
                    InputChip(true, { playableOnly = false }, label = { Text("آمادهٔ پخش") },
                        trailingIcon = { Icon(Icons.Outlined.Close, "برداشتن فیلتر پخش", Modifier.size(16.dp)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("search-playable-active"))
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("search-loading"), color = CinemaAccent, trackColor = CinemaLine)
            LazyVerticalGrid(GridCells.Adaptive(if (window.fontScale >= 1.6f) 160.dp else 136.dp),
                state = gridState, modifier = Modifier.weight(1f).testTag("search-results"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (normalized.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "welcome") {
                        Column(Modifier.padding(top = if (compact) 0.dp else 8.dp, bottom = 4.dp)) {
                            if (!compact) Text("یک انتخاب تازه", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CinemaGold)
                            Text("چی دوست داری ببینی؟", fontSize = if (compact) 22.sp else 30.sp, lineHeight = if (compact) 32.sp else 42.sp,
                                fontWeight = FontWeight.Bold, color = CinemaPaper, modifier = Modifier.padding(top = 5.dp))
                            Text("نام فارسی یا انگلیسی یک اثر را بنویس.", fontSize = 13.sp, lineHeight = 21.sp, color = CinemaSoft, modifier = Modifier.padding(top = 6.dp))
                            if (onDiscover != null) OutlinedButton({ keyboard?.hide(); onDiscover() }, shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CinemaPaper, containerColor = CinemaInk.copy(alpha = .58f)),
                                modifier = Modifier.padding(top = 14.dp).heightIn(min = 48.dp).testTag("search-discover")) {
                                Icon(Icons.Outlined.TravelExplore, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("مرور کشور، ژانر و سال")
                            }
                        }
                    }
                    if (history.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "history") {
                        SearchHistory(history, onQuery = { query = it }, onClear = { search.clearHistory(); history = emptyList() })
                    }
                    if (catalogLoading && starters.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-loading") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CinemaAccent); Spacer(Modifier.width(12.dp)); Text("در حال دریافت عنوان‌ها…", color = CinemaSoft, fontSize = 13.sp)
                        }
                    }
                    if (starterTitles.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "starter-heading") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("برای شروع", color = CinemaPaper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("عنوان‌های دریافت‌شده از سرویس", color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                                Icon(Icons.Outlined.Movie, null, tint = CinemaGold, modifier = Modifier.size(24.dp))
                            }
                        }
                        items(starterTitles, key = { "starter-" + cinemaMediaKey(it) }) { media -> CinemaPoster(media, { openTitle(media) }, Modifier.fillMaxWidth()) }
                    } else if (!catalogLoading && starters.isNotEmpty() && hasFilters) item(span = { GridItemSpan(maxLineSpan) }, key = "starter-filter-empty") {
                        CinemaNotice("این انتخاب نتیجه‌ای ندارد", "فیلترها را بردار یا نام یک اثر را جست‌وجو کن.", Icons.Outlined.FilterAltOff, "برداشتن فیلترها", ::resetFilters)
                    } else if (catalogFailed && starters.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-error") {
                        CinemaNotice("پوسترها دریافت نشدند", "می‌توانی نام اثر را جست‌وجو کنی یا دریافت عنوان‌های این صفحه را دوباره امتحان کنی.", Icons.Outlined.CloudOff, "دریافت دوباره", { catalogRetry++ })
                    }
                } else if (normalized.length < 2) item(span = { GridItemSpan(maxLineSpan) }, key = "short-query") {
                    Text("حداقل دو حرف بنویس.", color = CinemaSoft, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    if (!loading && error == null && visible.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "result-heading") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("نتیجه‌های جست‌وجو", color = CinemaPaper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(visible.size.toString() + " عنوان · " + order.label, color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            TextButton({ keyboard?.hide(); showFilters = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("search-sort")) {
                                Text("ترتیب", color = CinemaAccent); Spacer(Modifier.width(5.dp)); Icon(Icons.Outlined.Sort, null, Modifier.size(20.dp), tint = CinemaAccent)
                            }
                        }
                    }
                    error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }, key = "search-error") { CinemaNotice("ارتباط برقرار نشد", message, Icons.Outlined.CloudOff, "تلاش دوباره", { retry++ }) } }
                    if (!loading && error == null && visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "search-empty") {
                        val filteredOut = titles.isNotEmpty() && hasFilters
                        CinemaNotice(if (filteredOut) "با این فیلتر، عنوانی پیدا نشد" else "عنوانی پیدا نشد",
                            if (filteredOut) "نتیجه‌ها دریافت شده‌اند؛ فیلتر پخش یا نوع اثر را بردار." else "نام کوتاه‌تر یا نام انگلیسی را امتحان کن.",
                            Icons.Outlined.SearchOff, if (hasFilters) "برداشتن فیلترها" else null, if (hasFilters) (::resetFilters) else null)
                    }
                    items(visible, key = ::cinemaMediaKey) { media -> CinemaPoster(media, { openTitle(media) }, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }, containerColor = CinemaSurface, contentColor = CinemaPaper) {
        SearchResultOptions(order, playableOnly, onOrder = { orderName = it.name }, onPlayable = { playableOnly = it }, onReset = ::resetFilters, onDone = { showFilters = false })
    }
}

/** Static, bounded decorative artwork. Tile decoding never allocates a full-size poster. */
@Composable
private fun SearchPosterAtmosphere(artwork: List<String>, compact: Boolean, searching: Boolean) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().height(if (compact) 280.dp else 460.dp).clip(RoundedCornerShape(0.dp)).clearAndSetSemantics {}) {
        if (artwork.isNotEmpty()) Row(Modifier.fillMaxSize().graphicsLayer { rotationZ = -9f; scaleX = 1.15f; scaleY = 1.15f; alpha = if (searching) .24f else .56f },
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(4) { column ->
                Column(Modifier.weight(1f).graphicsLayer { translationY = if (column % 2 == 0) -58.dp.toPx() else -20.dp.toPx() }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    artwork.filterIndexed { index, _ -> index % 4 == column }.forEach { url ->
                        val request = remember(context, url) { ImageRequest.Builder(context).data(url).size(240, 360).crossfade(false).build() }
                        AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(CinemaSurface))
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaInk.copy(alpha = .66f), CinemaInk.copy(alpha = .46f), CinemaInk.copy(alpha = .88f), CinemaInk))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(CinemaInk.copy(alpha = .3f), Color.Transparent, CinemaInk.copy(alpha = .3f)))))
    }
}

@Composable
private fun SearchHistory(history: List<String>, onQuery: (String) -> Unit, onClear: () -> Unit) {
    Surface(color = CinemaSurface.copy(alpha = .94f), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CinemaLine)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("جست‌وجوهای اخیر", color = CinemaPaper, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClear, modifier = Modifier.heightIn(min = 48.dp).testTag("search-history-clear")) { Text("پاک‌کردن", color = CinemaSoft, fontSize = 12.sp) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(history.take(6), key = { it }) { previous ->
                    AssistChip({ onQuery(previous) }, label = { Text(previous, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 260.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchResultOptions(order: CinematicSearchOrder, playableOnly: Boolean, onOrder: (CinematicSearchOrder) -> Unit,
    onPlayable: (Boolean) -> Unit, onReset: () -> Unit, onDone: () -> Unit) {
    val largeText = LocalConfiguration.current.fontScale >= 1.6f
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().testTag("search-options-sheet")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("تنظیم نتیجه‌ها", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onDone) { Icon(Icons.Outlined.Close, "بستن تنظیم نتیجه‌ها") }
        }
        Text("ترتیب عنوان‌های دریافت‌شده", color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        Column(Modifier.selectableGroup()) {
            CinematicSearchOrder.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(order == choice, role = Role.RadioButton, onClick = { onOrder(choice) })
                    .testTag("search-order-" + choice.name), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(order == choice, onClick = null); Text(choice.label, modifier = Modifier.padding(start = 10.dp), fontSize = 14.sp)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = CinemaLine)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("فقط آمادهٔ پخش", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("عنوان‌های دارای نسخهٔ قابل پخش در سرور", fontSize = 12.sp, lineHeight = 20.sp, color = CinemaSoft, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(playableOnly, onPlayable, modifier = Modifier.testTag("search-playable-switch").semantics { contentDescription = "فقط عنوان‌های آمادهٔ پخش" })
        }
        if (largeText) Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CinemaAccent, contentColor = Color.White)) { Text("اعمال") }
            OutlinedButton(onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("بازنشانی") }
        } else Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onReset, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("بازنشانی") }
            Button(onDone, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CinemaAccent, contentColor = Color.White)) { Text("اعمال") }
        }
    }
}
