package com.filmiqoo.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    backend:BackendRepository,
    onSuccess:()->Unit,
    onPreview:()->Unit
) {
    val scope=rememberCoroutineScope()
    var register by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var forgotOpen by rememberSaveable { mutableStateOf(false) }
    var recoveryEmail by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if(loading) return
        loading=true
        error=null
        scope.launch {
            runCatching {
                if(register) {
                    backend.register(
                        email.trim(),
                        username.trim(),
                        displayName.trim(),
                        password,
                        Build.MANUFACTURER+" "+Build.MODEL
                    )
                } else {
                    backend.login(
                        login.trim(),
                        password,
                        Build.MANUFACTURER+" "+Build.MODEL
                    )
                }
            }.onSuccess {
                onSuccess()
            }.onFailure {
                error=it.message ?: "ورود انجام نشد. دوباره تلاش کن."
            }
            loading=false
        }
    }

    val cleanEmail=email.trim()
    val cleanUsername=username.trim()
    val usernameValid=cleanUsername.matches(Regex("^[A-Za-z0-9_.]{3,24}$"))
    val emailValid=cleanEmail.length<=254 &&
        cleanEmail.contains("@") &&
        !cleanEmail.startsWith("@") &&
        !cleanEmail.endsWith("@")

    val canSubmit=!loading &&
        password.isNotBlank() &&
        if(register) {
            password.length in 10..128 &&
                emailValid &&
                usernameValid &&
                displayName.trim().length in 2..50
        } else {
            login.isNotBlank()
        }

    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF111620),
                        FqBg,
                        FqBg
                    )
                )
            )
    ) {
        Box(
            Modifier.size(220.dp)
                .offset(x=(-75).dp,y=(-65).dp)
                .background(
                    FqGold.copy(alpha=.045f),
                    CircleShape
                )
        )
        Box(
            Modifier.size(180.dp)
                .align(Alignment.BottomEnd)
                .offset(x=65.dp,y=60.dp)
                .background(
                    FqBlue.copy(alpha=.035f),
                    CircleShape
                )
        )

        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal=20.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(42.dp))

            FilmiqooBrandMark(size=68.dp)

            Text(
                "FILMIQOO",
                color=FqText,
                style=MaterialTheme.typography.headlineLarge,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=14.dp)
            )
            Text(
                "فیلم، سریال و آدم‌هایی که عاشق سینما هستن",
                color=FqMuted,
                style=MaterialTheme.typography.bodyMedium,
                textAlign=TextAlign.Center,
                modifier=Modifier.padding(top=5.dp)
            )

            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(18.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                modifier=Modifier.fillMaxWidth()
                    .padding(top=28.dp)
            ) {
                Row(Modifier.padding(4.dp)) {
                    AuthModeButton(
                        text="ورود",
                        selected=!register,
                        onClick={
                            register=false
                            error=null
                        },
                        modifier=Modifier.weight(1f)
                    )
                    AuthModeButton(
                        text="ساخت حساب",
                        selected=register,
                        onClick={
                            register=true
                            error=null
                        },
                        modifier=Modifier.weight(1f)
                    )
                }
            }

            FqCard(
                modifier=Modifier.padding(top=12.dp)
            ) {
                Text(
                    if(register) "حسابت رو بساز" else "خوش برگشتی",
                    style=MaterialTheme.typography.titleLarge,
                    fontWeight=FontWeight.Bold
                )
                Text(
                    if(register)
                        "چند ثانیه بیشتر طول نمی‌کشه."
                    else
                        "برای ادامه تماشا و کلاب وارد حسابت شو.",
                    color=FqMuted,
                    style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.padding(top=3.dp,bottom=16.dp)
                )

                if(register) {
                    OutlinedTextField(
                        value=email,
                        onValueChange={email=it},
                        label={Text("ایمیل")},
                        singleLine=true,
                        leadingIcon={Icon(Icons.Default.Email,null)},
                        keyboardOptions=KeyboardOptions(
                            keyboardType=KeyboardType.Email
                        ),
                        shape=RoundedCornerShape(16.dp),
                        modifier=Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value=username,
                        onValueChange={
                            username=it.filter { ch ->
                                ch in 'a'..'z' ||
                                    ch in 'A'..'Z' ||
                                    ch in '0'..'9' ||
                                    ch=='_' ||
                                    ch=='.'
                            }.take(24)
                        },
                        label={Text("نام کاربری")},
                        singleLine=true,
                        leadingIcon={Icon(Icons.Default.AlternateEmail,null)},
                        supportingText={
                            Text("۳ تا ۲۴ کاراکتر • حروف انگلیسی، عدد، _ یا .")
                        },
                        shape=RoundedCornerShape(16.dp),
                        modifier=Modifier.fillMaxWidth()
                            .padding(top=10.dp)
                    )
                    OutlinedTextField(
                        value=displayName,
                        onValueChange={displayName=it},
                        label={Text("نام نمایشی")},
                        singleLine=true,
                        leadingIcon={Icon(Icons.Default.Badge,null)},
                        shape=RoundedCornerShape(16.dp),
                        modifier=Modifier.fillMaxWidth()
                            .padding(top=10.dp)
                    )
                } else {
                    OutlinedTextField(
                        value=login,
                        onValueChange={login=it},
                        label={Text("ایمیل یا نام کاربری")},
                        singleLine=true,
                        leadingIcon={Icon(Icons.Default.PersonOutline,null)},
                        shape=RoundedCornerShape(16.dp),
                        modifier=Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value=password,
                    onValueChange={password=it},
                    label={Text("رمز عبور")},
                    visualTransformation=
                        if(passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    singleLine=true,
                    leadingIcon={Icon(Icons.Default.Lock,null)},
                    trailingIcon={
                        IconButton(
                            onClick={passwordVisible=!passwordVisible}
                        ) {
                            Icon(
                                if(passwordVisible)
                                    Icons.Default.VisibilityOff
                                else
                                    Icons.Default.Visibility,
                                contentDescription=
                                    if(passwordVisible)
                                        "مخفی کردن رمز"
                                    else
                                        "نمایش رمز"
                            )
                        }
                    },
                    supportingText={
                        if(register) {
                            Text("حداقل ۱۰ کاراکتر")
                        }
                    },
                    keyboardOptions=KeyboardOptions(
                        keyboardType=KeyboardType.Password
                    ),
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth()
                        .padding(top=10.dp)
                )

                if(!register) {
                    TextButton(onClick={ recoveryEmail=login.takeIf { it.contains("@") }.orEmpty(); forgotOpen=true },modifier=Modifier.align(Alignment.End)) {
                        Text("رمز عبور را فراموش کردی؟",color=FqGold)
                    }
                }

                error?.let { message ->
                    Surface(
                        color=FqDanger.copy(alpha=.10f),
                        contentColor=FqDanger,
                        shape=RoundedCornerShape(14.dp),
                        border=androidx.compose.foundation.BorderStroke(
                            1.dp,
                            FqDanger.copy(alpha=.18f)
                        ),
                        modifier=Modifier.fillMaxWidth()
                            .padding(top=12.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment=Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                null,
                                modifier=Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                message,
                                style=MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                FqPrimaryButton(
                    text=if(register) "ساخت حساب" else "ورود به Filmiqoo",
                    icon=if(register) Icons.Default.PersonAdd else Icons.Default.Login,
                    onClick={submit()},
                    enabled=canSubmit,
                    loading=loading,
                    modifier=Modifier.fillMaxWidth()
                        .padding(top=16.dp)
                )

                if(register) {
                    Text(
                        "با ساخت حساب، قوانین استفاده و حریم خصوصی Filmiqoo رو می‌پذیری.",
                        color=FqMuted,
                        style=MaterialTheme.typography.labelSmall,
                        textAlign=TextAlign.Center,
                        modifier=Modifier.fillMaxWidth()
                            .padding(top=12.dp)
                    )
                }
            }

            Surface(
                color=Color.Transparent,
                modifier=Modifier.fillMaxWidth()
                    .padding(top=18.dp)
            ) {
                Row(
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Security,
                        null,
                        tint=FqGreen,
                        modifier=Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "نشست امن • کنترل دستگاه‌ها • حذف و دریافت خروجی اطلاعات",
                        color=FqMuted,
                        style=MaterialTheme.typography.labelSmall,
                        textAlign=TextAlign.Center
                    )
                }
            }



            Spacer(Modifier.height(28.dp))
        }

        if(forgotOpen) {
            AlertDialog(
                onDismissRequest={forgotOpen=false},
                icon={Icon(Icons.Default.LockReset,null,tint=FqGold)},
                title={Text("بازیابی رمز عبور")},
                text={Column {
                    Text("ایمیل حساب Filmiqoo را وارد کن. لینک بازیابی برایت ارسال می‌شود.",color=FqMuted,style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value=recoveryEmail,onValueChange={recoveryEmail=it},label={Text("ایمیل")},leadingIcon={Icon(Icons.Default.Email,null)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                }},
                confirmButton={Button(onClick={forgotOpen=false; error="درخواست بازیابی ثبت شد. اگر این ایمیل حسابی داشته باشد، لینک بازیابی ارسال می‌شود."},enabled=recoveryEmail.contains("@"),colors=ButtonDefaults.buttonColors(containerColor=FqGold)){Text("ارسال لینک",color=Color.Black)}},
                dismissButton={TextButton(onClick={forgotOpen=false}){Text("لغو")}}
            )
        }

        /* end auth content */
    }
}

@Composable
private fun AuthModeButton(
    text:String,
    selected:Boolean,
    onClick:()->Unit,
    modifier:Modifier=Modifier
) {
    Button(
        onClick=onClick,
        colors=ButtonDefaults.buttonColors(
            containerColor=if(selected) FqGold else Color.Transparent,
            contentColor=if(selected) Color(0xFF171000) else FqMuted
        ),
        shape=RoundedCornerShape(14.dp),
        elevation=ButtonDefaults.buttonElevation(0.dp),
        contentPadding=PaddingValues(vertical=11.dp),
        modifier=modifier.heightIn(min=46.dp)
    ) {
        Text(
            text,
            style=MaterialTheme.typography.labelLarge,
            fontWeight=FontWeight.Bold
        )
    }
}
