package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The social destination shares existing identity, follow, safety and messaging APIs. */
@Composable
fun CinemaSocialScreen(
    social: SocialRepository, backend: BackendRepository, loggedIn: Boolean,
    onMedia: (MediaItem) -> Unit, onOpenClip: (String) -> Unit, onOpenRoom: (SocialRoom) -> Unit,
    onCreator: (Creator) -> Unit, onSearch: () -> Unit, onInbox: () -> Unit, onCreate: () -> Unit,
    onRequireAuth: () -> Unit, onCollection: (String?) -> Unit, onStories: (List<SocialStory>, Int) -> Unit,
    initialPostId: String? = null, onFocusedPostConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var posts by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    var stories by remember { mutableStateOf<List<SocialStory>>(emptyList()) }
    var collections by remember { mutableStateOf<List<SocialCollection>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<SocialRoom>>(emptyList()) }
    var clips by remember { mutableStateOf<List<ReelFeedItem>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var appending by remember { mutableStateOf(false) }
    var mutation by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var commentsFor by remember { mutableStateOf<SocialPost?>(null) }
    var safetyFor by remember { mutableStateOf<SocialPost?>(null) }
    suspend fun load() {
        when (mode) {
            0 -> { val page = social.feedPage(limit = 20); posts = page.items; next = page.nextCursor
                stories = cinemaUiOptional { social.stories() }.orEmpty() }
            1 -> collections = SocialCollectionsRepository(backend).discover()
            2 -> rooms = social.rooms()
            3 -> clips = social.reelsPage(limit = 30).items
        }
    }
    LaunchedEffect(mode, refresh, loggedIn) {
        loading = true; error = null
        try { load() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "ارتباط با سینماکلاب کامل نشد. دوباره تلاش کن." }
        finally { loading = false }
    }
    LaunchedEffect(initialPostId) {
        if (!initialPostId.isNullOrBlank()) {
            try { commentsFor = social.post(initialPostId); mode = 0; onFocusedPostConsumed() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "این پست در دسترس نیست." }
        }
    }
    fun mutate(block: suspend () -> Unit) {
        if (!loggedIn) { onRequireAuth(); return }; if (mutation) return
        mutation = true
        scope.launch { try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "تغییر ثبت نشد؛ دوباره تلاش کن." } finally { mutation = false } }
    }
    LazyColumn(Modifier.fillMaxSize().background(CinemaInk).testTag("cinema-social"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("header") {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(CinemaAccent.copy(alpha = .13f), CinemaInk))).statusBarsPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("سینماکلاب", color = CinemaPaper, fontSize = 29.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    IconButton(onSearch) { Icon(Icons.Default.Search, "جستجوی سینماکلاب", tint = CinemaPaper) }
                    IconButton({ refresh++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "تازه‌کردن سینماکلاب", tint = CinemaPaper) }
                }
                Text("بعد از تیتراژ، قصهٔ ما شروع می‌شود.", color = CinemaSoft, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CinemaAction(Icons.Default.EditNote, "از سینما بنویس", { if (loggedIn) onCreate() else onRequireAuth() }, Modifier.weight(1f), primary = true)
                    OutlinedIconButton({ if (loggedIn) onInbox() else onRequireAuth() }, Modifier.size(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.ChatBubbleOutline, "پیام‌ها", tint = CinemaPaper) }
                }
            }
        }
        item("modes") { LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(listOf("گفت‌وگوها", "لیستِ آدم‌ها", "اتاق‌های سینما", "کلیپ‌ها")) { i, label -> CinemaTag(label, mode == i) { mode = i } }
        } }
        if (loading || mutation) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = CinemaAccent) }
        error?.let { message -> item("error") { Box(Modifier.padding(horizontal = 20.dp)) { CinemaNotice("دریافت کامل نشد", message, Icons.Default.CloudOff, "تلاش دوباره", { refresh++ }) } } }
        when (mode) {
            0 -> {
                if (stories.isNotEmpty()) item("stories") { LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(stories, key = { _, it -> it.id }) { i, story ->
                        Column(Modifier.width(76.dp).clickable { onStories(stories, i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(shape = CircleShape, border = androidx.compose.foundation.BorderStroke(2.dp, CinemaAccent), modifier = Modifier.size(72.dp)) {
                                CinemaImage(story.author.avatarUrl, Modifier.padding(4.dp).clip(CircleShape))
                            }
                            Text(story.author.displayName, color = CinemaPaper, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                } }
                if (posts.isEmpty() && !loading && error == null) item("empty") { Box(Modifier.padding(20.dp)) { CinemaNotice("اولین بحث را تو شروع کن", "پیشنهاد فیلم، نقد کوتاه یا یک سؤال سینمایی بنویس. پست‌های آدم‌هایی که دنبال می‌کنی هم اینجا دیده می‌شود.", Icons.Default.Forum, "نوشتن پست", { if (loggedIn) onCreate() else onRequireAuth() }) } }
                items(posts, key = { it.id }) { post ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        post.media?.asMediaItem()?.let { media ->
                            Surface(onClick = { onMedia(media) }, color = CinemaLine.copy(alpha = .4f), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CinemaImage(media.posterPath, Modifier.width(42.dp).height(58.dp).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text("گفت‌وگو دربارهٔ", color = CinemaSoft, fontSize = 10.sp)
                                        Text(media.title, color = CinemaPaper, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(Icons.Default.ArrowBack, "صفحهٔ عنوان", tint = CinemaAccent, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        SocialPostCard(post, social, loggedIn, onRequireAuth,
                            onLike = { mutate { val liked = social.togglePostLike(post.id); posts = posts.map { if (it.id == post.id) it.copy(likedByMe = liked, likes = (it.likes + if (liked) 1 else -1).coerceAtLeast(0)) else it } } },
                            onSave = { mutate { val (saved, count) = social.togglePostSave(post.id); posts = posts.map { if (it.id == post.id) it.copy(savedByMe = saved, saves = count) else it } } },
                            onShare = { FilmiqooDeepLinks.share(context, post.author.displayName, FilmiqooDeepLinks.post(post.id)) },
                            onComments = { commentsFor = post }, onSafety = { if (loggedIn) safetyFor = post else onRequireAuth() },
                            onCreator = { onCreator(Creator(post.author.displayName, "@${post.author.username}", "", "", post.author.verified, post.author.id, avatarUrl = post.author.avatarUrl)) })
                    }
                }
                next?.let { cursor -> item("next") { Box(Modifier.padding(horizontal = 20.dp)) {
                    CinemaAction(Icons.Default.ExpandMore, if (appending) "در حال دریافت…" else "گفت‌وگوهای بیشتر", {
                        if (!appending) { appending = true; scope.launch {
                            try { val page = social.feedPage(cursor, 20); posts = (posts + page.items).distinctBy { it.id }; next = page.nextCursor }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = "ادامهٔ گفت‌وگوها دریافت نشد؛ دوباره تلاش کن." }
                            finally { appending = false }
                        } }
                    }, Modifier.fillMaxWidth(), enabled = !appending && !loading)
                } } }
            }
            1 -> {
                item("collection-intro") { CinemaHeading("سلیقه‌ها را دنبال کن", "لیست‌های واقعی ساخته‌شده توسط کاربران", "همهٔ لیست‌ها", { onCollection(null) }) }
                items(collections, key = { it.id }) { collection ->
                    CinemaCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { onCollection(collection.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CinemaImage(collection.posterUrl, Modifier.width(90.dp).height(120.dp).clip(RoundedCornerShape(15.dp)))
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(collection.name, color = CinemaPaper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(collection.owner.displayName, color = CinemaGold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                Text("${collection.itemCount} عنوان · ${collection.followers} دنبال‌کننده", color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                if (collections.isEmpty() && !loading && error == null) item("collections-empty") { Box(Modifier.padding(20.dp)) { CinemaNotice("لیست عمومی هنوز ساخته نشده", "لیستت را در بخش من بساز و با دیگران به اشتراک بگذار.") } }
            }
            2 -> {
                items(rooms, key = { it.id }) { room -> CinemaCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { if (loggedIn) onOpenRoom(room) else onRequireAuth() }) {
                    Icon(Icons.Default.Forum, null, tint = CinemaAccent)
                    Text(room.name, color = CinemaPaper, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(room.topic, color = CinemaSoft, fontSize = 13.sp, maxLines = 2)
                    Text("${room.members} عضو · ورود به گفت‌وگو", color = CinemaGold, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                } }
                if (rooms.isEmpty() && !loading && error == null) item("rooms-empty") { Box(Modifier.padding(20.dp)) { CinemaNotice("هنوز اتاقی وجود ندارد", "اتاق هر عنوان از صفحهٔ همان فیلم یا سریال در دسترس قرار می‌گیرد.") } }
            }
            3 -> {
                items(clips, key = { it.id }) { clip -> CinemaCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { onOpenClip(clip.id) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) {
                        if (!clip.spoiler) CinemaImage(clip.coverUrl, Modifier.fillMaxSize(), true)
                        Icon(if (clip.spoiler) Icons.Default.VisibilityOff else Icons.Default.PlayCircle, null, tint = CinemaPaper, modifier = Modifier.align(Alignment.Center).size(44.dp))
                    }
                    Text(if (clip.spoiler) "کلیپ دارای اسپویل · برای تماشا باز کن" else clip.caption.ifBlank { "کلیپ سینمایی" }, color = CinemaPaper, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                    Text("${clip.author.displayName} · ${clip.views} بازدید", color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                } }
                if (clips.isEmpty() && !loading && error == null) item("clips-empty") { Box(Modifier.padding(20.dp)) { CinemaNotice("هنوز کلیپی منتشر نشده", "کلیپ‌های تازهٔ جامعهٔ فیلمیکو اینجا دیده می‌شود.") } }
            }
        }
    }
    commentsFor?.let { post -> PostCommentsSheet(post, social, loggedIn, onRequireAuth, { commentsFor = null }) }
    safetyFor?.let { post -> SafetyActionSheet(backend = backend, targetType = "post", targetId = post.id, targetLabel = "دیدگاه ${post.author.displayName}", userTargetId = post.author.id,
        onDismiss = { safetyFor = null }, onChanged = { safetyFor = null; refresh++ }) }
}
