package com.rimvon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun VideoView(track: VideoTrack?, egl: EglBase, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(ctx).apply {
            init(egl.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderMediaOverlay(overlay)
        }
    }
    DisposableEffect(Unit) { onDispose { renderer.release() } }
    DisposableEffect(track) {
        track?.addSink(renderer)
        onDispose { track?.removeSink(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, bg: Color, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun CallScreen(onAccept: () -> Unit) {
    val core = RimvonApp.core
    val cm = core.call
    val st by cm.state.collectAsStateWithLifecycle()
    val remote by cm.remoteTrack.collectAsStateWithLifecycle()
    val local by cm.localTrack.collectAsStateWithLifecycle()
    val name = core.displayName(st.peer)
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val status = when (st.phase) {
        Phase.Outgoing -> "يتصل…"
        Phase.Incoming -> if (st.video) "مكالمة فيديو واردة" else "مكالمة صوتية واردة"
        Phase.Connecting -> "جارٍ الاتصال…"
        Phase.Active -> fmtDur(((now - st.startedAt) / 1000).toInt().coerceAtLeast(0))
        Phase.Idle -> ""
    }
    val glass = Color(0x33FFFFFF)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BlueDark, Blue)))) {
        val rt = remote
        if (st.video && rt != null) {
            VideoView(rt, cm.egl, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.fillMaxSize().padding(top = 72.dp, bottom = 40.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!(st.video && rt != null)) {
                Spacer(Modifier.height(40.dp))
                Avatar(name, st.peer, 128.dp)
                Spacer(Modifier.height(20.dp))
            }
            Text(name, color = Color.White, fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(status, color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("مشفّرة من طرف إلى طرف", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))

            if (st.phase == Phase.Incoming) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Filled.CallEnd, "رفض", RedCall) { cm.hangup() }
                    RoundButton(if (st.video) Icons.Filled.Videocam else Icons.Filled.Call, "رد", GreenCall, onClick = onAccept)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(if (st.muted) Icons.Filled.MicOff else Icons.Filled.Mic, "كتم", if (st.muted) Color.White else glass,
                        tint = if (st.muted) Blue else Color.White) { cm.toggleMute() }
                    RoundButton(if (st.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "السماعة", if (st.speaker) Color.White else glass,
                        tint = if (st.speaker) Blue else Color.White) { cm.toggleSpeaker() }
                    if (st.video) {
                        RoundButton(if (st.camOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "الكاميرا", if (st.camOn) glass else Color.White,
                            tint = if (st.camOn) Color.White else Blue) { cm.toggleCamera() }
                        RoundButton(Icons.Filled.Cameraswitch, "تبديل", glass) { cm.switchCamera() }
                    }
                }
                Spacer(Modifier.height(28.dp))
                RoundButton(Icons.Filled.CallEnd, "إنهاء", RedCall) { cm.hangup() }
            }
        }
        val lt = local
        if (st.video && lt != null && st.camOn) {
            VideoView(
                lt, cm.egl, mirror = true, overlay = true,
                modifier = Modifier.padding(top = 56.dp, start = 16.dp).size(width = 100.dp, height = 140.dp)
                    .clip(RoundedCornerShape(12.dp)).align(Alignment.TopStart),
            )
        }
    }
}
