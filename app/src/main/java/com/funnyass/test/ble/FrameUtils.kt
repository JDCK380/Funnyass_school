package com.funnyass.test.ble

import kotlin.math.abs

/**
 * 公寓水控 BLE/经典蓝牙共用的帧协议（移植自 BlueUtils/CmdBtUtils/AnalyTools）。
 *
 * 二进制帧：[0]=0x60 [1]=0x00 [2]=dataLen+3 [3]=0x80 [4]=cmd [5]=0x00
 *           [6..6+len-1]=data [6+len]=crc [7+len]=0x16
 *   crc = (sum(data) + 0x80 + cmd) % 256
 *
 * 线上帧：0x23('#' ) + 二进制帧大写HEX文本(ASCII) + 0x0A('
')
 */
object FrameUtils {

    fun buildFrame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size + 3
        val frame = ByteArray(data.size + 8)
        frame[0] = 0x60
        frame[1] = 0x00
        frame[2] = len.toByte()
        frame[3] = 0x80.toByte()
        frame[4] = cmd.toByte()
        frame[5] = 0x00
        System.arraycopy(data, 0, frame, 6, data.size)
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        val crc = (sum + 0x80 + cmd) % 256
        frame[6 + data.size] = crc.toByte()
        frame[7 + data.size] = 0x16
        return frame
    }

    fun frameToWire(frame: ByteArray): ByteArray {
        val hex = toHex(frame).toByteArray(Charsets.US_ASCII)
        val out = ByteArray(hex.size + 2)
        out[0] = 0x23
        System.arraycopy(hex, 0, out, 1, hex.size)
        out[out.size - 1] = 0x0A
        return out
    }

    fun cmdToWire(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        frameToWire(buildFrame(cmd, data))

    /** 解析一个完整线帧（以 0x0A 结尾），返回二进制帧；校验失败返回 null */
    fun parseWire(wire: ByteArray): ByteArray? {
        if (wire.isEmpty() || wire[wire.size - 1].toInt() != 0x0A) return null
        var start = 0
        if (wire[0].toInt() == 0x23) start = 1
        val hexLen = wire.size - start - 1
        if (hexLen <= 0 || hexLen % 2 != 0) return null
        val hex = String(wire, start, hexLen, Charsets.US_ASCII)
        val frame = fromHex(hex) ?: return null
        return if (verifyFrame(frame)) frame else null
    }

    /** 校验二进制帧并返回 data 区（应答时 payload[0]==0x80 表示成功） */
    fun parsePayload(frame: ByteArray): ByteArray? {
        if (!verifyFrame(frame)) return null
        if (frame.size < 8) return null
        val dataLen = (frame[2].toInt() and 0xFF) - 3
        if (dataLen < 0) return null
        val payload = ByteArray(dataLen)
        System.arraycopy(frame, 6, payload, 0, dataLen)
        return payload
    }

    fun verifyFrame(frame: ByteArray): Boolean {
        if (frame.size < 8) return false
        if ((frame[0].toInt() and 0xFF) != 0x60) return false
        val len = frame[2].toInt() and 0xFF
        if (frame.size != len + 5) return false // 总长 = dataLen+8 = (len-3)+8 = len+5
        if (frame[frame.size - 1].toInt() != 0x16) return false
        val dataLen = len - 3
        var sum = 0
        for (i in 0 until dataLen) sum += frame[6 + i].toInt() and 0xFF
        val expect = (sum + (frame[3].toInt() and 0xFF) + (frame[4].toInt() and 0xFF)) % 256
        return (frame[frame.size - 2].toInt() and 0xFF) == expect
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexVal(hex[i * 2]) ?: return null
            val lo = hexVal(hex[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 大端解析 4 字节为有符号 int（协议里金额/accountId 等） */
    fun intAt(data: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (data[off + i].toInt() and 0xFF)
        return v
    }

    fun intLE(data: ByteArray, off: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (data[off + i].toInt() and 0xFF)
        return v
    }

    fun bytesToHexString(data: ByteArray): String = toHex(data)

    private fun hexVal(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    private const val HEX = "0123456789ABCDEF"
}
