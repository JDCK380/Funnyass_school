package com.funnyass.test.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.funnyass.test.Logger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生 BLE 管理：扫描 → 连接 → 打开 FF01 通知 → FF02 写。
 * 线上帧 = '#' + 二进制帧HEX(ASCII) + 0x0A（见 FrameUtils）。
 */
@SuppressLint("MissingPermission")
class BleManager(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        val NOTIFY_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onScanResult(device: BluetoothDevice, rssi: Int)
        fun onStateChanged(state: Int, msg: String)
        fun onFrame(cmd: Int, payload: ByteArray)   // 收到设备回帧（二进制帧已拆好）
        fun onWriteDone(cmd: Int, success: Boolean)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val rxBuffer = java.io.ByteArrayOutputStream()
    private val writeLock = Any()
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var lastWriteStatus = BluetoothGatt.GATT_FAILURE

    private val scanTimeout = Runnable {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        listener?.onStateChanged(11, "扫描完成")
    }

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun startScan() {
        val a = adapter ?: return
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            main.post { listener?.onStateChanged(5, "蓝牙未开启") }
            return
        }
        stopScan()
        try {
            scanner.startScan(scanCallback)
            main.post { listener?.onStateChanged(10, "扫描中…") }
            main.postDelayed(scanTimeout, 12_000)
        } catch (e: Exception) {
            main.post { listener?.onStateChanged(5, "扫描启动失败") }
        }
    }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = try { dev.name } catch (_: Exception) { null }
            main.post { listener?.onScanResult(dev, result.rssi) }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { listener?.onStateChanged(5, "扫描失败 $errorCode") }
        }
    }

    fun connect(mac: String) {
        val a = adapter ?: return
        val dev = try { a.getRemoteDevice(mac) } catch (e: Exception) { null }
        if (dev == null) { main.post { listener?.onStateChanged(5, "无效 MAC") }; return }
        stopScan()
        main.post { listener?.onStateChanged(1, "连接中…") }
        try {
            gatt = dev.connectGatt(ctx, false, gattCallback)
        } catch (e: Exception) {
            main.post { listener?.onStateChanged(5, "connectGatt 失败") }
        }
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        rxBuffer.reset()
    }

    /** 发送命令：cmd + data，自动组帧并拆 20B/片写入 */
    fun send(cmd: Int, data: ByteArray = ByteArray(0)) {
        val wire = FrameUtils.cmdToWire(cmd, data)
        writeWire(cmd, wire)
    }

    fun sendRawHex(cmd: Int, downDataHex: String) {
        val data = FrameUtils.fromHex(downDataHex) ?: ByteArray(0)
        send(cmd, data)
    }

    private fun writeWire(cmd: Int, wire: ByteArray) {
        val c = writeChar
        val g = gatt
        if (c == null || g == null) {
            main.post { listener?.onWriteDone(cmd, false) }
            return
        }
        // 按 20B/片写，片间 20ms
        Thread {
            synchronized(writeLock) {
                Logger.log("BLE TX wire=" + FrameUtils.toHex(wire))
                var off = 0
                while (off < wire.size) {
                    val len = minOf(20, wire.size - off)
                    val chunk = wire.copyOfRange(off, off + len)
                    val latch = CountDownLatch(1)
                    writeLatch = latch
                    lastWriteStatus = BluetoothGatt.GATT_FAILURE
                    c.value = chunk
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    val accepted = g.writeCharacteristic(c)
                    Logger.log("BLE TX chunk=" + FrameUtils.toHex(chunk) + " accepted=" + accepted)
                    if (!accepted || !latch.await(3, TimeUnit.SECONDS) || lastWriteStatus != BluetoothGatt.GATT_SUCCESS) {
                        writeLatch = null
                        main.post { listener?.onWriteDone(cmd, false) }
                        return@synchronized
                    }
                    writeLatch = null
                    off += len
                }
                main.post { listener?.onWriteDone(cmd, true) }
            }
        }.start()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    main.post { listener?.onStateChanged(2, "已连接，发现服务…") }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    main.post { listener?.onStateChanged(4, "已断开") }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener?.onStateChanged(5, "发现服务失败 $status") }
                return
            }
            val svc = g.getService(SERVICE_UUID)
            val notify = svc?.getCharacteristic(NOTIFY_UUID)
            writeChar = svc?.getCharacteristic(WRITE_UUID)
            if (svc == null || notify == null || writeChar == null) {
                main.post { listener?.onStateChanged(5, "未找到 FF00/FF01/FF02 服务") }
                return
            }
            val enabled = g.setCharacteristicNotification(notify, true)
            val desc = notify.getDescriptor(CCCD_UUID)
            if (desc == null) {
                main.post { listener?.onStateChanged(5, "未找到 CCCD 描述符") }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
            // 等 onDescriptorWrite 成功后再发 state 3
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                main.post { listener?.onStateChanged(3, "通知已开启，等待设备回帧") }
            } else {
                main.post { listener?.onStateChanged(5, "通知开启失败 status=" + status) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            Logger.log("BLE RX raw=" + FrameUtils.toHex(bytes))
            rxBuffer.write(bytes)
            drainRx()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Logger.log("BLE TX result status=" + status)
            lastWriteStatus = status
            writeLatch?.countDown()
        }
    }

    private fun drainRx() {
        val all = rxBuffer.toByteArray()
        var idx = all.indexOfLast { it.toInt() == 0x0A }
        if (idx < 0) return
        val frameBytes = all.copyOfRange(0, idx + 1)
        rxBuffer.reset()
        rxBuffer.write(all, idx + 1, all.size - idx - 1)
        val frame = FrameUtils.parseWire(frameBytes)
        if (frame != null) {
            val cmd = frame[4].toInt() and 0xFF
            val payload = FrameUtils.parsePayload(frame) ?: ByteArray(0)
            main.post { listener?.onFrame(cmd, payload) }
        }
    }
}
