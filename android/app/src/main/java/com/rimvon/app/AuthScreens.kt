package com.rimvon.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val shieldColor = Color(0xFF3B4A6B)

@Composable
fun WelcomeScreen(server: String, onServer: (String) -> Unit, onNew: () -> Unit, onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Image(
            painterResource(R.drawable.logo), contentDescription = null,
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(26.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text("Rimvon", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Blue)
        Spacer(Modifier.height(8.dp))
        Text("مراسلة ومكالمات خاصة ومشفّرة", fontSize = 18.sp, color = Ink)
        Text("بدون رقم هاتف ولا جهات اتصال", fontSize = 15.sp, color = TextGray)
        Spacer(Modifier.height(36.dp))
        Text("عنوان الخادم", fontSize = 14.sp, color = TextGray, modifier = Modifier.fillMaxWidth())
        UnderlineField(server, onServer, placeholder = "chat.example.com", keyboard = KeyboardType.Uri)
        Spacer(Modifier.height(4.dp))
        Text(
            "هذا هو عنوان خادم Rimvon الذي يديره صاحبك (أو أنت). الرسائل مشفّرة ولا يراها الخادم.",
            fontSize = 13.sp, color = TextGray, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("إنشاء رقم جديد", enabled = server.isNotBlank(), onClick = onNew)
        Spacer(Modifier.height(12.dp))
        GrayButton("لدي رقم — تسجيل الدخول", onClick = onLogin)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PasswordScreen(
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
    onSkip: () -> Unit,
) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val mismatch = p2.isNotEmpty() && p1 != p2
    val valid = p1.length >= 6 && p1 == p2
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Blue, modifier = Modifier.size(30.dp))
            }
        }
        Text(
            "ضبط كلمة السر", fontSize = 32.sp, color = Ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = Blue, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "كلمة المرور مطلوبة لتسجيل الدخول مرة أخرى. إذا تخطيت هذه الخطوة، فلن تتمكن من الوصول إلى حسابك بعد تسجيل الخروج.",
            fontSize = 18.sp, lineHeight = 32.sp, color = Ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "يمكنك أيضًا تعيين أو تغيير كلمة المرور الخاصة بك في الإعدادات.",
            fontSize = 16.sp, lineHeight = 28.sp, color = TextGray, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))
        UnderlineField(p1, { p1 = it }, password = true, placeholder = "كلمة السر", textSize = 22.sp)
        UnderlineField(p2, { p2 = it }, password = true, placeholder = "تأكيد كلمة السر", textSize = 22.sp)
        Spacer(Modifier.height(24.dp))
        Text(
            when {
                error != null -> error
                mismatch -> "كلمتا السر غير متطابقتين."
                else -> "يجب أن تحتوي كلمة المرور على 6 عناصر على الأقل."
            },
            fontSize = 17.sp, color = if (error != null || mismatch) RedCall else Ink,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        if (busy) {
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Blue)
            }
        } else {
            PrimaryButton("الاستمرار.", enabled = valid) { onContinue(p1) }
            Spacer(Modifier.height(12.dp))
            GrayButton("تخطّي", onClick = onSkip)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun NumberScreen(number: String, onContinue: () -> Unit, onInvite: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) { Spacer(Modifier.height(48.dp)) }
        Text(fmtNumber(number), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(Modifier.height(28.dp))
        Text("هذا هو", fontSize = 26.sp, color = Color.Black)
        Spacer(Modifier.height(12.dp))
        Text("رقم Rimvon الخاص", fontSize = 28.sp, color = Blue)
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Filled.Info, contentDescription = null, tint = Blue, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(24.dp))
        BulletRow("شارك هذا الرقم مع أصدقائك ليتواصلوا معك.")
        Spacer(Modifier.height(20.dp))
        BulletRow("للدخول مرة أخرى إلى حسابك بعد تسجيل الخروج، استخدم هذا الرقم وكلمة المرور.")
        Spacer(Modifier.weight(1f))
        Text("نحن نركز على الحماية القصوى!", fontSize = 28.sp, color = Color.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        PrimaryButton("الاستمرار.", onClick = onContinue)
        Spacer(Modifier.height(12.dp))
        OutlineButton("قم بدعوة الأصدقاء للتواصل.", onClick = onInvite)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BulletRow(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Start) {
        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = shieldColor, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 18.sp, lineHeight = 30.sp, color = Ink, modifier = Modifier.weight(1f))
    }
}

@Composable
fun LoginScreen(
    server: String,
    onServer: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val digits = digitsOnly(id)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Blue, modifier = Modifier.size(30.dp))
            }
        }
        Text("تسجيل الدخول", fontSize = 32.sp, color = Ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))
        UnderlineField(server, onServer, placeholder = "عنوان الخادم", keyboard = KeyboardType.Uri)
        UnderlineField(id, { id = it }, placeholder = "رقم Rimvon الخاص (10 خانات)", keyboard = KeyboardType.Number, textSize = 22.sp)
        UnderlineField(pass, { pass = it }, password = true, placeholder = "كلمة السر", textSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Text(error, fontSize = 16.sp, color = RedCall, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "إذا أنشأت حسابك بدون كلمة سر فلا يمكن استرجاعه بعد تسجيل الخروج.",
            fontSize = 14.sp, color = TextGray, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        if (busy) {
            Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Blue)
            }
        } else {
            PrimaryButton("الدخول", enabled = digits.length == 10 && pass.length >= 6 && server.isNotBlank()) {
                onLogin(digits, pass)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
