package com.vuhpquang.zentask.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.vuhpquang.zentask.ZenTaskCore
import com.vuhpquang.zentask.model.Attachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Attachment bytes on the phone: a cache in app storage (`files/attachments/<sha256>`), photos shrunk (longest side
 * 2560 px, JPEG 80), and a sync pass that uploads what the server lacks and downloads the rest (kept offline).
 */
class AttachmentFiles(private val context: Context, private val core: ZenTaskCore, private val scope: CoroutineScope) {
    class TooLarge(name: String) : Exception("$name is larger than 10 MB. Attach a smaller file.")

    private val folder = File(context.filesDir, "attachments").apply { mkdirs() }
    private val _cached = MutableStateFlow(folder.list()?.filter { it.length == 64 }?.toSet().orEmpty())
    val cached: StateFlow<Set<String>> = _cached
    private val lock = Mutex()
    private var pending: Job? = null

    fun file(fileId: String): File? = File(folder, fileId).takeIf { fileId in _cached.value && it.exists() }

    /** Adds the picked [uri] to [taskId] (off the main thread). */
    suspend fun add(uri: Uri, taskId: String) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        var bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read $name")
        var w = 0
        var h = 0
        if (mime.startsWith("image/") && mime != "image/gif") {
            shrink(bytes)?.let { (b, size) ->
                bytes = b; w = size.first; h = size.second; mime = "image/jpeg"
                if (!name.endsWith(".jpg", true) && !name.endsWith(".jpeg", true)) name = name.substringBeforeLast('.') + ".jpg"
            }
        }
        if (bytes.size > MAX) throw TooLarge(name)
        val sha = sha256(bytes)
        File(folder, sha).writeBytes(bytes)
        _cached.value = _cached.value + sha
        core.attachmentActions.add(taskId, sha, name, mime, bytes.size.toLong(), w, h)
        syncSoon()
    }

    private fun shrink(bytes: ByteArray): Pair<ByteArray, Pair<Int, Int>>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray() to (bmp.width to bmp.height)
    }

    /** Opens [a] in another app (a copy under its real name, shared through the FileProvider). */
    fun open(a: Attachment) {
        val src = file(a.fileId) ?: return
        val dir = File(context.cacheDir, "open/${a.fileId}").apply { mkdirs() }
        val copy = File(dir, a.name.replace('/', '_'))
        if (!copy.exists()) src.copyTo(copy)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", copy)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(view, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun syncSoon() {
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) { delay(1000); sync() }
    }

    /** Upload cached files the server lacks; download needed files the phone lacks. Quiet when offline. */
    suspend fun sync() = lock.withLock {
        val token = core.token ?: return@withLock
        val base = ZenTaskCore.DEFAULT_SERVER + "/v1/files"
        try {
            val local = core.attachmentActions.neededFileIds().intersect(_cached.value)
            if (local.isNotEmpty()) {
                val body = """{"ids":[${local.joinToString(",") { "\"$it\"" }}]}"""
                val missing = request("$base/missing", "POST", token, body.toByteArray(), "application/json")?.decodeToString()
                    ?.let { Regex("[0-9a-f]{64}").findAll(it).map { m -> m.value }.toList() }.orEmpty()
                for (id in missing) request("$base/$id", "PUT", token, File(folder, id).readBytes(), "application/octet-stream")
            }
            for (id in core.attachmentActions.toDownload(_cached.value)) {
                val bytes = request("$base/$id", "GET", token, null, null) ?: continue
                if (sha256(bytes) != id) continue
                File(folder, id).writeBytes(bytes)
                _cached.value = _cached.value + id
            }
        } catch (e: Exception) {
            Log.i("ZenTask", "Attachment sync skipped: ${e.message}")
        }
    }

    private fun request(url: String, method: String, token: String, body: ByteArray?, type: String?): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                type?.let { c.setRequestProperty("Content-Type", it) }
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            return if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val MAX = 10 * 1024 * 1024
        private const val SIDE = 2560
        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
