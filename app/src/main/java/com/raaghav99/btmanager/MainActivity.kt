package com.raaghav99.btmanager

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var a2dpProxy: BluetoothProfile? = null
    private var isConnecting = false

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    toast("Connected: ${device?.name}")
                    isConnecting = false
                    releaseProxy()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (isConnecting) toast("Connection failed — try again")
                    isConnecting = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        registerReceiver(btReceiver, IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        loadDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(btReceiver)
        releaseProxy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadDevices()
    }

    private fun loadDevices() {
        val devices = btAdapter?.bondedDevices?.toList() ?: emptyList()
        val list = findViewById<RecyclerView>(R.id.deviceList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = DeviceAdapter(devices, ::connectDevice)
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (isConnecting) return
        isConnecting = true
        toast("Connecting to ${device.name}…")

        btAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dpProxy = proxy
                try {
                    // Skip disconnect if this device is already connected
                    val connected = proxy.connectedDevices
                    val alreadyConnected = connected.any { it.address == device.address }

                    if (!alreadyConnected) {
                        // Disconnect others first, then wait before connecting
                        connected.forEach { other ->
                            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                .apply { isAccessible = true }
                                .invoke(proxy, other)
                        }
                        // Wait for disconnect to settle before connecting
                        Thread.sleep(if (connected.isNotEmpty()) 1000L else 0L)
                    }

                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        .apply { isAccessible = true }
                        .invoke(proxy, device)

                    // Proxy kept alive — released only after connection confirmed via BroadcastReceiver
                } catch (e: Exception) {
                    toast("Error: ${e.message}")
                    isConnecting = false
                    releaseProxy()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                a2dpProxy = null
            }
        }, BluetoothProfile.A2DP)
    }

    private fun releaseProxy() {
        a2dpProxy?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dpProxy = null
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

class DeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = devices[position]
        holder.name.text = device.name ?: "Unknown"
        holder.address.text = device.address
        holder.itemView.setOnClickListener { onClick(device) }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
    }

    override fun getItemCount() = devices.size
}
