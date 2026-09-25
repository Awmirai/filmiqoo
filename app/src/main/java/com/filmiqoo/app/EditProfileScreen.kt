package com.filmiqoo.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun EditProfileScreen(
    backend: BackendRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()

    var profile by remember { mutableStateOf<AccountProfile?>(null) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf("") }
    var coverUrl by remember { mutableStateOf("") }
    var privateAccount by remember { mutableStateOf(false) }

    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val avatarPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if(uri!=null) avatarUri=uri }

    val coverPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if(uri!=null) coverUri=uri }

    BackHandler(enabled=!saving) { onBack() }

    LaunchedEffect(Unit) {
        runCatching { backend.me() }
            .onSuccess {
                profile=it
                username=it.username
                displayName=it.displayName
                bio=it.bio
                avatarUrl=it.avatarUrl
                coverUrl=it.coverUrl
                privateAccount=it.privateAccount
            }
            .onFailure { error=it.message }
        loading=false
    }

    if(loading) {
        LoadingPage("در حال دریافت پروفایل...")
        return
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack,enabled=!saving) {
                Icon(Icons.Default.ArrowBack,null)
            }
            Column(Modifier.weight(1f)) {
                Text("ویرایش پروفایل",fontSize=21.sp,fontWeight=FontWeight.Black)
                Text("هویت عمومی تو در Filmiqoo",color=FqMuted,fontSize=11.sp)
            }
            TextButton(
                enabled=!saving && username.trim().length>=3 && displayName.trim().length>=2,
                onClick={
                    saving=true
                    error=null
                    scope.launch {
                        runCatching {
                            var newAvatar=avatarUrl
                            var newCover=coverUrl

                            avatarUri?.let {
                                stage="در حال آپلود Avatar..."
                                newAvatar=backend.uploadMedia(context,it,"image").mediaUrl
                            }
                            coverUri?.let {
                                stage="در حال آپلود Cover..."
                                newCover=backend.uploadMedia(context,it,"image").mediaUrl
                            }

                            stage="در حال ذخیره پروفایل..."
                            backend.updateProfile(
                                username=username,
                                displayName=displayName,
                                bio=bio,
                                avatarUrl=newAvatar,
                                coverUrl=newCover,
                                privateAccount=privateAccount
                            )
                        }.onSuccess {
                            stage=""
                            saving=false
                            onSaved()
                        }.onFailure {
                            error=it.message ?: "ذخیره پروفایل ناموفق بود"
                            stage=""
                            saving=false
                        }
                    }
                }
            ) {
                Text("ذخیره",color=if(saving)FqMuted else FqGold)
            }
        }

        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding=PaddingValues(bottom=28.dp)
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    if(coverUri!=null) {
                        AsyncImage(
                            model=coverUri,
                            contentDescription=null,
                            contentScale=ContentScale.Crop,
                            modifier=Modifier.fillMaxWidth().height(190.dp)
                        )
                    } else if(coverUrl.isNotBlank()) {
                        RemoteImage(
                            coverUrl,
                            Modifier.fillMaxWidth().height(190.dp),
                            ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(190.dp).background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF1B2437),Color(0xFF47330A))
                                )
                            )
                        )
                    }

                    Box(
                        Modifier.fillMaxWidth().height(130.dp).align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent,FqBg)
                                )
                            )
                    )

                    FilledTonalButton(
                        onClick={
                            coverPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier=Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera,null,modifier=Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Cover",fontSize=11.sp)
                    }

                    Box(
                        Modifier.size(112.dp).align(Alignment.BottomCenter)
                            .background(FqGold,CircleShape).padding(4.dp)
                            .clickable {
                                avatarPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                    ) {
                        if(avatarUri!=null) {
                            AsyncImage(
                                model=avatarUri,
                                contentDescription=null,
                                contentScale=ContentScale.Crop,
                                modifier=Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            RemoteImage(
                                avatarUrl.takeIf(String::isNotBlank),
                                Modifier.fillMaxSize().clip(CircleShape),
                                ContentScale.Crop
                            )
                        }
                        Box(
                            Modifier.size(34.dp).align(Alignment.BottomEnd)
                                .background(FqGold,CircleShape),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.Edit,null,tint=Color.Black,modifier=Modifier.size(18.dp))
                        }
                    }
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal=16.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value=displayName,
                        onValueChange={displayName=it.take(50)},
                        label={Text("نام نمایشی")},
                        leadingIcon={Icon(Icons.Default.Badge,null)},
                        singleLine=true,
                        shape=RoundedCornerShape(15.dp),
                        modifier=Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value=username,
                        onValueChange={
                            username=it.filter { c->
                                c.isLetterOrDigit() || c=='_' || c=='.'
                            }.take(24)
                        },
                        label={Text("Username")},
                        prefix={Text("@")},
                        leadingIcon={Icon(Icons.Default.AlternateEmail,null)},
                        singleLine=true,
                        shape=RoundedCornerShape(15.dp),
                        modifier=Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value=bio,
                        onValueChange={bio=it.take(300)},
                        label={Text("Bio")},
                        minLines=3,
                        maxLines=5,
                        supportingText={
                            Text(bio.length.toString()+"/300",fontSize=11.sp)
                        },
                        shape=RoundedCornerShape(15.dp),
                        modifier=Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                PremiumSectionHeader(
                    "حریم خصوصی",
                    "کنترل نمایش پروفایل و درخواست Follow",
                    Icons.Default.Security
                )
            }

            item {
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)
                ) {
                    Row(
                        Modifier.padding(13.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(
                            if(privateAccount)Icons.Default.Lock else Icons.Default.Public,
                            null,
                            tint=FqGold
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("حساب خصوصی",fontSize=12.sp,fontWeight=FontWeight.Bold)
                            Text(
                                "Followerهای جدید باید تأیید شوند.",
                                color=FqMuted,fontSize=11.sp
                            )
                        }
                        Switch(
                            checked=privateAccount,
                            onCheckedChange={privateAccount=it}
                        )
                    }
                }
            }

            profile?.let { p ->
                item {
                    PremiumSectionHeader(
                        "وضعیت حساب",
                        "اطلاعات فقط خواندنی",
                        Icons.Default.VerifiedUser
                    )
                }
                item {
                    Surface(
                        color=FqSurface,
                        shape=RoundedCornerShape(18.dp),
                        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            ProfileInfoRow("Email",p.email)
                            ProfileInfoRow(
                                "Verification",
                                if(p.verified)"Verified Creator" else "Standard account"
                            )
                            ProfileInfoRow(
                                "Followers",
                                p.followers.toString()+" / Following "+p.following
                            )
                        }
                    }
                }
            }

            error?.let {
                item {
                    Text(
                        it,
                        color=FqDanger,
                        fontSize=11.sp,
                        modifier=Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
            }

            if(saving) {
                item {
                    Surface(
                        color=FqGold.copy(alpha=.09f),
                        shape=RoundedCornerShape(16.dp),
                        modifier=Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Row(
                            Modifier.padding(13.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color=FqGold,
                                strokeWidth=2.dp,
                                modifier=Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(stage.ifBlank{"در حال ذخیره..."},fontSize=11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(label:String,value:String) {
    Row(Modifier.fillMaxWidth().padding(vertical=6.dp)) {
        Text(label,color=FqMuted,fontSize=11.sp,modifier=Modifier.weight(1f))
        Text(value,fontSize=11.sp)
    }
}
