package com.rimvon.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface Route {
    data class Chat(val peer: String) : Route
    data class Info(val peer: String) : Route
    data object Servers : Route
}

class MainActivity : FragmentActivity() {
    private val core get() = RimvonApp.core
    private var afterPerm: (() -> Unit)? = null
    val locked = MutableStateFlow(false)

    companion object {
        val openPeerRequest = MutableStateFlow<String?>(null)
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val go = afterPerm
            afterPerm = null
            if (has(Manifest.permission.RECORD_AUDIO)) go?.invoke()
            else toast(this, "اسمح للتطبيق باستخدام الميكروفون لإجراء المكالمات")
        }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        locked.value = core.lockEnabled() && core.loggedIn.value
        if (core.loggedIn.value) core.startService()
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { RimvonTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("peer")?.let { openPeerRequest.value = it }
    }

    override fun onStart() {
        super.onStart()
        core.foreground = true
        core.openPeer.value?.let { core.openChat(it) }
    }

    override fun onStop() {
        core.foreground = false
        if (core.lockEnabled() && core.call.state.value.phase == Phase.Idle) locked.value = true
        super.onStop()
    }

    /* ---------- actions used by the UI ---------- */
    private fun withCallPerms(video: Boolean, go: () -> Unit) {
        val need = ArrayList<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (video && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (need.isEmpty()) {
            go()
        } else {
            afterPerm = go
            permLauncher.launch(need.toTypedArray())
        }
    }

    fun placeCall(peer: String, video: Boolean) {
        if (core.call.state.value.phase != Phase.Idle) return
        if (!core.relay.isOnline()) { toast(this, "لا يوجد اتصال بالخادم"); return }
        withCallPerms(video) { core.call.start(peer, video) }
    }

    fun acceptCall() {
        withCallPerms(core.call.state.value.video) { core.call.accept() }
    }

    fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, core.inviteText())
        startActivity(Intent.createChooser(send, "دعوة الأصدقاء"))
    }

    fun requestBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast(this, "التطبيق مستثنى من توفير البطارية بالفعل")
        } else {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun toggleLock(want: Boolean): Boolean {
        if (want && BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            toast(this, "اضبط بصمة أو قفل شاشة في إعدادات الهاتف أولاً")
            return false
        }
        core.setLock(want)
        return true
    }

    fun authenticate(onOk: () -> Unit) {
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            core.setLock(false)
            onOk()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("فتح Rimvon")
            .setAllowedAuthenticators(authenticators)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("إلغاء") }
            .build()
        BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
            },
        ).authenticate(info)
    }
}

@Composable
fun AppRoot(act: MainActivity) {
    val core = RimvonApp.core
    val ctx = LocalContext.current
    val loggedIn by core.loggedIn.collectAsStateWithLifecycle()
    val lost by core.sessionLost.collectAsStateWithLifecycle()
    val call by core.call.state.collectAsStateWithLifecycle()
    val locked by act.locked.collectAsStateWithLifecycle()
    val openReq by MainActivity.openPeerRequest.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Route>() }
    var showNumber by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { core.call.events.collect { toast(ctx, it) } }
    LaunchedEffect(openReq) {
        val p = openReq
        if (p != null) {
            if (core.loggedIn.value) { stack.clear(); stack.add(Route.Chat(p)) }
            MainActivity.openPeerRequest.value = null
        }
    }
    LaunchedEffect(loggedIn) { if (!loggedIn) stack.clear() }
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty() && call.phase == Phase.Idle) { pop() }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        when {
            !loggedIn -> AuthFlow(act) { showNumber = it }
            showNumber -> NumberScreen(
                number = core.number() ?: "",
                onContinue = { showNumber = false },
                onInvite = { act.share() },
            )
            locked && call.phase == Phase.Idle -> LockScreen { act.authenticate { act.locked.value = false } }
            else -> {
                when (val top = stack.lastOrNull()) {
                    null -> MainScreen(
                        onChat = { stack.add(Route.Chat(it)) },
                        onInfo = { stack.add(Route.Info(it)) },
                        onCall = { p, v -> act.placeCall(p, v) },
                        onServers = { stack.add(Route.Servers) },
                        onShare = { act.share() },
                        onBattery = { act.requestBattery() },
                        onToggleLock = { act.toggleLock(it) },
                    )
                    is Route.Chat -> ChatScreen(
                        peer = top.peer,
                        onBack = pop,
                        onInfo = { stack.add(Route.Info(top.peer)) },
                        onCall = { v -> act.placeCall(top.peer, v) },
                    )
                    is Route.Info -> ContactInfoScreen(
                        peer = top.peer,
                        onBack = pop,
                        onChat = {
                            pop()
                            if (stack.lastOrNull() != Route.Chat(top.peer)) stack.add(Route.Chat(top.peer))
                        },
                        onCall = { v -> act.placeCall(top.peer, v) },
                        onDeleted = { stack.clear() },
                    )
                    Route.Servers -> ServersScreen(onBack = pop)
                }
            }
        }
        if (call.phase != Phase.Idle) CallScreen(onAccept = { act.acceptCall() })
        lost?.let { msg ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("انتهت الجلسة") },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { core.logout() }) { Text("حسناً") } },
            )
        }
    }
}

@Composable
private fun AuthFlow(act: MainActivity, onNewAccount: (Boolean) -> Unit) {
    val core = RimvonApp.core
    var step by rememberSaveable { mutableStateOf("welcome") }
    var server by rememberSaveable { mutableStateOf(core.servers().firstOrNull() ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun register(pass: String?, id: String?) {
        busy = true
        error = null
        onNewAccount(id == null)
        scope.launch {
            val r = core.register(server, pass, id)
            busy = false
            r.onSuccess { core.startService() }
            r.onFailure { e ->
                onNewAccount(false)
                error = if (e is AuthError) errText(e.code) else errText("timeout")
            }
        }
    }

    when (step) {
        "welcome" -> WelcomeScreen(
            server = server, onServer = { server = it },
            onNew = { error = null; step = "password" },
            onLogin = { error = null; step = "login" },
        )
        "password" -> PasswordScreen(
            busy = busy, error = error,
            onBack = { step = "welcome" },
            onContinue = { register(it, null) },
            onSkip = { register(null, null) },
        )
        else -> LoginScreen(
            server = server, onServer = { server = it }, busy = busy, error = error,
            onBack = { step = "welcome" },
            onLogin = { id, p -> register(p, id) },
        )
    }
}
