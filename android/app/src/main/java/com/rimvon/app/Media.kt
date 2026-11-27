@file:Suppress("DEPRECATION")

package com.rimvon.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

object Media {
    /** Files travel inside one encrypted envelope through the relay, so they are kept small. */
    const val MAX_BYTES = 3 * 1024 * 1024

    fun decodeSampled(path: String, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > maxDim * 2) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Downscales to 1280px, fixes rotation and writes a JPEG under 3 MB. */
    fun importImage(ctx: Context, uri: Uri, dir: File): File? {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        var sample = 1
        while (max(bo.outWidth, bo.outHeight) / sample > 2560) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val rot = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        val scale = min(1f, 1280f / max(bmp.width, bmp.height))
        if (rot != 0f || scale < 1f) {
            val m = Matrix().apply { postRotate(rot); postScale(scale, scale) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        var q = 80
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            out = bos.toByteArray()
            q -= 20
        } while (out.size > MAX_BYTES && q >= 20)
        if (out.size > MAX_BYTES) return null
        val f = File(dir, UUID.randomUUID().toString() + ".jpg")
        f.writeBytes(out)
        return f
    }

    /** Copies a picked file into app storage. Returns null if it is larger than [MAX_BYTES]. */
    fun importFile(ctx: Context, uri: Uri, dir: File): Pair<File, String>? {
        var name = "file"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size > MAX_BYTES) return null
        val ext = name.substringAfterLast('.', "bin").lowercase().let { if (it.matches(Regex("[a-z0-9]{1,5}"))) it else "bin" }
        val f = File(dir, UUID.randomUUID().toString() + "." + ext)
        var total = 0L
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { o ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { f.delete(); return null }
                    o.write(buf, 0, n)
                }
            }
        } ?: return null
        return f to name
    }

    fun openFile(ctx: Context, path: String) {
        try {
            val f = File(path)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "لا يوجد تطبيق لفتح هذا الملف", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "تعذّر فتح الملف", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Records AAC voice notes at 24 kbps mono (about 180 KB per minute). */
class VoiceRecorder(private val ctx: Context, private val dir: File) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = try {
        val f = File(dir, UUID.randomUUID().toString() + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(24_000)
        r.setAudioSamplingRate(16_000)
        r.setAudioChannels(1)
        r.setMaxDuration(180_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r
        file = f
        startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        cancel()
        false
    }

    /** Returns the file and its length in seconds, or null if too short / failed. */
    fun stop(): Pair<File, Int>? {
        val r = rec ?: return null
        val f = file
        rec = null
        file = null
        return try {
            r.stop()
            r.release()
            val secs = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            if (f != null && secs >= 1) f to secs else { f?.delete(); null }
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            f?.delete()
            null
        }
    }

    fun cancel() {
        val r = rec
        val f = file
        rec = null
        file = null
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
        f?.delete()
    }
}

object Player {
    private var mp: MediaPlayer? = null
    val current = MutableStateFlow<String?>(null)

    fun toggle(path: String) {
        if (current.value == path) { stop(); return }
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { this@Player.stop() }
                prepare()
                start()
            }
            current.value = path
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        current.value = null
    }
}
