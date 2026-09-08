package com.funnyass.test.util

import com.klcxkj.jni.JniUtils
import java.security.MessageDigest

object Crypto {
    fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    fun md5Upper(s: String): String = md5(s).uppercase()

    /** 密码登录：MD5(明文).toUpperCase() 的后 10 位 */
    fun loginPassword(plain: String): String {
        val m = md5Upper(plain)
        return m.substring(m.length - 10)
    }

    /** 发验证码 secret：MD5(手机前3位 + 后4位 + "klcx") */
    fun smsSecret(phone: String): String {
        val seg = phone.take(3) + phone.takeLast(4) + "klcx"
        return md5(seg)
    }

    /** 第一套签名：key 升序拼接 key+value(null跳过value) + getSk() → MD5（小写） */
    fun signMd5(params: Map<String, String?>): String {
        val sb = StringBuilder()
        params.keys.sorted().forEach { k ->
            sb.append(k)
            params[k]?.let { sb.append(it) }
        }
        sb.append(JniUtils.getSk())
        return md5(sb.toString())
    }

    /** 第二套签名：key 升序拼接 key+value → native signParams(loginCode, raw) */
    fun signNative(loginCode: String, params: Map<String, String?>): String {
        val sb = StringBuilder()
        params.keys.sorted().forEach { k ->
            sb.append(k)
            params[k]?.let { sb.append(it) }
        }
        return JniUtils.signParams(loginCode, sb.toString())
    }
}
