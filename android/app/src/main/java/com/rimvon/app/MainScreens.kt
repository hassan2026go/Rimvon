@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.rimvon.app

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

fun toast(ctx: android.content.Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

@Composable
fun MainScreen(
    onChat: (String) -> Unit,
    onInfo: (String) -> Unit,
    onCall: (String, Boolean) -> Unit,
    onServers: () -> Unit,
    onShare: () -> Unit,
    onBattery: () -> Unit,
    onToggleLock: (Boolean) -> Boolean,
) {
    val core = RimvonApp.core
    var tab by rememberSaveable { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val conn by core.relay.state.collectAsStateWithLifecycle()
    val ver by core.version.collectAsStateWithLifecycle()
    val unread = remember(ver) { core.db.totalUnread() }
    val titles = listOf("الدردشات", "المكالمات", "جهات الاتصال", "الإعدادات")

    Scaffold(
        containerColor = Color.White,
        topBar = {
            BlueBar(titles[tab], actions = {
                if (tab == 0 || tab == 2) {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "إضافة", tint = Color.White)
                    }
                }
                if (tab == 1) {
                    IconButton(onClick = { core.db.clearCalls(); core.changed() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "مسح السجل", tint = Color.White)
                    }
                }
            })
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val items = listOf<Pair<ImageVector, String>>(
                    Icons.Filled.Chat to "الدردشات", Icons.Filled.Call to "المكالمات",
                    Icons.Filled.Contacts to "الأصدقاء", Icons.Filled.Settings to "الإعدادات",
                )
                items.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            if (i == 0 && unread > 0) {
                                BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                                    Icon(icon, contentDescription = label)
                                }
                            } else {
                                Icon(icon, contentDescription = label)
                            }
                        },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Blue, selectedTextColor = Blue, indicatorColor = BlueLight,
                            unselectedIconColor = TextGray, unselectedTextColor = TextGray,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnBanner(conn)
            when (tab) {
                0 -> ChatsTab(onChat)
                1 -> CallsTab(onCall)
                2 -> ContactsTab(onChat, onInfo, onCall)
                else -> SettingsTab(onServers, onShare, onBattery, onToggleLock)
            }
        }
    }
    if (showAdd) {
        AddContactDialog(onDismiss = { showAdd = false }, onAdded = { showAdd = false; onChat(it) })
    }
}

@Composable
private fun ChatsTab(onChat: (String) -> Unit) {
    val core = RimvonApp.core
    val ver by core.version.collectAsStateWithLifecycle()
    val rows = remember(ver) { core.db.chats() }
    var menuFor by remember { mutableStateOf<ChatRow?>(null) }
    var confirm by remember { mutableStateOf<ChatRow?>(null) }

    if (rows.isEmpty()) {
        EmptyState("لا توجد محادثات بعد", "اضغط + لإضافة جهة اتصال برقمها ثم ابدأ المراسلة")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.contact.id }) { r ->
                Column {
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { onChat(r.contact.id) }, onLongClick = { menuFor = r })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(r.contact.name, r.contact.id, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.contact.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val last = r.last
                        val preview = when {
                            last == null -> ""
                            last.kind == "sys" -> last.text
                            last.out -> "أنت: " + previewOf(last.kind, last.text)
                            else -> previewOf(last.kind, last.text)
                        }
                        Text(preview, fontSize = 14.sp, color = TextGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        r.last?.let { Text(fmtTime(it.ts), fontSize = 12.sp, color = TextGray) }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (r.contact.pinned) {
                                Icon(Icons.Filled.PushPin, null, tint = TextGray, modifier = Modifier.size(16.dp))
                            }
                            if (r.unread > 0) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier.clip(CircleShape).background(Blue).padding(horizontal = 7.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(r.unread.toString(), color = Color.White, fontSize = 12.sp) }
                            }
                        }
                    }
                }
                HorizontalDivider(color = GrayLine, modifier = Modifier.padding(start = 80.dp))
                }
            }
        }
    }

    menuFor?.let { r ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(r.contact.name) },
            text = {
                Column {
                    TextButton(onClick = {
                        core.db.setPinned(r.contact.id, !r.contact.pinned); core.changed(); menuFor = null
                    }) { Text(if (r.contact.pinned) "إلغاء التثبيت" else "تثبيت المحادثة") }
                    TextButton(onClick = { confirm = r; menuFor = null }) { Text("حذف المحادثة", color = RedCall) }
                }
            },
            confirmButton = {},
        )
    }
    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("حذف المحادثة؟") },
            text = { Text("ستُحذف الرسائل والملفات من هذا الجهاز فقط.") },
            confirmButton = { TextButton(onClick = { core.deleteChat(r.contact.id); confirm = null }) { Text("حذف", color = RedCall) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun CallsTab(onCall: (String, Boolean) -> Unit) {
    val core = RimvonApp.core
    val ver by core.version.collectAsStateWithLifecycle()
    val logs = remember(ver) { core.db.calls() }
    if (logs.isEmpty()) {
        EmptyState("لا توجد مكالمات", "ستظهر هنا مكالماتك الصادرة والواردة والفائتة")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(logs, key = { it.id }) { c ->
                Column {
                val name = core.displayName(c.peer)
                Row(
                    Modifier.fillMaxWidth().clickable { onCall(c.peer, c.video) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, c.peer, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (c.missed) RedCall else Ink, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (c.missed) Icons.Filled.CallMissed else if (c.out) Icons.Filled.CallMade else Icons.Filled.CallReceived,
                                null, tint = if (c.missed) RedCall else GreenCall, modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            val kind = if (c.video) "فيديو" else "صوتية"
                            val dur = if (c.dur > 0) " • " + fmtDur(c.dur) else ""
                            Text("$kind • ${fmtTime(c.ts)}$dur", fontSize = 13.sp, color = TextGray)
                        }
                    }
                    IconButton(onClick = { onCall(c.peer, c.video) }) {
                        Icon(if (c.video) Icons.Filled.Videocam else Icons.Filled.Call, null, tint = Blue)
                    }
                }
                HorizontalDivider(color = GrayLine, modifier = Modifier.padding(start = 76.dp))
                }
            }
        }
    }
}

@Composable
private fun ContactsTab(onChat: (String) -> Unit, onInfo: (String) -> Unit, onCall: (String, Boolean) -> Unit) {
    val core = RimvonApp.core
    val ver by core.version.collectAsStateWithLifecycle()
    val list = remember(ver) { core.db.contacts() }
    if (list.isEmpty()) {
        EmptyState("لا توجد جهات اتصال", "اضغط + وأدخل رقم Rimvon الخاص بصديقك")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { c ->
                Column {
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { onChat(c.id) }, onLongClick = { onInfo(c.id) })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.name, c.id, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(fmtNumber(c.id) + if (c.blocked) " • محظور" else "", fontSize = 13.sp, color = TextGray)
                    }
                    IconButton(onClick = { onCall(c.id, false) }) { Icon(Icons.Filled.Call, null, tint = Blue) }
                    IconButton(onClick = { onInfo(c.id) }) { Icon(Icons.Filled.Person, null, tint = TextGray) }
                }
                HorizontalDivider(color = GrayLine, modifier = Modifier.padding(start = 76.dp))
                }
            }
        }
    }
}

@Composable
fun AddContactDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val core = RimvonApp.core
    var num by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("إضافة جهة اتصال") },
        text = {
            Column {
                UnderlineField(num, { num = it }, placeholder = "رقم Rimvon (10 خانات)", keyboard = KeyboardType.Number)
                UnderlineField(name, { name = it }, placeholder = "الاسم (اختياري)")
                val e = err
                if (e != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(e, color = RedCall, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && digitsOnly(num).length == 10,
                onClick = {
                    busy = true; err = null
                    scope.launch {
                        val r = core.addContact(num, name)
                        busy = false
                        r.onSuccess { c -> onAdded(c.id) }.onFailure { ex -> err = ex.message }
                    }
                },
            ) { Text(if (busy) "…" else "إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") } },
    )
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String? = null, tint: Color = Blue, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = if (tint == RedCall) RedCall else Ink)
            if (sub != null) Text(sub, fontSize = 13.sp, color = TextGray)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsTab(onServers: () -> Unit, onShare: () -> Unit, onBattery: () -> Unit, onToggleLock: (Boolean) -> Boolean) {
    val core = RimvonApp.core
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(core.myName()) }
    var lock by remember { mutableStateOf(core.lockEnabled()) }
    var dlg by remember { mutableStateOf("") }
    val number = core.number() ?: ""

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name.ifBlank { "R" }, number, 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { "بدون اسم" }, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(fmtNumber(number), fontSize = 22.sp, color = Blue, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { clip.setText(AnnotatedString(number)); toast(ctx, "تم نسخ الرقم") }) {
                Icon(Icons.Filled.ContentCopy, null, tint = TextGray)
            }
        }
        HorizontalDivider(color = GrayLine)
        SettingRow(Icons.Filled.Person, "الاسم الظاهر لأصدقائك", name.ifBlank { "غير محدد" }) { dlg = "name" }
        SettingRow(Icons.Filled.Password, "كلمة السر", if (core.hasPassword()) "تغيير كلمة السر" else "لم تضبط كلمة سر — اضبطها الآن") { dlg = "pass" }
        SettingRow(
            Icons.Filled.Lock, "قفل التطبيق", "بصمة الإصبع أو قفل الشاشة",
            trailing = {
                Switch(
                    checked = lock, onCheckedChange = { want -> if (onToggleLock(want)) lock = want },
                    colors = SwitchDefaults.colors(checkedTrackColor = Blue),
                )
            },
        ) { if (onToggleLock(!lock)) lock = !lock }
        SettingRow(Icons.Filled.Dns, "الخادم والعناوين البديلة", core.servers().firstOrNull() ?: "", onClick = onServers)
        SettingRow(Icons.Filled.BatteryChargingFull, "استمرار وصول الرسائل", "اسمح للتطبيق بالعمل في الخلفية دون تقييد البطارية", onClick = onBattery)
        SettingRow(Icons.Filled.Share, "دعوة الأصدقاء", onClick = onShare)
        HorizontalDivider(color = GrayLine)
        SettingRow(Icons.Filled.Logout, "تسجيل الخروج", tint = Color(0xFF7A5B00)) { dlg = "logout" }
        SettingRow(Icons.Filled.DeleteForever, "حذف الحساب نهائياً", tint = RedCall) { dlg = "delete" }
        Spacer(Modifier.height(24.dp))
        Text(
            "Rimvon 1.0 — الرسائل والمكالمات مشفّرة من طرف إلى طرف",
            fontSize = 12.sp, color = TextGray, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    when (dlg) {
        "name" -> {
            var v by remember { mutableStateOf(name) }
            AlertDialog(
                onDismissRequest = { dlg = "" },
                title = { Text("الاسم الظاهر") },
                text = { UnderlineField(v, { v = it.take(30) }, placeholder = "اسمك") },
                confirmButton = { TextButton(onClick = { core.setMyName(v); name = v.trim(); dlg = "" }) { Text("حفظ") } },
                dismissButton = { TextButton(onClick = { dlg = "" }) { Text("إلغاء") } },
            )
        }
        "pass" -> {
            var p1 by remember { mutableStateOf("") }
            var p2 by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!busy) dlg = "" },
                title = { Text("كلمة السر الجديدة") },
                text = {
                    Column {
                        UnderlineField(p1, { p1 = it }, password = true, placeholder = "كلمة السر (6 على الأقل)")
                        UnderlineField(p2, { p2 = it }, password = true, placeholder = "تأكيد كلمة السر")
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && p1.length >= 6 && p1 == p2, onClick = {
                        busy = true
                        scope.launch {
                            val e = core.changePassword(p1)
                            busy = false
                            if (e == null) { toast(ctx, "تم تغيير كلمة السر"); dlg = "" } else toast(ctx, e)
                        }
                    }) { Text(if (busy) "…" else "حفظ") }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { dlg = "" }) { Text("إلغاء") } },
            )
        }
        "logout" -> AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("تسجيل الخروج؟") },
            text = {
                Text(
                    (if (core.hasPassword()) "للدخول مجدداً ستحتاج رقمك وكلمة السر." else "لم تضبط كلمة سر، ولن تتمكن من الدخول إلى هذا الحساب بعد الخروج!") +
                        "\nستُحذف المحادثات المحفوظة على هذا الجهاز."
                )
            },
            confirmButton = { TextButton(onClick = { dlg = ""; core.logout() }) { Text("خروج", color = RedCall) } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("إلغاء") } },
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("حذف الحساب نهائياً؟") },
            text = { Text("سيُحذف رقمك من الخادم ولن يمكن استرجاعه، وستُحذف كل بياناتك من هذا الجهاز.") },
            confirmButton = {
                TextButton(onClick = {
                    dlg = ""
                    scope.launch { core.deleteAccount()?.let { toast(ctx, it) } }
                }) { Text("حذف", color = RedCall) }
            },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("إلغاء") } },
        )
    }
}
