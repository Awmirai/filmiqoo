package com.filmiqoo.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun MessagesScreen(
    onChat: (String) -> Unit,
    onWatchParty: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs=listOf("پیام‌ها","روم‌ها","کانال‌ها")
    val direct=listOf(
        Triple("Sara","اون Reel آخرت خیلی خوب بود 🔥","2m"),
        Triple("CineVerse","ممنون بابت پیشنهاد فیلم","10m"),
        Triple("AnimeHub","فصل جدید رو دیدی؟","1h"),
        Triple("Nima","عالیه 👍","2h"),
        Triple("Film Club","امشب Watch Party داریم","3h")
    )
    val rooms=listOf(
        Triple("The Last of Us","846 آنلاین • 12.8K عضو",""),
        Triple("Anime Club","1.2K آنلاین • 24K عضو",""),
        Triple("K-Drama Fans","620 آنلاین • 18K عضو",""),
        Triple("Horror Night","418 آنلاین • 9K عضو","")
    )
    val channels=listOf(
        Triple("CineVerse News","1.3M دنبال‌کننده",""),
        Triple("AnimeHub","842K دنبال‌کننده",""),
        Triple("K-Drama Land","520K دنبال‌کننده",""),
        Triple("Film News","430K دنبال‌کننده","")
    )

    Column(Modifier.fillMaxSize()) {
        BrandTopBar()
        Row(
            Modifier.fillMaxWidth().padding(horizontal=16.dp)
                .background(FqSurface, RoundedCornerShape(16.dp)).padding(4.dp)
        ) {
            tabs.forEachIndexed { index,label ->
                FilterChip(
                    selected=tab==index,
                    onClick={tab=index},
                    label={Text(label)},
                    modifier=Modifier.weight(1f),
                    colors=FilterChipDefaults.filterChipColors(
                        selectedContainerColor=FqGold,
                        selectedLabelColor=Color.Black,
                        containerColor=Color.Transparent
                    )
                )
            }
        }
        if(tab==1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)
                    .clip(RoundedCornerShape(16.dp)).background(
                        Brush.horizontalGradient(listOf(Color(0xFF3F2C08),FqSurface))
                    ).clickable { onWatchParty() }.padding(14.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LiveTv,null,tint=FqGold,modifier=Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Watch Party زنده",fontSize=14.sp)
                    Text("همین حالا وارد اتاق تماشا شو",color=FqMuted,fontSize=9.sp)
                }
                Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
            }
        }
        val list=when(tab){0->direct;1->rooms;else->channels}
        LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
            items(list) { row ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChat(row.first) }
                        .padding(horizontal=14.dp,vertical=6.dp)
                        .clip(RoundedCornerShape(18.dp)).background(FqSurface).padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(FqGold,Color(0xFFFF5D5D)))),
                        contentAlignment=Alignment.Center
                    ) {
                        Text(row.first.take(1),color=Color.Black,fontSize=18.sp)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.first,fontSize=13.sp)
                        Text(row.second,color=FqMuted,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                    }
                    if(row.third.isNotBlank()) Text(row.third,color=FqMuted,fontSize=8.sp)
                    if(tab==0) {
                        Spacer(Modifier.width(7.dp))
                        Surface(color=FqGold,shape=CircleShape) {
                            Text("1",color=Color.Black,fontSize=8.sp,modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatRoomScreen(
    title: String,
    media: MediaItem?,
    repository: TmdbRepository,
    onBack: () -> Unit,
    onMedia: ((MediaItem)->Unit)? = null
) {
    var text by remember { mutableStateOf("") }
    val messages=remember {
        mutableStateListOf(
            ChatMessage(1,"Nima","این قسمت رو دیدین؟ واقعاً پایانش عجیب بود 😳"),
            ChatMessage(2,"Sara","آره ولی درباره پایان چیزی نگید برای بقیه 😅"),
            ChatMessage(3,"Ali","من یه تئوری دارم که احتمالاً همه‌چی رو عوض می‌کنه.",spoiler=true),
            ChatMessage(4,"Reza","اسپویلر رو با تگ بفرستید لطفاً 🔥")
        )
    }
    var revealSpoiler by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(FqSurface).padding(horizontal=8.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
            Box(Modifier.size(42.dp).clip(CircleShape).background(FqGold),contentAlignment=Alignment.Center) {
                Icon(Icons.Default.Groups,null,tint=Color.Black)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title,fontSize=15.sp)
                Text("846 آنلاین • 12.8K عضو",color=FqMuted,fontSize=8.sp)
            }
            IconButton(onClick={}) { Icon(Icons.Default.Search,null) }
            IconButton(onClick={}) { Icon(Icons.Default.MoreVert,null) }
        }
        if(media!=null) {
            Row(
                Modifier.fillMaxWidth().clickable { onMedia?.invoke(media) }
                    .background(FqSurface2).padding(10.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                RemoteImage(repository.poster(media.posterPath),Modifier.size(42.dp,58.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(media.title,fontSize=11.sp)
                    Text("روم رسمی این عنوان",color=FqMuted,fontSize=8.sp)
                }
                Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
            }
        }
        LazyColumn(
            modifier=Modifier.weight(1f),
            contentPadding=PaddingValues(horizontal=12.dp,vertical=14.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)
        ) {
            items(messages,key={it.id}) { msg ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=if(msg.mine)Arrangement.Start else Arrangement.End
                ) {
                    Surface(
                        color=if(msg.mine)Color(0xFF49370A) else FqSurface,
                        shape=RoundedCornerShape(16.dp),
                        modifier=Modifier.widthIn(max=300.dp)
                    ) {
                        Column(Modifier.padding(11.dp)) {
                            if(!msg.mine) Text(msg.author,color=FqGold,fontSize=9.sp)
                            if(msg.spoiler && !revealSpoiler) {
                                Surface(
                                    color=FqDanger.copy(alpha=.13f),
                                    shape=RoundedCornerShape(10.dp),
                                    modifier=Modifier.clickable { revealSpoiler=true }.padding(top=4.dp)
                                ) {
                                    Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning,null,tint=FqDanger,modifier=Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("اسپویلر • برای نمایش لمس کنید",fontSize=9.sp)
                                    }
                                }
                            } else {
                                Text(msg.text,fontSize=11.sp,lineHeight=18.sp,modifier=Modifier.padding(top=3.dp))
                            }
                            Row(Modifier.padding(top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(msg.time,color=FqMuted,fontSize=7.sp)
                                Spacer(Modifier.width(7.dp))
                                Text("❤️  "+((msg.id*3)%17+1),fontSize=8.sp,color=FqMuted)
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
            IconButton(onClick={}) { Icon(Icons.Default.AddCircleOutline,null,tint=FqGold) }
            OutlinedTextField(
                value=text,
                onValueChange={text=it},
                placeholder={Text("پیام بنویس...")},
                shape=RoundedCornerShape(22.dp),
                modifier=Modifier.weight(1f),
                maxLines=4
            )
            IconButton(
                onClick={
                    val clean=text.trim()
                    if(clean.isNotEmpty()) {
                        messages.add(ChatMessage(System.currentTimeMillis(),"شما",clean,mine=true))
                        text=""
                    }
                }
            ) {
                Icon(Icons.Default.Send,null,tint=if(text.isBlank())FqMuted else FqGold)
            }
        }
    }
}

@Composable
fun CreatorProfileScreen(
    creator: Creator,
    repository: TmdbRepository,
    store: LocalStore,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    var followed by remember(creator.handle) { mutableStateOf(store.contains("follows",creator.handle)) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        media=runCatching { repository.trending() }.getOrDefault(emptyList())
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={}) { Icon(Icons.Default.MoreVert,null) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal=18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(FqGold,Color(0xFFFF5B6E)))),
                    contentAlignment=Alignment.Center
                ) {
                    Text(creator.name.take(1),fontSize=34.sp,color=Color.Black)
                }
                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(top=10.dp)) {
                    Text(creator.name,fontSize=22.sp)
                    if(creator.verified) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(18.dp))
                    }
                }
                Text(creator.handle,color=FqMuted,fontSize=10.sp)
                Text(creator.bio,fontSize=11.sp,modifier=Modifier.padding(top=9.dp))
                Row(
                    Modifier.fillMaxWidth().padding(top=16.dp),
                    horizontalArrangement=Arrangement.SpaceEvenly
                ) {
                    CreatorMetric("347","پست")
                    CreatorMetric(creator.followers,"دنبال‌کننده")
                    CreatorMetric("421","دنبال‌شده")
                }
                Row(Modifier.fillMaxWidth().padding(top=15.dp)) {
                    Button(
                        onClick={followed=store.toggle("follows",creator.handle)},
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        modifier=Modifier.weight(1f),
                        shape=RoundedCornerShape(12.dp)
                    ) { Text(if(followed)"دنبال می‌کنی" else "دنبال کردن") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick={},
                        modifier=Modifier.weight(1f),
                        shape=RoundedCornerShape(12.dp)
                    ) { Text("پیام") }
                }
            }
        }
        item {
            TabRow(selectedTabIndex=tab,containerColor=FqBg,contentColor=FqGold,modifier=Modifier.padding(top=18.dp)) {
                listOf("Reels","پست‌ها","Review","لیست‌ها").forEachIndexed { i,label ->
                    Tab(selected=tab==i,onClick={tab=i},text={Text(label)})
                }
            }
        }
        item {
            if(media.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(260.dp),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator(color=FqGold)
                }
            } else {
                LazyVerticalGrid(
                    columns=GridCells.Fixed(3),
                    contentPadding=PaddingValues(4.dp),
                    horizontalArrangement=Arrangement.spacedBy(3.dp),
                    verticalArrangement=Arrangement.spacedBy(3.dp),
                    modifier=Modifier.height(650.dp)
                ) {
                    items(media.take(18),key={it.key}) { m ->
                        Box(
                            Modifier.aspectRatio(.75f).clickable { onMedia(m) }
                        ) {
                            RemoteImage(
                                repository.poster(m.posterPath),
                                Modifier.fillMaxSize(),
                                ContentScale.Crop
                            )
                            if(tab==0) {
                                Icon(Icons.Default.PlayArrow,null,tint=Color.White,modifier=Modifier.align(Alignment.Center).size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorMetric(value:String,label:String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(value,fontSize=17.sp)
        Text(label,color=FqMuted,fontSize=9.sp)
    }
}

@Composable
fun ProfileScreen(
    repository: TmdbRepository,
    store: LocalStore,
    onCreator: () -> Unit,
    onWatchParty: () -> Unit,
    onMessages: () -> Unit,
    onMedia: (MediaItem)->Unit
) {
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        media=runCatching { repository.trending() }.getOrDefault(emptyList())
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { BrandTopBar() }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal=18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(
                        Brush.linearGradient(listOf(FqGold,Color(0xFFFF6745)))
                    ),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(Icons.Default.Person,null,tint=Color.Black,modifier=Modifier.size(54.dp))
                }
                Text("Armin",fontSize=23.sp,modifier=Modifier.padding(top=10.dp))
                Text("@armin93",color=FqMuted,fontSize=10.sp)
                Text("عاشق سینما، سریال و داستان‌های خوب 🍿",fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(top=16.dp),
                    horizontalArrangement=Arrangement.SpaceEvenly
                ) {
                    CreatorMetric("347","فیلم")
                    CreatorMetric("82","سریال")
                    CreatorMetric("4.8K","دنبال‌کننده")
                }
                Surface(
                    color=Color(0xFF3A2C08),
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=16.dp)
                ) {
                    Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.EmojiEvents,null,tint=FqGold)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Level 18 • Film Buff",fontSize=12.sp)
                            LinearProgressIndicator(
                                progress={.74f},
                                color=FqGold,
                                trackColor=Color.White.copy(alpha=.15f),
                                modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(4.dp)
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("مرکز شما")
            LazyRow(
                contentPadding=PaddingValues(horizontal=16.dp),
                horizontalArrangement=Arrangement.spacedBy(9.dp)
            ) {
                item { ProfileQuick(Icons.Default.Campaign,"کانال من",onCreator) }
                item { ProfileQuick(Icons.Default.Groups,"Watch Party",onWatchParty) }
                item { ProfileQuick(Icons.Default.Chat,"پیام‌ها",onMessages) }
                item { ProfileQuick(Icons.Default.Bookmark,"لیست من",{}) }
                item { ProfileQuick(Icons.Default.Download,"دانلودها",{}) }
            }
        }
        item { SectionHeader("فیلم‌های مورد علاقه","ذخیره‌شده‌ها و پیشنهادها") }
        item {
            if(media.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(170.dp),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator(color=FqGold)
                }
            } else {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp)
                ) {
                    items(media.take(10),key={it.key}) { m ->
                        PosterCard(m,repository,{onMedia(m)})
                    }
                }
            }
        }
        item { SectionHeader("تنظیمات و حساب") }
        items(
            listOf(
                Icons.Default.History to "تاریخچه تماشا",
                Icons.Default.HighQuality to "کیفیت پیش‌فرض",
                Icons.Default.Security to "حریم خصوصی و امنیت",
                Icons.Default.Notifications to "تنظیم اعلان‌ها",
                Icons.Default.Language to "زبان و زیرنویس",
                Icons.Default.Info to "درباره Filmiqoo"
            )
        ) { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=5.dp)
                    .clip(RoundedCornerShape(15.dp)).background(FqSurface).padding(14.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(row.first,null,tint=FqGold)
                Spacer(Modifier.width(12.dp))
                Text(row.second,Modifier.weight(1f),fontSize=11.sp)
                Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun ProfileQuick(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,onClick:()->Unit) {
    Column(
        Modifier.width(98.dp).clip(RoundedCornerShape(17.dp)).background(FqSurface)
            .clickable { onClick() }.padding(vertical=15.dp),
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Icon(icon,null,tint=FqGold,modifier=Modifier.size(28.dp))
        Text(label,fontSize=9.sp,modifier=Modifier.padding(top=8.dp))
    }
}

@Composable
fun WatchPartyScreen(
    media: MediaItem?,
    repository: TmdbRepository,
    onBack: () -> Unit
) {
    var playing by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    val chat=remember {
        mutableStateListOf(
            ChatMessage(1,"Armin","این صحنه رو ببین 😍"),
            ChatMessage(2,"Sara","من همیشه با این قسمت گریه می‌کنم"),
            ChatMessage(3,"Nima","عالیه 🔥")
        )
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(300.dp).background(Color.Black)) {
            RemoteImage(
                repository.backdrop(media?.backdropPath ?: media?.posterPath),
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.35f)))
            IconButton(onClick=onBack,modifier=Modifier.padding(10.dp).align(Alignment.TopStart)) {
                Icon(Icons.Default.Close,null)
            }
            Icon(
                if(playing)Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                null,
                modifier=Modifier.size(74.dp).align(Alignment.Center).clickable { playing=!playing }
            )
            Surface(
                color=Color.Black.copy(alpha=.58f),
                shape=RoundedCornerShape(12.dp),
                modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp)
            ) {
                Text(if(playing)"همگام‌سازی فعال • در حال پخش" else "برای همه متوقف شده",fontSize=10.sp,modifier=Modifier.padding(horizontal=10.dp,vertical=6.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Watch Party",fontSize=20.sp)
                Text((media?.title ?: "The Last of Us")+" • 137 نفر حاضر",color=FqMuted,fontSize=9.sp)
            }
            Button(
                onClick={playing=!playing},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                shape=RoundedCornerShape(12.dp)
            ) {
                Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null)
                Spacer(Modifier.width(5.dp))
                Text(if(playing)"Pause for all" else "Resume")
            }
        }
        HorizontalDivider(color=FqSurface2)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)
        ) {
            items(chat,key={it.id}) { msg ->
                Row(verticalAlignment=Alignment.Top) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(FqSurface2),contentAlignment=Alignment.Center) {
                        Text(msg.author.take(1),fontSize=11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(msg.author,color=FqGold,fontSize=9.sp)
                        Text(msg.text,fontSize=10.sp,modifier=Modifier.padding(top=2.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(FqSurface).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton({}) { Icon(Icons.Default.EmojiEmotions,null,tint=FqGold) }
            OutlinedTextField(
                value=message,
                onValueChange={message=it},
                placeholder={Text("پیام Watch Party...")},
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.weight(1f)
            )
            IconButton({
                val clean=message.trim()
                if(clean.isNotEmpty()) {
                    chat.add(ChatMessage(System.currentTimeMillis(),"شما",clean,mine=true))
                    message=""
                }
            }) { Icon(Icons.Default.Send,null,tint=FqGold) }
        }
    }
}

@Composable
fun CreateHubScreen(
    social: SocialRepository,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onBack:()->Unit
) {
    val scope=rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var composer by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }
    var channelSlug by remember { mutableStateOf("") }
    var channelBio by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(false) }
    var publishMessage by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedUri=uri
    }

    if(published) {
        AlertDialog(
            onDismissRequest={published=false},
            confirmButton={TextButton(onClick={published=false}){Text("باشه")}},
            title={Text("انجام شد")},
            text={Text(publishMessage)}
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack) { Icon(Icons.Default.Close,null) }
                Text("Filmiqoo Studio",fontSize=22.sp)
            }
        }
        item {
            Text("چه چیزی می‌خواهی بسازی؟",color=FqMuted,fontSize=11.sp,modifier=Modifier.padding(horizontal=18.dp,vertical=8.dp))
        }
        items(
            listOf(
                Triple(Icons.Default.AutoStories,"استوری","عکس، ویدیو، متن و Spoiler Shield"),
                Triple(Icons.Default.VideoLibrary,"Reel","ویدیوی کوتاه برای اکسپلور"),
                Triple(Icons.Default.PostAdd,"پست","متن و محتوای Community"),
                Triple(Icons.Default.RateReview,"Review فیلم/سریال","نقد و امتیاز"),
                Triple(Icons.Default.Poll,"نظرسنجی","از جامعه نظر بپرس"),
                Triple(Icons.Default.LiveTv,"لایو","پخش زنده برای دنبال‌کننده‌ها"),
                Triple(Icons.Default.Campaign,"ساخت کانال","رسانه و Community خودت")
            )
        ) { option ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=5.dp)
                    .clip(RoundedCornerShape(18.dp)).background(FqSurface)
                    .clickable {
                        composer=option.second
                        error=null
                        if(option.second=="Reel" || option.second=="استوری") {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                    }.padding(15.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(FqSurface2),contentAlignment=Alignment.Center) {
                    Icon(option.first,null,tint=FqGold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(option.second,fontSize=13.sp)
                    Text(option.third,color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(top=3.dp))
                }
                Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
            }
        }

        if(composer!=null) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("در حال ساخت: "+composer,fontSize=14.sp,color=FqGold)

                    selectedUri?.let { uri ->
                        AsyncImage(
                            model=uri,
                            contentDescription=null,
                            contentScale=ContentScale.Crop,
                            modifier=Modifier.fillMaxWidth().height(230.dp).padding(top=10.dp).clip(RoundedCornerShape(18.dp))
                        )
                    }

                    if(composer=="ساخت کانال") {
                        OutlinedTextField(
                            value=caption,
                            onValueChange={caption=it},
                            label={Text("نام کانال")},
                            singleLine=true,
                            shape=RoundedCornerShape(16.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                        )
                        OutlinedTextField(
                            value=channelSlug,
                            onValueChange={channelSlug=it.lowercase().replace(" ","")},
                            label={Text("آیدی کانال")},
                            prefix={Text("@")},
                            singleLine=true,
                            shape=RoundedCornerShape(16.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                        )
                        OutlinedTextField(
                            value=channelBio,
                            onValueChange={channelBio=it},
                            label={Text("معرفی کانال")},
                            minLines=2,
                            shape=RoundedCornerShape(16.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value=caption,
                            onValueChange={caption=it},
                            label={Text(if(composer=="Review فیلم/سریال")"متن نقد" else "کپشن")},
                            placeholder={Text("درباره محتوایت بنویس...")},
                            minLines=3,
                            shape=RoundedCornerShape(16.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                        )
                        Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick={},label={Text("#فیلمیکو")},leadingIcon={Icon(Icons.Default.Tag,null)})
                            AssistChip(onClick={},label={Text("تگ فیلم")},leadingIcon={Icon(Icons.Default.Movie,null)})
                            FilterChip(
                                selected=spoiler,
                                onClick={spoiler=!spoiler},
                                label={Text("اسپویلر")},
                                leadingIcon={Icon(Icons.Default.Warning,null)}
                            )
                        }
                    }

                    if((composer=="Reel" || composer=="استوری") && selectedUri!=null) {
                        Surface(
                            color=FqGold.copy(alpha=.08f),
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                        ) {
                            Text(
                                "انتخاب فایل فعال است. مرحله بعدی Media Pipeline فایل را مستقیم به Object Storage می‌فرستد، Transcode می‌کند و بعد Publish می‌شود.",
                                color=FqGoldSoft,fontSize=9.sp,lineHeight=16.sp,modifier=Modifier.padding(10.dp)
                            )
                        }
                    }

                    error?.let {
                        Text(it,color=FqDanger,fontSize=9.sp,modifier=Modifier.padding(top=8.dp))
                    }

                    Button(
                        enabled=!publishing && when(composer) {
                            "ساخت کانال" -> caption.length>=2 && channelSlug.length>=3
                            "لایو","Reel" -> false
                            else -> caption.isNotBlank()
                        },
                        onClick={
                            if(!loggedIn) {
                                onRequireAuth()
                            } else {
                                publishing=true
                                error=null
                                scope.launch {
                                    runCatching {
                                        when(composer) {
                                            "پست" -> {
                                                social.createPost(caption.trim(),"post",spoiler)
                                                "پست روی Community منتشر شد."
                                            }
                                            "Review فیلم/سریال" -> {
                                                social.createPost(caption.trim(),"review",spoiler)
                                                "Review روی Community منتشر شد."
                                            }
                                            "نظرسنجی" -> {
                                                social.createPost(caption.trim(),"poll",spoiler)
                                                "نظرسنجی منتشر شد؛ گزینه‌های Poll در مرحله Editor اضافه می‌شوند."
                                            }
                                            "استوری" -> {
                                                if(selectedUri!=null) {
                                                    "فایل استوری انتخاب شد؛ Publish رسانه بعد از اتصال Media Upload فعال می‌شود."
                                                } else {
                                                    social.createTextStory(caption.trim(),spoiler)
                                                    "استوری متنی برای ۲۴ ساعت منتشر شد."
                                                }
                                            }
                                            "ساخت کانال" -> {
                                                social.createChannel(caption.trim(),channelSlug.trim(),channelBio.trim())
                                                "کانال @"+channelSlug.trim()+" ساخته شد."
                                            }
                                            else -> "این نوع محتوا وارد Media Pipeline مرحله بعد می‌شود."
                                        }
                                    }.onSuccess { message ->
                                        publishMessage=message
                                        published=true
                                        if(composer!="استوری" || selectedUri==null) {
                                            caption=""
                                            spoiler=false
                                        }
                                    }.onFailure {
                                        error=it.message ?: "انتشار ناموفق بود"
                                    }
                                    publishing=false
                                }
                            }
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                    ) {
                        if(publishing) {
                            CircularProgressIndicator(color=Color.Black,strokeWidth=2.dp,modifier=Modifier.size(17.dp))
                        } else {
                            Icon(Icons.Default.Publish,null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when(composer) {
                                "Reel" -> "Media Pipeline در حال ساخت"
                                "لایو" -> "Live Engine در مرحله بعد"
                                else -> "انتشار"
                            }
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

