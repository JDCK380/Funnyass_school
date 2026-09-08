package com.funnyass.test.net

import com.funnyass.test.data.BaseResponse
import com.funnyass.test.data.DeviceInfo
import com.funnyass.test.data.DownRateData
import com.funnyass.test.data.UploadData
import com.funnyass.test.data.UserInfo
import com.funnyass.test.data.WalletData
import com.funnyass.test.util.Crypto
import com.funnyass.test.Logger

object Api {

    fun sendSms(phone: String): BaseResponse<Any> {
        val params = mapOf(
            "secret" to Crypto.smsSecret(phone),
            "typeId" to "3",
            "telephone" to phone,
            "platform" to "1"
        )
        return ApiClient.parse(ApiClient.get(null, "user/verification/code/get", params), Any::class.java)
    }

    fun loginByCode(phone: String, code: String): BaseResponse<UserInfo> {
        val params = mapOf("type" to "5", "telephone" to phone, "smsCode" to code)
        return ApiClient.parse(ApiClient.post(null, "user/registerAndLogin", params), UserInfo::class.java)
    }

    fun loginByPassword(phone: String, plainPwd: String): BaseResponse<UserInfo> {
        val params = mapOf(
            "password" to Crypto.loginPassword(plainPwd),
            "telephone" to phone,
            "type" to "0",
            "identifier" to ""
        )
        return ApiClient.parse(ApiClient.post(null, "user/login", params), UserInfo::class.java)
    }

    fun userInfo(user: UserInfo): BaseResponse<UserInfo> =
        ApiClient.parse(ApiClient.get(user, "user/info"), UserInfo::class.java)

    fun wallet(user: UserInfo): BaseResponse<WalletData> =
        ApiClient.parse(ApiClient.get(user, "account/wallet"), WalletData::class.java)

    fun deviceByMac(user: UserInfo, mac: String): BaseResponse<DeviceInfo> =
        ApiClient.parse(
            ApiClient.get(user, "device/info/mac", mapOf(
                "macAddress" to mac,
                "isHaier" to "0",
                "isNew" to "1"
            )),
            DeviceInfo::class.java
        )

    fun rateOrder(
        user: UserInfo,
        device: DeviceInfo,
        type: Int,
        a1: Int,
        protocolType: String,
        randomNumber: String,
        xfModel: String = "0",
        couponId: String? = null
    ): BaseResponse<DownRateData> {
        val mac = (device.realMac ?: device.devMac ?: "").replace(":", "")
        val rn = randomNumber.lowercase()
        val macType = String.format("%02x%02x", type and 0xFF, a1 and 0xFF)
        val signParams = mapOf<String, String?>(
            "telephone" to user.telephone,
            "deviceId" to device.devID.toString(),
            "xfModel" to xfModel,
            "randomNumber" to rn
        )
        val authCode = ApiClient.authCode(user)
        val signature = Crypto.signNative(authCode, signParams)
        Logger.log("rate sign input=" + signParams.keys.sorted().joinToString("") { it + (signParams[it] ?: "") })
        Logger.log("rate signature=" + signature)
        val params = mutableMapOf(
            "deviceId" to device.devID.toString(),
            "macAddress" to mac,
            "macType" to macType,
            "bigTypeId" to device.dsbtypeid.toString(),
            "smallTypeId" to device.devTypeID.toString(),
            "protocolType" to protocolType,
            "randomNumber" to rn,
            "xfModel" to xfModel,
            "signature" to signature
        )
        couponId?.let { params["couponId"] = it }
        return ApiClient.parse(
            ApiClient.post(user, "order/downRate/bluetooth/rateOrder", params),
            DownRateData::class.java
        )
    }

    fun uploadBluetoothData(
        user: UserInfo,
        protocolType: String,
        randomNumber: String,
        xfData: String,
        accountId: Int = 0,
        upMoney: Int = 0,
        accountType: Int = 0
    ): BaseResponse<UploadData> {
        val rn = randomNumber.lowercase()
        val data = xfData.lowercase()
        val signParams = mapOf<String, String?>(
            "loginCode" to ApiClient.authCode(user),
            "telephone" to user.telephone,
            "xfData" to data
        )
        val params = mutableMapOf(
            "protocolType" to protocolType,
            "randomNumber" to rn,
            "xfData" to data,
            "signature" to Crypto.signNative(ApiClient.authCode(user), signParams)
        )
        if (accountId != 0) params["accountId"] = accountId.toString()
        if (upMoney != 0) params["upMoney"] = upMoney.toString()
        if (accountType != 0) params["accountType"] = accountType.toString()
        return ApiClient.parse(
            ApiClient.post(user, "order/upload/bluetooth/data", params),
            UploadData::class.java
        )
    }

    fun failBluetoothOrder(user: UserInfo, consumeDate: String): BaseResponse<Any> {
        val signParams = mapOf<String, String?>(
            "consumeDate" to consumeDate,
            "loginCode" to ApiClient.authCode(user),
            "telephone" to user.telephone
        )
        val params = mapOf(
            "consumeDate" to consumeDate,
            "signature" to Crypto.signNative(ApiClient.authCode(user), signParams)
        )
        return ApiClient.parse(
            ApiClient.post(user, "order/upload/bluetooth/fail", params),
            Any::class.java
        )
    }
}
