package com.funnyass.test.store

import android.content.Context
import com.funnyass.test.data.UserInfo
import com.google.gson.Gson

/** 登录态与设备信息持久化（SharedPreferences） */
object Session {
    private const val SP = "qzxy_lite"
    private const val KEY_USER = "user_info"
    private const val KEY_PHONE = "user_phone_num"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 新版原 App 在每次登录/刷新后都会执行一次 p0()：把 userAccount
     * 展平到顶层，并把新版登录令牌补到 v3LoginCode。下费率签名依赖的
     * 正是这个令牌，不能直接保存接口返回的嵌套对象。
     */
    fun normalizeUser(user: UserInfo, previous: UserInfo? = null): UserInfo {
        val account = user.userAccount
        val loginCode = user.loginCode?.takeIf { it.isNotBlank() }
            ?: previous?.loginCode?.takeIf { it.isNotBlank() }
        val v3LoginCode = user.v3LoginCode?.takeIf { it.isNotBlank() }
            ?: loginCode
            ?: previous?.v3LoginCode?.takeIf { it.isNotBlank() }
        return user.copy(
            userId = user.userId.takeIf { it != 0L } ?: account?.userId ?: previous?.userId ?: 0,
            telephone = user.telephone?.takeIf { it.isNotBlank() }
                ?: previous?.telephone?.takeIf { it.isNotBlank() },
            alias = user.alias ?: previous?.alias,
            loginCode = loginCode,
            v3LoginCode = v3LoginCode,
            projectId = user.projectId.takeIf { it != 0 }
                ?: account?.projectId ?: previous?.projectId ?: 0,
            accountId = user.accountId.takeIf { it != 0 }
                ?: account?.accountId ?: previous?.accountId ?: 0,
            accountRealMoney = account?.accountRealMoney ?: user.accountRealMoney,
            accountGivenMoney = account?.accountGivenMoney ?: user.accountGivenMoney,
            accountStatus = account?.accountStatus ?: user.accountStatus,
            // 当前发行版的登录流程固定走 v3；原 App 也在登录成功后强制置 1。
            isMigrated = 1,
            tags = user.tags ?: previous?.tags
        )
    }

    fun saveUser(ctx: Context, user: UserInfo): UserInfo {
        val normalized = normalizeUser(user, loadUser(ctx))
        sp(ctx).edit().putString(KEY_USER, gson.toJson(normalized)).apply()
        normalized.telephone?.let { sp(ctx).edit().putString(KEY_PHONE, it).apply() }
        return normalized
    }

    fun loadUser(ctx: Context): UserInfo? = try {
        val s = sp(ctx).getString(KEY_USER, null) ?: return null
        gson.fromJson(s, UserInfo::class.java)
    } catch (e: Exception) { null }

    fun phone(ctx: Context): String? = sp(ctx).getString(KEY_PHONE, null)

    fun loginCode(ctx: Context): String? = loadUser(ctx)?.let { user ->
        user.v3LoginCode?.takeIf { it.isNotBlank() }
            ?: user.loginCode?.takeIf { it.isNotBlank() }
    }

    fun logout(ctx: Context, keepPhone: Boolean = true) {
        val edit = sp(ctx).edit().remove(KEY_USER)
        if (!keepPhone) edit.remove(KEY_PHONE)
        edit.apply()
    }
}
