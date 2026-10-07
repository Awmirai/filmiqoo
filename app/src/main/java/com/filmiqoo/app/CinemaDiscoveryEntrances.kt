package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CinemaDiscoveryEntrances(onMovies:()->Unit,onSeries:()->Unit,modifier:Modifier=Modifier){
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        CinemaDiscoveryEntrance("فیلمی برای امشب","از سینمای ایران تا جهان؛ ژانرها و انتخاب‌های برتر",Icons.Outlined.Movie,onMovies,"home-open-movies")
        CinemaDiscoveryEntrance("داستانی برای دنبال‌کردن","سریال‌ها، فصل‌ها و قسمت‌های تازه",Icons.Outlined.Tv,onSeries,"home-open-series")
    }
}

@Composable
private fun CinemaDiscoveryEntrance(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit,tag:String){
    Surface(onClick=onClick,color=CinemaSurface,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().testTag(tag)){
        Row(Modifier.background(Brush.horizontalGradient(listOf(CinemaAccent.copy(alpha=.09f),CinemaSurface))).padding(18.dp),
            horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Icon(icon,null,tint=CinemaAccent,modifier=Modifier.padding(top=4.dp).size(28.dp))
            Column(Modifier.weight(1f)){
                Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text(subtitle,color=CinemaSoft,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=5.dp))
            }
            Icon(Icons.Outlined.ChevronLeft,null,modifier=Modifier.padding(top=4.dp))
        }
    }
}
