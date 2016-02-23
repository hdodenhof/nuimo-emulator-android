/**
 *  Created by Lars Blumberg on 2/5/16.
 *  Copyright © 2015 Senic. All rights reserved.
 *
 *  This software may be modified and distributed under the terms
 *  of the MIT license.  See the LICENSE file for details. *
 */

package com.senic.nuimo.emulator

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.ParcelUuid
import android.util.Log
import java.util.*

class Nuimo(val context: Context) {
    companion object {
        val TAG = "Nuimo"
        val MAX_ROTATION_EVENTS_PER_SEC = 10
        val SINGLE_ROTATION_VALUE = 2800
    }

    val bluetoothSupported: Boolean
        get() = adapter != null

    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            when (value) {
                true  -> { context.registerReceiver(bluetoothStateChangeBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
                false -> { context.unregisterReceiver(bluetoothStateChangeBroadcastReceiver) }
            }
            updateOnOffState()
        }
    var listener: NuimoListener? = null
    var isAdvertising = false
        private set
    var connectedDevice: BluetoothDevice? = null
        private set

    var on = false
        private set

    private val bluetoothStateChangeBroadcastReceiver = BluetoothStateChangeBroadcastReceiver()
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private val adapter: BluetoothAdapter? = manager.adapter
    private val addedServices = HashSet<UUID>()
    private var advertiser: BluetoothLeAdvertiser? = null
    private val advertiserListener = NuimoAdvertiseCallback()
    private var subscribedCharacteristics = HashMap<UUID, BluetoothGattCharacteristic>()
    private var originalDeviceName: String? = null
    private var accumulatedRotationValue = 0.0f
    private var lastRotationEventNanos = System.nanoTime()

    private fun updateOnOffState() {
        when {
            enabled  && adapter?.state == BluetoothAdapter.STATE_ON          -> powerOn()
            !enabled || adapter?.state == BluetoothAdapter.STATE_TURNING_OFF -> powerOff()
            !enabled || adapter?.state == BluetoothAdapter.STATE_OFF         -> powerOff()
        }
    }

    private fun powerOn() {
        if (on || adapter == null) return

        setNuimoDeviceName()

        advertiser = adapter.bluetoothLeAdvertiser
        gattServer = manager.openGattServer(context, NuimoGattServerCallback())
        if (advertiser == null || gattServer == null) {
            listener?.onStartAdvertisingFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            return
        }

        on = true

        NUIMO_SERVICE_UUIDS.forEach {
            gattServer!!.addService(BluetoothGattService(it, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                NUIMO_CHARACTERISTIC_UUIDS_FOR_SERVICE_UUID[it]!!.forEach {
                    val properties = PROPERTIES_FOR_CHARACTERISTIC_UUID[it]!!
                    val permissions = PERMISSIONS_FOR_CHARACTERISTIC_UUID[it]!!
                    addCharacteristic(BluetoothGattCharacteristic(it, properties, permissions).apply {
                        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0) {
                            addDescriptor(BluetoothGattDescriptor(CHARACTERISTIC_NOTIFICATION_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_WRITE))}})}})}

        listener?.onPowerOn()
    }

    private fun powerOff() {
        Log.i(TAG, "RESET")
        on = false
        stopAdvertising()
        addedServices.clear()
        accumulatedRotationValue = 0.0f
        subscribedCharacteristics.clear()
        if (connectedDevice != null) {
            disconnect(connectedDevice!!)
            connectedDevice = null
        }
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        advertiser = null
        resetDeviceName()
        listener?.onPowerOff()
    }

    private fun setNuimoDeviceName() {
        originalDeviceName = adapter?.name
        adapter?.name = "Nuimo"
    }

    private fun resetDeviceName() {
        adapter?.name = originalDeviceName
        originalDeviceName = null
    }

    private fun disconnect(device: BluetoothDevice) {
        gattServer?.cancelConnection(device)
        subscribedCharacteristics.clear()
        listener?.onDisconnect(device)
    }

    /*
     * User input
     */

    fun pressButton() {
        notifyCharacteristicChanged(SENSOR_BUTTON_CHARACTERISTIC_UUID, 1, BluetoothGattCharacteristic.FORMAT_UINT8)
    }

    fun releaseButton() {
        notifyCharacteristicChanged(SENSOR_BUTTON_CHARACTERISTIC_UUID, 0, BluetoothGattCharacteristic.FORMAT_UINT8)
    }

    fun swipe(direction: NuimoSwipeDirection) {
        notifyCharacteristicChanged(SENSOR_TOUCH_CHARACTERISTIC_UUID, direction.gattValue, BluetoothGattCharacteristic.FORMAT_UINT8)
    }

    fun rotate(value: Float) {
        accumulatedRotationValue += value
        when {
            accumulatedRotationValue == 0.0f -> return
            1000000000.0f / (System.nanoTime() - lastRotationEventNanos) > MAX_ROTATION_EVENTS_PER_SEC -> return
        }
        if (notifyCharacteristicChanged(SENSOR_ROTATION_CHARACTERISTIC_UUID, (SINGLE_ROTATION_VALUE * accumulatedRotationValue).toInt(), BluetoothGattCharacteristic.FORMAT_SINT16)) {
            accumulatedRotationValue = 0.0f
            lastRotationEventNanos = System.nanoTime()
        }
    }

    private fun notifyCharacteristicChanged(characteristicUuid: UUID, value: Int, formatType: Int): Boolean {
        val gattServer = gattServer ?: return false
        val connectedDevice = connectedDevice ?: return false
        val characteristic = subscribedCharacteristics[characteristicUuid] ?: return false
        characteristic.setValue(value, formatType, 0)
        return gattServer.notifyCharacteristicChanged(connectedDevice, characteristic, false)
    }

    /*
     * Bluetooth GATT Handling
     */

    private fun startAdvertising() {
        val advertiser = advertiser ?: return
        if (isAdvertising) return
        isAdvertising = true

        Log.i(TAG, "START ADVERTISING")

        val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

        val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(SENSOR_SERVICE_UUID))
                .build()

        advertiser.startAdvertising(settings, data, advertiserListener)
    }

    private fun stopAdvertising() {
        val advertiser = advertiser ?: return
        if (!isAdvertising) return
        isAdvertising = false

        Log.i(TAG, "STOP ADVERTISING")

        // Stop advertising (throws exception at least on Android 4.4 if Bluetooth is already off)
        try { advertiser.stopAdvertising(advertiserListener) } catch(_: Exception) { }
        listener?.onStopAdvertising()
    }

    private inner class BluetoothStateChangeBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            updateOnOffState()
        }
    }

    private inner class NuimoAdvertiseCallback : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started")
            listener?.onStartAdvertising()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.i(TAG, "Cannot advertise, error: $errorCode")
            listener?.onStartAdvertisingFailure(errorCode)
        }
    }

    private inner class NuimoGattServerCallback : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            addedServices.add(service.uuid)
            Log.i(TAG, "SERVICE ${service.uuid} ADDED, count= " + addedServices.size)
            if (addedServices.size == NUIMO_SERVICE_UUIDS.size) {
                startAdvertising()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Connection state changed for device ${device.address} new state: $newState")
            // Only allow one connection, refuse all other connection requests
            if (connectedDevice != null && connectedDevice != device) {
                gattServer?.cancelConnection(device)
                return
            }
            val previousConnectedDevice = connectedDevice
            when {
                status   != BluetoothGatt.GATT_SUCCESS        -> connectedDevice = null
                newState == BluetoothGatt.STATE_CONNECTING    -> stopAdvertising()
                newState == BluetoothGatt.STATE_CONNECTED     -> connectedDevice = device
                newState == BluetoothGatt.STATE_DISCONNECTING -> connectedDevice = null
                newState == BluetoothGatt.STATE_DISCONNECTED  -> connectedDevice = null
            }
            when {
                connectedDevice != null && previousConnectedDevice == null -> listener?.onConnect(connectedDevice!!)
                connectedDevice == null && previousConnectedDevice != null -> { disconnect(previousConnectedDevice); startAdvertising() }
                connectedDevice == null && previousConnectedDevice == null -> startAdvertising()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            Log.i(TAG, "onCharacteristicReadRequest ${characteristic.uuid}, $requestId")
            when (characteristic.uuid) {
                BATTERY_CHARACTERISTIC_UUID -> {
                    Log.i(TAG, "SEND BATTERY READ RESPONSE")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(getBatteryLevel().toByte()))
                }
                else -> {
                    Log.i(TAG, "SEND UNKNOWN READ RESPONSE")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, byteArrayOf())
                }
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            Log.i(TAG, "onCharacteristicWriteRequest ${characteristic.uuid}, $requestId, responseNeeded=$responseNeeded")
            when (characteristic.uuid) {
                LED_MATRIX_CHARACTERISTIC_UUID -> {
                    Log.i(TAG, "SEND MATRIX WRITE RESPONSE")
                    val errorCode = when {
                        !responseNeeded                   -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        value == null || value.size != 13 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                        offset != 0                       -> BluetoothGatt.GATT_INVALID_OFFSET
                        else                              -> BluetoothGatt.GATT_SUCCESS
                    }
                    gattServer?.sendResponse(device, requestId, errorCode, 0, byteArrayOf())
                    if (errorCode == BluetoothGatt.GATT_SUCCESS) {
                        val leds = value!!.slice(0..10).flatMap {
                            val i = it.toInt() and 0xFF
                            ((0..7).map { i and (1 shl it) > 0 })
                        }.toBooleanArray()
                        listener?.onReceiveLedMatrix(leds, (value[11].toInt() and 0xFF) / 255.0f, (value[12].toInt() and 0xFF) / 10.0f)
                    }
                }
                else -> {
                    Log.i(TAG, "SEND UNKNOWN WRITE RESPONSE")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, byteArrayOf())
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            Log.i(TAG, "onDescriptorReadRequest ${descriptor.characteristic.uuid}, $requestId")
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            Log.i(TAG, "onDescriptorWriteRequest ${descriptor.characteristic.uuid}, $requestId")

            val responseStatus = when {
                (PROPERTIES_FOR_CHARACTERISTIC_UUID[descriptor.characteristic.uuid] ?: 0) and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                value == null                                                         -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                value.equalsArray(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)  -> { subscribedCharacteristics[descriptor.characteristic.uuid] = descriptor.characteristic; BluetoothGatt.GATT_SUCCESS }
                value.equalsArray(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> { subscribedCharacteristics.remove(descriptor.characteristic.uuid); BluetoothGatt.GATT_SUCCESS }
                else                                                                  -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            }
            gattServer?.sendResponse(device, requestId, responseStatus, 0, byteArrayOf())
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Log.i(TAG, "onNotificationSent")
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            Log.i(TAG, "onNotificationSent  $requestId, $execute")
            //TODO: Call gattServer?.sendResponse()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "onMtuChanged  $mtu")
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level == -1 || scale == -1) { return 0 }
        return Math.min(100, Math.max(0, (level.toFloat() / scale.toFloat() * 100.0f).toInt()))
    }
}

enum class NuimoSwipeDirection(val gattValue: Int) {
    LEFT(0), RIGHT(1), UP(2), DOWN(3)
}

interface NuimoListener {
    fun onPowerOn()
    fun onPowerOff()
    fun onStartAdvertising()
    fun onStartAdvertisingFailure(errorCode: Int)
    fun onStopAdvertising()
    fun onConnect(device: BluetoothDevice)
    fun onDisconnect(device: BluetoothDevice)
    fun onReceiveLedMatrix(leds: BooleanArray, brightness: Float, displayInterval: Float)
}

/*
 * Nuimo BLE GATT service and characteristic UUIDs
 */

private val BATTERY_SERVICE_UUID                        = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
private val BATTERY_CHARACTERISTIC_UUID                 = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
private val DEVICE_INFORMATION_SERVICE_UUID             = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
private val DEVICE_INFORMATION_CHARACTERISTIC_UUID      = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
private val LED_MATRIX_SERVICE_UUID                     = UUID.fromString("f29b1523-cb19-40f3-be5c-7241ecb82fd1")
private val LED_MATRIX_CHARACTERISTIC_UUID              = UUID.fromString("f29b1524-cb19-40f3-be5c-7241ecb82fd1")
private val SENSOR_SERVICE_UUID                         = UUID.fromString("f29b1525-cb19-40f3-be5c-7241ecb82fd2")
private val SENSOR_FLY_CHARACTERISTIC_UUID              = UUID.fromString("f29b1526-cb19-40f3-be5c-7241ecb82fd2")
private val SENSOR_TOUCH_CHARACTERISTIC_UUID            = UUID.fromString("f29b1527-cb19-40f3-be5c-7241ecb82fd2")
private val SENSOR_ROTATION_CHARACTERISTIC_UUID         = UUID.fromString("f29b1528-cb19-40f3-be5c-7241ecb82fd2")
private val SENSOR_BUTTON_CHARACTERISTIC_UUID           = UUID.fromString("f29b1529-cb19-40f3-be5c-7241ecb82fd2")
private val CHARACTERISTIC_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

val NUIMO_SERVICE_UUIDS = arrayOf(
        BATTERY_SERVICE_UUID,
        DEVICE_INFORMATION_SERVICE_UUID,
        LED_MATRIX_SERVICE_UUID,
        SENSOR_SERVICE_UUID
)

val NUIMO_CHARACTERISTIC_UUIDS_FOR_SERVICE_UUID = mapOf(
        BATTERY_SERVICE_UUID                   to arrayOf(BATTERY_CHARACTERISTIC_UUID),
        DEVICE_INFORMATION_SERVICE_UUID        to arrayOf(DEVICE_INFORMATION_CHARACTERISTIC_UUID),
        LED_MATRIX_SERVICE_UUID                to arrayOf(LED_MATRIX_CHARACTERISTIC_UUID),
        SENSOR_SERVICE_UUID                    to arrayOf(SENSOR_FLY_CHARACTERISTIC_UUID, SENSOR_TOUCH_CHARACTERISTIC_UUID, SENSOR_ROTATION_CHARACTERISTIC_UUID, SENSOR_BUTTON_CHARACTERISTIC_UUID)
)

val PROPERTIES_FOR_CHARACTERISTIC_UUID = mapOf(
        BATTERY_CHARACTERISTIC_UUID            to (BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY),
        DEVICE_INFORMATION_CHARACTERISTIC_UUID to BluetoothGattCharacteristic.PROPERTY_READ,
        LED_MATRIX_CHARACTERISTIC_UUID         to BluetoothGattCharacteristic.PROPERTY_WRITE,
        SENSOR_FLY_CHARACTERISTIC_UUID         to BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        SENSOR_TOUCH_CHARACTERISTIC_UUID       to BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        SENSOR_ROTATION_CHARACTERISTIC_UUID    to BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        SENSOR_BUTTON_CHARACTERISTIC_UUID      to BluetoothGattCharacteristic.PROPERTY_NOTIFY
)

val PERMISSIONS_FOR_CHARACTERISTIC_UUID = mapOf(
        BATTERY_CHARACTERISTIC_UUID            to (BluetoothGattCharacteristic.PERMISSION_READ),
        DEVICE_INFORMATION_CHARACTERISTIC_UUID to BluetoothGattCharacteristic.PERMISSION_READ,
        LED_MATRIX_CHARACTERISTIC_UUID         to BluetoothGattCharacteristic.PERMISSION_WRITE,
        SENSOR_FLY_CHARACTERISTIC_UUID         to BluetoothGattCharacteristic.PERMISSION_READ,
        SENSOR_TOUCH_CHARACTERISTIC_UUID       to BluetoothGattCharacteristic.PERMISSION_READ,
        SENSOR_ROTATION_CHARACTERISTIC_UUID    to BluetoothGattCharacteristic.PERMISSION_READ,
        SENSOR_BUTTON_CHARACTERISTIC_UUID      to BluetoothGattCharacteristic.PERMISSION_READ
)

fun ByteArray.equalsArray(other: ByteArray) = Arrays.equals(this, other)