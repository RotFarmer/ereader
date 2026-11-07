package com.rotfarmer.ereader.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateException(message: String) : Exception(message)

/**
 * Self-update from this repository's "latest" GitHub release (published by CI on every push).
 * The APK is installed over the current app with Android's PackageInstaller, so books, notes
 * and drawings are kept. This works because every build is signed with the same key and has a
 * higher version code.
 */
object Updater {
    const val REPO = "RotFarmer/ereader"
    private const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/tags/latest"
    private const val UA = "E-Reader-Android-app"

    const val PRIVATE_REPO_MESSAGE = "The app can't see the update because the GitHub repository is private. " +
        "Either make the repository public, or add a read-only GitHub token under Updates."

    class Release(val build: Long, val title: String, val notes: String, val apiUrl: String, val publicUrl: String, val size: Long)

    fun installedBuild(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun installedVersion(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Reads the latest release. Call off the main thread. */
    fun check(token: String?): Release {
        val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> {}
                401 -> throw UpdateException("GitHub rejected the token. Check it under Updates.")
                404 -> throw UpdateException(
                    if (token.isNullOrBlank()) PRIVATE_REPO_MESSAGE
                    else "No update found, or the token doesn't have access to $REPO (it needs Contents: read-only)."
                )
                403, 429 -> throw UpdateException("GitHub is limiting requests right now. Try again later.")
                else -> throw UpdateException("Couldn't check for updates (GitHub said $code).")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val name = json.optString("name")
            val body = json.optString("body")
            val build = Regex("versionCode:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("build (\\d+)").find(name)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw UpdateException("The latest release has no build number.")
            val assets = json.optJSONArray("assets") ?: throw UpdateException("The latest release has no APK yet.")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) {
                    return Release(build, name, body, a.getString("url"), a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            throw UpdateException("The latest release has no APK yet. It may still be building.")
        } catch (e: IOException) {
            throw UpdateException("Couldn't reach GitHub. Check your internet connection.")
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into the cache. Call off the main thread. */
    fun download(ctx: Context, r: Release, token: String?, progress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        val useToken = !token.isNullOrBlank()
        var url = if (useToken) r.apiUrl else r.publicUrl
        var auth = useToken
        try {
            for (hop in 0 until 6) {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", UA)
                    if (auth) {
                        setRequestProperty("Authorization", "Bearer ${token!!.trim()}")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw UpdateException("Download failed (bad redirect).")
                    url = URL(URL(url), next).toString()
                    // The redirect target is a pre-signed link; sending the token there would break it.
                    auth = false
                    c.disconnect()
                    continue
                }
                if (code != 200) {
                    c.disconnect()
                    throw UpdateException(if (code == 404 && !useToken) PRIVATE_REPO_MESSAGE else "Download failed (GitHub said $code).")
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != last) {
                                last = pct; progress(pct)
                            }
                        }
                    }
                }
                c.disconnect()
                return out
            }
        } catch (e: IOException) {
            throw UpdateException("The download was interrupted. Check your connection and try again.")
        }
        throw UpdateException("Download failed (too many redirects).")
    }

    /** Hands the APK to Android's installer. The system then asks you to confirm the update. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the installer's result and shows Android's "Update this app?" confirmation. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                Toast.makeText(ctx, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
