package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun TitleComment.media():MediaItem {
    val parts=scope.split(':')
    return MediaItem(parts.getOrNull(1)?.toIntOrNull()?:0,if(parts.firstOrNull()=="series")MediaType.TV else MediaType.MOVIE,
        title.ifBlank { if(parts.firstOrNull()=="series")"گفت‌وگوی سریال"else "گفت‌وگوی فیلم" },
        posterPath=poster.takeIf(String::isNotBlank),backendId=if(parts.firstOrNull()=="catalog")parts.getOrNull(1)else null)
}

/** The club reads title_comments, exactly the same API and identities as each title page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaSocialScreen(social:SocialRepository,backend:BackendRepository,loggedIn:Boolean,
    onMedia:(MediaItem)->Unit,onOpenClip:(String)->Unit,onOpenRoom:(SocialRoom)->Unit,onCreator:(Creator)->Unit,
    onSearch:()->Unit,onInbox:()->Unit,onCreate:()->Unit,onRequireAuth:()->Unit,onCollection:(String?)->Unit,
    onStories:(List<SocialStory>,Int)->Unit,initialPostId:String?=null,onFocusedPostConsumed:()->Unit={}) {
    var legacy by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialPostId){if(initialPostId!=null)legacy=true}
    if(legacy) {
        BackHandler{legacy=false}
        Column(Modifier.fillMaxSize()) {
            TextButton({legacy=false},modifier=Modifier.statusBarsPadding()){Icon(Icons.Outlined.ArrowForward,null);Text("بازگشت به باشگاه فیلم")}
            Box(Modifier.weight(1f)) { LegacyCinemaSocialScreen(social,backend,loggedIn,onMedia,onOpenClip,onOpenRoom,onCreator,onSearch,onInbox,onCreate,onRequireAuth,onCollection,onStories,initialPostId,onFocusedPostConsumed) }
        }
        return
    }
    val context=LocalContext.current
    val repo=remember(backend){TitleDiscussionRepository(backend)}
    val personal=remember{CinemaPersonalStore(context,backend.viewerProfiles.activeId())}
    val scope=rememberCoroutineScope()
    var page by remember{mutableStateOf<DiscussionPage?>(null)}
    var cursor by rememberSaveable{mutableStateOf<String?>(null)}
    var refresh by remember{mutableIntStateOf(0)}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf<String?>(null)}
    var active by remember{mutableStateOf<TitleComment?>(null)}
    val snackbar=remember{SnackbarHostState()}
    LaunchedEffect(cursor,refresh,loggedIn){
        loading=true;error=null
        try{page=repo.page("feed",null,cursor)}catch(e:CancellationException){throw e}
        catch(_:Exception){error="گفت‌وگوها دریافت نشدند؛ دوباره تلاش کن."}finally{loading=false}
    }
    Box(Modifier.fillMaxSize().background(CinemaInk).testTag("cinema-social")) {
        LazyColumn(contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item("header"){CinemaPageHeader("باشگاه فیلم","نظر آدم‌ها، دربارهٔ همان فیلم‌ها") {
                IconButton({refresh++},enabled=!loading){Icon(Icons.Outlined.Refresh,"تازه‌کردن گفت‌وگوها")}
            }}
            item("start"){CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
                Text("دربارهٔ چه اثری حرف بزنیم؟",style=MaterialTheme.typography.titleLarge)
                Text("یک فیلم یا سریال پیدا کن؛ نظر کلی یا گفت‌وگوی هر قسمت در صفحهٔ همان اثر است.",color=CinemaSoft,modifier=Modifier.padding(vertical=10.dp))
                CinemaAction(Icons.Outlined.Search,"پیداکردن اثر و نوشتن نظر",onSearch,Modifier.fillMaxWidth(),primary=true)
            }}
            if(loading)item("loading"){LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp),color=CinemaAccent)}
            error?.let{message->item("error"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("ارتباط کامل نشد",message,Icons.Outlined.CloudOff,"تلاش دوباره",{refresh++})}}}
            if(!loading&&error==null&&page?.items.isNullOrEmpty())item("empty"){Box(Modifier.padding(horizontal=20.dp)){
                CinemaNotice("هنوز گفت‌وگویی شروع نشده","اولین نظر می‌تواند به انتخاب فیلم نفر بعد کمک کند. هیچ فعالیت ساختگی در این بخش نمایش داده نمی‌شود.",Icons.Outlined.Forum)
            }}
            items(page?.items.orEmpty(),key={it.id}){comment->
                val media=comment.media()
                var revealed by rememberSaveable(comment.id){mutableStateOf(false)}
                var saved by remember(comment.id){mutableStateOf(personal.contains("watchlist",media))}
                var busy by remember{mutableStateOf(false)}
                CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth().testTag("club-comment-${comment.id}")) {
                    Row(verticalAlignment=Alignment.CenterVertically){
                        CinemaImage(media.posterPath,Modifier.width(48.dp).height(72.dp))
                        Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                            Text(media.title,style=MaterialTheme.typography.titleMedium)
                            Text(if(comment.scope.contains(":s"))comment.scope.split(':').drop(2).let{"فصل ${it[0].drop(1)} · قسمت ${it[1].drop(1)}"}else "نظر کلی اثر",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton({onCreator(Creator(comment.author,"","","",false,comment.authorId))}){Text(comment.author)}
                    if(comment.spoiler&&!revealed)OutlinedButton({revealed=true},Modifier.fillMaxWidth()){Icon(Icons.Outlined.VisibilityOff,null);Spacer(Modifier.width(8.dp));Text("دیدگاه دارای اسپویل · نمایش")}
                    else {
                        if(comment.body.isNotBlank())Text(comment.body,style=MaterialTheme.typography.bodyLarge)
                        if(comment.sticker.isNotBlank())CinemaReactionSticker(comment.sticker)
                        if(comment.gif.isNotBlank())Text("پیوست GIF · در گفت‌وگو ببین",color=CinemaSoft)
                    }
                    Text("${comment.likes} پسند · ${comment.replies} پاسخ",color=CinemaSoft,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=12.dp))
                    CinemaAction(Icons.Outlined.ChatBubbleOutline,"خواندن و پاسخ",{active=comment},Modifier.fillMaxWidth().padding(top=8.dp))
                    Row(Modifier.fillMaxWidth()){
                        TextButton({onMedia(media)},Modifier.weight(1f)){Text("صفحهٔ اثر")}
                        TextButton({if(!busy){busy=true;scope.launch{
                            try {
                                val next=if(media.backendId!=null&&loggedIn)LibraryRepository(backend).toggleWatchlist(media.backendId)else !saved
                                personal.setSaved("watchlist",media,next);saved=next
                            } catch(e:CancellationException){throw e}catch(_:Exception){snackbar.showSnackbar("فهرست تغییر نکرد؛ دوباره تلاش کن.")}finally{busy=false}
                        }}},Modifier.weight(1f),enabled=!busy){Text(if(saved)"✓ در فهرست"else "+ فهرست تماشا")}
                    }
                }
            }
            item("pages"){Row(Modifier.fillMaxWidth().padding(horizontal=20.dp)){
                if(cursor!=null)TextButton({cursor=null},enabled=!loading){Text("تازه‌ترین‌ها")}
                page?.next?.let{next->TextButton({cursor=next},enabled=!loading){Text("گفت‌وگوهای قدیمی‌تر")}}
            }}
            item("legacy"){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp)){
                HorizontalDivider(color=CinemaLine)
                TextButton({legacy=true}){Text("پست‌ها و مجموعه‌های قبلی")}
                Text("محتوای قبلی حساب‌ها حفظ شده و از این مسیر در دسترس است.",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)
            }}
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter))
    }
    active?.let{comment->ModalBottomSheet(onDismissRequest={active=null;refresh++},containerColor=CinemaInk,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxHeight(.9f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()){
            Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text(comment.media().title,modifier=Modifier.weight(1f),fontWeight=FontWeight.Bold);IconButton({active=null;refresh++}){Icon(Icons.Outlined.Close,"بستن گفت‌وگو")}}
            TitleDiscussion(comment.media(),backend,onRequireAuth,initialScope=comment.scope)
        }
    }}
}
