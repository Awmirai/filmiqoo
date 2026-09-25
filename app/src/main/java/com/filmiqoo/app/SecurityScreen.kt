package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SecurityScreen(
    backend:BackendRepository,
    onBack:()->Unit,
    onCurrentSessionRevoked:()->Unit
) {
    val repo=remember { SecurityRepository(backend) }
    val scope=rememberCoroutineScope()
    val context=LocalContext.current

    var sessions by remember { mutableStateOf<List<AccountSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var confirmOthers by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<AccountSession?>(null) }

    var exportPayload by remember { mutableStateOf<String?>(null) }
    var privacyBusy by remember { mutableStateOf(false) }
    var deleteAccountOpen by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    var deleteConfirmation by remember { mutableStateOf("") }

    val exportLauncher=rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload=exportPayload
        exportPayload=null
        if(uri==null || payload==null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.writer(Charsets.UTF_8).use { writer ->
                            writer.write(payload)
                        }
                    } ?: error("فایل قابل ایجاد نیست")
                }
            }.onSuccess {
                actionMessage="نسخه اطلاعات حساب ذخیره شد."
            }.onFailure {
                error=it.message
            }
        }
    }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.sessions() }
            .onSuccess { sessions=it }
            .onFailure { error=it.message }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("امنیت و دستگاه‌ها",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text("Sessionهای فعال حساب Filmiqoo",color=FqMuted,fontSize=11.sp)
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
                modifier=Modifier.fillMaxWidth()
                    .background(FqDanger.copy(alpha=.08f))
                    .padding(10.dp)
            )
        }

        Surface(
            color=FqSurface,
            shape=RoundedCornerShape(20.dp),
            modifier=Modifier.fillMaxWidth().padding(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Security,null,tint=FqGold)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("کنترل Sessionها",fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Text(
                            "اگر دستگاه ناشناسی دیدی، Session اون دستگاه رو قطع کن.",
                            color=FqMuted,
                            fontSize=11.sp,
                            lineHeight=14.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick={confirmOthers=true},
                    enabled=sessions.any {!it.current},
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=FqDanger),
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                ) {
                    Icon(Icons.Default.Logout,null)
                    Spacer(Modifier.width(6.dp))
                    Text("خروج از همه دستگاه‌های دیگر")
                }
            }
        }

        Surface(
            color=FqSurface,
            shape=RoundedCornerShape(20.dp),
            modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.PrivacyTip,null,tint=FqGold)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("حریم خصوصی و حساب",fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Text(
                            "دریافت نسخه داده‌ها یا حذف کامل دسترسی حساب.",
                            color=FqMuted,
                            fontSize=11.sp,
                            lineHeight=14.sp
                        )
                    }
                }

                OutlinedButton(
                    enabled=!privacyBusy,
                    onClick={
                        privacyBusy=true
                        scope.launch {
                            runCatching { repo.accountExportJson() }
                                .onSuccess { payload ->
                                    exportPayload=payload
                                    exportLauncher.launch("filmiqoo-account-export.json")
                                }
                                .onFailure { error=it.message }
                            privacyBusy=false
                        }
                    },
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                ) {
                    Icon(Icons.Default.Download,null)
                    Spacer(Modifier.width(6.dp))
                    Text("دریافت نسخه اطلاعات من")
                }

                OutlinedButton(
                    enabled=!privacyBusy,
                    onClick={deleteAccountOpen=true},
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=FqDanger),
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                ) {
                    Icon(Icons.Default.DeleteForever,null)
                    Spacer(Modifier.width(6.dp))
                    Text("حذف حساب")
                }
            }
        }

        Text(
            "دستگاه‌های فعال",
            fontSize=14.sp,
            fontWeight=FontWeight.Bold,
            modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp)
        )

        if(!loading && sessions.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.Devices,
                "Session فعالی پیدا نشد",
                "بعد از ورود، دستگاه‌های فعال اینجا نمایش داده می‌شن."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp),
                modifier=Modifier.weight(1f)
            ) {
                items(sessions,key={it.id}) { session ->
                    SecuritySessionCard(
                        session=session,
                        onRevoke={revokeTarget=session}
                    )
                }
            }
        }
    }

    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest={revokeTarget=null},
            icon={Icon(Icons.Default.PhonelinkErase,null,tint=FqDanger)},
            title={Text(if(target.current)"خروج از این دستگاه؟" else "قطع این Session؟")},
            text={
                Text(
                    if(target.current)
                        "با قطع Session فعلی باید دوباره وارد حساب شوی."
                    else
                        "دسترسی «"+target.deviceName+"» فوراً قطع می‌شود."
                )
            },
            confirmButton={
                TextButton(onClick={
                    revokeTarget=null
                    scope.launch {
                        runCatching { repo.revoke(target.id) }
                            .onSuccess {
                                if(target.current) {
                                    backend.session.clear()
                                    onCurrentSessionRevoked()
                                } else {
                                    actionMessage="Session قطع شد."
                                    refresh++
                                }
                            }
                            .onFailure { error=it.message }
                    }
                }) { Text("قطع Session",color=FqDanger) }
            },
            dismissButton={TextButton(onClick={revokeTarget=null}){Text("لغو")}}
        )
    }

    if(deleteAccountOpen) {
        AlertDialog(
            onDismissRequest={
                if(!privacyBusy) {
                    deleteAccountOpen=false
                    deletePassword=""
                    deleteConfirmation=""
                }
            },
            icon={Icon(Icons.Default.DeleteForever,null,tint=FqDanger)},
            title={Text("حذف حساب Filmiqoo")},
            text={
                Column {
                    Text(
                        "Sessionها و داده‌های خصوصی حذف می‌شوند و محتوای قبلی به حالت ناشناس/حذف‌شده می‌رود."
                    )
                    OutlinedTextField(
                        value=deletePassword,
                        onValueChange={deletePassword=it.take(128)},
                        label={Text("رمز عبور")},
                        visualTransformation=PasswordVisualTransformation(),
                        singleLine=true,
                        modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                    )
                    OutlinedTextField(
                        value=deleteConfirmation,
                        onValueChange={deleteConfirmation=it.take(6)},
                        label={Text("برای تأیید DELETE بنویس")},
                        singleLine=true,
                        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                    )
                }
            },
            confirmButton={
                Button(
                    enabled=!privacyBusy &&
                        deletePassword.isNotBlank() &&
                        deleteConfirmation=="DELETE",
                    onClick={
                        privacyBusy=true
                        scope.launch {
                            runCatching {
                                repo.deleteAccount(
                                    password=deletePassword,
                                    confirmation=deleteConfirmation
                                )
                            }.onSuccess { deleted ->
                                if(deleted) {
                                    backend.session.clear()
                                    deleteAccountOpen=false
                                    deletePassword=""
                                    deleteConfirmation=""
                                    onCurrentSessionRevoked()
                                }
                            }.onFailure {
                                error=it.message
                            }
                            privacyBusy=false
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=FqDanger)
                ) {
                    if(privacyBusy) {
                        CircularProgressIndicator(
                            color=Color.White,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("حذف حساب",color=Color.White)
                }
            },
            dismissButton={
                TextButton(
                    enabled=!privacyBusy,
                    onClick={
                        deleteAccountOpen=false
                        deletePassword=""
                        deleteConfirmation=""
                    }
                ) { Text("لغو") }
            }
        )
    }

    if(confirmOthers) {
        AlertDialog(
            onDismissRequest={confirmOthers=false},
            icon={Icon(Icons.Default.DevicesOther,null,tint=FqGold)},
            title={Text("خروج از دستگاه‌های دیگر؟")},
            text={Text("Session فعلی باقی می‌مونه و تمام Sessionهای فعال دیگر Revoke می‌شن.")},
            confirmButton={
                TextButton(onClick={
                    confirmOthers=false
                    scope.launch {
                        runCatching { repo.revokeOthers() }
                            .onSuccess {
                                actionMessage=it.toString()+" Session قطع شد."
                                refresh++
                            }
                            .onFailure { error=it.message }
                    }
                }) { Text("تأیید") }
            },
            dismissButton={TextButton(onClick={confirmOthers=false}){Text("لغو")}}
        )
    }

    actionMessage?.let {
        Snackbar(
            modifier=Modifier.padding(16.dp),
            action={TextButton(onClick={actionMessage=null}){Text("باشه")}}
        ) { Text(it) }
    }
}

@Composable
private fun SecuritySessionCard(
    session:AccountSession,
    onRevoke:()->Unit
) {
    Surface(
        color=if(session.current)FqGold.copy(alpha=.08f) else FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).background(
                    if(session.current)FqGold.copy(alpha=.14f) else FqSurface2,
                    RoundedCornerShape(14.dp)
                ),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    deviceIcon(session),
                    null,
                    tint=if(session.current)FqGold else Color.White
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        session.deviceName,
                        fontSize=12.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    if(session.current) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color=FqGreen.copy(alpha=.15f),
                            shape=RoundedCornerShape(7.dp)
                        ) {
                            Text(
                                "این دستگاه",
                                color=FqGreen,
                                fontSize=6.sp,
                                modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                            )
                        }
                    }
                }

                if(session.ipAddress.isNotBlank()) {
                    Text(
                        "IP: "+session.ipAddress,
                        color=FqMuted,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }

                Text(
                    "آخرین فعالیت: "+formatSecurityTime(session.lastUsedAt),
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=2.dp)
                )

                if(session.userAgent.isNotBlank()) {
                    Text(
                        session.userAgent,
                        color=FqMuted,
                        fontSize=6.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
            }

            IconButton(onClick=onRevoke) {
                Icon(
                    Icons.Default.Logout,
                    null,
                    tint=if(session.current)FqGold else FqDanger
                )
            }
        }
    }
}

private fun deviceIcon(session:AccountSession)=when {
    session.userAgent.contains("Android",ignoreCase=true) -> Icons.Default.PhoneAndroid
    session.userAgent.contains("iPhone",ignoreCase=true) -> Icons.Default.PhoneIphone
    session.userAgent.contains("Windows",ignoreCase=true) -> Icons.Default.Computer
    session.userAgent.contains("Mac",ignoreCase=true) -> Icons.Default.LaptopMac
    else -> Icons.Default.Devices
}

private fun formatSecurityTime(value:String):String {
    if(value.isBlank()) return "—"
    return value.replace("T"," ").take(16)
}
