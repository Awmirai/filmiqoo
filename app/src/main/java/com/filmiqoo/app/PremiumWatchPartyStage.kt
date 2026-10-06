package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** All room actions scroll, so the conversation and composer survive large fonts/landscape. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PremiumWatchPartyStage(
    party:WatchPartyInfo,player:ExoPlayer,lobby:WatchPartyLobby?,myUserId:String?,
    canHostControl:Boolean,realtimeConnected:Boolean,syncing:Boolean,
    privateJoinRequired:Boolean,joinRequestPending:Boolean,lobbyBusy:Boolean,
    reminderEnabled:Boolean,reminderBusy:Boolean,reactions:List<WatchPartyReaction>,
    messages:List<RoomMessageItem>,messageText:String,sendingMessage:Boolean,error:String?,
    listState:LazyListState,onBack:()->Unit,onPrimaryControl:()->Unit,onInvite:()->Unit,
    onQueue:()->Unit,onShare:()->Unit,onLobby:()->Unit,onRequestJoin:()->Unit,
    onToggleReminder:()->Unit,onToggleReady:()->Unit,onReact:(String)->Unit,
    onMessageTextChange:(String)->Unit,onSendMessage:()->Unit,
    onSeek:(Long)->Unit={},onRetryPlayback:()->Unit={}
) {
    val closed=party.state in setOf("ended","cancelled")
    val admitted=lobby!=null && !privateJoinRequired
    val readyBlocked=party.state=="scheduled" && lobby?.readyCheckEnabled==true &&
        lobby.readyCount<(if(lobby.presenceKnown)lobby.onlineCount else lobby.participantCount)
    var position by remember(player){mutableLongStateOf(0)}
    var duration by remember(player){mutableLongStateOf(0)}
    var seekFraction by remember{mutableStateOf<Float?>(null)}
    LaunchedEffect(player){while(isActive){position=player.currentPosition.coerceAtLeast(0);duration=player.duration.coerceAtLeast(0);delay(500)}}
    Column(Modifier.fillMaxSize().background(CinemaInk).safeDrawingPadding().imePadding()) {
        LazyColumn(state=listState,modifier=Modifier.weight(1f).testTag("party-stage"),
            contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="stage-header") {
                Column {
                    CinemaPageHeader("باهم ببینیم",party.title,onBack=onBack){
                        IconButton(onClick=onShare){Icon(Icons.Outlined.Share,"اشتراک دعوت",tint=CinemaAccent)}
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black)) {
                        val videoHeight=(maxWidth*9f/16f).coerceIn(160.dp,300.dp)
                        AndroidView(factory={context->PlayerView(context).apply{
                            this.player=player;useController=false;resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT;keepScreenOn=true
                        }},update={it.player=player},modifier=Modifier.fillMaxWidth().height(videoHeight).testTag("party-video"))
                    }
                    Column(Modifier.padding(horizontal=20.dp,vertical=16.dp)) {
                        Text(party.media.title.ifBlank{party.title},style=MaterialTheme.typography.headlineSmall,color=CinemaPaper,fontWeight=FontWeight.Bold)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)) {
                            AssistChip(onClick=onLobby,enabled=admitted,label={Text(if(closed)"جلسه پایان یافت" else if(party.state=="scheduled")"اتاق انتظار" else "تماشای گروهی")})
                            AssistChip(onClick=onLobby,enabled=admitted,label={Text(lobby?.let{
                                if(it.presenceKnown)"${it.onlineCount} آنلاین · ${it.participantCount} عضو" else "${it.participantCount} عضو"
                            } ?: "${party.participants} عضو")})
                        }
                        if(!party.serverTimed) Surface(color=CinemaAccent.copy(alpha=.1f),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(top=12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("پخش هماهنگ نیاز به ارتقای سرور دارد",style=MaterialTheme.typography.titleMedium,color=CinemaPaper)
                                Text("سرور فعلی هنوز زمان و نسخهٔ مشترک پخش را ارائه نمی‌کند. اتاق و دعوت‌ها در دسترس‌اند؛ تماشای هماهنگ بعد از فعال‌سازی سرور جدید باز می‌شود.",
                                    style=MaterialTheme.typography.bodyMedium,color=CinemaSoft,modifier=Modifier.padding(top=8.dp).testTag("party-server-upgrade"))
                            }
                        }
                        Text(if(!party.serverTimed)"همگام‌سازی پخش هنوز فعال نیست" else if(realtimeConnected)"اتصال زنده برقرار است" else "همگام‌سازی دوره‌ای؛ اتصال زنده دوباره برقرار می‌شود",
                            style=MaterialTheme.typography.bodySmall,color=if(realtimeConnected)FqGreen else CinemaSoft,
                            modifier=Modifier.padding(top=4.dp).testTag("party-connection-status"))
                        if(canHostControl && admitted && !closed && party.serverTimed) {
                            Button(enabled=!readyBlocked && !syncing,onClick=onPrimaryControl,
                                modifier=Modifier.fillMaxWidth().padding(top=14.dp).heightIn(min=52.dp).testTag("party-play-control"),
                                colors=ButtonDefaults.buttonColors(containerColor=CinemaAccent,contentColor=CinemaInk)) {
                                if(syncing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                                else Icon(if(party.isPlaying)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,null)
                                Spacer(Modifier.width(8.dp))
                                Text(if(party.state=="scheduled")"شروع برای همه" else if(party.isPlaying)"توقف برای همه" else "پخش برای همه")
                            }
                            if(duration>0 && party.state=="live") {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Slider(value=seekFraction ?: (position.toFloat()/duration).coerceIn(0f,1f),onValueChange={seekFraction=it},
                                        onValueChangeFinished={seekFraction?.let{onSeek((it*duration).toLong())};seekFraction=null},enabled=!syncing,
                                        modifier=Modifier.fillMaxWidth().testTag("party-seek"))
                                }
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick={onSeek((position-10_000).coerceAtLeast(0))},enabled=!syncing){Text("۱۰ ثانیه عقب")}
                                    OutlinedButton(onClick={onSeek((position+10_000).coerceAtMost(duration))},enabled=!syncing){Text("۱۰ ثانیه جلو")}
                                }
                            }
                        } else if(!closed && party.serverTimed)Text(if(party.state=="scheduled")"منتظر شروع میزبان" else "کنترل پخش با میزبان هماهنگ می‌شود.",
                            style=MaterialTheme.typography.bodyMedium,color=CinemaSoft,modifier=Modifier.padding(top=12.dp))
                        if(privateJoinRequired) {
                            Text(if(joinRequestPending)"درخواست ارسال شده؛ منتظر تأیید میزبان باش." else "ورود به این اتاق خصوصی به تأیید میزبان نیاز دارد.",modifier=Modifier.padding(top=12.dp))
                            Button(onClick=onRequestJoin,enabled=!joinRequestPending && !lobbyBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("درخواست ورود")}
                        }
                        if(party.state=="scheduled" && admitted) {
                            party.scheduledAt?.let{Text(partyScheduleText(it),color=CinemaSoft,modifier=Modifier.padding(top=10.dp))}
                            OutlinedButton(onClick=onToggleReminder,enabled=!reminderBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                                Icon(Icons.Outlined.NotificationsActive,null);Spacer(Modifier.width(8.dp));Text(if(reminderEnabled)"یادآوری روشن است" else "شروع را یادم بیاور")
                            }
                            if(lobby?.readyCheckEnabled==true) {
                                Text(if(lobby.presenceKnown)"${lobby.readyCount} از ${lobby.onlineCount} نفر آنلاین آماده‌اند"
                                    else "${lobby.readyCount} از ${lobby.participantCount} عضو آماده‌اند",color=CinemaSoft,modifier=Modifier.padding(top=8.dp))
                                Button(onClick=onToggleReady,enabled=!lobbyBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(lobby.myReady)"آماده‌ام ✓" else "من آماده‌ام")}
                            }
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=12.dp)) {
                            OutlinedButton(onClick=onLobby,enabled=admitted){Icon(Icons.Outlined.People,null);Spacer(Modifier.width(6.dp));Text("افراد و آمادگی")}
                            if(canHostControl)OutlinedButton(onClick=onInvite){Icon(Icons.Outlined.GroupAdd,null);Spacer(Modifier.width(6.dp));Text("دعوت دوست‌ها")}
                            OutlinedButton(onClick=onQueue,enabled=admitted && !closed){Icon(Icons.Outlined.PlaylistPlay,null);Spacer(Modifier.width(6.dp));Text("صفِ تماشا")}
                        }
                        error?.let{
                            Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(top=12.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(it,color=MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.testTag("party-stage-error"))
                                    TextButton(onClick=onRetryPlayback){Text("تلاش دوباره برای اتصال")}
                                }
                            }
                        }
                        Text("همین لحظه چه حسی داری؟",style=MaterialTheme.typography.titleMedium,color=CinemaPaper,modifier=Modifier.padding(top=20.dp))
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.padding(top=8.dp)) {
                            listOf("❤️","😂","😮","🔥","👏","😢","🤯").forEach{emoji->
                                TextButton(onClick={onReact(emoji)},enabled=admitted && !closed,
                                    modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("party-reaction-$emoji")){Text(emoji,style=MaterialTheme.typography.titleLarge)}
                            }
                        }
                        if(reactions.isNotEmpty())Text(reactions.take(4).joinToString(" · "){it.displayName+" "+it.emoji},style=MaterialTheme.typography.bodySmall,color=CinemaSoft,modifier=Modifier.padding(top=6.dp))
                        HorizontalDivider(color=CinemaLine,modifier=Modifier.padding(vertical=18.dp))
                        Text("گفتگوی این تماشا",style=MaterialTheme.typography.titleLarge,color=CinemaPaper)
                        if(messages.isEmpty())Text("اولین پیام را بنویس؛ گفتگو برای اعضای همین اتاق است.",style=MaterialTheme.typography.bodyMedium,color=CinemaSoft,modifier=Modifier.padding(top=8.dp))
                    }
                }
            }
            items(messages,key={it.id}){message->
                var revealed by rememberSaveable(message.id){mutableStateOf(false)}
                Surface(color=if(message.author.id==myUserId)CinemaAccent.copy(alpha=.1f)else CinemaSurface,
                    shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("party-message-${message.id}")) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.Top) {
                        RemoteImage(message.author.avatarUrl.takeIf{it.isNotBlank()},Modifier.size(36.dp).clip(CircleShape))
                        Column(Modifier.weight(1f).padding(start=10.dp)) {
                            Text(message.author.displayName,style=MaterialTheme.typography.labelLarge,color=CinemaAccent)
                            if(message.spoiler && !revealed)TextButton(onClick={revealed=true}){Text("این پیام داستان را لو می‌دهد؛ نمایش")}
                            else Text(message.body,style=MaterialTheme.typography.bodyLarge,color=CinemaPaper,modifier=Modifier.padding(top=6.dp))
                        }
                    }
                }
            }
        }
        Surface(color=CinemaSurface,shadowElevation=4.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value=messageText,onValueChange=onMessageTextChange,enabled=admitted && !closed,
                    label={Text(if(closed)"جلسه پایان یافته" else "پیام به اتاق")},maxLines=3,
                    modifier=Modifier.weight(1f).testTag("party-message-input"),shape=RoundedCornerShape(18.dp))
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick=onSendMessage,enabled=admitted && !closed && !sendingMessage && messageText.isNotBlank(),
                    modifier=Modifier.size(48.dp).testTag("party-send")) {
                    if(sendingMessage)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Send,"ارسال پیام")
                }
            }
        }
    }
}

private fun partyScheduleText(value:String):String=runCatching {
    java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd · HH:mm").withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.parse(value))
}.getOrDefault(value)
