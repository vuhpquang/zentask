package com.vuhpquang.zentask.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.vuhpquang.zentask.update.AppUpdates
import com.vuhpquang.zentask.update.AvailableUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update: reads the latest GitHub release, downloads `ZenTask-X.Y.Z-android.apk` and installs it
 * with [PackageInstaller]. Android asks the owner to confirm the install (and, the first time, to allow
 * ZenTask to install apps); the APK must be signed with the same key as the installed app.
 */
class AppUpdater(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val update: AvailableUpdate) : State
        data class Downloading(val percent: Int) : State
        data object Installing : State
        /** Android needs "Install unknown apps" allowed for ZenTask first. */
        data object NeedsPermission : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("zentask.updates", Context.MODE_PRIVATE)

    val currentVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    /** Debug builds (`.dev`) are a different app: the release APK would install next to them. */
    private val isRelease = context.packageName == RELEASE_PACKAGE

    /** Checks at most every 6 hours (on app start/resume). */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY) return
        if (_state.value !is State.Idle && _state.value !is State.UpToDate && _state.value !is State.Failed) return
        check()
    }

    suspend fun check() {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return
        _state.value = State.Checking
        _state.value = try {
            val body = withContext(Dispatchers.IO) { get(AppUpdates.LATEST_URL) }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            AppUpdates.parse(body, AppUpdates.Platform.ANDROID, currentVersion)?.let { State.Available(it) } ?: State.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            State.Failed("Couldn't check for updates. Check the connection and try again.")
        }
    }

    /** Downloads and hands the APK to the system installer. */
    suspend fun install() {
        val update = (_state.value as? State.Available)?.update
            ?: pending.takeIf { _state.value is State.NeedsPermission } ?: return
        pending = update
        if (!isRelease) {
            _state.value = State.Failed("Updates install on the release build only")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        try {
            val apk = withContext(Dispatchers.IO) { download(update.downloadUrl) }
            _state.value = State.Installing
            withContext(Dispatchers.IO) { commit(apk) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update install failed", e)
            _state.value = State.Failed("The update didn't download. Try again.")
        }
    }

    /** Back from the "Install unknown apps" screen: carry on if it was allowed. */
    suspend fun resumeAfterPermission() {
        if (_state.value is State.NeedsPermission && context.packageManager.canRequestPackageInstalls()) install()
    }

    private var pending: AvailableUpdate? = null

    internal fun installerResult(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> State.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> pending?.let { State.Available(it) } ?: State.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                State.Failed("Android refused the update: it isn't signed like the installed app. Install it from the release page once.")
            else -> State.Failed("Android couldn't install the update${message?.let { ": $it" } ?: ""}")
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "ZenTask-Android/$currentVersion")
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "zentask.apk")
        // GitHub answers with a redirect to its download host; HttpURLConnection follows it (both https).
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "ZenTask-Android/$currentVersion")
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            _state.value = State.Downloading(0)
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = State.Downloading((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return file
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Skips the confirmation when ZenTask itself installed the current copy (Android 12+ rule).
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("zentask.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    companion object {
        private const val TAG = "ZenTaskUpdater"
        private const val RELEASE_PACKAGE = "com.vuhpquang.zentask"
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val CHECK_EVERY = 6 * 60 * 60 * 1000L
    }
}

/** PackageInstaller's answers: show its confirmation screen, or report the result. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val zen = (context.applicationContext as ZenTaskApplication).zen
        zen.updater.installerResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
