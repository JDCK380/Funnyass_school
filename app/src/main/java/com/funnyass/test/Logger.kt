package com.funnyass.test

import android.util.Log

/** 日志：logcat + 内存缓冲（供 CLI 实时读取） */
object Logger {
    private val buf = StringBuilder()
    private var status = "idle"

    @Synchronized
    fun log(s: String) {
        Log.d("QZXY", s)
        buf.append(s).append("\n")
    }

    @Synchronized
    fun status(s: String) {
        status = s
        log("[status] " + s)
    }

    @Synchronized
    fun getLog(): String = buf.toString()

    @Synchronized
    fun getStatus(): String = status

    @Synchronized
    fun clear() {
        buf.setLength(0)
    }
}
