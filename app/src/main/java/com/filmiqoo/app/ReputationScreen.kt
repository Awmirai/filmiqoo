package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReputationScreen(
    userId:String,
    backend:BackendRepository,
    onBack:()->Unit
) {
    val repo=remember { ReputationRepository(backend) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reputation by remember { mutableStateOf<UserReputation?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(userId,refresh) {
        loading=true
        error=null
        runCatching { repo.load(userId) }
            .onSuccess { reputation=it }
            .onFailure { error=it.message ?: "خطا در دریافت Reputation" }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("Reputation و Badgeها",fontSize=21.sp,fontWeight=FontWeight.Black)
                Text("بر اساس فعالیت واقعی و عمومی در Filmiqoo",color=FqMuted,fontSize=11.sp)
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

        reputation?.let { rep ->
            LazyColumn(
                contentPadding=PaddingValues(bottom=28.dp)
            ) {
                item {
                    ReputationHero(rep)
                }

                item {
                    PremiumSectionHeader(
                        title="اثرگذاری Community",
                        subtitle="آمار عمومی؛ Watch history خصوصی در این امتیاز استفاده نمی‌شود",
                        icon=Icons.Default.Insights
                    )
                }

                item {
                    ReputationStatsGrid(rep.stats)
                }

                val earned=rep.badges.filter { it.earned }
                val progress=rep.badges.filterNot { it.earned }

                item {
                    PremiumSectionHeader(
                        title="Badgeهای دریافت‌شده",
                        subtitle=earned.size.toString()+" Badge",
                        icon=Icons.Default.MilitaryTech
                    )
                }

                if(earned.isEmpty()) {
                    item {
                        Surface(
                            color=FqSurface,
                            shape=RoundedCornerShape(18.dp),
                            modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
                        ) {
                            Text(
                                "هنوز Badge دریافت نشده؛ با Review، Collection و مشارکت عمومی قابل دریافت‌اند.",
                                color=FqMuted,
                                fontSize=11.sp,
                                lineHeight=14.sp,
                                modifier=Modifier.padding(14.dp)
                            )
                        }
                    }
                } else {
                    items(earned,key={it.id}) { badge ->
                        ReputationBadgeCard(badge)
                    }
                }

                if(progress.isNotEmpty()) {
                    item {
                        PremiumSectionHeader(
                            title="در مسیر دریافت",
                            subtitle="پیشرفت با شرط شفاف",
                            icon=Icons.Default.Timeline
                        )
                    }
                    items(progress,key={it.id}) { badge ->
                        ReputationBadgeCard(badge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReputationHero(rep:UserReputation) {
    Box(
        Modifier.fillMaxWidth().background(
            Brush.linearGradient(
                listOf(Color(0xFF172236),Color(0xFF3B2C08),FqBg)
            )
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=22.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier.size(62.dp).background(
                        FqGold.copy(alpha=.13f),
                        RoundedCornerShape(18.dp)
                    ),
                    contentAlignment=Alignment.Center
                ) {
                    Text(
                        rep.level.toString(),
                        color=FqGold,
                        fontSize=25.sp,
                        fontWeight=FontWeight.Black
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(rep.displayName,fontSize=18.sp,fontWeight=FontWeight.Black)
                        if(rep.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Verified,
                                null,
                                tint=Color(0xFF4AB7FF),
                                modifier=Modifier.size(17.dp)
                            )
                        }
                    }
                    Text("@"+rep.username,color=FqMuted,fontSize=11.sp)
                    Text(
                        "Level "+rep.level+" • "+compactReputation(rep.score)+" XP",
                        color=FqGold,
                        fontSize=11.sp,
                        fontWeight=FontWeight.Bold,
                        modifier=Modifier.padding(top=5.dp)
                    )
                }
                Icon(Icons.Default.MilitaryTech,null,tint=FqGold,modifier=Modifier.size(34.dp))
            }

            if(rep.level<50 && rep.nextLevelScore>rep.score) {
                val previous=((rep.level-1).coerceAtLeast(0)*250L)
                val span=(rep.nextLevelScore-previous).coerceAtLeast(1L)
                val progress=((rep.score-previous).toFloat()/span.toFloat()).coerceIn(0f,1f)

                LinearProgressIndicator(
                    progress={progress},
                    color=FqGold,
                    trackColor=Color.White.copy(alpha=.12f),
                    modifier=Modifier.fillMaxWidth().padding(top=17.dp).height(6.dp)
                )
                Text(
                    (rep.nextLevelScore-rep.score).coerceAtLeast(0).toString()+" XP تا Level بعد",
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=5.dp)
                )
            }
        }
    }
}

@Composable
private fun ReputationStatsGrid(stats:ReputationStats) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal=14.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ReputationStat("Review",stats.reviews,Icons.Default.RateReview,Modifier.weight(1f))
            ReputationStat("Review Like",stats.reviewLikes,Icons.Default.ThumbUp,Modifier.weight(1f))
            ReputationStat("Post + Reel",stats.posts+stats.reels,Icons.Default.DynamicFeed,Modifier.weight(1f))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ReputationStat("Engagement",stats.engagement,Icons.Default.Favorite,Modifier.weight(1f))
            ReputationStat("Collection",stats.publicCollections,Icons.Default.CollectionsBookmark,Modifier.weight(1f))
            ReputationStat("Follower لیست",stats.collectionFollowers,Icons.Default.Groups,Modifier.weight(1f))
        }

        if(stats.topGenre.isNotBlank()) {
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(17.dp),
                modifier=Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalMovies,null,tint=FqGold)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ژانر پرتکرار در Reviewها",fontSize=11.sp,color=FqMuted)
                        Text(
                            stats.topGenre,
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.padding(top=2.dp)
                        )
                    }
                    Text(
                        stats.topGenreReviews.toString()+" Review",
                        color=FqGold,
                        fontSize=11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ReputationStat(
    label:String,
    value:Long,
    icon:ImageVector,
    modifier:Modifier=Modifier
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(17.dp),
        modifier=modifier
    ) {
        Column(
            Modifier.padding(horizontal=10.dp,vertical=12.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(19.dp))
            Text(
                compactReputation(value),
                fontSize=13.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=5.dp)
            )
            Text(label,color=FqMuted,fontSize=6.sp)
        }
    }
}

@Composable
private fun ReputationBadgeCard(badge:ReputationBadge) {
    Surface(
        color=if(badge.earned)FqGold.copy(alpha=.08f) else FqSurface,
        shape=RoundedCornerShape(19.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
    ) {
        Row(
            Modifier.padding(13.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).background(
                    if(badge.earned)FqGold.copy(alpha=.15f) else FqSurface2,
                    RoundedCornerShape(14.dp)
                ),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    reputationBadgeIcon(badge.icon),
                    null,
                    tint=if(badge.earned)FqGold else FqMuted
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        badge.title,
                        fontSize=12.sp,
                        fontWeight=FontWeight.Bold
                    )
                    if(badge.earned) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            tint=FqGreen,
                            modifier=Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    badge.description,
                    color=FqMuted,
                    fontSize=11.sp,
                    lineHeight=13.sp,
                    modifier=Modifier.padding(top=3.dp)
                )

                if(!badge.earned && badge.target>0) {
                    LinearProgressIndicator(
                        progress={badge.progress},
                        color=FqGold,
                        trackColor=FqSurface3,
                        modifier=Modifier.fillMaxWidth().padding(top=7.dp).height(4.dp)
                    )
                    Text(
                        badge.current.toString()+" / "+badge.target,
                        color=FqMuted,
                        fontSize=6.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
            }
        }
    }
}

private fun reputationBadgeIcon(value:String):ImageVector=when(value) {
    "rate_review" -> Icons.Default.RateReview
    "star" -> Icons.Default.Star
    "collections" -> Icons.Default.CollectionsBookmark
    "groups" -> Icons.Default.Groups
    "campaign" -> Icons.Default.Campaign
    "animation" -> Icons.Default.Animation
    "local_movies" -> Icons.Default.LocalMovies
    else -> Icons.Default.MilitaryTech
}

private fun compactReputation(value:Long):String=when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
