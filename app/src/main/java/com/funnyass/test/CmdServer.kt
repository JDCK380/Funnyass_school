package com.funnyass.test

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.InetAddress

/** 本地 HTTP 命令端口（127.0.0.1:8080），供 adb forward + curl 直接控制 */
object CmdServer {
    interface Commands {
        fun connectDevice(mac: String)
        fun scanDevices()
        fun startBath()
        fun stopBath()
        fun disconnectDev()
        fun queryDev()
        fun collectDev(randomNumber: String = "")
        fun uploadDev(randomNumber: String, xfData: String)
        fun failDev(consumeDate: String)
    }

    private var handler: Handler? = null
    private var cmds: Commands? = null

    fun start(c: Commands) {
        cmds = c
        handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val server = ServerSocket(8080, 1, InetAddress.getLoopbackAddress())
                Logger.log("cmd server listening :8080")
                while (true) {
                    val sock = server.accept()
                    Thread { handle(sock) }.start()
                }
            } catch (e: Exception) {
                Logger.log("cmd server error: " + e.message)
            }
        }.start()
    }

    fun detach(c: Commands) {
        if (cmds === c) cmds = null
    }

    private fun handle(sock: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return
            val seg = line.split(" ")
            val fullPath = seg.getOrNull(1) ?: "/"
            val url = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")
            val params = query.split("&").filter { it.contains("=") }.associate {
                val kv = it.split("=", limit = 2)
                java.net.URLDecoder.decode(kv[0], "UTF-8") to java.net.URLDecoder.decode(kv.getOrNull(1) ?: "", "UTF-8")
            }
            val body = when (url) {
                "/log" -> Logger.getLog()
                "/status" -> Logger.getStatus()
                "/clear" -> { Logger.clear(); "cleared" }
                "/cmd" -> {
                    val action = params["action"] ?: ""
                    handler?.post {
                        when (action) {
                            "connect" -> cmds?.connectDevice(params["mac"] ?: "")
                            "scan" -> cmds?.scanDevices()
                            "start" -> cmds?.startBath()
                            "stop" -> cmds?.stopBath()
                            "disconnect" -> cmds?.disconnectDev()
                            "query" -> cmds?.queryDev()
                            "collect" -> cmds?.collectDev(params["random"] ?: "")
                            "upload" -> cmds?.uploadDev(params["random"] ?: "", params["data"] ?: "")
                            "fail" -> cmds?.failDev(params["date"] ?: "")
                        }
                    }
                    "cmd=" + action
                }
                else -> "unknown url=" + url
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
            val out = sock.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            // ignore
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
