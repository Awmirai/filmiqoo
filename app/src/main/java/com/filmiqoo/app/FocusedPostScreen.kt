package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A notification/share link opens one existing post without resurrecting the retired feed tab. */
@Composable
internal fun FocusedPostScreen(id:String,social:SocialRepository,backend:BackendRepository,onBack:()->Unit,
    onMedia:(MediaItem)->Unit,onCreator:(Creator)->Unit,onRequireAuth:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var post by remember(id){mutableStateOf<SocialPost?>(null)}
    var viewerId by remember(backend.session.isLoggedIn){mutableStateOf<String?>(null)}
    var refresh by remember{mutableIntStateOf(0)}
    var loading by remember{mutableStateOf(true)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var replies by rememberSaveable(id){mutableStateOf(false)}
    var safety by remember{mutableStateOf(false)}
    var remove by remember{mutableStateOf(false)}
    val loggedIn=backend.session.isLoggedIn
    LaunchedEffect(id,refresh,loggedIn){
        loading=true;error=null
        try{post=social.post(id)}catch(e:CancellationException){throw e}
        catch(_:Exception){error="این پست دریافت نشد؛ ممکن است اتصال قطع باشد یا پست در دسترس نباشد."}
        finally{loading=false}
    }
    LaunchedEffect(loggedIn){viewerId=if(loggedIn)cinemaUiOptional{backend.me().id}else null}
    fun mutate(action:suspend ()->Unit){
        if(!loggedIn){onRequireAuth();return}
        if(busy)return
        busy=true
        scope.launch{try{action();error=null}catch(e:CancellationException){throw e}
            catch(_:Exception){error="تغییر ثبت نشد؛ دوباره تلاش کن."}finally{busy=false}}
    }
    Box(Modifier.fillMaxSize().background(CinemaInk).testTag("focused-post")){
        LazyColumn(contentPadding=PaddingValues(bottom=CinemaTokens.page)){
            item{CinemaPageHeader("گفت‌وگوی سینمایی",onBack=onBack)}
            if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth(),color=CinemaAccent)}
            error?.let{message->item{Box(Modifier.padding(horizontal=CinemaTokens.page)){
                CinemaNotice("پست آماده نشد",message,Icons.Outlined.CloudOff,"تلاش دوباره",{refresh++})
            }}}
            post?.let{entry->item{
                CommunityPostCard(entry,social,loggedIn,viewerId==entry.author.id,busy,onRequireAuth,onMedia,onCreator,
                    onLike={mutate{val liked=social.togglePostLike(id);post=post?.let{it.copy(likedByMe=liked,likes=(it.likes+if(liked)1 else -1).coerceAtLeast(0))}}},
                    onSave={mutate{val(saved,_)=social.togglePostSave(id);post=post?.copy(savedByMe=saved)}},
                    onComments={replies=true},
                    onShare={FilmiqooDeepLinks.share(context,entry.author.displayName,FilmiqooDeepLinks.post(id))},
                    onSafety={if(loggedIn)safety=true else onRequireAuth()},onRemove={remove=true})
            }}
        }
    }
    if(replies)post?.let{entry->CommunityReplySheet(entry,social,loggedIn,onRequireAuth,{replies=false},
        onCountChanged={count->post=post?.copy(comments=count)})}
    if(safety)post?.let{entry->SafetyActionSheet(backend,"post",id,"پست ${entry.author.displayName}",entry.author.id,
        onDismiss={safety=false},onChanged={safety=false;post=null;refresh++})}
    if(remove)AlertDialog(onDismissRequest={if(!busy)remove=false},title={Text("این پست حذف شود؟")},
        text={Text("پست منتشرشدهٔ تو از دسترس خارج می‌شود.")},
        confirmButton={TextButton({mutate{check(social.removePost(id));remove=false;onBack()}},enabled=!busy){Text("حذف")}},
        dismissButton={TextButton({remove=false},enabled=!busy){Text("انصراف")}})
}
