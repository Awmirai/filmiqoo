package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun FollowRequestsScreen(
    social:SocialRepository,
    onBack:()->Unit,
    onCreator:(Creator)->Unit
) {
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var requests by remember { mutableStateOf<List<FollowRequestItem>>(emptyList()) }
    var busyIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { social.followRequests() }
            .onSuccess { requests=it }
            .onFailure { error=it.message }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("درخواست‌های Follow",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text(
                    if(requests.isEmpty())"درخواست جدیدی نداری"
                    else requests.size.toString()+" درخواست در انتظار",
                    color=if(requests.isEmpty())FqMuted else FqGold,
                    fontSize=11.sp
                )
            }
            IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=11.sp,
                modifier=Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        if(!loading && requests.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.PersonAddDisabled,
                "درخواستی در انتظار نیست",
                "اگر حسابت Private باشه، درخواست‌های Follow اینجا ظاهر می‌شن."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp),
                modifier=Modifier.weight(1f)
            ) {
                items(requests,key={it.id}) { request ->
                    FollowRequestCard(
                        request=request,
                        busy=request.id in busyIds,
                        onCreator={onCreator(request.asCreator())},
                        onAccept={
                            busyIds=busyIds+request.id
                            scope.launch {
                                runCatching { social.acceptFollowRequest(request.id) }
                                    .onSuccess { refresh++ }
                                    .onFailure { error=it.message }
                                busyIds=busyIds-request.id
                            }
                        },
                        onDecline={
                            busyIds=busyIds+request.id
                            scope.launch {
                                runCatching { social.declineFollowRequest(request.id) }
                                    .onSuccess { refresh++ }
                                    .onFailure { error=it.message }
                                busyIds=busyIds-request.id
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowRequestCard(
    request:FollowRequestItem,
    busy:Boolean,
    onCreator:()->Unit,
    onAccept:()->Unit,
    onDecline:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(19.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment=Alignment.CenterVertically,
                modifier=Modifier.fillMaxWidth().clickable { onCreator() }
            ) {
                RemoteImage(
                    request.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(54.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(
                            request.displayName,
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold
                        )
                        if(request.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Verified,
                                null,
                                tint=Color(0xFF4AB7FF),
                                modifier=Modifier.size(13.dp)
                            )
                        }
                    }
                    Text("@"+request.username,color=FqMuted,fontSize=11.sp)
                    if(request.bio.isNotBlank()) {
                        Text(
                            request.bio,
                            color=Color.White.copy(alpha=.67f),
                            fontSize=11.sp,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=3.dp)
                        )
                    }
                    Text(
                        compactFollowRequestCount(request.followers)+" دنبال‌کننده",
                        color=FqMuted,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
                Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
            }

            Row(
                Modifier.fillMaxWidth().padding(top=10.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick=onAccept,
                    enabled=!busy,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                    shape=RoundedCornerShape(12.dp),
                    modifier=Modifier.weight(1f)
                ) {
                    if(busy) {
                        CircularProgressIndicator(
                            color=Color.Black,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(16.dp)
                        )
                    } else {
                        Icon(Icons.Default.Check,null,tint=Color.Black)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Accept",color=Color.Black,fontSize=11.sp)
                }

                OutlinedButton(
                    onClick=onDecline,
                    enabled=!busy,
                    shape=RoundedCornerShape(12.dp),
                    modifier=Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Close,null,modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Decline",fontSize=11.sp)
                }
            }
        }
    }
}

private fun compactFollowRequestCount(value:Long):String=when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
