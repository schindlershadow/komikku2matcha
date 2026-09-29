package com.schindler.k2m

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(val versionCode: Long, val versionName: String, val sha256: String, val size: Long, val apkUrl: String = "")

/**
 * Self-update from the server and from GitHub Releases: /api/app/version says what the server publishes
 * (android/publish.sh writes it, and uploads the same APK + JSON to a GitHub release), /app.apk is the APK.
 * Both are checked and the newest wins (the server on a tie: it's on the LAN). GitHub alone is enough, so
 * the app can update without a server. The download is checked against the published SHA-256, and Android only accepts
 * it if it's signed with the same key as the installed app.
 *
 * Installing needs "Install unknown apps" allowed for this app once. After that, Android 12+ installs
 * updates without a confirmation screen when this app installed the current version itself
 * (setRequireUserAction(USER_ACTION_NOT_REQUIRED) + UPDATE_PACKAGES_WITHOUT_USER_ACTION); the very first
 * self-update, replacing a version installed from the browser, still asks.
 */
object Updater {
    private const val GITHUB_LATEST = "https://github.com/schindlershadow/komikku2matcha/releases/latest/download"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    fun installedCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Newest candidate that beats [installed]; the first of equals, so list the preferred source first. */
    internal fun newest(installed: Long, candidates: List<UpdateInfo>): UpdateInfo? =
        candidates.filter { it.versionCode > installed }.maxByOrNull { it.versionCode }

    private fun fetchInfo(jsonUrl: String, apkUrl: String): UpdateInfo? =
        http.newCall(Request.Builder().url(jsonUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val j = JSONObject(r.body!!.string())
            UpdateInfo(j.getLong("versionCode"), j.getString("versionName"), j.getString("sha256"), j.optLong("size"), apkUrl)
        }

    /** The newest published version (server or GitHub) if it's newer than this one, else null. */
    suspend fun check(ctx: Context, api: Api): UpdateInfo? = withContext(Dispatchers.IO) {
        val server = runCatching { fetchInfo(api.baseUrl() + "/api/app/version", api.baseUrl() + "/app.apk") }.getOrNull()
        val github = runCatching { fetchInfo("$GITHUB_LATEST/komikku2matcha.apk.json", "$GITHUB_LATEST/komikku2matcha.apk") }.getOrNull()
        newest(installedCode(ctx), listOfNotNull(server, github))
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system page where "Install unknown apps" is allowed for this app. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Download, verify and hand the APK to the system installer; the result arrives in [UpdateReceiver]. */
    suspend fun install(ctx: Context, api: Api, info: UpdateInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val apk = File(ctx.cacheDir, "update.apk")
            val md = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(info.apkUrl.ifEmpty { api.baseUrl() + "/app.apk" }).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException("Update download failed: HTTP ${r.code}")
                val total = r.body!!.contentLength()
                apk.outputStream().use { out ->
                    val input = r.body!!.byteStream()
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                        done += n; RateMeter.add(n.toLong()); onProgress(done, total)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != info.sha256) { apk.delete(); throw ApiException("Update download corrupted (checksum mismatch); try again") }

            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val intent = Intent(ctx, UpdateReceiver::class.java).putExtra("version", info.versionName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
            apk.delete()
        }
}

/** Installer callbacks: asks the user to confirm when Android requires it, reports failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val version = intent.getStringExtra("version") ?: ""
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                               else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // From the foreground this opens the confirmation directly; from the background Android may block
                // that, so a notification carries it too.
                runCatching { ctx.startActivity(confirm) }
                Notify.action(ctx, "Update $version ready", "Tap to install", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Notify.result(ctx, "Updated to $version", "Komikku → Matcha was updated")
            else -> Notify.result(ctx, "Update failed",
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The installer reported an error", error = true)
        }
    }
}
