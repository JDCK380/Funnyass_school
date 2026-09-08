package com.funnyass.test.data

import com.google.gson.annotations.SerializedName

/** 通用响应包裹 */
data class BaseResponse<T>(
    val success: Boolean = false,
    val errorCode: Int = -1,
    val errorMessage: String? = null,
    val data: T? = null
)

/** 登录/用户信息（user/info 与 user/login 返回的核心字段，v3 小驼峰） */
data class UserInfo(
    val userId: Long = 0,
    val telephone: String? = null,
    val alias: String? = null,
    val loginCode: String? = null,
    @SerializedName("v3LoginCode") val v3LoginCode: String? = null,
    val projectId: Int = 0,
    val accountId: Int = 0,
    val accountRealMoney: Int = 0,
    val accountGivenMoney: Int = 0,
    val accountStatus: Int = 0,
    val hasPassword: Boolean = false,
    val isMigrated: Int = 0,
    val tags: String? = null,
    val userAccount: UserAccount? = null
)

data class UserAccount(
    val projectId: Int = 0,
    val accountId: Int = 0,
    val userId: Long = 0,
    val name: String? = null,
    val genderName: String? = null,
    val accountStatus: Int = 0,
    val accountRealMoney: Int = 0,
    val accountGivenMoney: Int = 0,
    val isCard: Int = 0,
    val cardStatus: Int = -1,
    val gradeClassFullName: String? = null
)

/** 钱包余额 account/wallet */
data class WalletData(
    val projectId: Int = 0,
    val accountId: Int = 0,
    val accountRealMoney: Int = 0,
    val accountGivenMoney: Int = 0,
    val accountRealMoneyStr: String? = null,
    val accountGivenMoneyStr: String? = null,
    val money: String? = null,
    val projectHasMonthCard: Boolean? = null
)

/** 公寓洗澡设备（字段名兼容大驼峰 value + 小驼峰 alternate，原 App 注解） */
data class DeviceInfo(
    @SerializedName(value = "DevID", alternate = ["deviceId"]) val devID: Int = 0,
    @SerializedName(value = "devMac", alternate = ["macAddress"]) val devMac: String? = null,
    val realMac: String? = null,
    @SerializedName(value = "DevName", alternate = ["deviceName"]) val devName: String? = null,
    @SerializedName(value = "DevTypeID", alternate = ["smallTypeId"]) val devTypeID: Int = 0,
    @SerializedName(value = "DevTypeName", alternate = ["smallTypeName"]) val devTypeName: String? = null,
    @SerializedName(value = "devTypeStatus", alternate = ["communicationTypeId"]) val devTypeStatus: Int = 0,
    @SerializedName(value = "Dsbtypeid", alternate = ["bigTypeId"]) val dsbtypeid: Int = 0,
    @SerializedName(value = "DsbName", alternate = ["bigTypeName"]) val dsbName: String? = null,
    val isBle: Boolean = false,
    @SerializedName(value = "isOnline", alternate = ["onlineStatusId"]) val isOnline: Int = 0,
    @SerializedName(value = "IsUse", alternate = ["isUse"]) val isUse: Int = 0,
    val keyNo: String? = null,
    @SerializedName(value = "keyType", alternate = ["keyStatus"]) val keyType: Int = 0,
    val snCode: String? = null,
    @SerializedName(value = "PrjID", alternate = ["projectId"]) val prjID: Int = 0,
    @SerializedName(value = "PrjName", alternate = ["projectName"]) val prjName: String? = null,
    val chargeMode: Int = 0,
    val deductionMethod: Int = 0,
    val withholdMoney: String? = null,
    @SerializedName(value = "confuseSecret", alternate = ["isUpdateProtocol"]) val confuseSecret: String? = null,
    val modelCode: String? = null,
    @SerializedName(value = "FJName", alternate = ["roomName"]) val roomName: String? = null
)

/** 下费率响应 order/downRate/bluetooth/rateOrder */
data class DownRateData(
    val downData: String? = null,
    val perMoney: String? = null,
    val consumeDate: String? = null,
    val orderNo: String? = null,
    val liquidOrderNo: String? = null,
    val rate: String? = null
)

/** 上传消费响应 order/upload/bluetooth/data */
data class UploadData(
    val clData: String? = null,
    @SerializedName(value = "upMoney", alternate = ["consumeMoney"]) val upMoney: Int = 0,
    @SerializedName(value = "upLeadMoney", alternate = ["preDeductMoneyAfter"]) val upLeadMoney: Int = 0,
    @SerializedName(value = "perMoney", alternate = ["preDeductMoney"]) val perMoney: Int = 0,
    @SerializedName(value = "fishTime", alternate = ["consumeTime"]) val fishTime: String? = null
)
