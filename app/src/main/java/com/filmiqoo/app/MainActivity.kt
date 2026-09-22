package com.filmiqoo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF080A0E)
private val Card = Color(0xFF11151B)
private val Card2 = Color(0xFF171C23)
private val Gold = Color(0xFFFFB900)
private val Muted = Color(0xFFA4A9B2)

data class Media(
    val title: String,
    val meta: String,
    val quality: String,
    val score: String,
    val colors: List<Color>
)

private val media = listOf(
    Media("تل‌ماسه: بخش دوم","2024 • علمی‌تخیلی","4K HDR","8.9", listOf(Color(0xFF8A5B2A),Color(0xFF21130A))),
    Media("آخرین بازمانده از ما","2023 • سریال","1080p","8.7", listOf(Color(0xFF40525C),Color(0xFF10161A))),
    Media("بازی مرکب","2025 • کره‌ای","1080p","8.0", listOf(Color(0xFF8B173A),Color(0xFF160A10))),
    Media("اوپنهایمر","2023 • درام","4K","8.6", listOf(Color(0xFF794520),Color(0xFF15100D))),
    Media("حمله به تایتان","انیمه • فصل 4","1080p","9.1", listOf(Color(0xFF596878),Color(0xFF151719))),
    Media("سقوط","ایرانی • فیلم","1080p","7.4", listOf(Color(0xFF37443E),Color(0xFF0E1110)))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Filmiqoo() }
    }
}

@Composable
fun Filmiqoo() {
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Media?>(null) }
    var player by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold, background = Bg, surface = Card,
            onPrimary = Color.Black, onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            when {
                player -> Player { player = false }
                selected != null -> Detail(selected!!, { selected = null }, { player = true })
                else -> Scaffold(
                    containerColor = Bg,
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF0C0F14)) {
                            val tabs = listOf(
                                Triple(Icons.Default.Home,"خانه",0),
                                Triple(Icons.Default.Search,"جستجو",1),
                                Triple(Icons.Default.Download,"دانلودها",2),
                                Triple(Icons.Default.Bookmark,"لیست من",3),
                                Triple(Icons.Default.Person,"پروفایل",4)
                            )
                            tabs.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t.third,
                                    onClick = { tab = t.third },
                                    icon = { Icon(t.first,null) },
                                    label = { Text(t.second,fontSize=10.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor=Gold, selectedTextColor=Gold,
                                        indicatorColor=Card2, unselectedIconColor=Muted, unselectedTextColor=Muted
                                    )
                                )
                            }
                        }
                    }
                ) { p ->
                    Box(Modifier.padding(p)) {
                        when(tab) {
                            0 -> Home { selected = it }
                            1 -> Search { selected = it }
                            2 -> Downloads()
                            3 -> Library { selected = it }
                            else -> Profile()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Brand() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.SpaceBetween
    ) {
        Text("FILMIQOO",color=Gold,fontSize=23.sp,fontWeight=FontWeight.Black)
        Row {
            IconButton({}) { Icon(Icons.Default.Cast,null) }
            IconButton({}) { Icon(Icons.Default.Notifications,null) }
        }
    }
}

@Composable
fun Home(open:(Media)->Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Brand() }
        item {
            Box(
                Modifier.padding(horizontal=16.dp).fillMaxWidth().height(340.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF4B5F6B),Color(0xFF202930),Bg)))
            ) {
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text("آخرین بازمانده از ما",fontSize=29.sp,fontWeight=FontWeight.Black)
                    Text("2023 • Drama • 2 Season • IMDb 8.7",color=Muted,modifier=Modifier.padding(top=7.dp))
                    Row(Modifier.padding(top=15.dp)) {
                        Button(
                            onClick={open(media[1])},
                            colors=ButtonDefaults.buttonColors(containerColor=Gold)
                        ) { Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(6.dp)); Text("تماشا") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick={}) { Icon(Icons.Default.Add,null); Spacer(Modifier.width(6.dp)); Text("لیست من") }
                    }
                }
            }
        }
        item { Header("ادامه تماشا") }
        item {
            LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items(media.take(3)) { m -> Continue(m,open) }
            }
        }
        item { Header("تازه اضافه شده") }
        item { Posters(media,open) }
        item { Header("سریال‌های محبوب") }
        item { Posters(media.reversed(),open) }
        item { Header("فیلم‌های ایرانی") }
        item { Posters(media.takeLast(4),open) }
        item { Header("K-Drama و آسیایی") }
        item { Posters(media.drop(1),open) }
        item { Header("انیمه") }
        item { Posters(media.reversed().take(5),open) }
        item { Spacer(Modifier.height(25.dp)) }
    }
}

@Composable
fun Header(text:String) {
    Row(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=26.dp,bottom=12.dp),
        horizontalArrangement=Arrangement.SpaceBetween,
        verticalAlignment=Alignment.CenterVertically
    ) {
        Text(text,fontSize=21.sp,fontWeight=FontWeight.Bold)
        Text("مشاهده همه",color=Muted,fontSize=12.sp)
    }
}

@Composable
fun Posters(list:List<Media>,open:(Media)->Unit) {
    LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        items(list) { m -> Poster(m,open) }
    }
}

@Composable
fun Poster(m:Media,open:(Media)->Unit) {
    Column(Modifier.width(145.dp).clickable{open(m)}) {
        Box(
            Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(m.colors))
        ) {
            Text(
                m.quality, color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold,
                modifier=Modifier.align(Alignment.TopStart).padding(9.dp)
                    .background(Color.Black.copy(alpha=.55f),RoundedCornerShape(8.dp))
                    .padding(horizontal=7.dp,vertical=4.dp)
            )
            Text(m.title,Modifier.align(Alignment.BottomStart).padding(12.dp),fontSize=17.sp,fontWeight=FontWeight.Bold)
        }
        Text(m.title,modifier=Modifier.padding(top=8.dp),maxLines=1,fontWeight=FontWeight.SemiBold)
        Text(m.meta+" • ⭐ "+m.score,color=Muted,fontSize=11.sp,maxLines=1)
    }
}

@Composable
fun Continue(m:Media,open:(Media)->Unit) {
    Column(Modifier.width(210.dp).clickable{open(m)}) {
        Box(
            Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(m.colors))
        ) {
            Icon(Icons.Default.PlayCircle,null,tint=Color.White,modifier=Modifier.align(Alignment.Center).size(48.dp))
            Text(m.title,Modifier.align(Alignment.BottomStart).padding(10.dp),fontWeight=FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress={.58f},modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(3.dp),
            color=Gold,trackColor=Card2
        )
    }
}

@Composable
fun Detail(m:Media,back:()->Unit,play:()->Unit) {
    LazyColumn {
        item {
            Box(Modifier.fillMaxWidth().height(435.dp).background(Brush.verticalGradient(m.colors+Bg))) {
                IconButton(back,Modifier.padding(12.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack,null) }
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text(m.title,fontSize=31.sp,fontWeight=FontWeight.Black)
                    Text(m.meta+" • IMDb "+m.score,color=Muted,modifier=Modifier.padding(top=8.dp))
                    Button(
                        onClick=play,modifier=Modifier.fillMaxWidth().padding(top=16.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=Gold)
                    ) { Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(8.dp)); Text("ورود و تماشا",fontWeight=FontWeight.Bold) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Action(Icons.Default.BookmarkBorder,"لیست من",Modifier.weight(1f))
                Action(Icons.Default.ThumbUp,"پسندیدم",Modifier.weight(1f))
                Action(Icons.Default.Download,"دانلود",Modifier.weight(1f))
            }
        }
        item {
            Text("درباره",Modifier.padding(horizontal=18.dp,vertical=10.dp),fontSize=21.sp,fontWeight=FontWeight.Bold)
            Text(
                "در نسخه نهایی، توضیحات فارسی، بازیگران، ژانر، کشور، مدت زمان، امتیاز و همه اطلاعات این عنوان به‌صورت خودکار دریافت و نمایش داده می‌شود.",
                Modifier.padding(horizontal=18.dp),color=Color(0xFFD4D7DD),lineHeight=25.sp
            )
        }
        item { Header("قسمت‌ها و کیفیت‌ها") }
        items((1..6).toList()) { e -> Episode(e,play) }
        item { Header("فیلم‌های مشابه") }
        item { Posters(media,{}) }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
fun Action(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,mod:Modifier) {
    Column(
        mod.height(82.dp).clip(RoundedCornerShape(16.dp)).background(Card2),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center
    ) {
        Icon(icon,null); Spacer(Modifier.height(6.dp)); Text(label,fontSize=12.sp)
    }
}

@Composable
fun Episode(n:Int,play:()->Unit) {
    Row(
        Modifier.padding(horizontal=16.dp,vertical=6.dp).fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(Card).clickable{play()}.padding(14.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(96.dp,62.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF39444F),Color(0xFF161A20)))),
            contentAlignment=Alignment.Center
        ) { Text("E"+n,fontWeight=FontWeight.Black) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("قسمت "+n,fontWeight=FontWeight.Bold)
            Text("1080p • HEVC • 1.4 GB",color=Muted,fontSize=11.sp)
        }
        IconButton({}) { Icon(Icons.Default.Download,null) }
        IconButton(play) { Icon(Icons.Default.PlayArrow,null,tint=Gold) }
    }
}

@Composable
fun Search(open:(Media)->Unit) {
    var q by remember { mutableStateOf("") }
    Column {
        Brand()
        OutlinedTextField(
            value=q,onValueChange={q=it},modifier=Modifier.fillMaxWidth().padding(16.dp),
            placeholder={Text("جستجوی فیلم، سریال، بازیگر و ...")},
            leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,shape=RoundedCornerShape(18.dp)
        )
        LazyColumn {
            item { Header(if(q.isBlank()) "پیشنهادها" else "نتایج جستجو") }
            items(media.filter{q.isBlank()||it.title.contains(q,true)}) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable{open(m)}.padding(horizontal=16.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(Modifier.size(78.dp,108.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(m.colors)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(m.title,fontSize=18.sp,fontWeight=FontWeight.Bold)
                        Text(m.meta,color=Muted)
                        Text("IMDb "+m.score+" • "+m.quality,color=Gold,fontSize=12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Downloads() {
    LazyColumn {
        item { Brand(); Header("دانلودها") }
        items(media.take(4)) { m ->
            Column(
                Modifier.padding(horizontal=16.dp,vertical=8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Card).padding(14.dp)
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp,78.dp).clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(m.colors)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title,fontWeight=FontWeight.Bold)
                        Text("1080p • 1.8 GB",color=Muted,fontSize=12.sp)
                    }
                    Icon(Icons.Default.PauseCircle,null,tint=Gold)
                }
                LinearProgressIndicator(progress={.64f},modifier=Modifier.fillMaxWidth().padding(top=10.dp),color=Gold,trackColor=Card2)
                Text("64% • دانلود آفلاین",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
            }
        }
    }
}

@Composable
fun Library(open:(Media)->Unit) {
    LazyColumn {
        item { Brand(); Header("لیست من") }
        items(media) { m ->
            Row(
                Modifier.fillMaxWidth().clickable{open(m)}.padding(horizontal=16.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Box(Modifier.size(72.dp,100.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(m.colors)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title,fontWeight=FontWeight.Bold)
                    Text(m.meta,color=Muted,fontSize=12.sp)
                }
                Icon(Icons.Default.Bookmark,null,tint=Gold)
            }
        }
    }
}

@Composable
fun Profile() {
    val rows = listOf(
        Icons.Default.History to "تاریخچه تماشا",
        Icons.Default.Download to "دانلودها",
        Icons.Default.HighQuality to "کیفیت پیش‌فرض",
        Icons.Default.Language to "زبان برنامه",
        Icons.Default.Security to "حریم خصوصی",
        Icons.Default.Info to "درباره Filmiqoo"
    )
    LazyColumn {
        item { Brand() }
        item {
            Column(Modifier.fillMaxWidth().padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(92.dp).clip(RoundedCornerShape(46.dp)).background(Gold),
                    contentAlignment=Alignment.Center
                ) { Icon(Icons.Default.Person,null,tint=Color.Black,modifier=Modifier.size(52.dp)) }
                Text("Filmiqoo Preview",fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                Text("نسخه نمایشی",color=Gold)
            }
        }
        items(rows) { r ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp).clip(RoundedCornerShape(14.dp))
                    .background(Card).padding(16.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(r.first,null,tint=Gold); Spacer(Modifier.width(14.dp)); Text(r.second,Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight,null,tint=Muted)
            }
        }
    }
}

@Composable
fun Player(back:()->Unit) {
    var q by remember { mutableStateOf("1080p") }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier.fillMaxWidth().height(310.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF26323A),Color.Black)))
        ) {
            IconButton(back,Modifier.padding(12.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack,null) }
            Icon(Icons.Default.PlayCircle,null,modifier=Modifier.align(Alignment.Center).size(78.dp))
            Text("24:36 / 58:14",Modifier.align(Alignment.BottomStart).padding(16.dp),color=Muted)
        }
        LazyColumn(Modifier.padding(18.dp)) {
            item {
                Text("آخرین بازمانده از ما",fontSize=22.sp,fontWeight=FontWeight.Bold)
                Text("فصل 2 • قسمت 3",color=Muted)
                Text("کیفیت",fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=20.dp))
            }
            items(listOf("4K Dolby Vision","1080p","720p","480p")) { x ->
                Row(
                    Modifier.fillMaxWidth().clickable{q=x}.padding(vertical=7.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    RadioButton(q==x,{q=x},colors=RadioButtonDefaults.colors(selectedColor=Gold))
                    Text(x)
                }
            }
            item {
                HorizontalDivider(color=Card2)
                Text("صدا",fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=15.dp))
                Text("● فارسی AAC 2.0",color=Gold,modifier=Modifier.padding(top=10.dp))
                Text("○ English E-AC3 5.1",color=Muted,modifier=Modifier.padding(top=8.dp))
                Text("زیرنویس",fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp))
                Text("● فارسی",color=Gold,modifier=Modifier.padding(top=10.dp))
                Text("○ English",color=Muted,modifier=Modifier.padding(top=8.dp))
            }
        }
    }
}
