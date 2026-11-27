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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ContactInfoScreen(
    peer: String,
    onBack: () -> Unit,
    onChat: () -> Unit,
    onCall: (Boolean) -> Unit,
    onDeleted: () -> Unit,
) {
    val core = RimvonApp.core
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val ver by core.version.collectAsStateWithLifecycle()
    val c = remember(ver, peer) { core.db.contact(peer) }
    var dlg by remember { mutableStateOf("") }

    if (c == null) {
        LaunchedEffect(Unit) { onDeleted() }
        return
    }
    Column(Modifier.fillMaxSize()) {
        BlueBar("معلومات جهة الاتصال", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(c.name, c.id, 96.dp)
            Spacer(Modifier.height(12.dp))
            Text(c.name, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtNumber(c.id), fontSize = 18.sp, color = Blue, fontWeight = FontWeight.Bold)
                IconButton(onClick = { clip.setText(AnnotatedString(c.id)); toast(ctx, "تم نسخ الرقم") }) {
                    Icon(Icons.Filled.ContentCopy, null, tint = TextGray, modifier = Modifier.size(18.dp))
                }
            }
            TextButton(onClick = { dlg = "rename" }) { Text("تغيير الاسم") }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("رسالة", Modifier.weight(1f), onClick = onChat)
                OutlineButton("صوت", Modifier.weight(1f)) { onCall(false) }
                OutlineButton("فيديو", Modifier.weight(1f)) { onCall(true) }
            }
            Spacer(Modifier.height(28.dp))
            Text("رمز الأمان", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            val theirs = c.pub
            if (theirs != null) {
                Text(
                    Crypto.safetyNumber(core.myPub(), theirs),
                    fontSize = 20.sp, fontFamily = FontFamily.Monospace, color = Ink, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(vertical = 10.dp),
                )
                Text(
                    "قارن هذا الرمز مع الرمز الظاهر عند صديقك (بالاتصال به أو لقائه). إذا تطابق الرمزان فمحادثتكما مشفّرة بينكما فقط ولا يستطيع أحد، حتى الخادم، التنصت عليها. إذا تغيّر الرمز فيعني أن مفتاحه تغيّر (جهاز جديد غالباً).",
                    fontSize = 13.sp, color = TextGray, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("سيظهر الرمز بعد أول اتصال بالخادم.", fontSize = 14.sp, color = TextGray, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(28.dp))
            TextButton(onClick = { core.db.setBlocked(c.id, !c.blocked); core.changed() }) {
                Text(if (c.blocked) "إلغاء حظر هذا الرقم" else "حظر هذا الرقم", color = RedCall, fontSize = 16.sp)
            }
            TextButton(onClick = { dlg = "delete" }) { Text("حذف جهة الاتصال والمحادثة", color = RedCall, fontSize = 16.sp) }
        }
    }

    when (dlg) {
        "rename" -> {
            var v by remember { mutableStateOf(c.name) }
            AlertDialog(
                onDismissRequest = { dlg = "" },
                title = { Text("تغيير الاسم") },
                text = { UnderlineField(v, { v = it.take(40) }, placeholder = "الاسم") },
                confirmButton = {
                    TextButton(enabled = v.isNotBlank(), onClick = { core.db.rename(c.id, v.trim()); core.changed(); dlg = "" }) { Text("حفظ") }
                },
                dismissButton = { TextButton(onClick = { dlg = "" }) { Text("إلغاء") } },
            )
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("حذف جهة الاتصال؟") },
            text = { Text("ستُحذف المحادثة والملفات من هذا الجهاز.") },
            confirmButton = {
                TextButton(onClick = {
                    dlg = ""
                    core.deleteChat(c.id)
                    core.db.deleteContact(c.id)
                    core.changed()
                    onDeleted()
                }) { Text("حذف", color = RedCall) }
            },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("إلغاء") } },
        )
    }
}

@Composable
fun ServersScreen(onBack: () -> Unit) {
    val core = RimvonApp.core
    val ctx = LocalContext.current
    val conn by core.relay.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(core.servers().joinToString("\n")) }
    Column(Modifier.fillMaxSize()) {
        BlueBar("الخادم", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("عنوان الخادم وعناوينه البديلة", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "اكتب كل عنوان في سطر. إذا حُجب العنوان الأول ينتقل التطبيق تلقائياً إلى التالي. " +
                    "يجب أن تكون كلها عناوين لنفس الخادم (دومين آخر، أو عنوان IP مع المنفذ).",
                fontSize = 14.sp, color = TextGray, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(12.dp))
            UnderlineField(text, { text = it }, singleLine = false, keyboard = KeyboardType.Uri, placeholder = "chat.example.com")
            Spacer(Modifier.height(12.dp))
            Text(
                when (conn) { 2 -> "الحالة: متصل ✓"; 1 -> "الحالة: جاري الاتصال…"; else -> "الحالة: غير متصل" },
                fontSize = 15.sp, color = if (conn == 2) GreenCall else TextGray,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton("حفظ وإعادة الاتصال") {
                core.setServers(text.lines())
                core.relay.stop()
                core.relay.start()
                toast(ctx, "تم الحفظ")
            }
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
        Spacer(Modifier.height(16.dp))
        Text("Rimvon مقفل", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("فتح", Modifier.width(220.dp), onClick = onUnlock)
    }
}
