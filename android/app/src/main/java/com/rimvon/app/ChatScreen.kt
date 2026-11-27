@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.rimvon.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val chatBg = Color(0xFFF1F5F9)

@Composable
fun ChatScreen(peer: String, onBack: () -> Unit, onInfo: () -> Unit, onCall: (Boolean) -> Unit) {
    val core = RimvonApp.core
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val ver by core.version.collectAsStateWithLifecycle()
    val conn by core.relay.state.collectAsStateWithLifecycle()
    val contact = remember(ver, peer) { core.db.contact(peer) }
    val msgs = remember(ver, peer) { core.db.messages(peer) }
    val byMid = remember(msgs) { msgs.associateBy { it.mid } }
    val name = contact?.name ?: fmtNumber(peer)

    var text by rememberSaveable(peer) { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Msg?>(null) }
    var actions by remember { mutableStateOf<Msg?>(null) }
    var forwardMsg by remember { mutableStateOf<Msg?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceRecorder(ctx, core.mediaDir) }
    val listState = rememberLazyListState()

    DisposableEffect(peer) {
        core.openChat(peer)
        onDispose { core.closeChat(); recorder.cancel() }
    }
    LaunchedEffect(ver) { core.openChat(peer) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.scrollToItem(msgs.lastIndex) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val r = replyTo?.mid
            replyTo = null
            scope.launch(Dispatchers.IO) {
                val f = Media.importImage(ctx, uri, core.mediaDir)
                if (f == null) withContext(Dispatchers.Main) { toast(ctx, "تعذّر إرسال الصورة") }
                else core.sendFile(peer, "img", f, "صورة.jpg", reply = r)
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val r = replyTo?.mid
            replyTo = null
            scope.launch(Dispatchers.IO) {
                val res = Media.importFile(ctx, uri, core.mediaDir)
                if (res == null) withContext(Dispatchers.Main) { toast(ctx, "الملف أكبر من 3 ميغابايت") }
                else core.sendFile(peer, "file", res.first, res.second, reply = r)
            }
        }
    }

    val beginRecording: () -> Unit = {
        if (recorder.start()) {
            recSecs = 0
            recording = true
        } else {
            toast(ctx, "تعذّر بدء التسجيل")
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginRecording() else toast(ctx, "اسمح للتطبيق باستخدام الميكروفون لتسجيل الرسائل الصوتية")
    }
    val finishRecording: () -> Unit = {
        recording = false
        val r = recorder.stop()
        if (r != null) core.sendFile(peer, "voice", r.first, "رسالة صوتية", r.second, replyTo?.mid)
        replyTo = null
    }
    LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            recSecs++
            if (recSecs >= 178) finishRecording()
        }
    }
    val sendText: () -> Unit = {
        if (text.isNotBlank()) {
            core.sendText(peer, text, replyTo?.mid)
            text = ""
            replyTo = null
        }
    }

    Column(Modifier.fillMaxSize().background(chatBg)) {
        // ----- top bar -----
        Row(
            Modifier.fillMaxWidth().background(Blue).height(60.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Color.White) }
            Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onInfo), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, peer, 38.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtNumber(peer), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
            IconButton(onClick = { onCall(true) }) { Icon(Icons.Filled.Videocam, "فيديو", tint = Color.White) }
            IconButton(onClick = { onCall(false) }) { Icon(Icons.Filled.Call, "اتصال", tint = Color.White) }
        }
        ConnBanner(conn)

        // ----- messages -----
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(msgs, key = { it.id }) { m ->
                MessageItem(
                    m = m,
                    replied = m.reply?.let { byMid[it] },
                    onLong = { if (m.kind != "sys") actions = m },
                    onImage = { viewer = it },
                    onRetry = { core.retry(m) },
                )
            }
        }

        // ----- input -----
        val reply = replyTo
        if (reply != null) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(32.dp).background(Blue))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (reply.out) "أنت" else name, fontSize = 12.sp, color = Blue, fontWeight = FontWeight.Medium)
                    Text(previewOf(reply.kind, reply.text), fontSize = 13.sp, color = TextGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, "إلغاء", tint = TextGray) }
            }
        }
        HorizontalDivider(color = GrayLine)
        when {
            contact?.blocked == true -> Row(
                Modifier.fillMaxWidth().background(Color.White).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("هذا الرقم محظور", modifier = Modifier.weight(1f), color = RedCall)
                TextButton(onClick = { core.db.setBlocked(peer, false); core.changed() }) { Text("إلغاء الحظر") }
            }
            recording -> Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { recorder.cancel(); recording = false }) { Icon(Icons.Filled.Delete, "إلغاء", tint = RedCall) }
                Box(Modifier.size(10.dp).clip(CircleShape).background(RedCall))
                Spacer(Modifier.width(8.dp))
                Text("جاري التسجيل  " + fmtDur(recSecs), modifier = Modifier.weight(1f), fontSize = 16.sp)
                IconButton(onClick = finishRecording) { Icon(Icons.AutoMirrored.Filled.Send, "إرسال", tint = Blue) }
            }
            else -> Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.AttachFile, "إرفاق", tint = TextGray) }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("صورة") }, onClick = { attachMenu = false; pickImage.launch("image/*") })
                        DropdownMenuItem(text = { Text("ملف") }, onClick = { attachMenu = false; pickFile.launch("*/*") })
                    }
                }
                TextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالة…", color = TextGray) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = GrayBg, unfocusedContainerColor = GrayBg,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (text.isNotBlank()) {
                    IconButton(onClick = sendText) { Icon(Icons.AutoMirrored.Filled.Send, "إرسال", tint = Blue) }
                } else {
                    IconButton(onClick = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) beginRecording() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Filled.Mic, "تسجيل", tint = Blue) }
                }
            }
        }
    }

    // ----- message actions -----
    actions?.let { m ->
        AlertDialog(
            onDismissRequest = { actions = null },
            confirmButton = {},
            text = {
                Column {
                    TextButton(onClick = { replyTo = m; actions = null }) { Text("رد") }
                    if (m.kind == "text") {
                        TextButton(onClick = { clip.setText(AnnotatedString(m.text)); actions = null; toast(ctx, "تم النسخ") }) { Text("نسخ") }
                    }
                    TextButton(onClick = { forwardMsg = m; actions = null }) { Text("إعادة توجيه") }
                    TextButton(onClick = { core.deleteForMe(m); actions = null }) { Text("حذف لدي") }
                    if (m.out) {
                        TextButton(onClick = {
                            if (!core.deleteForEveryone(m)) toast(ctx, "تحتاج اتصالاً بالخادم لحذف الرسالة عند الجميع")
                            actions = null
                        }) { Text("حذف للجميع", color = RedCall) }
                    }
                }
            },
        )
    }

    forwardMsg?.let { m ->
        val all = remember { core.db.contacts() }
        AlertDialog(
            onDismissRequest = { forwardMsg = null },
            title = { Text("إعادة توجيه إلى") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { forwardMsg = null }) { Text("إلغاء") } },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    for (c in all) {
                        Row(
                            Modifier.fillMaxWidth().clickable { core.forward(m, c.id); forwardMsg = null; toast(ctx, "تمت إعادة التوجيه") }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.name, c.id, 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, fontSize = 16.sp)
                        }
                    }
                }
            },
        )
    }

    viewer?.let { path ->
        Dialog(onDismissRequest = { viewer = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val bmp by produceState<ImageBitmap?>(null, path) {
                value = withContext(Dispatchers.IO) { Media.decodeSampled(path, 1600)?.asImageBitmap() }
            }
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewer = null }, contentAlignment = Alignment.Center) {
                bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
    }
}

@Composable
private fun MessageItem(m: Msg, replied: Msg?, onLong: () -> Unit, onImage: (String) -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    if (m.kind == "sys") {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                m.text, fontSize = 12.sp, color = Color(0xFF5B6B7B),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFE3EAF1)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        // The layout is right-to-left, so Start = right side: my messages on the right, theirs on the left.
        horizontalArrangement = if (m.out) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .background(if (m.out) BlueLight else Color.White)
                .combinedClickable(
                    onClick = {
                        val p = m.path
                        if (p != null && m.kind == "img") onImage(p)
                        else if (p != null && m.kind == "file") Media.openFile(ctx, p)
                    },
                    onLongClick = onLong,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (m.reply != null) {
                Row(Modifier.padding(bottom = 4.dp)) {
                    Box(Modifier.width(3.dp).height(28.dp).background(Blue))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (replied != null) previewOf(replied.kind, replied.text) else "رسالة محذوفة",
                        fontSize = 12.sp, color = TextGray, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (m.kind) {
                "img" -> m.path?.let { ImageThumb(it) }
                "voice" -> VoiceRow(m)
                "file" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AttachFile, null, tint = Blue)
                    Spacer(Modifier.width(6.dp))
                    Text(m.text, fontSize = 15.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> Text(m.text, fontSize = 16.sp, color = Ink)
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtClock(m.ts), fontSize = 11.sp, color = TextGray)
                if (m.out) {
                    Spacer(Modifier.width(4.dp))
                    when (m.status) {
                        -1 -> Icon(Icons.Filled.Warning, "فشل — اضغط لإعادة المحاولة", tint = RedCall, modifier = Modifier.size(15.dp).clickable(onClick = onRetry))
                        0 -> Icon(Icons.Filled.Schedule, null, tint = TextGray, modifier = Modifier.size(14.dp))
                        1 -> Icon(Icons.Filled.Done, null, tint = TextGray, modifier = Modifier.size(15.dp))
                        2 -> Icon(Icons.Filled.DoneAll, null, tint = TextGray, modifier = Modifier.size(15.dp))
                        else -> Icon(Icons.Filled.DoneAll, null, tint = Blue, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageThumb(path: String) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { Media.decodeSampled(path, 600)?.asImageBitmap() }
    }
    Box(Modifier.size(width = 220.dp, height = 180.dp).clip(RoundedCornerShape(10.dp)).background(GrayBg)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun VoiceRow(m: Msg) {
    val cur by Player.current.collectAsStateWithLifecycle()
    val playing = cur != null && cur == m.path
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { m.path?.let { Player.toggle(it) } }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "تشغيل", tint = Blue, modifier = Modifier.size(32.dp))
        }
        Text("▁▃▅▇▅▃▂▃▅▃▂", color = Blue, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(fmtDur(m.dur), fontSize = 13.sp, color = TextGray)
    }
}
