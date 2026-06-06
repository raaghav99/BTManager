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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var a2dpProxy: BluetoothProfile? = null
    private var isConnecting = false
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var discoveredAdapter: DeviceAdapter? = null
    private val handler = Handler(Looper.getMainLooper())

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    toast("Scanning for devices…")
                    discoveredDevices.clear()
                    discoveredAdapter?.notifyDataSetChanged()
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (it.name != null && !discoveredDevices.any { d -> d.address == it.address }) {
                            discoveredDevices.add(it)
                            discoveredAdapter?.notifyItemInserted(discoveredDevices.size - 1)
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    toast("Scan done — ${discoveredDevices.size} device(s) found")
                    findViewById<Button>(R.id.scanBtn).text = "Scan for New Devices"
                }

                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    device?.let { autoConfirmPairing(it, variant) }
                    abortBroadcast()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    when (newState) {
                        BluetoothDevice.BOND_BONDED -> {
                            toast("Paired: ${device?.name} — now connecting…")
                            device?.let { connectDevice(it) }
                            loadBondedDevices()
                        }
                        BluetoothDevice.BOND_NONE -> toast("Pairing failed: ${device?.name}")
                    }
                }

                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            toast("Connected: ${device?.name}")
                            isConnecting = false
                            releaseProxy()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (isConnecting) toast("Connection failed — retry")
                            isConnecting = false
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        }
        registerReceiver(btReceiver, filter)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 1)
                return
            }
        }

        setup()
    }

    override fun onDestroy() {
        super.onDestroy()
        btAdapter?.cancelDiscovery()
        unregisterReceiver(btReceiver)
        releaseProxy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) setup()
        else toast("Bluetooth permission required")
    }

    private fun setup() {
        loadBondedDevices()

        val scanBtn = findViewById<Button>(R.id.scanBtn)
        scanBtn.setOnClickListener {
            if (btAdapter?.isDiscovering == true) {
                btAdapter.cancelDiscovery()
                scanBtn.text = "Scan for New Devices"
            } else {
                btAdapter?.startDiscovery()
                scanBtn.text = "Stop Scan"
            }
        }

        val discoveredList = findViewById<RecyclerView>(R.id.discoveredList)
        discoveredList.layoutManager = LinearLayoutManager(this)
        discoveredAdapter = DeviceAdapter(discoveredDevices, "Pair") { device ->
            btAdapter?.cancelDiscovery()
            toast("Pairing with ${device.name}…")
            device.createBond()
        }
        discoveredList.adapter = discoveredAdapter
    }

    private fun loadBondedDevices() {
        val devices = btAdapter?.bondedDevices?.toList() ?: emptyList()
        val list = findViewById<RecyclerView>(R.id.bondedList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = DeviceAdapter(devices, "Connect", ::connectDevice)
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (isConnecting) return
        isConnecting = true
        toast("Connecting to ${device.name}…")

        btAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dpProxy = proxy
                try {
                    val connected = proxy.connectedDevices
                    val alreadyConnected = connected.any { it.address == device.address }

                    if (!alreadyConnected) {
                        connected.forEach { other ->
                            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                .apply { isAccessible = true }
                                .invoke(proxy, other)
                        }
                        Thread.sleep(if (connected.isNotEmpty()) 1000L else 0L)
                    }

                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        .apply { isAccessible = true }
                        .invoke(proxy, device)
                } catch (e: Exception) {
                    toast("Error: ${e.message}")
                    isConnecting = false
                    releaseProxy()
                }
            }
            override fun onServiceDisconnected(profile: Int) { a2dpProxy = null }
        }, BluetoothProfile.A2DP)
    }

    private fun autoConfirmPairing(device: BluetoothDevice, variant: Int) {
        try {
            when (variant) {
                // PAIRING_VARIANT_PASSKEY_CONFIRMATION (2) or PAIRING_VARIANT_CONSENT (3)
                2, 3 -> {
                    device.javaClass.getMethod("setPairingConfirmation", Boolean::class.java)
                        .apply { isAccessible = true }
                        .invoke(device, true)
                    toast("Auto-confirmed pairing: ${device.name}")
                }
                // PAIRING_VARIANT_PIN (0)
                0 -> {
                    device.javaClass.getMethod("setPin", ByteArray::class.java)
                        .apply { isAccessible = true }
                        .invoke(device, "0000".toByteArray())
                    toast("Auto-entered PIN for: ${device.name}")
                }
            }
        } catch (e: Exception) {
            toast("Auto-pair failed, confirm manually: ${e.message}")
        }
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
    private val actionLabel: String,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
        val action: TextView = view.findViewById(R.id.deviceAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = devices[position]
        holder.name.text = device.name ?: "Unknown"
        holder.address.text = device.address
        holder.action.text = actionLabel
        holder.itemView.setOnClickListener { onClick(device) }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
    }

    override fun getItemCount() = devices.size
}
