package com.filmiqoo.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A real route into shared viewing; presence counts are supplied by the lobby, never invented here. */
@Composable
internal fun CinemaTogetherCard(onOpen:()->Unit,modifier:Modifier=Modifier,title:String?=null,enabled:Boolean=true) {
    Surface(modifier.testTag("together-entry"),shape=RoundedCornerShape(24.dp),
        color=CinemaSurface,border=BorderStroke(1.dp,Color(0xFF65CFC6).copy(alpha=.26f))) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF173338),CinemaSurface))).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(color=Color(0xFF65CFC6).copy(alpha=.14f),shape=RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Groups,null,tint=Color(0xFF9AE8DF),modifier=Modifier.padding(12.dp).size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("هم‌تماشا",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=CinemaPaper)
                    Text("یک فیلم؛ کنار هم",style=MaterialTheme.typography.bodySmall,color=Color(0xFF9AE8DF))
                }
            }
            Text(if(title==null)"با دوستانت یک اتاق بساز، هم‌زمان فیلم ببین و همان‌جا گفت‌وگو کن." else "«$title» را با دوستانت هم‌زمان تماشا کن؛ دعوت، گفت‌وگو و واکنش در یک اتاق.",
                style=MaterialTheme.typography.bodyMedium,color=CinemaSoft)
            CinemaAction(Icons.Outlined.PlayCircle,if(title==null)"ورود به هم‌تماشا"else "تماشای این اثر با دوستان",onOpen,Modifier.fillMaxWidth(),primary=true,enabled=enabled)
            if(!enabled)Text("ساخت اتاق به فایل قابل پخش نیاز دارد؛ پس از آماده‌شدن عنوان دوباره امتحان کن.",style=MaterialTheme.typography.bodySmall,color=CinemaSoft)
        }
    }
}
