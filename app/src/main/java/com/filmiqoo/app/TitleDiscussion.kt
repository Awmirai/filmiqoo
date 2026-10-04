package com.filmiqoo.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal data class TitleComment(
    val id: String, val body: String, val spoiler: Boolean, val sticker: String, val gif: String,
    val authorId: String, val author: String, val avatar: String, val own: Boolean,
    val likes: Int, val liked: Boolean, val replies: Int, val deleted: Boolean, val created: String
)
internal data class DiscussionPage(val items: List<TitleComment>, val next: String?)
internal fun discussionKey(media: MediaItem): String = if (media.id > 0)
    "${if (media.type == MediaType.TV) "series" else "movie"}:${media.id}" else "catalog:${media.backendId}"

internal class TitleDiscussionRepository(private val backend: BackendRepository) {
    suspend fun page(key: String, parent: String?, cursor: String?): DiscussionPage {
        val viewer = if (backend.session.isLoggedIn) "/viewer" else ""
        val root = backend.getJson("/v1/discussions/${Uri.encode(key)}$viewer?parent=${parent.orEmpty()}&cursor=${Uri.encode(cursor.orEmpty())}", backend.session.isLoggedIn)
        val arr = root.optJSONArray("items")
        return DiscussionPage(buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                add(TitleComment(x.getString("id"), x.optString("body"), x.optBoolean("spoiler"), x.optString("sticker"), x.optString("gifUrl"),
                    x.optString("authorId"), x.optString("authorName"), x.optString("avatarUrl"), x.optBoolean("own"), x.optInt("likes"),
                    x.optBoolean("liked"), x.optInt("replies"), x.optBoolean("deleted"), x.optString("createdAt")))
            }
        }, root.optString("nextCursor").takeUnless { it.isBlank() || it == "null" })
    }
    suspend fun send(key: String, client: String, text: String, parent: String?, spoiler: Boolean, sticker: String, upload: String) =
        backend.postJson("/v1/discussions/${Uri.encode(key)}", JSONObject().put("clientId", client).put("body", text)
            .put("parentId", parent.orEmpty()).put("spoiler", spoiler).put("sticker", sticker).put("uploadId", upload), true)
    suspend fun like(id: String, liked: Boolean) = backend.postJson("/v1/discussion-comments/$id/like", JSONObject().put("liked", liked), true)
    suspend fun remove(id: String) = backend.postJson("/v1/discussion-comments/$id/remove", JSONObject(), true)
    suspend fun report(id: String, reason: String) = backend.postJson("/v1/moderation/report", JSONObject().put("targetType", "discussion").put("targetId", id).put("reason", reason), true)
}

internal val cinemaStickers = linkedMapOf("popcorn" to "وقتِ سینما", "masterpiece" to "شاهکار!", "mindblown" to "چه پایانـی!", "tears" to "اشکم درآمد", "applause" to "تشویق ایستاده", "rewatch" to "دوباره می‌بینم", "boring" to "خوابم گرفت", "heart" to "عاشقش شدم")

/** Original vector sticker pack: scalable and available offline, without a third-party key. */
@Composable
internal fun CinemaReactionSticker(id: String) {
    val icon = when (id) {
        "popcorn" -> Icons.Default.LocalMovies; "masterpiece" -> Icons.Default.WorkspacePremium
        "mindblown" -> Icons.Default.AutoAwesome; "tears" -> Icons.Default.WaterDrop
        "applause" -> Icons.Default.Celebration; "rewatch" -> Icons.Default.Replay
        "boring" -> Icons.Default.Bedtime; else -> Icons.Default.Favorite
    }
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(24.dp)).background(
        Brush.linearGradient(listOf(CinemaAccent.copy(alpha = .25f), CinemaSurface, CinemaGold.copy(alpha = .18f))))
        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = CinemaGold, modifier = Modifier.size(55.dp))
        Text(cinemaStickers[id].orEmpty(), color = CinemaPaper, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 10.dp))
        Text("FILMIQOO", color = CinemaSoft, fontSize = 8.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TitleDiscussion(media: MediaItem, backend: BackendRepository, onRequireAuth: () -> Unit) {
    val key = discussionKey(media)
    var reply by remember(key) { mutableStateOf<TitleComment?>(null) }
    var refresh by remember(key) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().testTag("title-discussion")) {
        CinemaHeading("بعد از تماشا، حرف بزنیم", "دیدگاه‌ها، واکنش‌ها و گفت‌وگوی این عنوان")
        DiscussionThread(key, null, backend, onRequireAuth, refresh, { reply = it })
    }
    reply?.let { root ->
        ModalBottomSheet(onDismissRequest = { reply = null; refresh++ }, containerColor = CinemaInk,
            contentColor = CinemaPaper, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxHeight(.9f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("پاسخ به ${root.author}", color = CinemaPaper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton({ reply = null; refresh++ }) { Icon(Icons.Default.Close, "بستن پاسخ‌ها") }
                }
                DiscussionThread(key, root.id, backend, onRequireAuth, 0, {})
            }
        }
    }
}

@Composable
private fun DiscussionThread(key: String, parent: String?, backend: BackendRepository, onRequireAuth: () -> Unit, externalRefresh: Int, onReply: (TitleComment) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(backend) { TitleDiscussionRepository(backend) }
    var page by remember(key, parent) { mutableStateOf<DiscussionPage?>(null) }
    var cursor by remember(key, parent) { mutableStateOf<String?>(null) }
    var refresh by remember(key, parent) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(key, parent) { mutableStateOf("") }
    var spoiler by rememberSaveable(key, parent) { mutableStateOf(false) }
    var sticker by rememberSaveable(key, parent) { mutableStateOf("") }
    var upload by rememberSaveable(key, parent) { mutableStateOf("") }
    var gif by rememberSaveable(key, parent) { mutableStateOf("") }
    var client by rememberSaveable(key, parent) { mutableStateOf(UUID.randomUUID().toString()) }
    var lastDraft by rememberSaveable(key, parent) { mutableStateOf("") }
    val draftSignature = listOf(draft, spoiler.toString(), sticker, upload).joinToString("\u0000")
    LaunchedEffect(draftSignature) {
        if (lastDraft != draftSignature) { client = UUID.randomUUID().toString(); lastDraft = draftSignature }
    }
    var pickSticker by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TitleComment?>(null) }
    var reporting by remember { mutableStateOf<TitleComment?>(null) }
    val gifLoader = remember(context) { ImageLoader.Builder(context).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build() }
    DisposableEffect(gifLoader) { onDispose { gifLoader.shutdown() } }
    fun action(block: suspend () -> Unit) {
        if (!backend.session.isLoggedIn) { onRequireAuth(); return }
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "عملیات انجام نشد؛ متن و پیوستت محفوظ است. دوباره تلاش کن." }
            finally { busy = false }
        }
    }
    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) action {
            val resolver = context.contentResolver
            require(resolver.getType(uri) == "image/gif")
            // Bound input before initiating an upload, including providers without a size column.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input); val header = ByteArray(6); require(input.read(header) == 6)
                    require(String(header, Charsets.US_ASCII) in listOf("GIF87a", "GIF89a"))
                    val buffer = ByteArray(8192); var bytes = 6L
                    while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= 10 * 1024 * 1024) }
                }
            }
            val ticket = backend.uploadMedia(context, uri, "title-comment")
            upload = ticket.uploadId; gif = ticket.mediaUrl; sticker = ""
        }
    }
    LaunchedEffect(key, parent, cursor, refresh, externalRefresh, backend.session.isLoggedIn) {
        loading = true; error = null
        try { page = repository.page(key, parent, cursor) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "دیدگاه‌ها دریافت نشد. اتصال را بررسی کن؛ این بخش به نسخهٔ جدید سرویس دیدگاه‌ها نیاز دارد." }
        finally { loading = false }
    }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CinemaCard(Modifier.fillMaxWidth(), accent = true) {
            if (!backend.session.isLoggedIn) {
                Text("سلیقهٔ تو، شروع یک گفت‌وگوست", color = CinemaPaper, fontWeight = FontWeight.Bold)
                TextButton(onRequireAuth) { Text("ورود و نوشتن دیدگاه", color = CinemaAccent) }
            } else {
                OutlinedTextField(draft, { if (it.length <= 3000) draft = it },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("discussion-draft"), minLines = 2, maxLines = 6,
                    placeholder = { Text(if (parent == null) "از تجربهٔ تماشایت بنویس…" else "پاسخت را بنویس…") }, shape = RoundedCornerShape(16.dp),
                    supportingText = { Text("${draft.length}/3000", color = CinemaSoft) })
                if (sticker.isNotBlank()) CinemaReactionSticker(sticker)
                if (gif.isNotBlank()) AsyncImage(gif, "GIF انتخاب‌شده", imageLoader = gifLoader, modifier = Modifier.fillMaxWidth().height(150.dp))
                if (sticker.isNotBlank() || gif.isNotBlank()) TextButton({ sticker = ""; gif = ""; upload = "" }, enabled = !busy) { Text("حذف پیوست") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(spoiler, { spoiler = it }, enabled = !busy)
                    Text("داستان را لو می‌دهد", color = CinemaSoft, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ pickSticker = !pickSticker }, enabled = !busy) { Icon(Icons.Default.EmojiEmotions, "انتخاب استیکر", tint = CinemaGold) }
                    TextButton({ gifPicker.launch("image/gif") }, enabled = !busy) { Text("GIF", color = CinemaGold) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { action {
                        repository.send(key, client, draft, parent, spoiler, sticker, upload)
                        draft = ""; sticker = ""; upload = ""; gif = ""; spoiler = false; client = UUID.randomUUID().toString()
                        cursor = null; refresh++; notice = "دیدگاهت منتشر شد."
                    } }, enabled = !busy && (draft.isNotBlank() || sticker.isNotBlank() || upload.isNotBlank()),
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaAccent, contentColor = CinemaInk)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CinemaInk) else Text("ارسال")
                    }
                }
                if (pickSticker) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cinemaStickers.keys.toList()) { id ->
                        TextButton({ sticker = id; upload = ""; gif = ""; pickSticker = false }, enabled = !busy) { CinemaReactionSticker(id) }
                    }
                }
                Text("GIF از گالری · حداکثر ۱۰ مگابایت", color = CinemaSoft, fontSize = 11.sp)
            }
        }
        notice?.let { Text(it, color = CinemaGold, fontSize = 13.sp) }
        error?.let { CinemaNotice("ارتباط کامل نشد", it, Icons.Default.CloudOff, "بررسی دوباره", { refresh++ }) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CinemaAccent)
        if (page?.items?.isEmpty() == true && !loading) Text(if (parent == null) "اولین دیدگاه این عنوان را تو بنویس." else "هنوز پاسخی ثبت نشده است.", color = CinemaSoft, modifier = Modifier.padding(vertical = 12.dp))
        page?.items?.forEach { comment ->
            androidx.compose.runtime.key(comment.id) {
                var revealed by rememberSaveable(comment.id) { mutableStateOf(false) }
                CinemaCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(CinemaLine), contentAlignment = Alignment.Center) {
                            if (comment.avatar.isBlank()) Text(comment.author.take(1), color = CinemaGold) else AsyncImage(comment.avatar, null, modifier = Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(comment.author, color = CinemaPaper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(comment.created.take(10), color = CinemaSoft, fontSize = 11.sp)
                        }
                        if (!comment.deleted) IconButton({ if (!backend.session.isLoggedIn) onRequireAuth() else if (comment.own) deleting = comment else reporting = comment }) {
                            Icon(if (comment.own) Icons.Default.DeleteOutline else Icons.Default.Flag, if (comment.own) "حذف دیدگاه" else "گزارش دیدگاه", tint = CinemaSoft, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (comment.deleted) Text("این دیدگاه حذف شده است.", color = CinemaSoft)
                    else if (comment.spoiler && !revealed) OutlinedButton({ revealed = true }, Modifier.fillMaxWidth()) { Text("هشدار اسپویل · نمایش دیدگاه") }
                    else {
                        if (comment.body.isNotBlank()) Text(comment.body, color = CinemaPaper, lineHeight = 25.sp, fontSize = 14.sp)
                        if (comment.sticker.isNotBlank()) CinemaReactionSticker(comment.sticker)
                        if (comment.gif.isNotBlank()) AsyncImage(comment.gif, "GIF دیدگاه", imageLoader = gifLoader, modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!comment.deleted) TextButton({ action {
                            repository.like(comment.id, !comment.liked)
                            page = page?.copy(items = page!!.items.map { if (it.id == comment.id) it.copy(liked = !it.liked, likes = (it.likes + if (it.liked) -1 else 1).coerceAtLeast(0)) else it })
                        } }, enabled = !busy) {
                            Icon(if (comment.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "پسندیدن", tint = if (comment.liked) CinemaAccent else CinemaSoft, modifier = Modifier.size(19.dp))
                            Text(" ${comment.likes}", color = CinemaSoft)
                        }
                        if (parent == null) TextButton({ onReply(comment) }) { Text("${comment.replies} پاسخ · گفت‌وگو", color = CinemaGold) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (cursor != null) TextButton({ cursor = null }, enabled = !loading) { Text("تازه‌ترین دیدگاه‌ها") }
            page?.next?.let { next -> TextButton({ cursor = next }, enabled = !loading) { Text("دیدگاه‌های قدیمی‌تر") } }
        }
    }
    deleting?.let { comment -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("دیدگاه حذف شود؟") },
        text = { Text("پاسخ‌های دیگران باقی می‌ماند؛ متن و پیوست تو حذف می‌شود.") }, confirmButton = { TextButton({ deleting = null; action { repository.remove(comment.id); refresh++ } }) { Text("حذف") } },
        dismissButton = { TextButton({ deleting = null }) { Text("انصراف") } }) }
    reporting?.let { comment -> AlertDialog(onDismissRequest = { reporting = null }, title = { Text("گزارش دیدگاه") },
        text = { Column { listOf("spam" to "هرزنامه", "harassment" to "توهین و آزار", "spoiler" to "اسپویل بدون هشدار").forEach { (reason, label) ->
            TextButton({ reporting = null; action { repository.report(comment.id, reason); notice = "گزارش برای بررسی ثبت شد." } }) { Text(label) }
        } } }, confirmButton = { TextButton({ reporting = null }) { Text("انصراف") } }) }
}
