package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun CinemaSpotlight(bundle:HomeBundle,onMedia:(MediaItem)->Unit,onStory:(MediaItem,Int)->Unit) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    val choices=(when(category){1->bundle.popularMovies;2->bundle.popularTv;else->bundle.trending}).distinctBy(::cinemaMediaKey).take(5)
    val pager=rememberPagerState { choices.size }
    LaunchedEffect(category){if(choices.isNotEmpty())pager.scrollToPage(0)}
    val window=LocalConfiguration.current
    Column(Modifier.testTag("home-spotlight")) {
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("این روزها","فیلم","سریال").forEachIndexed { i,label -> item { CinemaTag(label,category==i){category=i} } }
        }
        if(choices.isNotEmpty()) HorizontalPager(pager,key={cinemaMediaKey(choices[it])},contentPadding=PaddingValues(horizontal=20.dp),pageSpacing=12.dp) { index ->
            val media=choices[index]
            Surface(shape=RoundedCornerShape(20.dp),color=CinemaSurface,modifier=Modifier.fillMaxWidth()) {
                Column {
                    Box(Modifier.fillMaxWidth().height((window.screenHeightDp.dp*.25f).coerceIn(128.dp,230.dp))) {
                        CinemaImage(media.backdropPath?:media.posterPath,Modifier.fillMaxSize(),true)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,CinemaSurface))))
                        Text("${index+1} / ${choices.size}",color=CinemaPaper,style=MaterialTheme.typography.labelMedium,
                            modifier=Modifier.align(Alignment.TopEnd).padding(14.dp).background(CinemaInk.copy(alpha=.8f),RoundedCornerShape(8.dp)).padding(8.dp))
                    }
                    Column(Modifier.padding(horizontal=20.dp).padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(media.title,color=CinemaPaper,fontWeight=FontWeight.Bold,fontSize=24.sp,lineHeight=32.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(listOf(media.year,if(media.type==MediaType.TV)"سریال" else "فیلم",if(media.vote>0)"TMDB ${String.format(Locale.US,"%.1f",media.vote)}" else "").filter(String::isNotBlank).joinToString(" · "),color=CinemaSoft,fontSize=12.sp)
                        CinemaAction(Icons.Outlined.ArrowBack,"کشف این عنوان",{onMedia(media)},Modifier.fillMaxWidth(),primary=true)
                    }
                }
            }
        } else Box(Modifier.padding(20.dp)) { CinemaNotice("هنوز عنوانی در این بخش نیست","از جست‌وجو برای پیدا کردن فیلم یا سریال استفاده کن.") }
    }
}
