package com.rotfarmer.ereader

import android.app.Application
import android.content.Context
import com.rotfarmer.ereader.data.Db
import com.rotfarmer.ereader.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

class App : Application() {
    val db by lazy { Db(this) }
    val settings by lazy { Settings(this) }

    val booksDir: File get() = File(filesDir, "books").apply { mkdirs() }
    val coversDir: File get() = File(filesDir, "covers").apply { mkdirs() }
    val excerptsDir: File get() = File(filesDir, "excerpts").apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        CrashActivity.install(this)
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set

        fun of(ctx: Context): App = ctx.applicationContext as App

        /** Fire-and-forget database writes that must finish even if the screen closes. */
        val io = CoroutineScope(SupervisorJob() + Db.io)
    }
}
