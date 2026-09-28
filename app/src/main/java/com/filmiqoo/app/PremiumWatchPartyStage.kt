package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun PremiumWatchPartyStage(
    party:WatchPartyInfo,
    player:ExoPlayer,
    lobby:WatchPartyLobby?,
    myUserId:String?,
    canHostControl:Boolean,
    realtimeConnected:Boolean,
    syncing:Boolean,
    privateJoinRequired:Boolean,
    joinRequestPending:Boolean,
    lobbyBusy:Boolean,
    reminderEnabled:Boolean,
    reminderBusy:Boolean,
    reactions:List<WatchPartyReaction>,
    messages:List<RoomMessageItem>,
    messageText:String,
    sendingMessage:Boolean,
    error:String?,
    listState:LazyListState,
    onBack:()->Unit,
    onPrimaryControl:()->Unit,
    onInvite:()->Unit,
    onQueue:()->Unit,
    onShare:()->Unit,
    onLobby:()->Unit,
    onRequestJoin:()->Unit,
    onToggleReminder:()->Unit,
    onToggleReady:()->Unit,
    onReact:(String)->Unit,
    onMessageTextChange:(String)->Unit,
    onSendMessage:()->Unit
) {
    val readyBlocked=
        party.state=="scheduled" &&
        lobby?.readyCheckEnabled==true &&
        (lobby.readyCount < lobby.participantCount)

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f/9f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory={ctx->
                    PlayerView(ctx).apply {
                        this.player=player
                        useController=false
                        resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn=true
                    }
                },
                update={it.player=player},
                modifier=Modifier.fillMaxSize()
            )

            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha=.58f),
                            Color.Transparent,
                            Color.Black.copy(alpha=.82f)
                        )
                    )
                )
            )

            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                WatchPartyGlassButton(Icons.Default.Close,onBack)
                Spacer(Modifier.width(8.dp))
                Surface(
                    color=if(realtimeConnected)FqGreen.copy(alpha=.18f) else FqSurface2.copy(alpha=.82f),
                    shape=RoundedCornerShape(99.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal=9.dp,vertical=6.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if(realtimeConnected)FqGreen else FqGold)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if(realtimeConnected)"LIVE SYNC" else "SYNCING",
                            color=if(realtimeConnected)FqGreen else FqGold,
                            fontSize=8.sp,
                            fontWeight=FontWeight.Black
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                if(canHostControl && party.visibility in setOf("invite","private")) {
                    WatchPartyGlassButton(Icons.Default.VpnKey,onInvite)
                    Spacer(Modifier.width(6.dp))
                }
                WatchPartyGlassButton(Icons.Default.PlaylistPlay,onQueue,lobby!=null)
                Spacer(Modifier.width(6.dp))
                WatchPartyGlassButton(Icons.Default.Share,onShare)
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal=14.dp,vertical=12.dp)
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Surface(
                        color=if(party.state=="live")FqDanger else FqGold.copy(alpha=.18f),
                        shape=RoundedCornerShape(99.dp)
                    ) {
                        Text(
                            if(party.state=="live")"● LIVE" else partyStatePremiumLabel(party.state),
                            color=if(party.state=="live")Color.White else FqGold,
                            fontSize=8.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp)
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        party.media.quality ?: "AUTO",
                        color=Color.White.copy(alpha=.7f),
                        fontSize=8.sp,
                        fontWeight=FontWeight.Bold
                    )
                }

                Text(
                    party.media.title.ifBlank { party.title },
                    fontSize=21.sp,
                    lineHeight=24.sp,
                    fontWeight=FontWeight.Black,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=7.dp)
                )
                Text(
                    party.title+"  •  "+party.participants+" نفر",
                    color=Color.White.copy(alpha=.72f),
                    fontSize=9.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )

                Row(
                    Modifier.fillMaxWidth().padding(top=9.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    if(canHostControl) {
                        Button(
                            enabled=!readyBlocked,
                            onClick=onPrimaryControl,
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            shape=RoundedCornerShape(13.dp),
                            contentPadding=PaddingValues(horizontal=13.dp,vertical=8.dp)
                        ) {
                            Icon(
                                when {
                                    party.state=="scheduled" -> Icons.Default.RocketLaunch
                                    party.isPlaying -> Icons.Default.Pause
                                    else -> Icons.Default.PlayArrow
                                },
                                null,
                                tint=Color.Black,
                                modifier=Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                when {
                                    party.state=="scheduled" -> "شروع برای همه"
                                    party.isPlaying -> "توقف برای همه"
                                    else -> "پخش برای همه"
                                },
                                color=Color.Black,
                                fontSize=9.sp,
                                fontWeight=FontWeight.Black
                            )
                        }
                    } else {
                        Surface(
                            color=Color.Black.copy(alpha=.5f),
                            shape=RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal=10.dp,vertical=8.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Sync,null,tint=FqGold,modifier=Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if(party.state=="scheduled")"منتظر شروع میزبان"
                                    else "کنترل پخش با میزبان",
                                    fontSize=8.sp,
                                    fontWeight=FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if(syncing) {
                        CircularProgressIndicator(
                            color=FqGold,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        PartyIdentityBar(
            party=party,
            lobby=lobby,
            realtimeConnected=realtimeConnected,
            onLobby=onLobby
        )

        if(privateJoinRequired) {
            PartyActionCard(
                icon=Icons.Default.Lock,
                title="Party خصوصی",
                subtitle=if(joinRequestPending)
                    "درخواستت ارسال شده؛ منتظر تأیید میزبان باش."
                else
                    "میزبان باید ورودت را تأیید کند.",
                action=if(joinRequestPending)"ارسال شد" else "درخواست ورود",
                actionEnabled=!joinRequestPending && !lobbyBusy,
                onAction=onRequestJoin
            )
        }

        if(party.state=="scheduled") {
            PartyActionCard(
                icon=Icons.Default.Event,
                title="برای بعد برنامه‌ریزی شده",
                subtitle=party.scheduledAt?.let(::premiumPartySchedule) ?: "زمان شروع ثبت شده",
                action=if(reminderEnabled)"یادآوری روشن" else "یادم بنداز",
                actionEnabled=!reminderBusy,
                onAction=onToggleReminder
            )
        }

        if(party.state=="scheduled" && lobby?.readyCheckEnabled==true) {
            PartyActionCard(
                icon=Icons.Default.HowToReg,
                title="Ready Check",
                subtitle=lobby.readyCount.toString()+" از "+lobby.participantCount+" نفر آماده‌اند",
                action=if(lobby.myReady)"آماده‌ام ✓" else "من آماده‌ام",
                actionEnabled=!lobbyBusy,
                onAction=onToggleReady
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=7.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            PartyShortcut(
                Icons.Default.Groups,
                (lobby?.participantCount ?: party.participants).toString()+" نفر",
                Modifier.weight(1f),
                onLobby
            )
            if(canHostControl) {
                PartyShortcut(Icons.Default.GroupAdd,"دعوت",Modifier.weight(1f),onInvite)
            }
            PartyShortcut(Icons.Default.PlaylistPlay,"صف پخش",Modifier.weight(1f),onQueue)
            PartyShortcut(Icons.Default.Share,"اشتراک",Modifier.weight(1f),onShare)
        }

        PremiumReactionRail(
            reactions=reactions,
            enabled=lobby!=null && !privateJoinRequired,
            onReact=onReact
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Chat,null,tint=FqGold,modifier=Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("چت Party",fontSize=11.sp,fontWeight=FontWeight.Black)
            Spacer(Modifier.width(6.dp))
            Surface(color=FqSurface2,shape=RoundedCornerShape(99.dp)) {
                Text(
                    messages.size.toString(),
                    color=FqMuted,
                    fontSize=7.sp,
                    modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if(realtimeConnected)"زنده" else "در حال اتصال",
                color=if(realtimeConnected)FqGreen else FqMuted,
                fontSize=8.sp
            )
        }

        HorizontalDivider(color=FqSurface3)

        LazyColumn(
            state=listState,
            modifier=Modifier.weight(1f),
            contentPadding=PaddingValues(horizontal=12.dp,vertical=9.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            if(messages.isEmpty()) {
                item {
                    Surface(
                        color=FqSurface.copy(alpha=.78f),
                        shape=RoundedCornerShape(18.dp),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            horizontalAlignment=Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Forum,null,tint=FqGold,modifier=Modifier.size(28.dp))
                            Text(
                                "اولین پیام Party را بفرست",
                                fontSize=10.sp,
                                fontWeight=FontWeight.Bold,
                                modifier=Modifier.padding(top=7.dp)
                            )
                            Text(
                                "چت با پخش همزمان کنار هم می‌ماند.",
                                color=FqMuted,
                                fontSize=8.sp,
                                modifier=Modifier.padding(top=3.dp)
                            )
                        }
                    }
                }
            }

            items(messages,key={it.id}) { msg->
                val mine=msg.author.id==myUserId
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start,
                    verticalAlignment=Alignment.Bottom
                ) {
                    if(!mine) {
                        RemoteImage(
                            msg.author.avatarUrl.takeIf(String::isNotBlank),
                            Modifier.size(28.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Surface(
                        color=if(mine)FqGold.copy(alpha=.16f) else FqSurface2,
                        shape=RoundedCornerShape(
                            topStart=17.dp,
                            topEnd=17.dp,
                            bottomStart=if(mine)17.dp else 5.dp,
                            bottomEnd=if(mine)5.dp else 17.dp
                        ),
                        modifier=Modifier.widthIn(max=310.dp)
                    ) {
                        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp)) {
                            if(!mine) {
                                Text(
                                    msg.author.displayName,
                                    color=FqGold,
                                    fontSize=7.sp,
                                    fontWeight=FontWeight.Bold
                                )
                            }
                            Text(
                                msg.body,
                                fontSize=9.sp,
                                lineHeight=14.sp,
                                modifier=Modifier.padding(top=if(mine)0.dp else 2.dp)
                            )
                        }
                    }
                }
            }
        }

        Surface(color=FqSurface,tonalElevation=5.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal=9.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value=messageText,
                    onValueChange=onMessageTextChange,
                    enabled=lobby!=null && !privateJoinRequired,
                    placeholder={
                        Text(
                            if(privateJoinRequired)"بعد از تأیید میزبان می‌تونی پیام بدی"
                            else "برای Party پیام بفرست…",
                            fontSize=9.sp
                        )
                    },
                    singleLine=true,
                    shape=RoundedCornerShape(20.dp),
                    modifier=Modifier.weight(1f)
                )
                Spacer(Modifier.width(5.dp))
                FilledIconButton(
                    enabled=!sendingMessage && messageText.isNotBlank() && lobby!=null && !privateJoinRequired,
                    onClick=onSendMessage,
                    colors=IconButtonDefaults.filledIconButtonColors(containerColor=FqGold)
                ) {
                    if(sendingMessage) {
                        CircularProgressIndicator(
                            color=Color.Black,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(18.dp)
                        )
                    } else {
                        Icon(Icons.Default.Send,null,tint=Color.Black)
                    }
                }
            }
        }

        error?.let {
            Surface(color=FqDanger.copy(alpha=.1f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=7.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ErrorOutline,null,tint=FqDanger,modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it,
                        color=FqDanger,
                        fontSize=8.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PartyIdentityBar(
    party:WatchPartyInfo,
    lobby:WatchPartyLobby?,
    realtimeConnected:Boolean,
    onLobby:()->Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box {
            RemoteImage(
                party.host.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(38.dp).clip(CircleShape)
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if(realtimeConnected)FqGreen else FqGold)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                party.host.displayName,
                fontSize=10.sp,
                fontWeight=FontWeight.Black,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
            Text(
                "میزبان  •  @"+party.host.username,
                color=FqMuted,
                fontSize=7.sp,
                modifier=Modifier.padding(top=1.dp)
            )
        }
        TextButton(onClick=onLobby,enabled=lobby!=null) {
            Text("مدیریت Party",fontSize=8.sp,color=FqGold)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Default.ChevronRight,null,tint=FqGold,modifier=Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PartyActionCard(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    subtitle:String,
    action:String,
    actionEnabled:Boolean,
    onAction:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(16.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)).background(FqGold.copy(alpha=.12f)),
                contentAlignment=Alignment.Center
            ) {
                Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title,fontSize=9.sp,fontWeight=FontWeight.Black)
                Text(
                    subtitle,
                    color=FqMuted,
                    fontSize=7.sp,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
            TextButton(enabled=actionEnabled,onClick=onAction) {
                Text(action,color=FqGold,fontSize=8.sp,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PartyShortcut(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    modifier:Modifier=Modifier,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(14.dp),
        modifier=modifier.clickable(onClick=onClick)
    ) {
        Column(
            Modifier.padding(horizontal=6.dp,vertical=9.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
            Text(
                title,
                fontSize=7.sp,
                fontWeight=FontWeight.Bold,
                maxLines=1,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=4.dp)
            )
        }
    }
}

@Composable
private fun WatchPartyGlassButton(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    onClick:()->Unit,
    enabled:Boolean=true
) {
    IconButton(
        enabled=enabled,
        onClick=onClick,
        modifier=Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha=.48f))
    ) {
        Icon(
            icon,
            null,
            tint=if(enabled)Color.White else Color.White.copy(alpha=.35f),
            modifier=Modifier.size(19.dp)
        )
    }
}

@Composable
private fun PremiumReactionRail(
    reactions:List<WatchPartyReaction>,
    enabled:Boolean,
    onReact:(String)->Unit
) {
    val emojis=listOf("❤️","😂","😮","🔥","👏","😢","🤯")
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp)) {
        if(reactions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom=5.dp),
                horizontalArrangement=Arrangement.spacedBy(5.dp)
            ) {
                reactions.take(6).forEach { reaction->
                    Surface(color=FqSurface2,shape=RoundedCornerShape(99.dp)) {
                        Row(
                            Modifier.padding(horizontal=7.dp,vertical=4.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Text(reaction.emoji,fontSize=13.sp)
                            if(reaction.displayName.isNotBlank()) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    reaction.displayName,
                                    color=FqMuted,
                                    fontSize=6.sp,
                                    maxLines=1,
                                    overflow=TextOverflow.Ellipsis,
                                    modifier=Modifier.widthIn(max=55.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Surface(color=FqSurface.copy(alpha=.72f),shape=RoundedCornerShape(16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=4.dp),
                horizontalArrangement=Arrangement.SpaceEvenly
            ) {
                emojis.forEach { emoji->
                    Text(
                        emoji,
                        fontSize=18.sp,
                        modifier=Modifier
                            .clip(CircleShape)
                            .clickable(enabled=enabled) { onReact(emoji) }
                            .padding(5.dp)
                    )
                }
            }
        }
    }
}

private fun partyStatePremiumLabel(state:String):String=when(state.lowercase()) {
    "scheduled" -> "SCHEDULED"
    "ended" -> "ENDED"
    "cancelled" -> "CANCELLED"
    else -> state.uppercase()
}

private fun premiumPartySchedule(value:String):String =
    runCatching {
        java.time.Instant.parse(value)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm"))
    }.getOrDefault(value)
