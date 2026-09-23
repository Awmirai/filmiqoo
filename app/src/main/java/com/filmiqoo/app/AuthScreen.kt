package com.filmiqoo.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    backend: BackendRepository,
    onSuccess: () -> Unit,
    onPreview: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(backend.session.baseUrl) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showServer by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111827), FqBg))
        )
    ) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier.size(76.dp).background(FqGold, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(52.dp))
            }
            Text("FILMIQOO", color=FqGold, fontSize=30.sp, modifier=Modifier.padding(top=12.dp))
            Text(
                if(register) "ساخت حساب کاربری" else "ورود به دنیای فیلم و سریال",
                color=FqMuted,
                fontSize=12.sp,
                modifier=Modifier.padding(top=4.dp,bottom=22.dp)
            )

            Surface(color=FqSurface,shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    if(register) {
                        OutlinedTextField(
                            value=email,onValueChange={email=it},
                            label={Text("ایمیل")},singleLine=true,
                            leadingIcon={Icon(Icons.Default.Email,null)},
                            modifier=Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value=username,onValueChange={username=it},
                            label={Text("نام کاربری")},singleLine=true,
                            leadingIcon={Icon(Icons.Default.AlternateEmail,null)},
                            modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                        )
                        OutlinedTextField(
                            value=displayName,onValueChange={displayName=it},
                            label={Text("نام نمایشی")},singleLine=true,
                            leadingIcon={Icon(Icons.Default.Badge,null)},
                            modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value=login,onValueChange={login=it},
                            label={Text("ایمیل یا نام کاربری")},singleLine=true,
                            leadingIcon={Icon(Icons.Default.Person,null)},
                            modifier=Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value=password,onValueChange={password=it},
                        label={Text("رمز عبور")},
                        visualTransformation=PasswordVisualTransformation(),
                        singleLine=true,
                        leadingIcon={Icon(Icons.Default.Lock,null)},
                        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                    )

                    if(showServer) {
                        OutlinedTextField(
                            value=server,onValueChange={server=it},
                            label={Text("Backend URL")},
                            supportingText={Text("برای Emulator: http://10.0.2.2:8080")},
                            singleLine=true,
                            leadingIcon={Icon(Icons.Default.Dns,null)},
                            modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                        )
                    }

                    error?.let {
                        Surface(
                            color=FqDanger.copy(alpha=.12f),
                            shape=RoundedCornerShape(12.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                        ) {
                            Text(it,color=FqDanger,fontSize=10.sp,modifier=Modifier.padding(10.dp))
                        }
                    }

                    Button(
                        enabled=!loading && password.length>=10 &&
                            if(register) email.isNotBlank() && username.length>=3 && displayName.length>=2
                            else login.isNotBlank(),
                        onClick={
                            backend.session.baseUrl=server
                            loading=true
                            error=null
                            scope.launch {
                                runCatching {
                                    if(register) backend.register(
                                        email,username,displayName,password,
                                        Build.MANUFACTURER+" "+Build.MODEL
                                    ) else backend.login(
                                        login,password,Build.MANUFACTURER+" "+Build.MODEL
                                    )
                                }.onSuccess {
                                    onSuccess()
                                }.onFailure {
                                    error=it.message ?: "ورود ناموفق بود"
                                }
                                loading=false
                            }
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=14.dp)
                    ) {
                        if(loading) {
                            CircularProgressIndicator(
                                color=Color.Black,strokeWidth=2.dp,modifier=Modifier.size(18.dp)
                            )
                        } else {
                            Icon(if(register)Icons.Default.PersonAdd else Icons.Default.Login,null)
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(if(register)"ساخت حساب" else "ورود")
                    }

                    TextButton(
                        onClick={register=!register;error=null},
                        modifier=Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(if(register)"قبلاً حساب داری؟ ورود" else "حساب نداری؟ ثبت‌نام")
                    }

                    TextButton(
                        onClick={showServer=!showServer},
                        modifier=Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.SettingsEthernet,null,modifier=Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("تنظیم آدرس سرور",fontSize=10.sp)
                    }
                }
            }

            OutlinedButton(
                onClick=onPreview,
                modifier=Modifier.fillMaxWidth().padding(top=14.dp),
                shape=RoundedCornerShape(14.dp)
            ) {
                Text("فعلاً ورود به حالت Preview")
            }
        }
    }
}
