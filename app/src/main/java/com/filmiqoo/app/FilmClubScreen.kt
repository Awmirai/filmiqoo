package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal fun TitleComment.media(): MediaItem {
    val parts=scope.split(':')
    return MediaItem(parts.getOrNull(1)?.toIntOrNull()?:0,
        if(kind=="series" || parts.firstOrNull()=="series" || scope.contains(":s"))MediaType.TV else MediaType.MOVIE,
        title.ifBlank { if(parts.firstOrNull()=="series")"گفت‌وگوی سریال"else "گفت‌وگوی فیلم" },
        posterPath=poster.takeIf(String::isNotBlank),backendId=if(parts.firstOrNull()=="catalog")parts.getOrNull(1)else null)
}

internal fun discussionScopeLabel(key:String):String {
    val match=Regex(":s(\\d+):e(\\d+)$").find(key)
    return match?.let { "فصل ${it.groupValues[1]} · قسمت ${it.groupValues[2]}" } ?: "نظر کلی اثر"
}

/** One community, with separate post and title-discussion identities. No copied comments. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaSocialScreen(
    social:SocialRepository,backend:BackendRepository,loggedIn:Boolean,
    onMedia:(MediaItem)->Unit,onOpenClip:(String)->Unit,onOpenRoom:(SocialRoom)->Unit,onCreator:(Creator)->Unit,
    onSearch:()->Unit,onInbox:()->Unit,onCreate:()->Unit,onRequireAuth:()->Unit,onCollection:(String?)->Unit,
    onStories:(List<SocialStory>,Int)->Unit,initialPostId:String?=null,onFocusedPostConsumed:()->Unit={},
    onWatchParty:()->Unit={},onNotifications:()->Unit={},onClips:()->Unit={}
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val discussions=remember(backend){TitleDiscussionRepository(backend)}
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var posts by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    var comments by remember { mutableStateOf<List<TitleComment>>(emptyList()) }
    var clips by remember { mutableStateOf<List<ReelFeedItem>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var appending by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var viewerId by remember { mutableStateOf<String?>(null) }
    var activePost by remember { mutableStateOf<SocialPost?>(null) }
    var activeDiscussion by remember { mutableStateOf<TitleComment?>(null) }
    var safetyPost by remember { mutableStateOf<SocialPost?>(null) }
    var removePost by remember { mutableStateOf<SocialPost?>(null) }
    val busyIds=remember { mutableStateMapOf<String,Boolean>() }
    val snackbar=remember { SnackbarHostState() }

    LaunchedEffect(loggedIn) {
        viewerId=null
        if(loggedIn) try { viewerId=backend.me().id } catch(e:CancellationException){throw e} catch(_:Exception){}
    }
    LaunchedEffect(mode,refresh,loggedIn) {
        loading=true;loadError=null;next=null;posts=emptyList();comments=emptyList();clips=emptyList();appending=false
        try {
            when(mode) {
                0,1 -> if(mode==0 || loggedIn) {
                    val page=social.feedPage(limit=20,followingOnly=mode==1)
                    posts=page.items;next=page.nextCursor
                }
                2 -> {val page=discussions.page("feed",null,null);comments=page.items;next=page.next}
                3 -> {val page=social.reelsPage(limit=20);clips=page.items;next=page.nextCursor}
            }
        } catch(e:CancellationException){throw e}
        catch(e:CommunityServerUpgradeRequired){loadError=e.message}
        catch(_:Exception){loadError="این بخش دریافت نشد. اتصال را بررسی کن و دوباره تلاش کن."}
        finally{loading=false}
    }
    LaunchedEffect(initialPostId) {
        if(!initialPostId.isNullOrBlank()) try {
            activePost=social.post(initialPostId);onFocusedPostConsumed()
        } catch(e:CancellationException){throw e} catch(_:Exception){snackbar.showSnackbar("این پست در دسترس نیست.");onFocusedPostConsumed()}
    }
    fun authenticated(action:()->Unit) { if(loggedIn)action()else onRequireAuth() }
    fun mutate(id:String,action:suspend ()->Unit) {
        if(!loggedIn){onRequireAuth();return}
        if(busyIds[id]==true)return
        busyIds[id]=true
        scope.launch {
            try{action()}catch(e:CancellationException){throw e}
            catch(_:Exception){scope.launch { snackbar.showSnackbar("تغییر ثبت نشد؛ وضعیت قبلی حفظ شد.") }}
            finally{busyIds.remove(id)}
        }
    }
    fun more() {
        val cursor=next?:return
        if(appending||loading)return
        val requestedMode=mode;appending=true
        scope.launch {
            try {
                when(requestedMode) {
                    0,1 -> {val p=social.feedPage(cursor,20,followingOnly=requestedMode==1);ensureActive();if(mode==requestedMode){posts=(posts+p.items).distinctBy{it.id};next=p.nextCursor}}
                    2 -> {val p=discussions.page("feed",null,cursor);ensureActive();if(mode==requestedMode){comments=(comments+p.items).distinctBy{it.id};next=p.next}}
                    3 -> {val p=social.reelsPage(cursor,20);ensureActive();if(mode==requestedMode){clips=(clips+p.items).distinctBy{it.id};next=p.nextCursor}}
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){snackbar.showSnackbar("صفحهٔ بعد دریافت نشد؛ دوباره تلاش کن.")}
            finally{if(mode==requestedMode)appending=false}
        }
    }
    Box(Modifier.fillMaxSize().background(CinemaInk).testTag("cinema-social")) {
        LazyColumn(contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item("header") {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(CinemaAccent.copy(alpha=.15f),CinemaInk)))) {
                    CinemaPageHeader("سینماکلاب","فیلم بهانهٔ آشنایی ماست") {
                        IconButton({authenticated(onNotifications)}){Icon(Icons.Outlined.Notifications,"اعلان‌های اجتماعی")}
                        IconButton({authenticated(onInbox)}){Icon(Icons.Outlined.ChatBubbleOutline,"پیام‌های خصوصی")}
                    }
                    Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        CinemaAction(Icons.Outlined.EditNote,"از سینما بنویس",{authenticated(onCreate)},Modifier.weight(1f),primary=true)
                        OutlinedIconButton({refresh++},enabled=!loading,modifier=Modifier.size(52.dp)){Icon(Icons.Outlined.Refresh,"تازه‌کردن سینماکلاب")}
                    }
                    Text("نقد کوتاه، سؤال، کلیپ و تجربهٔ تماشا؛ با آدم‌هایی که سینما را دوست دارند.",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(20.dp))
                }
            }
            item("party") { CommunityWatchTogetherCard({authenticated(onWatchParty)}) }
            item("modes") { LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                itemsIndexed(listOf("کشف","دنبال‌شده‌ها","دیدگاه آثار","کلیپ‌ها")) { index,label ->
                    FilterChip(selected=mode==index,onClick={mode=index},label={Text(label)},modifier=Modifier.testTag("community-tab-$index"))
                }
            } }
            if(loading)item("loading"){LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp),color=CinemaAccent)}
            loadError?.let { message->item("error"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("اتصال کامل نشد",message,Icons.Outlined.CloudOff,"تلاش دوباره",{refresh++})}} }
            if(!loading&&loadError==null) {
                when(mode) {
                    0,1 -> {
                        if(mode==1&&!loggedIn)item("auth"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("سلیقه‌های نزدیک به تو","برای دیدن پست‌های آدم‌ها و کانال‌هایی که دنبال می‌کنی وارد شو.",Icons.Outlined.PeopleOutline,"ورود",onRequireAuth)}}
                        else if(posts.isEmpty())item("empty"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice(
                            if(mode==1)"هنوز پستی از دنبال‌شده‌ها نیست"else "اولین گفت‌وگو را تو شروع کن",
                            if(mode==1)"از بخش کشف، پروفایل آدم‌ها را باز کن و دنبالشان کن. فقط پست‌های همین آدم‌ها و کانال‌ها اینجا می‌آیند."else "یک پیشنهاد فیلم، نقد کوتاه یا سؤال بنویس. پست‌های منتشرشدهٔ کاربران اینجا دیده می‌شود.",
                            Icons.Outlined.Forum,if(mode==1)"کشف آدم‌ها"else "نوشتن پست",{if(mode==1)mode=0 else authenticated(onCreate)} )}}
                        items(posts,key={"post-${it.id}"}) { post ->
                            CommunityPostCard(post,social,loggedIn,post.author.id==viewerId,busyIds[post.id]==true,onRequireAuth,
                                onMedia,onCreator,
                                onLike={mutate(post.id){val liked=social.togglePostLike(post.id);posts=posts.map{if(it.id==post.id)it.copy(likedByMe=liked,likes=(it.likes+if(liked==it.likedByMe)0 else if(liked)1 else -1).coerceAtLeast(0))else it}}},
                                onSave={mutate(post.id){val(saved,count)=social.togglePostSave(post.id);posts=posts.map{if(it.id==post.id)it.copy(savedByMe=saved,saves=count)else it}}},
                                onComments={activePost=post},onShare={FilmiqooDeepLinks.share(context,post.author.displayName,FilmiqooDeepLinks.post(post.id))},
                                onSafety={authenticated{safetyPost=post}},onRemove={removePost=post})
                        }
                    }
                    2 -> {
                        item("discussion-intro"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("بعد از تیتراژ","این همان دیدگاه‌های صفحهٔ فیلم و سریال است؛ پاسخ‌ها و لایک‌ها در هر دو جا یکسان می‌مانند.",Icons.Outlined.LocalMovies,"انتخاب اثر و نوشتن نظر",onSearch)}}
                        if(comments.isEmpty())item("discussion-empty"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("هنوز دیدگاهی منتشر نشده","از صفحهٔ یک اثر، نظرت را بنویس تا بقیه هم بتوانند درباره‌اش حرف بزنند.")}}
                        items(comments,key={"discussion-${it.id}"}){comment->
                            CommunityDiscussionCard(comment,busyIds[comment.id]==true,{activeDiscussion=comment},{onMedia(comment.media())},
                                {onCreator(Creator(comment.author,"","","",false,comment.authorId,avatarUrl=comment.avatar))},
                                {mutate(comment.id){val liked=!comment.liked;discussions.like(comment.id,liked);comments=comments.map{if(it.id==comment.id)it.copy(liked=liked,likes=(it.likes+if(liked)1 else -1).coerceAtLeast(0))else it}}})
                        }
                    }
                    3 -> {
                        item("clips-intro"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("سینما، چند ثانیه نزدیک‌تر","کلیپ‌ها فقط با لمس تو پخش می‌شوند. پیش‌نمایش‌های دارای اسپویل پوشیده می‌مانند.",Icons.Outlined.PlayCircleOutline,"انتشار کلیپ",{authenticated(onCreate)})}}
                        if(clips.isEmpty())item("clips-empty"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("هنوز کلیپی منتشر نشده","کلیپ را از بخش ساخت محتوا منتشر کن؛ بعد از پردازش و انتشار در این بخش ظاهر می‌شود.")}}
                        items(clips,key={"clip-${it.id}"}){clip->CommunityClipCard(clip,{onOpenClip(clip.id)},{onCreator(Creator(clip.author.displayName,"@${clip.author.username}","","",clip.author.verified,clip.author.id,avatarUrl=clip.author.avatarUrl))})}
                    }
                }
            }
            next?.let { item("next"){Box(Modifier.padding(horizontal=20.dp)){CinemaAction(Icons.Outlined.ExpandMore,if(appending)"در حال دریافت…"else "بیشتر ببین",::more,Modifier.fillMaxWidth(),enabled=!appending&&!loading)}} }
            item("collections"){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp)){
                HorizontalDivider(color=CinemaLine)
                TextButton({onCollection(null)},Modifier.fillMaxWidth()){Icon(Icons.Outlined.CollectionsBookmark,null);Spacer(Modifier.width(8.dp));Text("لیست‌های سینمایی کاربران");Spacer(Modifier.weight(1f));Icon(Icons.Outlined.ChevronLeft,null)}
            }}
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter))
    }
    activePost?.let { post->CommunityReplySheet(post,social,loggedIn,onRequireAuth,onDismiss={activePost=null},onCountChanged={count->posts=posts.map{if(it.id==post.id)it.copy(comments=count)else it}}) }
    activeDiscussion?.let { comment->ModalBottomSheet(onDismissRequest={activeDiscussion=null;refresh++},containerColor=CinemaInk,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxHeight(.9f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()){
            Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text(comment.media().title,Modifier.weight(1f),fontWeight=FontWeight.Bold);IconButton({activeDiscussion=null;refresh++}){Icon(Icons.Outlined.Close,"بستن گفت‌وگو")}}
            TitleDiscussion(comment.media(),backend,onRequireAuth,initialScope=comment.scope)
        }
    } }
    safetyPost?.let { post->SafetyActionSheet(backend,"post",post.id,"پست ${post.author.displayName}",post.author.id,onDismiss={safetyPost=null},onChanged={safetyPost=null;refresh++}) }
    removePost?.let { post->AlertDialog(onDismissRequest={if(busyIds[post.id]!=true)removePost=null},title={Text("این پست حذف شود؟")},text={Text("این کار پست منتشرشدهٔ تو را از دسترس خارج می‌کند.")},
        confirmButton={TextButton({mutate(post.id){check(social.removePost(post.id));posts=posts.filterNot{it.id==post.id};removePost=null}},enabled=busyIds[post.id]!=true,modifier=Modifier.testTag("community-confirm-remove")){Text("حذف پست")}},
        dismissButton={TextButton({removePost=null},enabled=busyIds[post.id]!=true){Text("انصراف")}}) }
}

@Composable
internal fun CommunityWatchTogetherCard(onWatchParty:()->Unit) {
    Surface(onClick=onWatchParty,color=CinemaAccent.copy(alpha=.10f),shape=RoundedCornerShape(22.dp),border=androidx.compose.foundation.BorderStroke(1.dp,CinemaAccent.copy(alpha=.3f)),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("community-watch-together")) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Icon(Icons.Outlined.Groups,null,tint=CinemaAccent,modifier=Modifier.size(36.dp))
            Column(Modifier.weight(1f)){Text("با هم ببینیم",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("یک اتاق، یک فیلم، چند دوست؛ شروع تماشا از اینجا.",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=4.dp))}
            Icon(Icons.Outlined.ChevronLeft,null,tint=CinemaAccent)
        }
    }
}

@Composable
internal fun CommunityPostCard(post:SocialPost,social:SocialRepository,loggedIn:Boolean,own:Boolean,busy:Boolean,
    onRequireAuth:()->Unit,onMedia:(MediaItem)->Unit,onCreator:(Creator)->Unit,onLike:()->Unit,onSave:()->Unit,onComments:()->Unit,onShare:()->Unit,onSafety:()->Unit,onRemove:()->Unit) {
    var revealed by rememberSaveable(post.id){mutableStateOf(false)}
    CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth().testTag("community-post-${post.id}")) {
        Row(verticalAlignment=Alignment.CenterVertically){
            Row(Modifier.weight(1f).clickable{onCreator(Creator(post.author.displayName,"@${post.author.username}","","",post.author.verified,post.author.id,avatarUrl=post.author.avatarUrl))},verticalAlignment=Alignment.CenterVertically){
                CinemaImage(post.author.avatarUrl,Modifier.size(44.dp).clip(CircleShape))
                Column(Modifier.weight(1f).padding(horizontal=10.dp)){
                    Text(post.author.displayName.ifBlank{"کاربر فیلمیکو"},style=MaterialTheme.typography.titleMedium)
                    Text("@${post.author.username}",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)
                }
                if(post.author.verified)Icon(Icons.Outlined.Verified,"حساب تأییدشده",tint=CinemaAccent,modifier=Modifier.size(20.dp))
            }
            IconButton(onSafety){Icon(Icons.Outlined.MoreHoriz,"گزارش و ایمنی")}
        }
        Text(when(post.type){"review"->"نقد و تجربهٔ تماشا";"poll"->"نظرسنجی";"announcement"->"خبر سینمایی";else->"از سینما"},color=CinemaAccent,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=12.dp))
        post.media?.asMediaItem()?.let { media->Surface(onClick={onMedia(media)},color=CinemaLine.copy(alpha=.3f),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(top=10.dp)){
            Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){CinemaImage(media.posterPath,Modifier.width(38.dp).height(55.dp).clip(RoundedCornerShape(8.dp)));Text(media.title,Modifier.weight(1f).padding(horizontal=12.dp),style=MaterialTheme.typography.titleSmall);Icon(Icons.Outlined.ChevronLeft,"صفحهٔ اثر")}
        }}
        if(post.spoiler&&!revealed)OutlinedButton({revealed=true},Modifier.fillMaxWidth().padding(top=14.dp).testTag("community-reveal-${post.id}")){
            Icon(Icons.Outlined.VisibilityOff,null);Spacer(Modifier.width(8.dp));Text("دارای اسپویل · نمایش محتوا")
        } else {
            Text(post.body,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.padding(top=14.dp).testTag("community-body-${post.id}"))
            if(post.type=="poll")CommunityPoll(post.id,social,loggedIn,onRequireAuth)
        }
        Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            TextButton(onLike,enabled=!busy,modifier=Modifier.testTag("community-like-${post.id}")){Icon(if(post.likedByMe)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,"پسندیدن",tint=if(post.likedByMe)CinemaAccent else CinemaSoft);Spacer(Modifier.width(6.dp));Text(post.likes.toString())}
            TextButton(onComments,modifier=Modifier.testTag("community-replies-${post.id}")){Icon(Icons.Outlined.ChatBubbleOutline,"پاسخ‌ها");Spacer(Modifier.width(6.dp));Text(post.comments.toString())}
            Spacer(Modifier.weight(1f))
            IconButton(onSave,enabled=!busy,modifier=Modifier.testTag("community-save-${post.id}")){Icon(if(post.savedByMe)Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,if(post.savedByMe)"حذف از ذخیره‌ها"else "ذخیرهٔ پست",tint=if(post.savedByMe)CinemaAccent else CinemaSoft)}
            IconButton(onShare){Icon(Icons.Outlined.Share,"اشتراک‌گذاری پست")}
        }
        if(own)TextButton(onRemove,enabled=!busy,modifier=Modifier.testTag("community-remove-${post.id}")){Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(6.dp));Text("حذف پست من")}
    }
}

@Composable
private fun CommunityDiscussionCard(comment:TitleComment,busy:Boolean,onReply:()->Unit,onMedia:()->Unit,onCreator:()->Unit,onLike:()->Unit) {
    var revealed by rememberSaveable(comment.id){mutableStateOf(false)}
    CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth().testTag("club-comment-${comment.id}")){
        Row(Modifier.fillMaxWidth().clickable(onClick=onMedia),verticalAlignment=Alignment.CenterVertically){
            CinemaImage(comment.poster,Modifier.width(44.dp).height(66.dp).clip(RoundedCornerShape(9.dp)))
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(comment.media().title,style=MaterialTheme.typography.titleMedium);Text(discussionScopeLabel(comment.scope),color=CinemaAccent,style=MaterialTheme.typography.bodySmall)}
            Icon(Icons.Outlined.ChevronLeft,"صفحهٔ اثر")
        }
        TextButton(onCreator){Text(comment.author)}
        if(comment.spoiler&&!revealed)OutlinedButton({revealed=true},Modifier.fillMaxWidth()){Icon(Icons.Outlined.VisibilityOff,null);Spacer(Modifier.width(8.dp));Text("دیدگاه دارای اسپویل · نمایش")}
        else {
            if(comment.body.isNotBlank())Text(comment.body,style=MaterialTheme.typography.bodyLarge)
            if(comment.sticker.isNotBlank())CinemaReactionSticker(comment.sticker)
            if(comment.gif.isNotBlank())Text("GIF پیوست شده · در گفت‌وگو ببین",color=CinemaSoft)
        }
        Row(Modifier.fillMaxWidth().padding(top=8.dp)){
            TextButton(onLike,enabled=!busy){Icon(if(comment.liked)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,"پسندیدن دیدگاه");Spacer(Modifier.width(6.dp));Text("${comment.likes}")}
            TextButton(onReply,Modifier.weight(1f)){Icon(Icons.Outlined.ChatBubbleOutline,null);Spacer(Modifier.width(6.dp));Text("${comment.replies} پاسخ · گفت‌وگو")}
        }
    }
}

@Composable
internal fun CommunityClipCard(clip:ReelFeedItem,onClip:()->Unit,onCreator:()->Unit) {
    CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
        Surface(onClick=onClip,shape=RoundedCornerShape(16.dp),color=CinemaLine,modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f).testTag("community-clip-${clip.id}")){
            Box {
                if(!clip.spoiler)CinemaImage(clip.coverUrl,Modifier.fillMaxSize(),true)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,CinemaInk.copy(alpha=.8f)))))
                Icon(if(clip.spoiler)Icons.Outlined.VisibilityOff else Icons.Outlined.PlayCircleOutline,if(clip.spoiler)"کلیپ دارای اسپویل"else "پخش کلیپ",modifier=Modifier.align(Alignment.Center).size(48.dp))
                if(clip.durationMs>0)Text(communityClipDuration(clip.durationMs),Modifier.align(Alignment.BottomStart).padding(12.dp),style=MaterialTheme.typography.labelMedium)
            }
        }
        Text(if(clip.spoiler)"کلیپ دارای اسپویل · برای تماشا باز کن"else clip.caption.ifBlank{"کلیپ سینمایی"},style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp),maxLines=3,overflow=TextOverflow.Ellipsis)
        clip.media?.asMediaItem()?.title?.let{Text(it,color=CinemaAccent,style=MaterialTheme.typography.bodySmall)}
        Row(verticalAlignment=Alignment.CenterVertically){TextButton(onCreator){Text(clip.author.displayName)};Spacer(Modifier.weight(1f));Text("${clip.views} بازدید",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)}
    }
}

private fun communityClipDuration(milliseconds:Long):String {
    val seconds=(milliseconds/1000).coerceAtLeast(0)
    return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
}

@Composable
private fun CommunityPoll(id:String,social:SocialRepository,loggedIn:Boolean,onRequireAuth:()->Unit) {
    val scope=rememberCoroutineScope()
    var poll by remember(id){mutableStateOf<PollData?>(null)}
    var selected by remember(id){mutableStateOf<String?>(null)}
    var error by remember(id){mutableStateOf(false)}
    var busy by remember(id){mutableStateOf(false)}
    var refresh by remember(id){mutableIntStateOf(0)}
    LaunchedEffect(id,refresh){try{poll=social.poll(id);if(loggedIn)selected=social.pollSelection(id);error=false}catch(e:CancellationException){throw e}catch(_:Exception){error=true}}
    if(error)TextButton({refresh++}){Text("نظرسنجی دریافت نشد · تلاش دوباره")}
    poll?.let{data->Column(Modifier.fillMaxWidth().padding(top=12.dp)){
        data.options.forEach{option->OutlinedButton({
            if(!loggedIn)onRequireAuth()else if(!busy){busy=true;scope.launch{
                try{selected=social.votePoll(id,option.id);poll=social.poll(id);error=false}catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}
            }}
        },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("community-poll-${option.id}")){
            Text(option.label,Modifier.weight(1f));Text(if(data.totalVotes>0)"${option.votes*100/data.totalVotes}٪"else "۰٪");if(selected==option.id){Spacer(Modifier.width(8.dp));Icon(Icons.Outlined.Check,null)}
        }}
        Text("${data.totalVotes} رأی",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)
    }}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityReplySheet(post:SocialPost,social:SocialRepository,loggedIn:Boolean,onRequireAuth:()->Unit,onDismiss:()->Unit,onCountChanged:(Long)->Unit={}) {
    val scope=rememberCoroutineScope()
    var comments by remember(post.id){mutableStateOf<List<SocialComment>>(emptyList())}
    var draft by rememberSaveable(post.id){mutableStateOf("")}
    var spoiler by rememberSaveable(post.id){mutableStateOf(false)}
    var replyId by rememberSaveable(post.id){mutableStateOf<String?>(null)}
    var replyAuthor by rememberSaveable(post.id){mutableStateOf<String?>(null)}
    var error by remember(post.id){mutableStateOf<String?>(null)}
    var loading by remember(post.id){mutableStateOf(true)}
    var sending by remember(post.id){mutableStateOf(false)}
    var refresh by remember(post.id){mutableIntStateOf(0)}
    val busy=remember { mutableStateMapOf<String,Boolean>() }
    LaunchedEffect(post.id,refresh){loading=true;try{comments=threadedSocialComments(social.comments(post.id));onCountChanged(comments.size.toLong());error=null}catch(e:CancellationException){throw e}catch(_:Exception){error="پاسخ‌ها دریافت نشدند. اتصال و دسترسی پست را بررسی کن؛ سرورهای قدیمی برای نمایش امن پاسخ‌ها به ارتقا نیاز دارند."}finally{loading=false}}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=CinemaInk,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxHeight(.92f).imePadding().navigationBarsPadding().padding(horizontal=20.dp).testTag("community-reply-sheet")){
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("گفت‌وگو",style=MaterialTheme.typography.titleLarge);Text(post.media?.title?:"پست ${post.author.displayName}",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)};IconButton(onDismiss){Icon(Icons.Outlined.Close,"بستن پاسخ‌ها")}}
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=CinemaAccent)
            error?.let{message->Row(verticalAlignment=Alignment.CenterVertically){Text(message,Modifier.weight(1f),color=CinemaAccent);TextButton({refresh++},enabled=!loading){Text("تلاش دوباره")}}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)){
                if(comments.isEmpty()&&!loading&&error==null)item{Text("اولین پاسخ می‌تواند شروع یک گفت‌وگوی خوب باشد.",color=CinemaSoft)}
                items(comments,key={it.id}){comment->var revealed by rememberSaveable(comment.id){mutableStateOf(false)}
                    CinemaCard(Modifier.fillMaxWidth().padding(start=if(comment.parentCommentId!=null)16.dp else 0.dp).testTag("community-reply-${comment.id}")){
                        Text(comment.author.displayName,style=MaterialTheme.typography.titleSmall)
                        if(comment.parentCommentId!=null)Text("در پاسخ به یک دیدگاه",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)
                        if(comment.spoiler&&!revealed)TextButton({revealed=true},Modifier.testTag("community-reply-reveal-${comment.id}")){Icon(Icons.Outlined.VisibilityOff,null);Spacer(Modifier.width(8.dp));Text("اسپویل · نمایش")}
                        else Text(comment.body,style=MaterialTheme.typography.bodyLarge)
                        Row{
                            TextButton({if(!loggedIn)onRequireAuth()else if(busy[comment.id]!=true){busy[comment.id]=true;scope.launch{try{val(liked,count)=social.toggleCommentLike(comment.id);comments=comments.map{if(it.id==comment.id)it.copy(likedByMe=liked,likes=count)else it}}catch(e:CancellationException){throw e}catch(_:Exception){error="پسند ثبت نشد."}finally{busy.remove(comment.id)}}}},enabled=busy[comment.id]!=true){Icon(if(comment.likedByMe)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,"پسندیدن پاسخ");Spacer(Modifier.width(6.dp));Text("${comment.likes}")}
                            TextButton({if(loggedIn){replyId=comment.id;replyAuthor=comment.author.displayName}else onRequireAuth()},Modifier.testTag("community-reply-to-${comment.id}")){Text("پاسخ")}
                        }
                    }
                }
            }
            replyAuthor?.let{Row(verticalAlignment=Alignment.CenterVertically){Text("پاسخ به $it",Modifier.weight(1f),color=CinemaAccent);IconButton({replyId=null;replyAuthor=null}){Icon(Icons.Outlined.Close,"لغو پاسخ")}}}
            if(!loggedIn)CinemaAction(Icons.Outlined.Login,"برای پاسخ وارد شو",onRequireAuth,Modifier.fillMaxWidth())
            else {
                OutlinedTextField(draft,{draft=it.take(2000)},label={Text("تجربه یا پاسخ تو")},enabled=!sending,maxLines=4,modifier=Modifier.fillMaxWidth().testTag("community-reply-draft"))
                Row(verticalAlignment=Alignment.CenterVertically){FilterChip(spoiler,{spoiler=!spoiler},label={Text("دارای اسپویل")},enabled=!sending);Spacer(Modifier.weight(1f));TextButton({
                    if(draft.isNotBlank()&&!sending){sending=true;scope.launch{
                        try{social.addComment(post.id,draft.trim(),spoiler,replyId);draft="";spoiler=false;replyId=null;replyAuthor=null;refresh++}
                        catch(e:CancellationException){throw e}catch(_:Exception){error="ارسال نشد؛ متن محفوظ است. دوباره تلاش کن."}finally{sending=false}
                    }}
                },enabled=draft.isNotBlank()&&!sending,modifier=Modifier.testTag("community-reply-send")){Icon(Icons.Outlined.Send,null);Spacer(Modifier.width(8.dp));Text(if(sending)"ارسال…"else "ارسال")}}
            }
        }
    }
}
