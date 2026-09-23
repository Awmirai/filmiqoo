package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ConnectedRoomScreen(
    roomId: String,
    title: String,
    social: SocialRepository,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onBack: () -> Unit
) {
    val scope=rememberCoroutineScope()
    val listState=rememberLazyListState()
    var messages by remember(roomId) { mutableStateOf<List<RoomMessageItem>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(true) }

    suspend fun refresh() {
        runCatching { social.roomMessages(roomId) }
            .onSuccess {
                val changed=it.size!=messages.size
                messages=it
                syncing=false
                if(changed && it.isNotEmpty()) {
                    scope.launch { listState.animateScrollToItem(it.lastIndex) }
                }
            }
            .onFailure { error=it.message }
    }

    LaunchedEffect(roomId) {
        while(isActive) {
            refresh()
            delay(3000)
        }
    }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(FqSurface).padding(horizontal=8.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
            Box(Modifier.size(42.dp).background(FqGold,CircleShape),contentAlignment=Alignment.Center) {
                Icon(Icons.Default.Forum,null,tint=Color.Black)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title,fontSize=14.sp)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if(error==null)FqGreen else FqDanger,CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(if(syncing)"در حال همگام‌سازی..." else "همگام با سرور",color=FqMuted,fontSize=8.sp)
                }
            }
            IconButton(onClick={scope.launch{refresh()}}) { Icon(Icons.Default.Refresh,null) }
        }

        error?.let {
            Text(it,color=FqDanger,fontSize=9.sp,modifier=Modifier.fillMaxWidth().background(FqDanger.copy(alpha=.08f)).padding(8.dp))
        }

        LazyColumn(
            state=listState,
            modifier=Modifier.weight(1f),
            contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            items(messages.size,key={messages[it].id}) { index ->
                val msg=messages[index]
                var reveal by remember(msg.id) { mutableStateOf(!msg.spoiler) }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                    RemoteImage(msg.author.avatarUrl.takeIf(String::isNotBlank),Modifier.size(34.dp).background(FqSurface2,CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Surface(color=FqSurface,shape=RoundedCornerShape(16.dp),modifier=Modifier.weight(1f)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(msg.author.displayName,color=FqGold,fontSize=9.sp)
                                if(msg.author.verified) {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(12.dp))
                                }
                            }
                            if(msg.spoiler && !reveal) {
                                Text(
                                    "⚠ Spoiler Shield • نمایش پیام",
                                    color=FqDanger,fontSize=9.sp,
                                    modifier=Modifier.padding(top=5.dp).clickable { reveal=true }
                                )
                            } else {
                                Text(msg.body,fontSize=10.sp,lineHeight=17.sp,modifier=Modifier.padding(top=4.dp))
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(FqSurface).padding(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            FilterChip(
                selected=spoiler,
                onClick={spoiler=!spoiler},
                label={Text("Spoiler",fontSize=8.sp)}
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value=text,onValueChange={text=it},
                placeholder={Text("پیام...")},
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.weight(1f),
                maxLines=4
            )
            IconButton(onClick={
                if(!loggedIn) {
                    onRequireAuth()
                } else if(text.isNotBlank()) {
                    val sending=text.trim()
                    text=""
                    scope.launch {
                        runCatching { social.sendMessage(roomId,sending,spoiler) }
                            .onSuccess { spoiler=false;refresh() }
                            .onFailure { error=it.message }
                    }
                }
            }) {
                Icon(Icons.Default.Send,null,tint=if(text.isBlank())FqMuted else FqGold)
            }
        }
    }
}
