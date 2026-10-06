package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.URI
import java.net.URLDecoder

internal data class PartyInviteLink(val id:String,val code:String?)
internal fun parsePartyInvite(raw:String):PartyInviteLink? = runCatching {
    val value=Regex("filmiqoo://party/[^\\s]+",RegexOption.IGNORE_CASE).find(raw.trim())?.value ?: raw.trim()
    val uuid=Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    if(uuid.matches(value))return@runCatching PartyInviteLink(value.lowercase(),null)
    val uri=URI(value)
    if(!uri.scheme.equals("filmiqoo",true)||!uri.host.equals("party",true))return@runCatching null
    val id=uri.path?.removePrefix("/").orEmpty()
    if(!uuid.matches(id))return@runCatching null
    val code=uri.rawQuery.orEmpty().split('&').firstOrNull{it.startsWith("invite=")}
        ?.substringAfter('=')?.let{URLDecoder.decode(it,"UTF-8")}
        ?.takeIf{it.isNotBlank() && it.length<=128}
    PartyInviteLink(id.lowercase(),code)
}.getOrNull()

/** Discovery and invite entry use the real party repository; room creation starts at a playable title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyLobbyScreen(
    backend:BackendRepository,
    onOpenParty:(String,String?)->Unit,
    onChooseTitle:()->Unit,
    onRequireAuth:()->Unit
) {
    val repository=remember(backend){WatchPartyRepository(backend)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var parties by remember{mutableStateOf<List<WatchPartyInfo>>(emptyList())}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf<String?>(null)}
    var refresh by remember{mutableIntStateOf(0)}
    var filter by rememberSaveable{mutableStateOf("all")}
    var invite by rememberSaveable{mutableStateOf("")}
    var inviteError by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(repository,refresh,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                try { parties=repository.list();error=null }
                catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){error="دریافت اتاق‌ها انجام نشد؛ اتصال را بررسی کن و دوباره تلاش کن."}
                finally { loading=false }
                delay(20_000)
            }
        }
    }
    val visible=parties.filter{filter=="all"||it.state==filter}
    LazyColumn(Modifier.fillMaxSize().background(CinemaInk).imePadding().testTag("party-lobby"),
        contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { CinemaPageHeader("باهم ببینیم","یک فیلم، یک لحظه، کنار هم") {
            IconButton(onClick={loading=true;refresh++},enabled=!loading,modifier=Modifier.testTag("party-refresh")) {
                Icon(Icons.Outlined.Refresh,"تازه‌سازی اتاق‌ها",tint=CinemaAccent)
            }
        } }
        item {
            Surface(shape=RoundedCornerShape(28.dp),color=CinemaSurface,
                modifier=Modifier.padding(horizontal=20.dp).fillMaxWidth()) {
                Column(Modifier.background(Brush.linearGradient(listOf(CinemaAccent.copy(alpha=.18f),CinemaSurface))).padding(22.dp)) {
                    Icon(Icons.Outlined.Groups,null,tint=CinemaAccent,modifier=Modifier.size(36.dp))
                    Text("قرارِ سینمایی شما",style=MaterialTheme.typography.headlineMedium,
                        fontWeight=FontWeight.Bold,color=CinemaPaper,modifier=Modifier.padding(top=12.dp))
                    Text("فیلم یا قسمت سریال را انتخاب کن، دوست‌ها را دعوت کن و با پخش هماهنگ، گفتگو و واکنش کنار هم تماشا کنید.",
                        style=MaterialTheme.typography.bodyLarge,color=CinemaSoft,modifier=Modifier.padding(top=8.dp))
                    Button(onClick={if(backend.session.isLoggedIn)onChooseTitle() else onRequireAuth()},
                        modifier=Modifier.fillMaxWidth().padding(top=18.dp).heightIn(min=52.dp).testTag("party-create"),
                        colors=ButtonDefaults.buttonColors(containerColor=CinemaAccent,contentColor=CinemaInk)) {
                        Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("یک تماشای گروهی بساز")
                    }
                    Text("کنترل پخش با میزبان و هم‌میزبان است؛ بیننده‌ها روی همان زمان فیلم می‌مانند.",
                        style=MaterialTheme.typography.bodySmall,color=CinemaSoft,modifier=Modifier.padding(top=12.dp))
                }
            }
        }
        item {
            Surface(shape=RoundedCornerShape(22.dp),color=CinemaSurface,
                modifier=Modifier.padding(horizontal=20.dp).fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("دعوت داری؟",style=MaterialTheme.typography.titleLarge,color=CinemaPaper)
                    Text("لینک دعوت را اینجا وارد کن؛ اتاق‌های خصوصی در فهرست عمومی نمایش داده نمی‌شوند.",
                        style=MaterialTheme.typography.bodyMedium,color=CinemaSoft,modifier=Modifier.padding(top=6.dp,bottom=12.dp))
                    OutlinedTextField(value=invite,onValueChange={invite=it.take(2048);inviteError=null},
                        label={Text("لینک دعوت یا شناسهٔ اتاق")},isError=inviteError!=null,
                        singleLine=true,modifier=Modifier.fillMaxWidth().testTag("party-invite-input"))
                    inviteError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,
                        modifier=Modifier.padding(top=8.dp).testTag("party-invite-error"))}
                    OutlinedButton(onClick={
                        val parsed=parsePartyInvite(invite)
                        if(parsed==null)inviteError="لینک معتبرِ filmiqoo://party یا شناسهٔ کامل اتاق را وارد کن."
                        else if(!backend.session.isLoggedIn)onRequireAuth() else onOpenParty(parsed.id,parsed.code)
                    },enabled=invite.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=12.dp).heightIn(min=48.dp).testTag("party-join")) {
                        Icon(Icons.Outlined.Login,null);Spacer(Modifier.width(8.dp));Text("ورود با دعوت")
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
                Text("اتاق‌های عمومی",style=MaterialTheme.typography.titleLarge,color=CinemaPaper)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=10.dp)) {
                    listOf("all" to "همه","live" to "در حال تماشا","scheduled" to "قرارهای بعدی").forEach{(id,label)->
                        FilterChip(selected=filter==id,onClick={filter=id},label={Text(label)})
                    }
                }
                if(loading)LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(top=12.dp),color=CinemaAccent)
                error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp).testTag("party-list-error"))}
            }
        }
        if(!loading && error==null && visible.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Theaters,null,tint=CinemaSoft,modifier=Modifier.size(36.dp))
                Text(if(filter=="all")"فعلاً اتاق عمومی‌ای باز نیست" else "در این بخش اتاقی پیدا نشد",
                    style=MaterialTheme.typography.titleMedium,color=CinemaPaper,modifier=Modifier.padding(top=12.dp))
                Text("می‌توانی برای یک عنوانِ آمادهٔ پخش، قرار خودت را بسازی.",style=MaterialTheme.typography.bodyMedium,
                    color=CinemaSoft,modifier=Modifier.padding(top=6.dp))
            }
        }
        items(visible,key={it.id}){party->
            Surface(color=CinemaSurface,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(party.media.posterUrl,Modifier.size(72.dp,104.dp).clip(RoundedCornerShape(14.dp)))
                        Column(Modifier.weight(1f).padding(start=14.dp)) {
                            Text(if(party.state=="live")"در حال تماشا" else "قرارِ بعدی",style=MaterialTheme.typography.labelLarge,color=CinemaAccent)
                            Text(party.media.title.ifBlank{party.title},style=MaterialTheme.typography.titleLarge,color=CinemaPaper,
                                modifier=Modifier.padding(top=6.dp))
                            Text("میزبان: "+party.host.displayName,style=MaterialTheme.typography.bodyMedium,color=CinemaSoft,
                                modifier=Modifier.padding(top=6.dp))
                            Text("${party.participants} عضو",style=MaterialTheme.typography.bodySmall,color=CinemaSoft)
                        }
                    }
                    party.scheduledAt?.takeIf{party.state=="scheduled"}?.let{value->
                        Text(runCatching{java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd · HH:mm")
                            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value))}.getOrDefault(value),
                            color=CinemaSoft,modifier=Modifier.padding(top=12.dp))
                    }
                    Button(onClick={if(backend.session.isLoggedIn)onOpenParty(party.id,null) else onRequireAuth()},
                        modifier=Modifier.fillMaxWidth().padding(top=14.dp).heightIn(min=48.dp).testTag("party-open-${party.id}")) {
                        Icon(Icons.Outlined.Groups,null);Spacer(Modifier.width(8.dp))
                        Text(if(party.state=="scheduled")"ورود به اتاق انتظار" else "همراهِ این تماشا شو")
                    }
                }
            }
        }
    }
}
