package com.funnyass.test.ui

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.funnyass.test.CmdServer
import com.funnyass.test.Logger
import com.funnyass.test.R
import com.funnyass.test.ble.BleManager
import com.funnyass.test.ble.FrameUtils
import com.funnyass.test.data.BaseResponse
import com.funnyass.test.data.DeviceInfo
import com.funnyass.test.data.DownRateData
import com.funnyass.test.data.UploadData
import com.funnyass.test.data.UserInfo
import com.funnyass.test.data.WalletData
import com.funnyass.test.net.Api
import com.funnyass.test.net.ApiClient
import com.funnyass.test.store.Session
import com.klcxkj.jni.JniUtils

@SuppressLint("MissingPermission")
class BathActivity : AppCompatActivity(), BleManager.Listener, CmdServer.Commands {

    private lateinit var ble: BleManager
    private lateinit var statusTv: TextView
    private lateinit var logTv: TextView
    private lateinit var deviceList: ListView
    private lateinit var walletTv: TextView
    private lateinit var accountSubtitleTv: TextView
    private lateinit var accountValueTv: TextView
    private lateinit var deviceTitleTv: TextView
    private lateinit var deviceDetailTv: TextView
    private lateinit var deviceMacTv: TextView
    private lateinit var deviceIdValueTv: TextView
    private lateinit var connectionValueTv: TextView
    private lateinit var protocolValueTv: TextView
    private lateinit var controlHintTv: TextView
    private lateinit var macInput: EditText
    private lateinit var advancedPanel: LinearLayout
    private lateinit var advancedToggle: TextView
    private lateinit var connectBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var reloginBtn: Button

    private var user: UserInfo? = null
    private var device: DeviceInfo? = null
    private val devices = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var adapter: ArrayAdapter<String>
    private var selectedMac: String? = null

    private var protocolType = "20"
    private var randomNumber = ""
    private var devType = 0
    private var a1 = 0
    private var connected = false
    private var pendingRateDate: String? = null
    private var pendingRateMac: String? = null
    private var deviceState = -1
    private var deviceAccountId = 0
    private var startRequestInFlight = false
    private var verifyingStart = false
    private var rollbackInFlight = false
    private var stopRequestInFlight = false
    private var stopAttempts = 0
    private var settlementInFlight = false
    private var collectRequested = false
    private var authRedirected = false
    private var authInvalidPending = false
    @Volatile private var sessionCheckInFlight = false
    private val opHandler = Handler(Looper.getMainLooper())
    private val authInvalidListener: (String) -> Unit = { message ->
        runOnUiThread { handleAuthInvalid(message) }
    }
    private val sessionCheck = object : Runnable {
        override fun run() {
            validateSessionSilently()
            opHandler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
        }
    }

    private val startAckTimeout = Runnable { verifyTimedOutStart() }
    private val startVerifyTimeout = Runnable {
        if (verifyingStart && pendingRateDate != null) {
            verifyingStart = false
            rollbackPendingStart("设备没有确认开阀，正在退回预扣")
        }
    }
    private val stopAckTimeout = Runnable { handleStopTimeout() }
    private val stopVerifyTimeout = Runnable {
        if (stopRequestInFlight) {
            stopRequestInFlight = false
            stopAttempts = 0
            setStatusText("未确认关阀，请再次点击停止")
            Logger.log("关阀命令与状态查询均无回执")
            updateActionButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bath)
        statusTv = findViewById(R.id.status)
        logTv = findViewById(R.id.log)
        deviceList = findViewById(R.id.device_list)
        walletTv = findViewById(R.id.wallet)
        accountSubtitleTv = findViewById(R.id.account_subtitle)
        accountValueTv = findViewById(R.id.account_value)
        deviceTitleTv = findViewById(R.id.device_title)
        deviceDetailTv = findViewById(R.id.device_detail)
        deviceMacTv = findViewById(R.id.device_mac)
        deviceIdValueTv = findViewById(R.id.device_id_value)
        connectionValueTv = findViewById(R.id.connection_value)
        protocolValueTv = findViewById(R.id.protocol_value)
        controlHintTv = findViewById(R.id.control_hint)
        macInput = findViewById(R.id.mac_input)
        advancedPanel = findViewById(R.id.advanced_panel)
        advancedToggle = findViewById(R.id.advanced_toggle)
        connectBtn = findViewById(R.id.connect_btn)
        scanBtn = findViewById(R.id.scan_btn)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)
        disconnectBtn = findViewById(R.id.disconnect_btn)
        reloginBtn = findViewById(R.id.relogin_btn)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        deviceList.adapter = adapter
        deviceList.setOnItemClickListener { _, _, pos, _ ->
            val mac = devices.keys.elementAtOrNull(pos) ?: return@setOnItemClickListener
            device = null
            selectedMac = mac
            macInput.setText(mac)
            renderDevice()
            connectDevice(mac)
        }
        deviceList.isNestedScrollingEnabled = true
        deviceList.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        user = Session.loadUser(this)
        restorePendingRate()
        ble = BleManager(this)
        ble.listener = this
        ApiClient.setAuthInvalidListener(authInvalidListener)
        Logger.log("user=" + (user?.userId ?: "null") + " 已登录=" + (user != null))
        selectedMac = macInput.text.toString().trim().takeIf { it.isNotEmpty() }
        renderUser()
        renderDevice()
        renderConnection()

        connectBtn.setOnClickListener {
            val m = macInput.text.toString().trim()
            if (m.isNotEmpty()) connectDevice(m)
        }
        scanBtn.setOnClickListener { scanDevices() }
        startBtn.setOnClickListener { startBath() }
        stopBtn.setOnClickListener { stopBath() }
        disconnectBtn.setOnClickListener { disconnectDev() }
        reloginBtn.setOnClickListener { requestRelogin() }
        findViewById<TextView>(R.id.refresh_btn).setOnClickListener { refreshUser() }
        advancedToggle.setOnClickListener {
            val expanded = advancedPanel.visibility != View.VISIBLE
            advancedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            advancedToggle.text = if (expanded) "选择洗澡设备  ‹" else "更换洗澡设备  ›"
        }

        refreshUser()
        CmdServer.start(this)
        scanDevices()
    }

    override fun onResume() {
        super.onResume()
        opHandler.removeCallbacks(sessionCheck)
        opHandler.post(sessionCheck)
    }

    override fun onPause() {
        opHandler.removeCallbacks(sessionCheck)
        super.onPause()
    }

    private fun renderUser() {
        val u = user
        if (u == null) {
            accountSubtitleTv.text = "未登录"
            accountValueTv.text = "账户 --"
            return
        }
        val phone = u.telephone?.trim().orEmpty()
        val masked = if (phone.length >= 7) phone.take(3) + "****" + phone.takeLast(4) else phone
        accountSubtitleTv.text = listOfNotNull(u.alias?.takeIf { it.isNotBlank() }, masked.takeIf { it.isNotBlank() })
            .joinToString(" · ").ifBlank { "校园账户已登录" }
        accountValueTv.text = "账户 " + if (u.accountId > 0) u.accountId else "--"
    }

    private fun renderDevice() {
        val d = device
        val hasSelection = !selectedMac.isNullOrBlank()
        deviceTitleTv.text = d?.devName?.takeIf { it.isNotBlank() }
            ?: when {
                connected -> "已连接洗澡设备"
                hasSelection -> "正在读取设备信息…"
                else -> "请选择洗澡设备"
            }
        val room = d?.roomName?.takeIf { it.isNotBlank() }
        val kind = d?.devTypeName?.takeIf { it.isNotBlank() }
        deviceDetailTv.text = when {
            room != null && kind != null -> room + " · " + kind
            room != null -> room
            connected -> "设备已就绪，开始前会再次校验信息"
            hasSelection -> "已选择附近设备，正在连接"
            else -> "请从下方扫描列表中点击选择"
        }
        val mac = d?.realMac?.takeIf { it.isNotBlank() }
            ?: d?.devMac?.takeIf { it.isNotBlank() }
            ?: selectedMac
        deviceMacTv.text = "MAC " + (mac ?: "--")
        deviceIdValueTv.text = if (d?.devID ?: 0 > 0) d?.devID.toString() else "--"
        protocolValueTv.text = if (hasSelection) protocolType.takeIf { it.isNotBlank() } ?: "--" else "--"
    }

    private fun renderConnection() {
        connectionValueTv.text = if (connected) "已连接" else "未连接"
        connectionValueTv.setTextColor(ContextCompat.getColor(this,
            if (connected) R.color.miui_green else R.color.miui_text))
        val hint = when {
            stopRequestInFlight -> "关阀命令已直接发送；关阀后再进行消费结算"
            settlementInFlight || deviceState == 3 -> "供水已经停止，正在完成消费结算"
            isOwnActiveSession() -> "设备正在供水，点击停止会立即发送关阀命令"
            deviceState == 1 -> "设备正被其他账户使用，已禁止误操作"
            connected && deviceState == 0 && device == null -> "设备状态正常，正在等待设备详情"
            connected && deviceState == 0 -> "设备状态已确认，可以开始洗澡"
            connected -> "正在读取设备状态，请稍候"
            else -> "连接设备后即可开始，停止时会自动结算"
        }
        controlHintTv.text = hint
        renderDevice()
        updateActionButtons()
    }

    private fun setStatusText(text: String) {
        statusTv.text = text
        val active = connected && (text.contains("使用") || text.contains("结算") || text.contains("关阀"))
        statusTv.setTextColor(ContextCompat.getColor(this,
            if (active) R.color.miui_green else R.color.miui_blue))
        statusTv.background = ContextCompat.getDrawable(this,
            if (active) R.drawable.bg_miui_pill_green else R.drawable.bg_miui_pill_blue)
        renderConnection()
    }

    private fun isOwnActiveSession(): Boolean {
        val aid = user?.accountId ?: 0
        return deviceState == 1 && aid > 0 && deviceAccountId == aid
    }

    private fun operationLocksDevice(): Boolean =
        startRequestInFlight || stopRequestInFlight || settlementInFlight || rollbackInFlight || isOwnActiveSession()

    private fun updateActionButtons() {
        if (!::startBtn.isInitialized) return
        val protocolReady = randomNumber.length == 8 && protocolType.length == 2
        val canStart = connected && deviceState == 0 && protocolReady &&
            !startRequestInFlight && !stopRequestInFlight && !settlementInFlight &&
            pendingRateDate == null && device != null
        val canStop = connected && isOwnActiveSession() && !stopRequestInFlight && !settlementInFlight
        val canChangeDevice = !operationLocksDevice()
        val canReconnectLockedDevice = !connected && !selectedMac.isNullOrBlank() && operationLocksDevice()

        startBtn.isEnabled = canStart
        stopBtn.isEnabled = canStop
        connectBtn.isEnabled = canChangeDevice || canReconnectLockedDevice
        scanBtn.isEnabled = canChangeDevice
        disconnectBtn.isEnabled = connected && canChangeDevice
        reloginBtn.isEnabled = !isOwnActiveSession() && !stopRequestInFlight && !startRequestInFlight
        macInput.isEnabled = canChangeDevice
        advancedToggle.isEnabled = canChangeDevice

        startBtn.text = if (startRequestInFlight) "正在启动…" else "开始洗澡"
        stopBtn.text = when {
            stopRequestInFlight -> "正在关阀…"
            settlementInFlight -> "已关阀，结算中"
            else -> "停止供水"
        }
        listOf(startBtn, stopBtn, connectBtn, scanBtn, disconnectBtn, reloginBtn).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.45f
        }
        advancedToggle.alpha = if (advancedToggle.isEnabled) 1f else 0.45f
    }

    private fun loadDeviceInfo(mac: String) {
        val u = user ?: return
        if (mac.isBlank()) return
        selectedMac = mac
        Thread {
            try {
                val r: BaseResponse<DeviceInfo> = Api.deviceByMac(u, mac)
                runOnUiThread {
                    if (r.errorCode == 0 && r.data != null) {
                        device = r.data
                        renderDevice()
                        updateActionButtons()
                    } else {
                        deviceTitleTv.text = "已选择洗澡设备"
                        deviceDetailTv.text = "设备详情暂时无法读取，仍可连接后重试"
                        Logger.log("设备信息失败: " + r.errorCode + " " + r.errorMessage)
                        updateActionButtons()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Logger.log("设备信息异常: " + e.message) }
            }
        }.start()
    }

    private fun restorePendingRate() {
        val prefs = getSharedPreferences(SAFETY_PREFS, MODE_PRIVATE)
        pendingRateDate = prefs.getString(KEY_PENDING_DATE, null)?.takeIf { it.isNotBlank() }
        pendingRateMac = prefs.getString(KEY_PENDING_MAC, null)?.takeIf { it.isNotBlank() }
        if (pendingRateDate != null) {
            Logger.log("发现上次未确认的预扣记录，将在连接原设备后自动核对")
        }
    }

    private fun persistPendingRate(consumeDate: String, mac: String) {
        pendingRateDate = consumeDate
        pendingRateMac = mac
        getSharedPreferences(SAFETY_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_PENDING_DATE, consumeDate)
            .putString(KEY_PENDING_MAC, mac)
            .apply()
    }

    private fun clearPendingRate(consumeDate: String? = pendingRateDate) {
        if (consumeDate != null && pendingRateDate != null && consumeDate != pendingRateDate) return
        pendingRateDate = null
        pendingRateMac = null
        getSharedPreferences(SAFETY_PREFS, MODE_PRIVATE).edit()
            .remove(KEY_PENDING_DATE)
            .remove(KEY_PENDING_MAC)
            .apply()
    }

    private fun requestRelogin() {
        if (operationLocksDevice()) {
            toast("请先停止供水并等待当前操作完成")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("重新登录")
            .setMessage("将清除本机登录凭据，并返回登录页面。")
            .setNegativeButton("取消", null)
            .setPositiveButton("重新登录") { _, _ -> goToLogin("请重新登录账户") }
            .show()
    }

    private fun handleAuthInvalid(message: String) {
        if (authRedirected) return
        if (isOwnActiveSession() || stopRequestInFlight) {
            authInvalidPending = true
            setStatusText("登录已失效，请先停止供水")
            toast("检测到账号已在其他设备登录；请先关阀")
            return
        }
        goToLogin(message)
    }

    private fun goToLogin(reason: String) {
        if (authRedirected) return
        authRedirected = true
        ApiClient.clearAuthInvalidListener(authInvalidListener)
        ble.stopScan()
        ble.disconnect()
        connected = false
        Session.logout(this, keepPhone = true)
        val intent = Intent(this, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_REASON, reason)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    // ---- CmdServer.Commands ----
    override fun connectDevice(mac: String) {
        if (mac.isEmpty()) { Logger.log("connect 需要 mac"); return }
        val reconnectingLockedDevice = !connected && selectedMac?.equals(mac, ignoreCase = true) == true
        if (operationLocksDevice() && !reconnectingLockedDevice) {
            toast("当前用水或结算尚未结束，不能更换设备")
            return
        }
        deviceState = -1
        deviceAccountId = 0
        randomNumber = ""
        selectedMac = mac
        macInput.setText(mac)
        renderDevice()
        loadDeviceInfo(mac)
        Logger.log("连接 " + mac)
        ble.connect(mac)
    }

    override fun scanDevices() {
        showDevicePicker()
        if (!ensurePermissions()) return
        if (!ble.isEnabled) {
            setStatusText("请开启蓝牙")
            try {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 101)
            } catch (_: Exception) {
                toast("请先在系统设置中开启蓝牙")
            }
            return
        }
        beginScan()
    }

    private fun beginScan() {
        if (operationLocksDevice()) {
            toast("请先停止并结算当前用水")
            return
        }
        if (connected) {
            ble.disconnect()
            connected = false
        }
        device = null
        deviceState = -1
        deviceAccountId = 0
        randomNumber = ""
        selectedMac = null
        macInput.text.clear()
        devices.clear()
        adapter.clear()
        renderDevice()
        setStatusText("正在扫描附近设备…")
        ble.startScan()
    }

    private fun showDevicePicker() {
        advancedPanel.visibility = View.VISIBLE
        advancedToggle.text = "选择洗澡设备  ‹"
    }

    override fun disconnectDev() {
        if (operationLocksDevice()) {
            toast("当前用水或结算尚未结束，不能断开设备")
            return
        }
        ble.disconnect(); connected = false; Logger.status("已断开"); setStatusText("已断开")
    }

    override fun queryDev() {
        Logger.log("手动查询 0x23")
        ble.send(0x23)
    }

    override fun collectDev(randomNumber: String) {
        if (randomNumber.isNotBlank()) this.randomNumber = randomNumber
        Logger.log("手动采集 0x85")
        ble.send(0x85)
    }

    override fun uploadDev(randomNumber: String, xfData: String) {
        if (randomNumber.isNotBlank()) this.randomNumber = randomNumber
        val data = FrameUtils.fromHex(xfData)
        if (data == null) Logger.log("手动上传数据格式错误") else uploadData(data)
    }

    override fun failDev(consumeDate: String) {
        rollbackRate(consumeDate, "正在退回未完成订单的预扣")
    }

    private fun rollbackPendingStart(reason: String) {
        val consumeDate = pendingRateDate ?: return
        opHandler.removeCallbacks(startAckTimeout)
        opHandler.removeCallbacks(startVerifyTimeout)
        startRequestInFlight = false
        verifyingStart = false
        rollbackRate(consumeDate, reason)
    }

    private fun rollbackRate(consumeDate: String, reason: String = "正在退回预扣") {
        val u = user ?: return
        if (consumeDate.isBlank()) return
        if (rollbackInFlight) return
        rollbackInFlight = true
        setStatusText(reason)
        updateActionButtons()
        Thread {
            try {
                val r = Api.failBluetoothOrder(u, consumeDate)
                runOnUiThread {
                    rollbackInFlight = false
                    Logger.log("失败订单回滚: " + r.errorCode + " " + r.errorMessage)
                    if (r.errorCode == 0) {
                        clearPendingRate(consumeDate)
                        deviceState = 0
                        deviceAccountId = 0
                        setStatusText("设备未开阀，预扣已退回")
                        loadWallet()
                    } else {
                        setStatusText("预扣退回失败，请重连原设备核对")
                        toast("为避免重复扣费，已暂停再次启动")
                    }
                    updateActionButtons()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    rollbackInFlight = false
                    Logger.log("失败订单回滚异常: " + e.message)
                    setStatusText("预扣退回失败，请重连原设备核对")
                    updateActionButtons()
                }
            }
        }.start()
    }

    override fun startBath() {
        val u = user ?: run { Logger.log("未登录"); return }
        if (!connected) { Logger.log("请先连接设备"); return }
        val mac = selectedMac ?: run { Logger.log("请先选择设备"); return }
        if (startRequestInFlight || stopRequestInFlight || settlementInFlight || rollbackInFlight) {
            toast("上一个操作正在处理中")
            return
        }
        if (pendingRateDate != null) {
            toast("存在待核对预扣，请先连接原设备完成核对")
            return
        }
        if (deviceState != 0 || randomNumber.length != 8) {
            setStatusText("正在确认设备状态…")
            Logger.log("启动被拦截：状态或随机数尚未就绪，重新查询 0x23")
            ble.send(0x23)
            return
        }
        if (device == null) {
            startRequestInFlight = true
            setStatusText("正在读取设备信息…")
            updateActionButtons()
            Logger.log("首次读取设备详情 device/info/mac?mac=" + mac)
            Thread {
                try {
                    val r: BaseResponse<DeviceInfo> = Api.deviceByMac(u, mac)
                    runOnUiThread {
                        if (r.errorCode == 0 && r.data != null && connected && selectedMac == mac && deviceState == 0) {
                            device = r.data
                            renderDevice()
                            doRateOrder(u)
                        } else {
                            startRequestInFlight = false
                            setStatusText("设备信息读取失败，未产生预扣")
                            Logger.log("设备详情失败: " + r.errorCode + " " + r.errorMessage)
                            updateActionButtons()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        startRequestInFlight = false
                        setStatusText("设备信息读取失败，未产生预扣")
                        Logger.log("设备详情异常: " + e.message)
                        updateActionButtons()
                    }
                }
            }.start()
            return
        }
        // 连接时已经取过设备详情，不再在每次启动前重复请求一次。
        startRequestInFlight = true
        setStatusText("准备启动…")
        updateActionButtons()
        doRateOrder(u)
    }

    override fun stopBath() {
        if (!connected) { Logger.log("未连接"); return }
        if (stopRequestInFlight) return
        if (!isOwnActiveSession()) {
            toast(if (deviceState == 1) "设备由其他账户使用，已禁止误关阀" else "当前设备没有你的用水会话")
            return
        }
        stopRequestInFlight = true
        stopAttempts = 1
        setStatusText("正在关阀…")
        Logger.log("直接发送关阀 0x22（第 1 次）")
        updateActionButtons()
        ble.send(0x22)
        opHandler.removeCallbacks(stopAckTimeout)
        opHandler.postDelayed(stopAckTimeout, STOP_ACK_TIMEOUT_MS)
    }

    private fun handleStopTimeout() {
        if (!stopRequestInFlight) return
        if (!connected) {
            stopRequestInFlight = false
            stopAttempts = 0
            setStatusText("连接已断开，无法确认关阀")
            return
        }
        if (stopAttempts < MAX_STOP_ATTEMPTS) {
            stopAttempts++
            setStatusText("关阀响应较慢，正在重试…")
            Logger.log("关阀未回执，重发 0x22（第 " + stopAttempts + " 次）")
            ble.send(0x22)
            opHandler.postDelayed(stopAckTimeout, STOP_ACK_TIMEOUT_MS)
        } else {
            setStatusText("正在核对关阀状态…")
            Logger.log("关阀两次未回执，查询设备 0x23")
            ble.send(0x23)
            opHandler.removeCallbacks(stopVerifyTimeout)
            opHandler.postDelayed(stopVerifyTimeout, STOP_VERIFY_TIMEOUT_MS)
        }
    }

    private fun confirmValveClosed(collect: Boolean) {
        opHandler.removeCallbacks(stopAckTimeout)
        opHandler.removeCallbacks(stopVerifyTimeout)
        stopRequestInFlight = false
        stopAttempts = 0
        deviceState = if (collect) 3 else 0
        if (!collect) {
            settlementInFlight = false
            collectRequested = false
            setStatusText("已关阀")
            if (authInvalidPending) goToLogin("设备已关阀，请重新登录") else loadWallet()
            return
        }
        if (authInvalidPending) {
            settlementInFlight = false
            collectRequested = false
            goToLogin("设备已关阀，请重新登录后完成结算")
            return
        }
        settlementInFlight = true
        setStatusText("已关阀，正在结算…")
        if (!collectRequested) {
            collectRequested = true
            Logger.log("关阀已确认，发送采集 0x85")
            ble.send(0x85)
        }
        updateActionButtons()
    }

    private fun refreshUser() {
        val u = user ?: return
        if (sessionCheckInFlight || authRedirected) return
        sessionCheckInFlight = true
        Thread {
            try {
                val r: BaseResponse<UserInfo> = Api.userInfo(u)
                if (r.errorCode == 0 && r.data != null) {
                    val refreshed = Session.saveUser(this, r.data)
                    runOnUiThread {
                        user = refreshed
                        renderUser()
                        Logger.log("user/info pid=" + refreshed.projectId + " aid=" + refreshed.accountId)
                        loadWallet()
                        val mac = macInput.text.toString().trim()
                        if (mac.isNotBlank()) loadDeviceInfo(mac)
                    }
                } else {
                    runOnUiThread { Logger.log("user/info 失败 " + r.errorCode + " " + r.errorMessage) }
                }
            } catch (e: Exception) {
                runOnUiThread { Logger.log("user/info 异常 " + e.message) }
            } finally {
                sessionCheckInFlight = false
            }
        }.start()
    }

    private fun validateSessionSilently() {
        val u = user ?: return
        if (sessionCheckInFlight || authRedirected) return
        sessionCheckInFlight = true
        Thread {
            try {
                val r: BaseResponse<UserInfo> = Api.userInfo(u)
                if (r.errorCode == 0 && r.data != null) {
                    val refreshed = Session.saveUser(this, r.data)
                    runOnUiThread {
                        user = refreshed
                        renderUser()
                    }
                }
            } catch (_: Exception) {
                // 静默检查遇到临时网络故障时保留登录态；只响应服务端明确的失效码。
            } finally {
                sessionCheckInFlight = false
            }
        }.start()
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (ble.isEnabled) beginScan()
            else {
                setStatusText("请开启蓝牙")
                try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 101) }
                catch (_: Exception) { toast("请先在系统设置中开启蓝牙") }
            }
        } else if (requestCode == 100) {
            setStatusText("需要蓝牙权限")
            toast("允许蓝牙权限后才能扫描洗澡设备")
        }
    }

    @Deprecated("Bluetooth enable result uses the platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            if (ble.isEnabled) beginScan() else setStatusText("蓝牙未开启")
        }
    }

    private fun loadWallet() {
        val u = user ?: return
        Thread {
            try {
                val r: BaseResponse<WalletData> = Api.wallet(u)
                runOnUiThread {
                    if (r.errorCode == 0 && r.data != null) {
                        walletTv.text = r.data.money ?: "--"
                        Logger.log("余额 " + r.data.money)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Logger.log("余额异常 " + e.message) }
            }
        }.start()
    }

    override fun onScanResult(device: BluetoothDevice, rssi: Int) {
        val mac = device.address
        val isNew = !devices.containsKey(mac)
        devices[mac] = device
        if (isNew) {
            val n = try { device.name?.takeIf { it.isNotBlank() } ?: "未命名设备" }
                catch (_: Exception) { "未命名设备" }
            adapter.add(n + "  ·  " + rssi + " dBm\n" + mac)
        }
    }

    override fun onStateChanged(state: Int, msg: String) {
        Logger.status(msg)
        when (state) {
            3 -> {
                connected = true
                advancedPanel.visibility = View.GONE
                advancedToggle.text = "更换洗澡设备  ›"
                setStatusText("已连接，正在确认设备状态…")
                Logger.log("发送查询 0x23")
                ble.send(0x23)
            }
            4 -> {
                connected = false
                opHandler.removeCallbacks(startAckTimeout)
                opHandler.removeCallbacks(startVerifyTimeout)
                opHandler.removeCallbacks(stopAckTimeout)
                opHandler.removeCallbacks(stopVerifyTimeout)
                if (pendingRateDate != null || isOwnActiveSession() || settlementInFlight) {
                    startRequestInFlight = false
                    verifyingStart = false
                    stopRequestInFlight = false
                    setStatusText("连接中断，请重连原设备核对")
                } else {
                    setStatusText("已断开")
                }
                Logger.status("已断开")
            }
            11 -> setStatusText(if (devices.isEmpty()) "未发现设备，请重试" else "请选择附近设备")
            else -> setStatusText(msg)
        }
        renderConnection()
    }

    override fun onFrame(cmd: Int, payload: ByteArray) {
        Logger.log("回帧 cmd=0x" + Integer.toHexString(cmd) + " payload=" + FrameUtils.toHex(payload))
        when (cmd) {
            0x23 -> onQueryResp(payload)
            0x21 -> {
                if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x80) {
                    if (rollbackInFlight) {
                        Logger.log("回滚过程中收到延迟开阀回执，立即关阀")
                        deviceState = 1
                        deviceAccountId = user?.accountId ?: 0
                        stopRequestInFlight = true
                        stopAttempts = 1
                        setStatusText("检测到延迟开阀，正在立即关闭…")
                        ble.send(0x22)
                        opHandler.postDelayed(stopAckTimeout, STOP_ACK_TIMEOUT_MS)
                    } else {
                        markStartConfirmed()
                    }
                } else {
                    Logger.log("设备拒绝费率，回滚预扣")
                    rollbackPendingStart("设备拒绝启动，正在退回预扣")
                }
            }
            0x22 -> {
                if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x80) {
                    Logger.log("设备已确认关阀")
                    confirmValveClosed(collect = true)
                } else if (stopRequestInFlight) {
                    Logger.log("设备拒绝关阀，立即进入重试")
                    opHandler.removeCallbacks(stopAckTimeout)
                    opHandler.post(stopAckTimeout)
                }
            }
            0x85, 0xFB -> onCollectResp(payload)
            0x86, 0xFA -> {
                if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x80) {
                    Logger.log("存储写回完成")
                    settlementInFlight = false
                    collectRequested = false
                    deviceState = 0
                    deviceAccountId = 0
                    clearPendingRate()
                    Logger.status("已关阀 · 结算完成")
                    setStatusText("已关阀 · 结算完成")
                    loadWallet()
                    opHandler.postDelayed({ if (connected) ble.send(0x23) }, 500L)
                } else {
                    Logger.log("存储写回失败 payload=" + FrameUtils.toHex(payload))
                    settlementInFlight = false
                    collectRequested = false
                    setStatusText("已关阀，设备记录待重试")
                }
            }
            0xF8, 0x50 -> Logger.log("密钥设置回执")
        }
    }

    override fun onWriteDone(cmd: Int, success: Boolean) {
        if (success) return
        Logger.log("BLE 命令写入失败 cmd=0x" + Integer.toHexString(cmd))
        when (cmd) {
            0x21 -> verifyTimedOutStart()
            0x22 -> if (stopRequestInFlight) {
                opHandler.removeCallbacks(stopAckTimeout)
                opHandler.post(stopAckTimeout)
            }
            0x85, 0x86 -> {
                settlementInFlight = false
                collectRequested = false
                setStatusText("已关阀，结算通信失败，请重连重试")
            }
        }
    }

    private fun markStartConfirmed() {
        opHandler.removeCallbacks(startAckTimeout)
        opHandler.removeCallbacks(startVerifyTimeout)
        startRequestInFlight = false
        verifyingStart = false
        rollbackInFlight = false
        clearPendingRate()
        deviceState = 1
        deviceAccountId = user?.accountId ?: 0
        Logger.log("下费率成功，设备开阀供水")
        Logger.status("使用中")
        setStatusText("使用中")
        loadWallet()
    }

    private fun verifyTimedOutStart() {
        if (pendingRateDate == null || verifyingStart || rollbackInFlight) return
        opHandler.removeCallbacks(startAckTimeout)
        if (!connected) {
            startRequestInFlight = false
            setStatusText("连接中断，请重连原设备核对预扣")
            updateActionButtons()
            return
        }
        verifyingStart = true
        setStatusText("启动响应超时，正在核对设备…")
        Logger.log("开阀回执超时，先查询 0x23 再决定是否回滚")
        ble.send(0x23)
        opHandler.removeCallbacks(startVerifyTimeout)
        opHandler.postDelayed(startVerifyTimeout, START_VERIFY_TIMEOUT_MS)
    }

    private fun onQueryResp(p: ByteArray) {
        if (p.size < 31) { Logger.log("应答过短 " + p.size + "B"); return }
        protocolType = String.format("%02X", p[19].toInt() and 0xFF)
        randomNumber = FrameUtils.toHex(p.copyOfRange(24, 28))
        val state = p[28].toInt() and 0xFF
        val accountId = FrameUtils.intAt(p, 9)
        devType = p[29].toInt() and 0xFF
        a1 = p[30].toInt() and 0xFF
        deviceState = state
        deviceAccountId = accountId
        protocolValueTv.text = protocolType
        Logger.log("协议=" + protocolType + " C1=" + randomNumber + " 状态=" + state +
            " accountId=" + accountId + " type=" + devType + " A1=" + a1)
        Logger.status(stateText(state))

        if (verifyingStart && pendingRateDate != null) {
            verifyingStart = false
            opHandler.removeCallbacks(startVerifyTimeout)
            when {
                state == 1 && isOwnActiveSession() -> markStartConfirmed()
                state == 3 -> {
                    startRequestInFlight = false
                    confirmValveClosed(collect = true)
                }
                else -> rollbackPendingStart("设备确认未开阀，正在退回预扣")
            }
            return
        }

        if (stopRequestInFlight) {
            when (state) {
                0 -> confirmValveClosed(collect = false)
                3 -> confirmValveClosed(collect = true)
                1 -> {
                    opHandler.removeCallbacks(stopVerifyTimeout)
                    stopRequestInFlight = false
                    stopAttempts = 0
                    setStatusText(if (isOwnActiveSession()) "设备仍在供水，请再次点击停止" else "设备账户已变化，已停止操作")
                    updateActionButtons()
                }
                else -> {
                    opHandler.removeCallbacks(stopVerifyTimeout)
                    stopRequestInFlight = false
                    stopAttempts = 0
                    setStatusText(stateText(state))
                    updateActionButtons()
                }
            }
            return
        }

        val samePendingDevice = pendingRateDate != null && pendingRateMac != null &&
            selectedMac?.replace(":", "")?.equals(pendingRateMac?.replace(":", ""), ignoreCase = true) == true
        if (samePendingDevice) {
            when {
                state == 1 && isOwnActiveSession() -> {
                    Logger.log("设备确认上次启动成功，保留有效预扣")
                    markStartConfirmed()
                    return
                }
                state == 3 -> {
                    startRequestInFlight = false
                    confirmValveClosed(collect = true)
                    return
                }
                state != 1 -> {
                    rollbackPendingStart("正在核对并退回上次未完成的预扣")
                    return
                }
                else -> {
                    rollbackPendingStart("设备由其他账户占用，正在退回预扣")
                    return
                }
            }
        }

        when (state) {
            0 -> setStatusText("空闲（可开始）")
            1 -> setStatusText(if (isOwnActiveSession()) "使用中" else "其他账户使用中")
            3 -> confirmValveClosed(collect = true)
            else -> setStatusText(stateText(state))
        }
        updateActionButtons()
    }

    private fun stateText(s: Int): String = when (s) {
        0 -> "空闲（可开始）"
        1 -> "使用中"
        2 -> "不可中断"
        3 -> "有遗留数据"
        5 -> "受控模式"
        else -> "状态=" + s
    }

    private fun onCollectResp(p: ByteArray) {
        if (p.size < 32) { Logger.log("采集应答过短 " + p.size + "B"); return }
        val accountId = FrameUtils.intAt(p, 15)
        var amount = FrameUtils.intAt(p, 28)
        if (amount < 0) amount = -amount
        Logger.log("采集记录 accountId=" + accountId + " 金额(分)=" + amount)
        collectRequested = false
        settlementInFlight = true
        setStatusText("已关阀，正在上传消费记录…")
        uploadData(p)
    }

    private fun doRateOrder(u: UserInfo) {
        val d = device ?: run {
            startRequestInFlight = false
            setStatusText("缺少设备信息，未产生预扣")
            return
        }
        val mac = selectedMac ?: run {
            startRequestInFlight = false
            setStatusText("未选择设备，未产生预扣")
            return
        }
        if (!connected || deviceState != 0 || randomNumber.length != 8) {
            startRequestInFlight = false
            setStatusText("设备状态已变化，未产生预扣")
            updateActionButtons()
            return
        }
        Logger.log("下费率 rateOrder…")
        Thread {
            try {
                val r: BaseResponse<DownRateData> = Api.rateOrder(u, d, devType, a1, protocolType, randomNumber)
                runOnUiThread {
                    if (r.errorCode == 0 && r.data != null) {
                        val down = r.data.downData.orEmpty()
                        val consumeDate = r.data.consumeDate.orEmpty()
                        if (down.isBlank() || consumeDate.isBlank() || FrameUtils.fromHex(down) == null) {
                            startRequestInFlight = false
                            setStatusText("服务器费率数据无效，已停止启动")
                            Logger.log("费率响应缺少 downData 或 consumeDate")
                            updateActionButtons()
                            return@runOnUiThread
                        }
                        if (!connected || selectedMac != mac || deviceState != 0) {
                            // 订单已经创建，但设备状态在网络请求期间发生了变化，必须回滚。
                            persistPendingRate(consumeDate, mac)
                            rollbackPendingStart("设备状态已变化，正在退回预扣")
                            return@runOnUiThread
                        }
                        persistPendingRate(consumeDate, mac)
                        Logger.log("下发费率 0x21（已保存预扣回滚凭据）")
                        setStatusText("已创建预扣，等待设备确认开阀…")
                        ble.sendRawHex(0x21, down)
                        opHandler.removeCallbacks(startAckTimeout)
                        opHandler.postDelayed(startAckTimeout, START_ACK_TIMEOUT_MS)
                    } else {
                        startRequestInFlight = false
                        setStatusText("启动订单失败，未产生设备用水")
                        Logger.log("下费率失败: " + r.errorCode + " " + r.errorMessage)
                        updateActionButtons()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    startRequestInFlight = false
                    setStatusText("启动请求失败，未产生设备用水")
                    Logger.log("下费率异常: " + e.message)
                    updateActionButtons()
                }
            }
        }.start()
    }

    private fun uploadData(collectPayload: ByteArray) {
        val u = user ?: return
        val xfData = FrameUtils.toHex(collectPayload)
        Logger.log("上传消费 xfData=" + xfData)
        Thread {
            try {
                val r: BaseResponse<UploadData> = Api.uploadBluetoothData(u, protocolType, randomNumber, xfData)
                runOnUiThread {
                    if (r.errorCode in setOf(0, 206, 209) && r.data != null) {
                        val clData = r.data.clData ?: ""
                        val plain = try { JniUtils.decryptByAES(clData) } catch (e: Exception) { "" }
                        val dataHex = plain.substringAfter("-", "")
                        if (dataHex.isBlank() || FrameUtils.fromHex(dataHex) == null) {
                            settlementInFlight = false
                            setStatusText("已关阀，结算写回数据无效")
                            Logger.log("结算响应无法解密或格式无效")
                            updateActionButtons()
                            return@runOnUiThread
                        }
                        // 服务器已接收消费数据，此时预扣不再属于“启动失败”订单。
                        clearPendingRate()
                        setStatusText("已关阀，正在完成结算…")
                        Logger.log("写回设备存储 0x86")
                        ble.sendRawHex(0x86, dataHex)
                    } else {
                        settlementInFlight = false
                        collectRequested = false
                        setStatusText("已关阀，消费记录上传失败，请重连重试")
                        Logger.log("上传失败: " + r.errorCode + " " + r.errorMessage + " data=" + r.data)
                        updateActionButtons()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    settlementInFlight = false
                    collectRequested = false
                    setStatusText("已关阀，消费记录上传失败，请重连重试")
                    Logger.log("上传异常: " + e.message)
                    updateActionButtons()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        opHandler.removeCallbacksAndMessages(null)
        ApiClient.clearAuthInvalidListener(authInvalidListener)
        CmdServer.detach(this)
        ble.listener = null
        super.onDestroy()
    }

    companion object {
        private const val SAFETY_PREFS = "bath_safety"
        private const val KEY_PENDING_DATE = "pending_consume_date"
        private const val KEY_PENDING_MAC = "pending_mac"
        private const val START_ACK_TIMEOUT_MS = 6_000L
        private const val START_VERIFY_TIMEOUT_MS = 3_000L
        private const val STOP_ACK_TIMEOUT_MS = 1_600L
        private const val STOP_VERIFY_TIMEOUT_MS = 2_500L
        private const val MAX_STOP_ATTEMPTS = 2
        private const val SESSION_CHECK_INTERVAL_MS = 30_000L
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
