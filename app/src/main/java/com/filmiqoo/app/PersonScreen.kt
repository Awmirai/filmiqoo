package com.filmiqoo.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class PersonTab { OVERVIEW, MOVIES, SERIES }

@Composable
fun PersonScreen(
    personId:Int,
    initialName:String,
    repository:TmdbRepository,
    onBack:()->Unit,
    onMedia:(MediaItem)->Unit
) {
    var loading by remember(personId) { mutableStateOf(true) }
    var detail by remember(personId) { mutableStateOf<PersonDetail?>(null) }
    var error by remember(personId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(PersonTab.OVERVIEW) }
    var bioExpanded by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(personId,retry) {
        loading=true
        error=null
        runCatching { repository.person(personId) }
            .onSuccess { detail=it }
            .onFailure { error=it.message ?: "دریافت اطلاعات شخص ناموفق بود" }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        val person=detail
        if(person==null) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(
                        if(error==null)Icons.Default.Person else Icons.Default.ErrorOutline,
                        null,
                        tint=if(error==null)FqGold else FqDanger,
                        modifier=Modifier.size(50.dp)
                    )
                    Text(
                        error ?: "در حال دریافت "+initialName+"...",
                        color=FqMuted,
                        fontSize=10.sp,
                        modifier=Modifier.padding(top=10.dp)
                    )
                    if(error!=null) {
                        TextButton(onClick={retry++}) { Text("تلاش دوباره") }
                    }
                }
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding=PaddingValues(bottom=28.dp)
        ) {
            item {
                PersonHero(person,repository,onBack)
            }

            item {
                PersonFacts(person)
            }

            if(person.biography.isNotBlank()) {
                item {
                    Surface(
                        color=FqSurface,
                        shape=RoundedCornerShape(20.dp),
                        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp)
                    ) {
                        Column(Modifier.padding(15.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.MenuBook,null,tint=FqGold)
                                Spacer(Modifier.width(7.dp))
                                Text("زندگی‌نامه",fontSize=14.sp,fontWeight=FontWeight.Bold)
                            }
                            Text(
                                person.biography,
                                fontSize=9.sp,
                                lineHeight=16.sp,
                                color=Color.White.copy(alpha=.83f),
                                maxLines=if(bioExpanded)Int.MAX_VALUE else 7,
                                overflow=TextOverflow.Ellipsis,
                                modifier=Modifier.padding(top=9.dp)
                            )
                            if(person.biography.length>420) {
                                TextButton(
                                    onClick={bioExpanded=!bioExpanded},
                                    contentPadding=PaddingValues(0.dp)
                                ) {
                                    Text(if(bioExpanded)"کمتر" else "ادامه",fontSize=8.sp)
                                }
                            }
                        }
                    }
                }
            }

            if(person.images.isNotEmpty()) {
                item {
                    PersonSectionTitle(
                        icon=Icons.Default.PhotoLibrary,
                        title="گالری",
                        subtitle=person.images.size.toString()+" عکس"
                    )
                }
                item {
                    LazyRow(
                        contentPadding=PaddingValues(horizontal=14.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(person.images.take(12)) { path ->
                            RemoteImage(
                                repository.profile(path),
                                Modifier.width(145.dp).height(205.dp)
                                    .clip(RoundedCornerShape(17.dp)),
                                ContentScale.Crop
                            )
                        }
                    }
                }
            }

            item {
                PersonTabBar(tab){tab=it}
            }

            when(tab) {
                PersonTab.OVERVIEW -> {
                    val known=person.credits
                        .sortedByDescending { it.media.popularity + it.media.vote*8 }
                        .distinctBy { it.media.key }
                        .take(14)

                    if(known.isNotEmpty()) {
                        item {
                            PersonSectionTitle(
                                icon=Icons.Default.AutoAwesome,
                                title="Known For",
                                subtitle="شناخته‌شده‌ترین آثار"
                            )
                        }
                        item {
                            PersonCreditsRow(known,repository,onMedia)
                        }
                    }

                    val recent=person.credits
                        .distinctBy { it.media.key }
                        .sortedByDescending { it.media.date }
                        .take(18)
                    if(recent.isNotEmpty()) {
                        item {
                            PersonSectionTitle(
                                icon=Icons.Default.History,
                                title="آثار اخیر",
                                subtitle="Filmography به ترتیب انتشار"
                            )
                        }
                        items(recent,key={it.media.key}) { credit ->
                            PersonCreditRow(credit,repository,onMedia)
                        }
                    }
                }

                PersonTab.MOVIES -> {
                    val movies=person.movieCredits
                        .distinctBy { it.media.key }
                        .sortedByDescending { it.media.date }
                    item {
                        PersonFilmographyHeader(
                            count=movies.size,
                            label="فیلم"
                        )
                    }
                    if(movies.isEmpty()) {
                        item { PersonEmpty("فیلمی در اطلاعات TMDB ثبت نشده.") }
                    } else {
                        items(movies,key={it.media.key}) { credit ->
                            PersonCreditRow(credit,repository,onMedia)
                        }
                    }
                }

                PersonTab.SERIES -> {
                    val tv=person.tvCredits
                        .distinctBy { it.media.key }
                        .sortedByDescending { it.media.date }
                    item {
                        PersonFilmographyHeader(
                            count=tv.size,
                            label="سریال"
                        )
                    }
                    if(tv.isEmpty()) {
                        item { PersonEmpty("سریالی در اطلاعات TMDB ثبت نشده.") }
                    } else {
                        items(tv,key={it.media.key}) { credit ->
                            PersonCreditRow(credit,repository,onMedia)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonHero(
    person:PersonDetail,
    repository:TmdbRepository,
    onBack:()->Unit
) {
    Box(
        Modifier.fillMaxWidth().height(390.dp).background(
            Brush.verticalGradient(
                listOf(Color(0xFF1A2234),Color(0xFF17120A),FqBg)
            )
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(
                onClick=onBack,
                modifier=Modifier.background(Color.Black.copy(alpha=.28f),CircleShape)
            ) { Icon(Icons.Default.ArrowBack,null) }
            Spacer(Modifier.weight(1f))
            Surface(
                color=Color.Black.copy(alpha=.35f),
                shape=RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(horizontal=9.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.MovieCreation,null,tint=FqGold,modifier=Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        person.credits.distinctBy { it.media.key }.size.toString()+" اثر",
                        fontSize=7.sp
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=18.dp,bottom=22.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(174.dp).background(FqGold,CircleShape).padding(4.dp)
            ) {
                RemoteImage(
                    repository.profile(person.profilePath),
                    Modifier.fillMaxSize().clip(CircleShape),
                    ContentScale.Crop
                )
            }
            Text(
                person.name,
                fontSize=27.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=12.dp)
            )
            if(person.knownForDepartment.isNotBlank()) {
                Text(
                    departmentLabel(person.knownForDepartment),
                    color=FqGold,
                    fontSize=10.sp,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(top=3.dp)
                )
            }
            if(person.alsoKnownAs.isNotEmpty()) {
                Text(
                    person.alsoKnownAs.take(2).joinToString(" • "),
                    color=FqMuted,
                    fontSize=7.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=5.dp)
                )
            }
        }
    }
}

@Composable
private fun PersonFacts(person:PersonDetail) {
    val facts=buildList {
        if(person.birthday.isNotBlank()) add(Icons.Default.Cake to person.birthday)
        if(person.ageOrYears.isNotBlank()) {
            add(Icons.Default.Timeline to (person.ageOrYears+" سال"))
        }
        if(person.placeOfBirth.isNotBlank()) add(Icons.Default.Place to person.placeOfBirth)
        if(person.deathday!=null) add(Icons.Default.EventBusy to person.deathday)
    }

    if(facts.isEmpty()) return

    LazyRow(
        contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp),
        horizontalArrangement=Arrangement.spacedBy(7.dp)
    ) {
        items(facts) { fact ->
            Surface(color=FqSurface,shape=RoundedCornerShape(13.dp)) {
                Row(
                    Modifier.padding(horizontal=10.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(fact.first,null,tint=FqGold,modifier=Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(fact.second,fontSize=7.sp)
                }
            }
        }
    }
}

@Composable
private fun PersonTabBar(
    selected:PersonTab,
    onSelected:(PersonTab)->Unit
) {
    TabRow(
        selectedTabIndex=selected.ordinal,
        containerColor=FqBg,
        contentColor=FqGold,
        modifier=Modifier.padding(top=14.dp)
    ) {
        listOf(
            PersonTab.OVERVIEW to "نمای کلی",
            PersonTab.MOVIES to "فیلم‌ها",
            PersonTab.SERIES to "سریال‌ها"
        ).forEach { item ->
            Tab(
                selected=selected==item.first,
                onClick={onSelected(item.first)},
                text={Text(item.second,fontSize=9.sp)}
            )
        }
    }
}

@Composable
private fun PersonCreditsRow(
    credits:List<PersonCredit>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=14.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(credits,key={it.media.key}) { credit ->
            Column(
                Modifier.width(132.dp).clickable { onMedia(credit.media) }
            ) {
                RemoteImage(
                    repository.poster(credit.media.posterPath),
                    Modifier.fillMaxWidth().height(194.dp).clip(RoundedCornerShape(17.dp)),
                    ContentScale.Crop
                )
                Text(
                    credit.media.title,
                    fontSize=8.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    credit.role.ifBlank {
                        if(credit.media.type==MediaType.MOVIE)"فیلم" else "سریال"
                    },
                    color=FqMuted,
                    fontSize=6.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PersonCreditRow(
    credit:PersonCredit,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
            .clickable { onMedia(credit.media) }
    ) {
        Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
            RemoteImage(
                repository.poster(credit.media.posterPath),
                Modifier.width(58.dp).height(84.dp).clip(RoundedCornerShape(11.dp)),
                ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    credit.media.title,
                    fontSize=10.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    listOf(
                        credit.media.year,
                        if(credit.media.type==MediaType.MOVIE)"فیلم" else "سریال",
                        credit.role
                    ).filter(String::isNotBlank).joinToString(" • "),
                    color=FqMuted,
                    fontSize=7.sp,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=3.dp)
                )
                if(credit.media.vote>0) {
                    Text(
                        "★ "+String.format(java.util.Locale.US,"%.1f",credit.media.vote),
                        color=FqGold,
                        fontSize=7.sp,
                        modifier=Modifier.padding(top=4.dp)
                    )
                }
            }
            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
        }
    }
}

@Composable
private fun PersonSectionTitle(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    subtitle:String
) {
    Row(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=22.dp,bottom=9.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Icon(icon,null,tint=FqGold)
        Spacer(Modifier.width(7.dp))
        Column {
            Text(title,fontSize=15.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=FqMuted,fontSize=7.sp)
        }
    }
}

@Composable
private fun PersonFilmographyHeader(count:Int,label:String) {
    PersonSectionTitle(
        icon=if(label=="فیلم")Icons.Default.Movie else Icons.Default.Tv,
        title="Filmography",
        subtitle=count.toString()+" "+label
    )
}

@Composable
private fun PersonEmpty(text:String) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Text(text,color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(16.dp))
    }
}

private fun departmentLabel(value:String)=when(value.lowercase()) {
    "acting" -> "بازیگر"
    "directing" -> "کارگردان"
    "writing" -> "نویسنده"
    "production" -> "تهیه‌کننده"
    "sound" -> "صدا و موسیقی"
    else -> value
}
