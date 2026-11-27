@file:Suppress("DEPRECATION")

package com.rimvon.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Phase { Idle, Outgoing, Incoming, Connecting, Active }

data class CallState(
    val phase: Phase = Phase.Idle,
    val peer: String = "",
    val video: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val camOn: Boolean = true,
    val startedAt: Long = 0,
    val remoteVideo: Boolean = false,
)

private open class SdpObs : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * Peer-to-peer voice/video calls over WebRTC (media is DTLS-SRTP encrypted end to end).
 * Signaling goes through the relay inside the same end-to-end encrypted envelope as messages.
 * Every method that changes state runs on the Core worker thread.
 */
class CallManager(private val core: Core) {
    val state = MutableStateFlow(CallState())
    val remoteTrack = MutableStateFlow<VideoTrack?>(null)
    val localTrack = MutableStateFlow<VideoTrack?>(null)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val egl: EglBase by lazy { EglBase.create() }

    private val ctx get() = core.app
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var pendingOffer: String? = null
    private val pendingCands = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var timeout: Job? = null
    private var disconnectJob: Job? = null
    private var callStart = 0L
    private var outgoing = false
    private var audioModeBefore = AudioManager.MODE_NORMAL

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /* ---------------- public API (any thread) ---------------- */
    fun start(peer: String, video: Boolean) {
        core.scope.launch {
            if (state.value.phase != Phase.Idle) return@launch
            outgoing = true
            callStart = System.currentTimeMillis()
            state.value = CallState(Phase.Outgoing, peer, video, speaker = video)
            setupAudio(video)
            startRingback()
            timeout = core.scope.launch {
                delay(45_000)
                if (state.value.phase == Phase.Outgoing) endCall(true, "لا يوجد رد")
            }
            try {
                createPc(video)
                val offer = createOffer()
                val d = SessionDescription(offer.type, tuneOpus(offer.description))
                setLocal(d)
                sendSignal(JSONObject().put("s", "offer").put("sdp", d.description).put("v", video))
            } catch (e: Exception) {
                Log.w("Rimvon", "start call failed", e)
                endCall(false, "تعذّر بدء المكالمة")
            }
        }
    }

    fun accept() {
        core.scope.launch {
            val st = state.value
            if (st.phase != Phase.Incoming) return@launch
            stopRing()
            core.notifier.cancelCall()
            timeout?.cancel()
            state.value = st.copy(phase = Phase.Connecting)
            setupAudio(st.video)
            try {
                createPc(st.video)
                setRemote(SessionDescription(SessionDescription.Type.OFFER, pendingOffer!!))
                remoteSet = true
                flushCandidates()
                val ans = createAnswer()
                val d = SessionDescription(ans.type, tuneOpus(ans.description))
                setLocal(d)
                sendSignal(JSONObject().put("s", "answer").put("sdp", d.description))
            } catch (e: Exception) {
                Log.w("Rimvon", "accept failed", e)
                endCall(true, "فشل الاتصال")
            }
        }
    }

    /** Decline an incoming call or hang up an active/outgoing one. */
    fun hangup() { core.scope.launch { endCall(true, "") } }

    fun forceEnd() { core.scope.launch { endCall(false, "") } }

    fun toggleMute() {
        core.scope.launch {
            val m = !state.value.muted
            audioTrack?.setEnabled(!m)
            state.update { it.copy(muted = m) }
        }
    }

    fun toggleSpeaker() {
        core.scope.launch {
            val s = !state.value.speaker
            ctx.getSystemService(AudioManager::class.java).isSpeakerphoneOn = s
            state.update { it.copy(speaker = s) }
        }
    }

    fun toggleCamera() {
        core.scope.launch {
            val on = !state.value.camOn
            localTrack.value?.setEnabled(on)
            state.update { it.copy(camOn = on) }
        }
    }

    fun switchCamera() { core.scope.launch { capturer?.switchCamera(null) } }

    /* ---------------- signaling from the relay (worker thread) ---------------- */
    suspend fun onSignal(from: String, j: JSONObject, ts: Long) {
        val st = state.value
        when (j.optString("s")) {
            "offer" -> {
                val video = j.optBoolean("v")
                if (System.currentTimeMillis() - ts > 45_000) {
                    log(from, false, ts, 0, video, true)
                    core.notifier.missedCall(from, core.displayName(from))
                    return
                }
                if (st.phase != Phase.Idle) {
                    if (st.peer != from) core.sendCallSignal(from, JSONObject().put("s", "busy"))
                    return
                }
                pendingOffer = j.getString("sdp")
                pendingCands.clear()
                remoteSet = false
                outgoing = false
                callStart = System.currentTimeMillis()
                state.value = CallState(Phase.Incoming, from, video, speaker = video)
                startRing()
                core.notifier.incomingCall(core.displayName(from), video)
                timeout = core.scope.launch {
                    delay(45_000)
                    if (state.value.phase == Phase.Incoming) endCall(false, "")
                }
            }
            "answer" -> if (st.peer == from && st.phase == Phase.Outgoing && pc != null) {
                try {
                    setRemote(SessionDescription(SessionDescription.Type.ANSWER, j.getString("sdp")))
                    remoteSet = true
                    flushCandidates()
                    timeout?.cancel()
                    state.update { it.copy(phase = Phase.Connecting) }
                } catch (e: Exception) {
                    endCall(true, "فشل الاتصال")
                }
            }
            "cand" -> if (st.peer == from && st.phase != Phase.Idle) {
                val c = IceCandidate(j.optString("m"), j.optInt("i"), j.optString("c"))
                if (remoteSet && pc != null) pc?.addIceCandidate(c) else pendingCands.add(c)
            }
            "hangup" -> if (st.peer == from && st.phase != Phase.Idle) endCall(false, if (st.phase == Phase.Incoming) "" else "انتهت المكالمة")
            "reject" -> if (st.peer == from && st.phase != Phase.Idle) endCall(false, "تم رفض المكالمة")
            "busy" -> if (st.peer == from && st.phase != Phase.Idle) endCall(false, "الخط مشغول")
        }
    }

    fun onSignalFailed(peer: String, note: String) {
        core.scope.launch {
            val st = state.value
            if (st.phase == Phase.Outgoing && st.peer == peer) endCall(false, note)
        }
    }

    /* ---------------- WebRTC plumbing ---------------- */
    private fun sendSignal(j: JSONObject) { core.sendCallSignal(state.value.peer, j) }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPc(video: Boolean) {
        ensureFactory()
        val f = factory!!
        val cfg = PeerConnection.RTCConfiguration(core.iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val conn = f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("no peer connection")
        pc = conn
        val aSrc = f.createAudioSource(MediaConstraints())
        val aTrack = f.createAudioTrack("a0", aSrc)
        audioSource = aSrc
        audioTrack = aTrack
        conn.addTrack(aTrack, listOf("s0"))
        if (video && has(Manifest.permission.CAMERA)) startVideo(conn, f)
        else if (video) state.update { it.copy(camOn = false) }
    }

    private fun startVideo(conn: PeerConnection, f: PeerConnectionFactory) {
        val en = Camera2Enumerator(ctx)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
        val cap = en.createCapturer(name, null) ?: return
        val th = SurfaceTextureHelper.create("rimvon-capture", egl.eglBaseContext)
        val vs = f.createVideoSource(false)
        cap.initialize(th, ctx, vs.capturerObserver)
        cap.startCapture(640, 480, 20)
        helper = th
        videoSource = vs
        capturer = cap
        val vt = f.createVideoTrack("v0", vs)
        localTrack.value = vt
        val sender = conn.addTrack(vt, listOf("s0"))
        try {
            val p = sender.parameters
            for (e in p.encodings) e.maxBitrateBps = 400_000
            sender.setParameters(p)
        } catch (e: Exception) { Log.w("Rimvon", "bitrate cap failed", e) }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            core.scope.launch { onIce(s) }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {
            if (c == null) return
            core.scope.launch {
                if (state.value.phase != Phase.Idle) {
                    sendSignal(JSONObject().put("s", "cand").put("m", c.sdpMid).put("i", c.sdpMLineIndex).put("c", c.sdp))
                }
            }
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        override fun onTrack(t: RtpTransceiver?) {
            val tr = t?.receiver?.track()
            if (tr is VideoTrack) {
                remoteTrack.value = tr
                state.update { it.copy(remoteVideo = true) }
            }
        }
    }

    private fun onIce(s: PeerConnection.IceConnectionState?) {
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                disconnectJob?.cancel()
                if (state.value.phase == Phase.Connecting || state.value.phase == Phase.Outgoing) {
                    timeout?.cancel()
                    stopRingback()
                    state.update { it.copy(phase = Phase.Active, startedAt = System.currentTimeMillis()) }
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                disconnectJob?.cancel()
                disconnectJob = core.scope.launch {
                    delay(12_000)
                    if (state.value.phase != Phase.Idle) endCall(true, "انقطع الاتصال")
                }
            }
            PeerConnection.IceConnectionState.FAILED -> endCall(true, "فشل الاتصال")
            else -> {}
        }
    }

    private fun flushCandidates() {
        for (c in pendingCands) pc?.addIceCandidate(c)
        pendingCands.clear()
    }

    private suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        pc!!.createOffer(object : SdpObs() {
            override fun onCreateSuccess(d: SessionDescription?) { cont.resume(d!!) }
            override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException(e)) }
        }, MediaConstraints())
    }

    private suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        pc!!.createAnswer(object : SdpObs() {
            override fun onCreateSuccess(d: SessionDescription?) { cont.resume(d!!) }
            override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException(e)) }
        }, MediaConstraints())
    }

    private suspend fun setLocal(d: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc!!.setLocalDescription(object : SdpObs() {
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException(e)) }
        }, d)
    }

    private suspend fun setRemote(d: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc!!.setRemoteDescription(object : SdpObs() {
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException(e)) }
        }, d)
    }

    /** Low-bandwidth voice: Opus capped at 24 kbps with in-band FEC and DTX (silence suppression). */
    private fun tuneOpus(sdp: String): String {
        val pt = Regex("a=rtpmap:(\\d+) opus/48000/2").find(sdp)?.groupValues?.get(1) ?: return sdp
        val extra = "maxaveragebitrate=24000;useinbandfec=1;usedtx=1;stereo=0"
        return sdp.lines().joinToString("\r\n") { line ->
            if (line.startsWith("a=fmtp:$pt ") && !line.contains("maxaveragebitrate")) "$line;$extra" else line
        }
    }

    /* ---------------- audio / ringing ---------------- */
    private fun setupAudio(speaker: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java)
        audioModeBefore = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = speaker
        core.setCallService(true, state.value.video)
    }

    private fun restoreAudio() {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.isSpeakerphoneOn = false
        am.mode = audioModeBefore
        core.setCallService(false, false)
    }

    private fun startRing() {
        try {
            val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
        } catch (e: Exception) { Log.w("Rimvon", "ringtone failed", e) }
    }

    private fun stopRing() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }

    private fun startRingback() {
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: Exception) { Log.w("Rimvon", "ringback failed", e) }
    }

    private fun stopRingback() {
        try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
        tone = null
    }

    /* ---------------- teardown ---------------- */
    private fun log(peer: String, out: Boolean, ts: Long, dur: Int, video: Boolean, missed: Boolean) {
        core.db.addCall(CallLog(0, peer, out, ts, dur, video, missed))
        core.changed()
    }

    private fun endCall(sendSignal: Boolean, note: String) {
        val st = state.value
        if (st.phase == Phase.Idle) return
        timeout?.cancel()
        disconnectJob?.cancel()
        stopRing()
        stopRingback()
        core.notifier.cancelCall()
        if (sendSignal) {
            core.sendCallSignal(st.peer, JSONObject().put("s", if (st.phase == Phase.Incoming) "reject" else "hangup"))
        }
        val dur = if (st.startedAt > 0) ((System.currentTimeMillis() - st.startedAt) / 1000).toInt() else 0
        val missed = st.phase == Phase.Incoming && !sendSignal
        log(st.peer, outgoing, callStart, dur, st.video, missed)
        if (missed) core.notifier.missedCall(st.peer, core.displayName(st.peer))

        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        capturer = null
        localTrack.value = null
        remoteTrack.value = null
        try { pc?.close() } catch (_: Exception) {}
        pc?.dispose()
        pc = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        videoSource?.dispose(); videoSource = null
        helper?.dispose(); helper = null
        pendingOffer = null
        pendingCands.clear()
        remoteSet = false
        restoreAudio()
        state.value = CallState()
        if (note.isNotEmpty()) events.tryEmit(note)
    }
}
