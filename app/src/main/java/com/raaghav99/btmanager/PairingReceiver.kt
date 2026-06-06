package com.raaghav99.btmanager

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)

        device?.let {
            try {
                when (variant) {
                    2, 3 -> it.javaClass.getMethod("setPairingConfirmation", Boolean::class.java)
                        .apply { isAccessible = true }.invoke(it, true)
                    0 -> it.javaClass.getMethod("setPin", ByteArray::class.java)
                        .apply { isAccessible = true }.invoke(it, "0000".toByteArray())
                }
                abortBroadcast()
            } catch (_: Exception) {}
        }
    }
}
