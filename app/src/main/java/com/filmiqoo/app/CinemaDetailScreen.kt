package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CinemaDetailScreen(
    media: MediaItem,
    repository: TmdbRepository,
    backend: BackendRepository,
    store: LocalStore,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onChat: (MediaItem) -> Unit,
    onWatchParty: (MediaItem) -> Unit,
    onClip: (String) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onPerson: (CastMember) -> Unit,
    onRequireAuth: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember(backend) { CinemaDataRepository(context, backend) }
    val profileId = backend.viewerProfiles.activeId()
    val personal = remember(profileId) { CinemaPersonalStore(context, profileId) }
    val library = remember(backend) { LibraryRepository(backend) }
    val series = remember(backend) { SeriesProgressRepository(backend) }
    val subscriptions = remember(backend) { SeriesAlertsRepository(backend) }
    val snackbars = remember { SnackbarHostState() }
    var data by remember(media.key) { mutableStateOf<CinemaTitleData?>(null) }
    var error by remember(media.key) { mutableStateOf<String?>(null) }
    var reload by remember(media.key) { mutableIntStateOf(0) }
    var busy by remember(media.key) { mutableStateOf(false) }
    var saved by remember(media.key, profileId) { mutableStateOf(personal.contains("watchlist", media)) }
    var favorite by remember(media.key, profileId) { mutableStateOf(personal.contains("favorites", media)) }
    var seen by remember(media.key, profileId) { mutableStateOf(personal.seen(media)) }
    var hideSpoilers by remember(profileId) { mutableStateOf(personal.hideSpoilers()) }
    var hasNote by remember(media.key, profileId) { mutableStateOf(personal.note(media).isNotBlank()) }
    var noteEditor by remember(media.key) { mutableStateOf(false) }
    var noteDraft by remember(media.key) { mutableStateOf("") }
    var collectionsOpen by remember(media.key) { mutableStateOf(false) }
    var availabilityOpen by remember(media.key) { mutableStateOf(false) }
    var following by remember(media.key) { mutableStateOf(false) }
    var progress by remember(media.key) { mutableStateOf<SeriesWatchProgress?>(null) }
    var progressRefresh by remember(media.key) { mutableIntStateOf(0) }
    var clips by remember(media.key) { mutableStateOf<List<ReelFeedItem>>(emptyList()) }

    fun action(block: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            var message: String? = null
            try { message = block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "عملیات انجام نشد؛ دوباره تلاش کن." }
            finally { busy = false }
            message?.let { snackbars.showSnackbar(it) }
        }
    }

    LaunchedEffect(media.key, reload) {
        error = null
        try { data = loader.title(media) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "دریافت عنوان ناموفق بود." }
    }
    LaunchedEffect(data?.platform?.id, backend.session.isLoggedIn, progressRefresh) {
        val current = data ?: return@LaunchedEffect
        val id = current.platform?.id ?: return@LaunchedEffect
        if (!backend.session.isLoggedIn) return@LaunchedEffect
        if (current.detail.media.type == MediaType.TV) {
            progress = cinemaUiOptional { series.load(id) }
            following = cinemaUiOptional { subscriptions.status(id).following } ?: following
        }
    }
    LaunchedEffect(data?.platform?.id, backend.session.isLoggedIn) {
        val id = data?.platform?.id ?: return@LaunchedEffect
        if (backend.session.isLoggedIn) {
            cinemaUiOptional { library.watchlist() }?.let { items -> saved = saved || items.any { it.backendId == id } }
            cinemaUiOptional { library.favorites() }?.let { items -> favorite = favorite || items.any { it.backendId == id } }
        }
        clips = cinemaUiOptional { SocialRepository(backend).mediaClips(id) }.orEmpty()
    }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(CinemaInk)) {
        val current = data
        if (current == null) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                IconButton(onBack) { Icon(Icons.Default.ArrowForward, "بازگشت", tint = CinemaPaper) }
                Spacer(Modifier.height(30.dp))
                if (error != null) CinemaNotice("عنوان آماده نشد", error.orEmpty(), Icons.Default.CloudOff, "تلاش دوباره", { reload++ })
                else {
                    CircularProgressIndicator(color = CinemaAccent)
                    Text("در حال آماده‌سازی صفحهٔ عنوان…", color = CinemaSoft, modifier = Modifier.padding(top = 18.dp))
                }
            }
        } else {
            val title = current.detail.media
            val id = current.platform?.id
            CinemaDetailContent(
                current, saved, favorite, seen, hideSpoilers, hasNote, following, busy, progress,
                actions = CinemaDetailActions(
                    back = onBack,
                    share = {
                        if (id != null) FilmiqooDeepLinks.share(context, title.title, FilmiqooDeepLinks.title(id))
                        else shareText(context, title.title + " (" + title.year + ")")
                    },
                    save = {
                        action {
                            val next = if (id != null && backend.session.isLoggedIn) library.toggleWatchlist(id) else !saved
                            personal.setSaved("watchlist", title, next); saved = next
                            if (next) "به فهرست من اضافه شد." else "از فهرست من حذف شد."
                        }
                    },
                    favorite = {
                        action {
                            val next = if (id != null && backend.session.isLoggedIn) backend.toggleFavorite(id) else !favorite
                            personal.setSaved("favorites", title, next); favorite = next
                            if (next) "در پسندیده‌های من ذخیره شد." else "از پسندیده‌ها حذف شد."
                        }
                    },
                    notes = { noteDraft = personal.note(title); noteEditor = true },
                    seen = { seen = !seen; personal.markSeen(title, seen) },
                    spoiler = { hideSpoilers = it; personal.setHideSpoilers(it) },
                    collection = { if (backend.session.isLoggedIn) collectionsOpen = true else onRequireAuth() },
                    availability = { if (backend.session.isLoggedIn) availabilityOpen = true else onRequireAuth() },
                    follow = {
                        if (!backend.session.isLoggedIn) onRequireAuth()
                        else if (id != null) action {
                            following = subscriptions.update(id, !following).following
                            if (following) "خبر قسمت‌های جدید فعال شد." else "دنبال‌کردن سریال متوقف شد."
                        }
                    },
                    trailer = { current.detail.trailerKey?.let { openYoutube(context, it) } },
                    play = { version ->
                        if (!backend.session.isLoggedIn) onRequireAuth()
                        else action {
                            val target = backend.playbackContext(version)
                            onPlay(target.copy(mediaTitleId = id ?: target.mediaTitleId))
                            null
                        }
                    },
                    download = { version ->
                        if (!backend.session.isLoggedIn) onRequireAuth()
                        else action {
                            val target = backend.playbackContext(version)
                            backend.enqueueDownload(context, target.copy(mediaTitleId = id ?: target.mediaTitleId))
                            "دانلود به صف اضافه شد؛ از کتابخانه → دانلودها پیگیری کن."
                        }
                    },
                    episodeSeen = { episode, watched ->
                        if (!backend.session.isLoggedIn) onRequireAuth()
                        else action { series.setEpisodeWatched(episode.id, watched); progressRefresh++; "وضعیت قسمت به‌روز شد." }
                    },
                    person = onPerson,
                    media = onMedia
                ),
                community = {
                    Column {
                        if (id != null) Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CinemaAction(Icons.Default.Forum, "اتاق این عنوان", { onChat(title) }, Modifier.weight(1f))
                            CinemaAction(Icons.Default.Groups, "با هم ببینیم", { onWatchParty(title) }, Modifier.weight(1f))
                        }
                        if (backend.viewerProfiles.active()?.kidsMode != true) TitleDiscussion(title, backend, onRequireAuth)
                    }
                }
            )
            if (noteEditor) AlertDialog(
                onDismissRequest = { noteEditor = false },
                title = { Text("یادداشت خصوصی من") },
                text = { Column { Text("فقط روی این دستگاه و برای همین پروفایل ذخیره می‌شود.", color = CinemaSoft)
                    OutlinedTextField(noteDraft, { noteDraft = it.take(2000) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 4, maxLines = 8,
                        placeholder = { Text("نظرت، جملهٔ محبوبت یا چیزی که می‌خواهی یادت بماند…") }) } },
                confirmButton = { TextButton({ personal.saveNote(title, noteDraft); hasNote = noteDraft.isNotBlank(); noteEditor = false }) { Text("ذخیره") } },
                dismissButton = { TextButton({ noteEditor = false }) { Text("انصراف") } }
            )
            if (collectionsOpen && id != null) CollectionPickerSheet(backend, title, { collectionsOpen = false }) { message ->
                collectionsOpen = false; scope.launch { snackbars.showSnackbar(message) }
            }
            if (availabilityOpen && id != null) AvailabilityAlertsSheet(id, backend) { availabilityOpen = false }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 85.dp))
    }
}
