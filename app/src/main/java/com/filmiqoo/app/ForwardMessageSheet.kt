package com.filmiqoo.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardMessageSheet(
    currentRoomId:String,
    messaging:MessagingRepository,
    onDismiss:()->Unit,
    onForward:suspend (String)->Unit
) {
    val scope=rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<InboxConversation>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sendingTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentRoomId) {
        runCatching { messaging.inbox() }
            .onSuccess { conversations=it.filterNot { room -> room.id==currentRoomId } }
            .onFailure { error=it.message }
        loading=false
    }

    val visible=remember(conversations,query) {
        val q=query.trim()
        if(q.isEmpty()) conversations
        else conversations.filter {
            it.title.contains(q,ignoreCase=true) ||
                it.otherUsername.contains(q,ignoreCase=true)
        }
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start=14.dp,end=14.dp,bottom=22.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Forward,null,tint=FqGold)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("فوروارد پیام",fontSize=19.sp)
                    Text("انتخاب گفتگو",color=FqMuted,fontSize=8.sp)
                }
                IconButton(onClick=onDismiss) {
                    Icon(Icons.Default.Close,null)
                }
            }

            OutlinedTextField(
                value=query,
                onValueChange={query=it.take(80)},
                placeholder={Text("جستجوی گفتگو...")},
                leadingIcon={Icon(Icons.Default.Search,null)},
                singleLine=true,
                modifier=Modifier.fillMaxWidth(),
                shape=RoundedCornerShape(15.dp)
            )

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )
            }
            error?.let {
                Text(it,color=FqDanger,fontSize=8.sp,modifier=Modifier.padding(top=8.dp))
            }

            if(!loading && visible.isEmpty() && error==null) {
                PremiumEmptyState(
                    Icons.Default.Forward,
                    "گفتگویی برای فوروارد نیست",
                    "اول یک DM یا گروه بساز."
                )
            } else {
                LazyColumn(
                    modifier=Modifier.heightIn(max=520.dp).padding(top=8.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    items(visible.size,key={visible[it].id}) { index ->
                        val room=visible[index]
                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(15.dp),
                            modifier=Modifier.fillMaxWidth()
                                .clickable(enabled=sendingTo==null) {
                                    sendingTo=room.id
                                    scope.launch {
                                        runCatching { onForward(room.id) }
                                            .onSuccess { onDismiss() }
                                            .onFailure { error=it.message }
                                        sendingTo=null
                                    }
                                }
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                RemoteImage(
                                    room.avatarUrl.takeIf(String::isNotBlank),
                                    Modifier.size(42.dp).clip(CircleShape)
                                )
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(room.title,fontSize=10.sp)
                                    Text(
                                        if(room.type=="dm") "@"+room.otherUsername else room.topic,
                                        color=FqMuted,
                                        fontSize=7.sp,
                                        maxLines=1
                                    )
                                }
                                if(sendingTo==room.id) {
                                    CircularProgressIndicator(
                                        color=FqGold,
                                        strokeWidth=2.dp,
                                        modifier=Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.Forward,null,tint=FqGold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
