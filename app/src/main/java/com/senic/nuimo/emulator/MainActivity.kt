package com.senic.nuimo.emulator

import android.bluetooth.BluetoothDevice
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.text.Html
import android.widget.TextView
import android.widget.Toast
import butterknife.bindView

class MainActivity : AppCompatActivity(), NuimoListener, NuimoView.GestureEventListener {
    companion object {
        val TAG = "Nuimo.MainActivity"
    }

    val nuimo: Nuimo by lazy{ Nuimo(this).apply{ listener = this@MainActivity } }

    val nuimoView: NuimoView by bindView(R.id.nuimo)
    val statusTextView: TextView by bindView(R.id.status)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nuimoView.gestureEventListener = this
        nuimoView.isEnabled = false

        updateStatusLabel()

        nuimo.enabled = true
    }

    override fun onDestroy() {
        nuimo.enabled = false

        super.onDestroy()
    }

    private fun updateStatusLabel() {
        val connectionState = when {
            nuimo.connectedDevice != null -> "Connected to ${(nuimo.connectedDevice as BluetoothDevice).address}"
            nuimo.isAdvertising           -> "Advertising (waiting for connection requests)"
            else                          -> ""
        }
        statusTextView.text = Html.fromHtml(when {
            !nuimo.bluetoothSupported -> "Your Android device does not support bluetooth advertising (peripheral mode). Please use a different Android device to run the Nuimo emulator."
            nuimo.on                  -> "<b>Nuimo is On</b><br/>Disable bluetooth to power off Nuimo<br/>$connectionState"
            else                      -> "<b>Nuimo is Off</b><br/>Enable bluetooth to power on Nuimo<br/>$connectionState"
        })
    }

    /*
     * NuimoListener
     */

    override fun onPowerOn() {
        updateStatusLabel()
        nuimoView.isEnabled = true
        nuimoView.displayLedMatrix(intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 1, 1, 0, 1, 1, 0, 0,
                0, 1, 1, 1, 1, 1, 1, 1, 0,
                1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1,
                0, 1, 1, 1, 1, 1, 1, 1, 0,
                0, 0, 1, 1, 1, 1, 1, 0, 0,
                0, 0, 0, 1, 1, 1, 0, 0, 0,
                0, 0, 0, 0, 1, 0, 0, 0, 0).map { it == 1 }.toBooleanArray(), 1.0f, 0.0f)
    }

    override fun onPowerOff() {
        updateStatusLabel()
        nuimoView.isEnabled = false
    }

    override fun onStartAdvertising() {
        updateStatusLabel()
    }

    override fun onStopAdvertising() {
        updateStatusLabel()
    }

    override fun onStartAdvertisingFailure(errorCode: Int) {
        runOnUiThread { Toast.makeText(this, "BLE advertising not supported by this device", Toast.LENGTH_LONG).show() }
    }

    override fun onConnect(device: BluetoothDevice) {
        runOnUiThread {
            updateStatusLabel()
            Toast.makeText(this, "Connected to ${device.address}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDisconnect(device: BluetoothDevice) {
        runOnUiThread {
            updateStatusLabel()
            Toast.makeText(this, "Disconnected from ${device.address}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onReceiveLedMatrix(leds: BooleanArray, brightness: Float, displayInterval: Float) {
        runOnUiThread { nuimoView.displayLedMatrix(leds, brightness, displayInterval) }
    }

    /*
     * NuimoView.GestureEventListener
     */

    override fun onButtonPress() {
        nuimo.pressButton()
    }

    override fun onButtonRelease() {
        nuimo.releaseButton()
    }

    override fun onSwipe(direction: NuimoSwipeDirection) {
        nuimo.swipe(direction)
    }

    override fun onRotate(value: Float) {
        nuimo.rotate(value)
    }
}
