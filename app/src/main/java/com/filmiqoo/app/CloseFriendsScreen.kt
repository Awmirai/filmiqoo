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
fun CloseFriendsScreen(
    backend:BackendRepository,
    onBack:()->Unit,
    onCreator:(Creator)->Unit
) {
    val repo=remember { CloseFriendsRepository(backend) }
    val scope=rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var people by remember { mutableStateOf<List<CloseFriendCandidate>>(emptyList()) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.list() }
            .onSuccess { people=it }
            .onFailure { error=it.message ?: "خطا در دریافت Following" }
        loading=false
    }

    val filtered=remember(people,query) {
        val q=query.trim().lowercase()
        if(q.isBlank()) people
        else people.filter {
            it.displayName.lowercase().contains(q) ||
                it.username.lowercase().contains(q)
        }
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("Close Friends",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text(
                    people.count{it.closeFriend}.toString()+" نفر • فقط برای Storyهای خصوصی",
                    color=FqGold,
                    fontSize=8.sp
                )
            }
            IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
        }

        Surface(
            color=FqGreen.copy(alpha=.08f),
            shape=RoundedCornerShape(18.dp),
            modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp)
        ) {
            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Star,null,tint=FqGreen)
                Spacer(Modifier.width(8.dp))
                Text(
                    "فقط افرادی که Follow می‌کنی می‌تونن وارد Close Friends بشن. خود لیست برای بقیه نمایش داده نمی‌شه.",
                    color=Color.White.copy(alpha=.78f),
                    fontSize=8.sp,
                    lineHeight=14.sp
                )
            }
        }

        OutlinedTextField(
            value=query,
            onValueChange={query=it},
            placeholder={Text("جستجو بین Following...")},
            leadingIcon={Icon(Icons.Default.Search,null)},
            singleLine=true,
            shape=RoundedCornerShape(17.dp),
            modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=7.dp)
        )

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=8.sp,
                modifier=Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        if(!loading && people.isEmpty()) {
            PremiumEmptyState(
                icon=Icons.Default.GroupAdd,
                title="هنوز کسی رو Follow نکردی",
                body="اول Creator یا کاربرهای موردعلاقه‌ات رو Follow کن؛ بعد می‌تونی از اینجا به Close Friends اضافه‌شون کنی."
            )
        } else if(!loading && filtered.isEmpty()) {
            PremiumEmptyState(
                icon=Icons.Default.SearchOff,
                title="نتیجه‌ای پیدا نشد",
                body="اسم یا Username دیگری امتحان کن."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(horizontal=12.dp,vertical=5.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp),
                modifier=Modifier.weight(1f)
            ) {
                items(filtered,key={it.id}) { person ->
                    Surface(
                        color=if(person.closeFriend)FqGreen.copy(alpha=.07f) else FqSurface,
                        shape=RoundedCornerShape(18.dp),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(11.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            RemoteImage(
                                person.avatarUrl.takeIf(String::isNotBlank),
                                Modifier.size(52.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(9.dp))
                            Column(
                                Modifier.weight(1f).clickable { onCreator(person.asCreator()) }
                            ) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(
                                        person.displayName,
                                        fontSize=10.sp,
                                        fontWeight=FontWeight.Bold,
                                        maxLines=1,
                                        overflow=TextOverflow.Ellipsis
                                    )
                                    if(person.verified) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Verified,
                                            null,
                                            tint=Color(0xFF4AB7FF),
                                            modifier=Modifier.size(13.dp)
                                        )
                                    }
                                }
                                Text(
                                    "@"+person.username,
                                    color=FqMuted,
                                    fontSize=7.sp
                                )
                            }

                            if(person.id in busy) {
                                CircularProgressIndicator(
                                    color=FqGold,
                                    strokeWidth=2.dp,
                                    modifier=Modifier.size(22.dp)
                                )
                            } else {
                                FilledTonalButton(
                                    onClick={
                                        busy=busy+person.id
                                        scope.launch {
                                            runCatching { repo.toggle(person.id) }
                                                .onSuccess { enabled->
                                                    people=people.map {
                                                        if(it.id==person.id)it.copy(closeFriend=enabled) else it
                                                    }
                                                }
                                                .onFailure { error=it.message }
                                            busy=busy-person.id
                                        }
                                    },
                                    colors=ButtonDefaults.filledTonalButtonColors(
                                        containerColor=if(person.closeFriend)
                                            FqGreen.copy(alpha=.16f)
                                        else FqSurface2
                                    )
                                ) {
                                    Icon(
                                        if(person.closeFriend)Icons.Default.Star else Icons.Default.StarBorder,
                                        null,
                                        tint=if(person.closeFriend)FqGreen else Color.White,
                                        modifier=Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if(person.closeFriend)"Close Friend" else "افزودن",
                                        fontSize=7.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
