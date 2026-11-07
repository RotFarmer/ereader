package com.rotfarmer.ereader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Shown (in its own process) when the app crashes, so the error can be copied and sent to the
 * developer. Deliberately uses only plain Android widgets so it can't crash itself.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT) ?: lastReport(this) ?: "No crash report found."
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF2, 0xF0, 0xE9))
            setPadding(dp(18), dp(36), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "E-Reader crashed"
            textSize = 22f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Tap Copy and paste this report to the developer (or Share it). Your books and notes are safe."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(10))
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("E-Reader crash", report))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report"))
            }
        })
        buttons.addView(Button(this).apply {
            text = "Restart app"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(it)
                }
                finish()
            }
        })
        root.addView(buttons)
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = report
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(10))
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    companion object {
        private const val EXTRA_REPORT = "report"
        private const val FILE = "last_crash.txt"

        fun lastReport(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()

        fun hasReport(ctx: Context) = File(ctx.filesDir, FILE).exists()

        /** Installs a handler that records any crash and shows it on screen. */
        fun install(app: Context) {
            if (isCrashProcess()) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    val report = buildReport(app, thread, error)
                    File(app.filesDir, FILE).writeText(report)
                    app.startActivity(
                        Intent(app, CrashActivity::class.java)
                            .putExtra(EXTRA_REPORT, report)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                } catch (t: Throwable) {
                    previous?.uncaughtException(thread, error)
                }
            }
        }

        private fun isCrashProcess(): Boolean = try {
            Application.getProcessName().endsWith(":crash")
        } catch (e: Throwable) {
            false
        }

        private fun buildReport(ctx: Context, thread: Thread, error: Throwable): String {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val pi = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            } catch (e: Exception) {
                null
            }
            return buildString {
                append("App: ${pi?.versionName} (build ${pi?.longVersionCode})\n")
                append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("Thread: ${thread.name}\n")
                append("Time: ${java.util.Date()}\n\n")
                append(sw.toString().take(30_000))
            }
        }
    }
}

private typealias Application = android.app.Application
