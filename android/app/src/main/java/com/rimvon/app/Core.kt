package com.rimvon.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.File
import java.io.IOException
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class NoUser : Exception()
class AuthError(val code: String) : Exception(code)

fun fmtNumber(id: String): String =
    if (id.length == 10) id.substring(0, 2) + "-" + id.substring(2, 6) + "-" + id.substring(6) else id

/** Keeps only digits and converts Arabic-Indic digits to Latin ones. */
fun digitsOnly(s: String): String = buildString {
    for (ch in s) if (ch.isDigit()) append(Character.digit(ch, 10))
}

fun errText(code: String): String = when (code) {
    "auth" -> "الرقم أو كلمة السر غير صحيحة"
    "locked" -> "تم قفل الحساب مؤقتاً بسبب محاولات كثيرة، حاول بعد 15 دقيقة"
    "rate" -> "محاولات كثيرة، انتظر قليلاً ثم حاول مجدداً"
    "bad" -> "بيانات غير صالحة"
    "offline" -> "لا يوجد اتصال بالخادم"
    else -> "تعذّر الاتصال بالخادم، تحقّق من العنوان والإنترنت"
}

fun previewOf(kind: String, text: String): String = when (kind) {
    "img" -> "📷 صورة"
    "voice" -> "🎤 رسالة صوتية"
    "file" -> "📎 " + text
    else -> text
}

/**
 * The brain of the app. All protocol work runs on one single thread (scope), so message
 * ordering is preserved and no locking is needed. The UI observes [version] and re-reads the DB.
 */
class Core(val app: Application) {
    val sp: SharedPreferences = app.getSharedPreferences("rimvon", Context.MODE_PRIVATE)
    private val vault = Vault(app)
    val db = Db(app)
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + worker)
    val notifier = Notifier(app)
    val relay = RelayClient(this)
    val call = CallManager(this)

    /** Bumped on every change to the database; composables key their reads on it. */
    val version = MutableStateFlow(0)
    val openPeer = MutableStateFlow<String?>(null)
    val loggedIn = MutableStateFlow(sp.getString("number", null) != null)
    val sessionLost = MutableStateFlow<String?>(null)
    @Volatile var foreground = false

    private class Pending(val type: String, val id: String?, val auth: String)

    @Volatile private var pending: Pending? = null
    @Volatile private var pendingHasPass = false
    @Volatile private var waiter: CompletableDeferred<String>? = null
    @Volatile private var opWaiter: CompletableDeferred<String>? = null
    private val lookups = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pubCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val keyRetries = ConcurrentHashMap<String, Int>()
    @Volatile private var priv: PrivateKey? = null
    @Volatile var pubB64: String = ""
        private set
    @Volatile private var ice: List<PeerConnection.IceServer> = emptyList()

    val mediaDir: File get() = File(app.filesDir, "media").apply { mkdirs() }

    fun changed() { version.update { it + 1 } }

    /* ---------------- account / settings ---------------- */
    fun number(): String? = sp.getString("number", null)
    fun myName(): String = sp.getString("name", "") ?: ""
    fun setMyName(n: String) { sp.edit().putString("name", n.trim()).apply() }
    fun hasPassword(): Boolean = sp.getBoolean("hasPass", false)
    fun lockEnabled(): Boolean = sp.getBoolean("lock", false)
    fun setLock(on: Boolean) { sp.edit().putBoolean("lock", on).apply() }

    fun servers(): List<String> =
        (sp.getString("servers", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun setServers(list: List<String>) {
        sp.edit().putString("servers", list.map { normalizeServer(it) }.filter { it.isNotEmpty() }.joinToString("\n")).apply()
    }

    fun normalizeServer(s: String): String {
        var x = s.trim().trimEnd('/')
        if (x.isEmpty()) return ""
        x = when {
            x.startsWith("https://") -> "wss://" + x.removePrefix("https://")
            x.startsWith("http://") -> "ws://" + x.removePrefix("http://")
            x.startsWith("wss://") || x.startsWith("ws://") -> x
            else -> "wss://$x"
        }
        return x
    }

    private fun savedAuth(): String? =
        sp.getString("auth", null)?.let { try { String(vault.open(it)) } catch (e: Exception) { null } }

    private fun ensureKeys() {
        if (priv != null) return
        val sealed = sp.getString("priv", null)
        val pubS = sp.getString("pub", null)
        if (sealed != null && pubS != null) {
            try {
                priv = Crypto.parsePriv(vault.open(sealed))
                pubB64 = pubS
                return
            } catch (e: Exception) { Log.w("Rimvon", "key restore failed", e) }
        }
        val kp = Crypto.genKeyPair()
        priv = kp.private
        pubB64 = Crypto.pubB64(kp.public)
        sp.edit().putString("priv", vault.seal(kp.private.encoded)).putString("pub", pubB64).apply()
    }

    private fun privKey(): PrivateKey { ensureKeys(); return priv!! }

    fun myPub(): String { ensureKeys(); return pubB64 }

    /** First message sent on every new socket. */
    fun helloJson(): JSONObject? {
        ensureKeys()
        val p = pending
        if (p != null) {
            val j = JSONObject().put("type", p.type).put("auth", p.auth).put("pub", pubB64)
            if (p.id != null) j.put("id", p.id)
            return j
        }
        val n = number() ?: return null
        val a = savedAuth() ?: return null
        return JSONObject().put("type", "login").put("id", n).put("auth", a).put("pub", pubB64)
    }

    /** Creates a new account (existingId == null) or logs in to an existing number. */
    suspend fun register(server: String, password: String?, existingId: String?): Result<String> =
        withContext(worker) {
            setServers(listOf(server))
            if (servers().isEmpty()) return@withContext Result.failure(IOException("no server"))
            ensureKeys()
            pending = Pending(if (existingId == null) "signup" else "login", existingId, password ?: Crypto.randomToken())
            pendingHasPass = password != null
            val d = CompletableDeferred<String>()
            waiter = d
            relay.stop()
            relay.start()
            try {
                Result.success(withTimeout(25_000) { d.await() })
            } catch (e: TimeoutCancellationException) {
                pending = null; waiter = null; relay.stop()
                Result.failure(IOException("timeout"))
            } catch (e: Exception) {
                pending = null; waiter = null; relay.stop()
                Result.failure(e)
            }
        }

    fun onReady(j: JSONObject) {
        scope.launch {
            val id = j.getString("id")
            parseIce(j.optJSONArray("ice"))
            val p = pending
            if (p != null) {
                sp.edit().putString("number", id)
                    .putString("auth", vault.seal(p.auth.toByteArray()))
                    .putBoolean("hasPass", pendingHasPass).apply()
                pending = null
                loggedIn.value = true
                waiter?.complete(id)
                waiter = null
            }
            resendPending()
        }
    }

    fun onAuthError(code: String) {
        scope.launch {
            val w = waiter
            if (w != null) {
                w.completeExceptionally(AuthError(code))
                waiter = null; pending = null
                relay.stop()
            } else if (code == "auth" || code == "locked") {
                relay.stop()
                sessionLost.value = "تعذّر تسجيل الدخول (كلمة السر تغيّرت؟). سجّل الدخول من جديد."
            }
        }
    }

    fun onServerError(code: String) {
        scope.launch { opWaiter?.complete(code) }
    }

    fun onKicked() {
        scope.launch {
            relay.stop()
            sessionLost.value = "تم تسجيل الدخول إلى هذا الرقم من جهاز آخر."
        }
    }

    fun logout() {
        relay.stop()
        call.forceEnd()
        try { app.stopService(Intent(app, RelayService::class.java)) } catch (_: Exception) {}
        try { mediaDir.deleteRecursively() } catch (_: Exception) {}
        db.wipe()
        sp.edit().clear().apply()
        priv = null
        pubB64 = ""
        pubCache.clear()
        notifier.cancelAll()
        loggedIn.value = false
        sessionLost.value = null
        openPeer.value = null
        changed()
    }

    private suspend fun op(msg: JSONObject): String = withContext(worker) {
        val d = CompletableDeferred<String>()
        opWaiter = d
        if (!relay.send(msg)) { opWaiter = null; return@withContext "offline" }
        try {
            withTimeout(15_000) { d.await() }
        } catch (e: TimeoutCancellationException) {
            "timeout"
        } finally {
            opWaiter = null
        }
    }

    /** Returns null on success or an error message. */
    suspend fun changePassword(newPass: String): String? {
        val old = savedAuth() ?: return "خطأ داخلي"
        val r = op(JSONObject().put("type", "setpass").put("old", old).put("auth", newPass))
        if (r != "ok") return errText(r)
        sp.edit().putString("auth", vault.seal(newPass.toByteArray())).putBoolean("hasPass", true).apply()
        return null
    }

    suspend fun deleteAccount(): String? {
        val a = savedAuth() ?: return "خطأ داخلي"
        val r = op(JSONObject().put("type", "delete").put("auth", a))
        if (r != "ok") return errText(r)
        logout()
        return null
    }

    fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, RelayService::class.java))
        } catch (e: Exception) { Log.w("Rimvon", "service start refused", e) }
    }

    fun setCallService(on: Boolean, video: Boolean) {
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, RelayService::class.java).setAction(if (on) "call_on" else "call_off").putExtra("video", video)
            )
        } catch (e: Exception) { Log.w("Rimvon", "call service update refused", e) }
    }

    /* ---------------- ICE ---------------- */
    private fun parseIce(arr: JSONArray?) {
        if (arr == null) return
        val out = ArrayList<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urlsAny = o.opt("urls")
            val urls = ArrayList<String>()
            if (urlsAny is JSONArray) for (k in 0 until urlsAny.length()) urls.add(urlsAny.getString(k))
            else if (urlsAny is String) urls.add(urlsAny)
            if (urls.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(urls)
            if (o.has("username")) b.setUsername(o.getString("username"))
            if (o.has("credential")) b.setPassword(o.getString("credential"))
            out.add(b.createIceServer())
        }
        if (out.isNotEmpty()) ice = out
    }

    fun iceServers(): List<PeerConnection.IceServer> =
        ice.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()) }

    /* ---------------- lookup / keys ---------------- */
    suspend fun lookup(id: String): JSONObject? {
        if (!relay.isOnline()) return null
        var first = false
        val d = lookups.getOrPut(id) { first = true; CompletableDeferred() }
        if (first) relay.send(JSONObject().put("type", "lookup").put("id", id))
        return try {
            withTimeout(8_000) { d.await() }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            lookups.remove(id)
        }
    }

    /** If the peer's key differs from the one we knew, warn in the chat. */
    private fun noteKey(peer: String, pub: String) {
        val c = db.contact(peer) ?: return
        if (c.pub == null) {
            db.setPub(peer, pub)
        } else if (c.pub != pub) {
            db.setPub(peer, pub)
            sysMessage(peer, "تغيّر مفتاح الأمان لهذا الرقم (ربما جهاز جديد). تحقّق من رمز الأمان في معلومات جهة الاتصال.")
        }
    }

    private suspend fun peerPub(peer: String): String {
        pubCache[peer]?.let { if (System.currentTimeMillis() - it.second < 120_000) return it.first }
        val r = lookup(peer) ?: throw IOException("offline")
        if (!r.optBoolean("exists")) throw NoUser()
        val pub = r.getString("pub")
        pubCache[peer] = pub to System.currentTimeMillis()
        noteKey(peer, pub)
        return pub
    }

    private fun sysMessage(peer: String, text: String) {
        db.insertMsg(Msg(0, UUID.randomUUID().toString(), peer, false, "sys", text, null, System.currentTimeMillis(), 0, null, false, 0))
        changed()
    }

    suspend fun addContact(idRaw: String, name: String): Result<Contact> = withContext(worker) {
        val id = digitsOnly(idRaw)
        if (id.length != 10) return@withContext Result.failure(IOException("الرقم يجب أن يتكون من 10 خانات"))
        if (id == number()) return@withContext Result.failure(IOException("هذا رقمك أنت"))
        db.contact(id)?.let { return@withContext Result.success(it) }
        if (!relay.isOnline()) return@withContext Result.failure(IOException("لا يوجد اتصال بالخادم"))
        val r = lookup(id) ?: return@withContext Result.failure(IOException("انتهت مهلة الاتصال"))
        if (!r.optBoolean("exists")) return@withContext Result.failure(IOException("لا يوجد حساب بهذا الرقم"))
        val c = Contact(id, name.trim().ifEmpty { fmtNumber(id) }, r.getString("pub"), false, false, System.currentTimeMillis())
        db.upsertContact(c)
        changed()
        Result.success(c)
    }

    private fun ensureContact(id: String, nameHint: String, pub: String?) {
        val c = db.contact(id)
        if (c == null) {
            db.upsertContact(Contact(id, nameHint.ifBlank { fmtNumber(id) }, pub, false, false, System.currentTimeMillis()))
        } else {
            if (nameHint.isNotBlank() && c.name == fmtNumber(id)) db.rename(id, nameHint)
            if (pub != null) noteKey(id, pub)
        }
    }

    fun displayName(id: String): String = db.contact(id)?.name ?: fmtNumber(id)

    /* ---------------- sending ---------------- */
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    private fun buildInner(m: Msg): JSONObject {
        val j = JSONObject().put("k", m.kind).put("n", myName()).put("ts", m.ts)
        if (m.reply != null) j.put("r", m.reply)
        if (m.kind == "text") {
            j.put("t", m.text)
        } else {
            j.put("f", m.text).put("dur", m.dur)
            val f = m.path?.let { File(it) }
            if (f != null && f.exists()) j.put("d", b64(f.readBytes()))
        }
        return j
    }

    private suspend fun transmit(m: Msg) {
        if (!relay.isOnline() || !inflight.add(m.mid)) return
        try {
            val pub = peerPub(m.peer)
            val key = Crypto.sharedKey(privKey(), pub)
            val env = Crypto.encrypt(key, buildInner(m).toString().toByteArray())
            relay.send(
                JSONObject().put("type", "msg").put("to", m.peer).put("mid", m.mid)
                    .put("env", env).put("rk", Crypto.fingerprint(pub))
            )
        } catch (e: NoUser) {
            db.setFailed(m.mid); changed()
        } catch (e: Exception) {
            Log.w("Rimvon", "transmit deferred: ${e.message}")
        } finally {
            inflight.remove(m.mid)
        }
    }

    private suspend fun transmitControl(peer: String, inner: JSONObject) {
        try {
            val pub = peerPub(peer)
            val key = Crypto.sharedKey(privKey(), pub)
            val env = Crypto.encrypt(key, inner.put("n", myName()).toString().toByteArray())
            relay.send(
                JSONObject().put("type", "msg").put("to", peer).put("mid", UUID.randomUUID().toString())
                    .put("env", env).put("rk", Crypto.fingerprint(pub))
            )
        } catch (e: Exception) { Log.w("Rimvon", "control message failed: ${e.message}") }
    }

    private fun resendPending() {
        scope.launch { for (m in db.pendingOut()) transmit(m) }
    }

    private fun queueOut(m: Msg) {
        db.insertMsg(m)
        db.touch(m.peer, m.ts)
        changed()
        scope.launch { transmit(m) }
    }

    fun sendText(peer: String, text: String, reply: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        queueOut(Msg(0, UUID.randomUUID().toString(), peer, true, "text", t, null, System.currentTimeMillis(), 0, reply, false, 0))
    }

    /** kind: img | voice | file. The file must already be inside [mediaDir]. */
    fun sendFile(peer: String, kind: String, file: File, name: String, dur: Int = 0, reply: String? = null) {
        queueOut(Msg(0, UUID.randomUUID().toString(), peer, true, kind, name, file.absolutePath, System.currentTimeMillis(), 0, reply, false, dur))
    }

    fun retry(m: Msg) {
        db.retryFailed(m.mid)
        changed()
        scope.launch { db.msgByMid(m.mid)?.let { transmit(it) } }
    }

    fun forward(m: Msg, to: String) {
        when (m.kind) {
            "text" -> sendText(to, m.text)
            "img", "voice", "file" -> {
                val src = m.path?.let { File(it) } ?: return
                if (!src.exists()) return
                val copy = File(mediaDir, UUID.randomUUID().toString() + "." + src.extension)
                src.copyTo(copy, overwrite = true)
                sendFile(to, m.kind, copy, m.text, m.dur)
            }
        }
    }

    private fun deleteLocal(m: Msg) {
        m.path?.let { try { File(it).delete() } catch (_: Exception) {} }
        db.deleteMsg(m.peer, m.mid)
        changed()
    }

    fun deleteForMe(m: Msg) = deleteLocal(m)

    /** Only works while online (the request is not queued). Returns false if offline. */
    fun deleteForEveryone(m: Msg): Boolean {
        if (!relay.isOnline()) return false
        if (m.out) scope.launch { transmitControl(m.peer, JSONObject().put("k", "del").put("m", m.mid)) }
        deleteLocal(m)
        return true
    }

    fun deleteChat(peer: String) {
        for (p in db.mediaPaths(peer)) try { File(p).delete() } catch (_: Exception) {}
        db.deleteChat(peer)
        changed()
    }

    private fun sendRcpt(peer: String, mid: String, st: String) {
        relay.send(JSONObject().put("type", "rcpt").put("to", peer).put("mid", mid).put("st", st))
    }

    fun openChat(peer: String) {
        openPeer.value = peer
        scope.launch { markReadAndNotify(peer) }
    }

    fun closeChat() { openPeer.value = null }

    private fun markReadAndNotify(peer: String) {
        val mids = db.markRead(peer)
        for (mid in mids) sendRcpt(peer, mid, "r")
        notifier.cancel(peer)
        if (mids.isNotEmpty()) changed()
    }

    /* ---------------- calls ---------------- */
    fun sendCallSignal(peer: String, j: JSONObject) {
        scope.launch {
            try {
                val pub = peerPub(peer)
                val key = Crypto.sharedKey(privKey(), pub)
                val env = Crypto.encrypt(key, j.toString().toByteArray())
                relay.send(JSONObject().put("type", "call").put("to", peer).put("env", env))
            } catch (e: Exception) {
                Log.w("Rimvon", "call signal failed: ${e.message}")
                if (j.optString("s") == "offer") call.onSignalFailed(peer, if (e is NoUser) "هذا الرقم غير موجود" else "تعذّر الاتصال")
            }
        }
    }

    /* ---------------- incoming ---------------- */
    fun onPacket(j: JSONObject) {
        scope.launch {
            try {
                when (j.optString("type")) {
                    "msg" -> onIncomingMsg(j)
                    "rcpt" -> {
                        db.updateStatus(j.getString("mid"), if (j.optString("st") == "r") 3 else 2)
                        relay.send(JSONObject().put("type", "ack").put("q", j.getString("q")))
                        changed()
                    }
                    "call" -> onIncomingCall(j)
                    "lookup" -> lookups[j.optString("id")]?.complete(j)
                    "sent" -> { db.updateStatus(j.getString("mid"), 1); changed() }
                    "nack" -> onNack(j)
                    "ice" -> parseIce(j.optJSONArray("ice"))
                    "ok" -> opWaiter?.complete("ok")
                    "callfail" -> call.onSignalFailed(j.optString("to"), "هذا الرقم غير موجود")
                }
            } catch (e: Exception) {
                Log.w("Rimvon", "packet error", e)
            }
        }
    }

    private suspend fun onNack(j: JSONObject) {
        val mid = j.optString("mid")
        if (j.optString("reason") == "key") {
            val m = db.msgByMid(mid) ?: return
            val n = (keyRetries[mid] ?: 0) + 1
            keyRetries[mid] = n
            if (n <= 3) {
                val pub = j.optString("pub")
                pubCache[m.peer] = pub to System.currentTimeMillis()
                noteKey(m.peer, pub)
                transmit(m)
                return
            }
        }
        db.setFailed(mid)
        changed()
    }

    private fun onIncomingCall(j: JSONObject) {
        val from = j.getString("from")
        val q = j.getString("q")
        relay.send(JSONObject().put("type", "ack").put("q", q))
        if (db.contact(from)?.blocked == true) return
        try {
            val key = Crypto.sharedKey(privKey(), j.getString("fpub"))
            val inner = JSONObject(String(Crypto.decrypt(key, j.getString("env"))))
            pubCache[from] = j.getString("fpub") to System.currentTimeMillis()
            scope.launch { call.onSignal(from, inner, j.optLong("ts", System.currentTimeMillis())) }
        } catch (e: Exception) {
            Log.w("Rimvon", "call decrypt failed", e)
        }
    }

    private fun safeExt(name: String, fallback: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return if (e.matches(Regex("[a-z0-9]{1,5}"))) e else fallback
    }

    private fun onIncomingMsg(j: JSONObject) {
        val from = j.getString("from")
        val q = j.getString("q")
        val mid = j.getString("mid")
        val fpub = j.getString("fpub")
        val ts = j.optLong("ts", System.currentTimeMillis())
        val ack = { relay.send(JSONObject().put("type", "ack").put("q", q)) }

        if (db.contact(from)?.blocked == true) { ack(); return }
        var inner: JSONObject? = null
        try {
            val key = Crypto.sharedKey(privKey(), fpub)
            inner = JSONObject(String(Crypto.decrypt(key, j.getString("env")), Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w("Rimvon", "decrypt failed", e)
        }
        if (inner == null) {
            ensureContact(from, "", null)
            sysMessage(from, "تعذّر فك تشفير رسالة (ربما غُيّر مفتاح المرسل).")
            ack()
            return
        }

        ensureContact(from, inner.optString("n"), fpub)
        pubCache[from] = fpub to System.currentTimeMillis()
        val kind = inner.optString("k")
        if (kind == "del") {
            db.msgOfPeer(from, inner.optString("m"))?.let { if (!it.out) deleteLocal(it) }
            ack()
            return
        }

        val reply = inner.optString("r").ifEmpty { null }
        val shown = openPeer.value == from && foreground
        val msg: Msg? = when (kind) {
            "text" -> Msg(0, mid, from, false, "text", inner.optString("t"), null, ts, 0, reply, !shown, 0)
            "img", "voice", "file" -> {
                val name = inner.optString("f")
                val ext = safeExt(name, if (kind == "img") "jpg" else if (kind == "voice") "m4a" else "bin")
                val data = inner.optString("d")
                if (data.isEmpty()) {
                    null
                } else {
                    val f = File(mediaDir, UUID.randomUUID().toString() + "." + ext)
                    f.writeBytes(unb64(data))
                    Msg(0, mid, from, false, kind, name, f.absolutePath, ts, 0, reply, !shown, inner.optInt("dur"))
                }
            }
            else -> null
        }
        if (msg != null && db.insertMsg(msg)) {
            db.touch(from, ts)
            sendRcpt(from, mid, "d")
            if (shown) {
                markReadAndNotify(from)
            } else {
                notifier.message(from, displayName(from), previewOf(msg.kind, msg.text))
            }
            changed()
        } else if (msg != null) {
            sendRcpt(from, mid, "d") // duplicate redelivery: just re-confirm
        }
        ack()
    }

    /* ---------------- misc ---------------- */
    fun inviteText(): String {
        val host = servers().firstOrNull()
            ?.replace("wss://", "https://")?.replace("ws://", "http://") ?: ""
        val n = number()?.let { fmtNumber(it) } ?: ""
        return "أنا على Rimvon للمراسلة والاتصال الآمن.\nرقمي: $n\nحمّل التطبيق: $host/rimvon.apk"
    }
}
