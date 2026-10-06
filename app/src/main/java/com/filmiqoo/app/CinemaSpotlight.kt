package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
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
            Surface(shape=RoundedCornerShape(28.dp),color=CinemaSurface,border=BorderStroke(1.dp,CinemaLine),modifier=Modifier.fillMaxWidth()) {
                Column {
                    Box(Modifier.fillMaxWidth().height((window.screenHeightDp.dp*.32f).coerceIn(150.dp,290.dp))) {
                        CinemaImage(media.backdropPath?:media.posterPath,Modifier.fillMaxSize(),true)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaInk.copy(alpha=.12f),Color.Transparent,CinemaSurface))))
                        Text(if(window.fontScale>=1.6f) listOf("این روزها","فیلم","سریال")[category] else "انتخابی از ${if(category==1)"فیلم‌ها"else if(category==2)"سریال‌ها"else "عنوان‌های پرطرفدار"}",color=CinemaPaper,style=MaterialTheme.typography.labelMedium,
                            modifier=Modifier.align(Alignment.TopStart).padding(14.dp).background(CinemaInk.copy(alpha=.8f),RoundedCornerShape(10.dp)).padding(horizontal=10.dp,vertical=8.dp))
                        Text("${index+1} / ${choices.size}",color=CinemaPaper,style=MaterialTheme.typography.labelMedium,
                            modifier=Modifier.align(Alignment.TopEnd).padding(14.dp).background(CinemaInk.copy(alpha=.8f),RoundedCornerShape(8.dp)).padding(8.dp))
                    }
                    Column(Modifier.padding(horizontal=18.dp).padding(bottom=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(window.fontScale<1.6f)CinemaImage(media.posterPath,Modifier.width(68.dp).aspectRatio(2f/3f).clip(RoundedCornerShape(12.dp)))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(media.title,color=CinemaPaper,fontWeight=FontWeight.Bold,fontSize=25.sp,lineHeight=34.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(listOf(media.year,if(media.type==MediaType.TV)"سریال" else "فیلم").filter(String::isNotBlank).joinToString(" · "),color=CinemaSoft,fontSize=13.sp)
                                if(media.vote>0)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                                    Icon(Icons.Outlined.Star,null,tint=Color(0xFFEBC873),modifier=Modifier.size(16.dp))
                                    Text("${String.format(Locale.US,"%.1f",media.vote)} / 10 · TMDB",color=Color(0xFFEBC873),style=MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        CinemaAction(Icons.Outlined.ArrowBack,"کشف این عنوان",{onMedia(media)},Modifier.fillMaxWidth(),primary=true)
                    }
                }
            }
        } else Box(Modifier.padding(20.dp)) { CinemaNotice("هنوز عنوانی در این بخش نیست","از جست‌وجو برای پیدا کردن فیلم یا سریال استفاده کن.") }
        if(choices.size>1)Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(5.dp,Alignment.CenterHorizontally)) {
            repeat(choices.size){index->Box(Modifier.size(width=if(pager.currentPage==index)22.dp else 6.dp,height=6.dp).clip(RoundedCornerShape(5.dp)).background(if(pager.currentPage==index)CinemaAccent else CinemaLine))}
        }
    }
}
