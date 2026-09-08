package com.funnyass.test.net

import com.funnyass.test.data.BaseResponse
import com.funnyass.test.data.UserInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ApiClient {
    const val BASE = "https://v3-api.china-qzxy.cn/"
    const val VERSION = "6.5.28"
    const val CONFIG_KEYS = "module_list,advertise_type,question_list,service_phone_list,banner_list_app,activity_list_app,aliCard_popup_config"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var authInvalidListener: ((String) -> Unit)? = null

    fun setAuthInvalidListener(listener: ((String) -> Unit)?) {
        authInvalidListener = listener
    }

    fun clearAuthInvalidListener(listener: (String) -> Unit) {
        if (authInvalidListener === listener) authInvalidListener = null
    }

    private fun notifyAuthInvalid(message: String?) {
        authInvalidListener?.invoke(message?.takeIf { it.isNotBlank() } ?: "登录已失效，请重新登录")
    }

    private fun pid(user: UserInfo?): Int {
        if (user == null) return 0
        return if (user.projectId != 0) user.projectId else (user.userAccount?.projectId ?: 0)
    }

    private fun aid(user: UserInfo?): Int {
        if (user == null) return 0
        return if (user.accountId != 0) user.accountId else (user.userAccount?.accountId ?: 0)
    }

    fun authCode(user: UserInfo): String =
        user.v3LoginCode?.takeIf { it.isNotBlank() }
            ?: user.loginCode?.takeIf { it.isNotBlank() }
            ?: ""

    private fun baseParams(user: UserInfo?): MutableMap<String, String> {
        val m = mutableMapOf<String, String>()
        if (user != null) {
            m["projectId"] = pid(user).toString()
            if (aid(user) != 0) m["accountId"] = aid(user).toString()
            m["userId"] = user.userId.toString()
            m["telephone"] = user.telephone ?: ""
            m["telPhone"] = user.telephone ?: ""
            m["loginCode"] = authCode(user)
        }
        m["phoneSystem"] = "android"
        m["version"] = VERSION
        return m
    }

    private fun headers(user: UserInfo?): Map<String, String> {
        val m = mutableMapOf<String, String>()
        if (user != null && pid(user) != 0) {
            m["Config-Project"] = pid(user).toString()
            m["Config-Keys"] = CONFIG_KEYS
        }
        return m
    }

    fun get(user: UserInfo?, path: String, extra: Map<String, String> = emptyMap()): String {
        val url = (BASE + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            baseParams(user).forEach { (k, v) -> addQueryParameter(k, v) }
            extra.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build()?.toString() ?: (BASE + path)
        val b = Request.Builder().url(url).get()
        headers(user).forEach { (k, v) -> b.addHeader(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string() ?: throw IllegalStateException("empty body, code=" + resp.code)
            if (user != null && resp.code in setOf(401, 403)) notifyAuthInvalid(null)
            return body
        }
    }

    fun post(user: UserInfo?, path: String, params: Map<String, String> = emptyMap()): String {
        val body = FormBody.Builder().apply {
            baseParams(user).forEach { (k, v) -> add(k, v) }
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val b = Request.Builder().url(BASE + path).post(body)
        headers(user).forEach { (k, v) -> b.addHeader(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            val body2 = resp.body?.string() ?: throw IllegalStateException("empty body, code=" + resp.code)
            if (user != null && resp.code in setOf(401, 403)) notifyAuthInvalid(null)
            return body2
        }
    }

    fun <T> parse(json: String, clazz: Class<T>): BaseResponse<T> {
        val type = TypeToken.getParameterized(BaseResponse::class.java, clazz).type
        val response: BaseResponse<T> = gson.fromJson(json, type)
        // 原版将 8 和 10000 统一视为登录凭据失效（含其他设备重新登录）。
        if (response.errorCode == 8 || response.errorCode == 10000) {
            notifyAuthInvalid(response.errorMessage)
        }
        return response
    }
}
