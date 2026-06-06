package com.raaghav99.btmanager

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var a2dpProxy: BluetoothProfile? = null
    private var isConnecting = false
    private var isActivityVisible = false
    private val connectedAddresses = mutableSetOf<String>()
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var discoveredAdapter: DeviceAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoConnectTimer: CountDownTimer? = null
    private var autoConnectDialog: AlertDialog? = null

    companion object {
        const val EXTRA_AUTO_CONNECT = "auto_connect_address"
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    toast("Scanning for devices…")
                    discoveredDevices.clear()
                    discoveredAdapter?.notifyDataSetChanged()
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = getDevice(intent)
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
                    val device = getDevice(intent)
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    // Try auto-confirm; if it fails (no BLUETOOTH_PRIVILEGED), let system show dialog
                    // Do NOT abort broadcast — system must receive it to show pairing dialog
                    device?.let { autoConfirmPairing(it, variant) }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = getDevice(intent)
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    when (newState) {
                        BluetoothDevice.BOND_BONDED -> {
                            toast("Paired: ${device?.name} — now connecting…")
                            device?.let { connectDevice(it) }
                            refreshDeviceLists()
                        }
                        BluetoothDevice.BOND_NONE -> {
                            device?.address?.let { connectedAddresses.remove(it) }
                            toast("Removed: ${device?.name}")
                            refreshDeviceLists()
                        }
                    }
                }

                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val device = getDevice(intent)
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            device?.address?.let { connectedAddresses.add(it) }
                            refreshDeviceLists()
                            if (!isConnecting) {
                                // Device auto-connected — ask user
                                device?.let { showAutoConnectDialog(it) }
                            } else {
                                toast("Connected: ${device?.name}")
                                isConnecting = false
                                releaseProxy()
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            device?.address?.let { connectedAddresses.remove(it) }
                            refreshDeviceLists()
                            if (isConnecting) {
                                toast("Connection failed — retry")
                                isConnecting = false
                            }
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

        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }

        setup()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        // Sync connected state from A2DP proxy
        syncConnectedState()
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        autoConnectTimer?.cancel()
        btAdapter?.cancelDiscovery()
        unregisterReceiver(btReceiver)
        releaseProxy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) setup()
        else toast("Bluetooth permission required")
    }

    private fun handleIntent(intent: Intent) {
        val address = intent.getStringExtra(EXTRA_AUTO_CONNECT) ?: return
        val device = btAdapter?.bondedDevices?.find { it.address == address } ?: return
        showAutoConnectDialogNow(device)
    }

    private fun syncConnectedState() {
        btAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxy.connectedDevices.forEach { connectedAddresses.add(it.address) }
                btAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                runOnUiThread { refreshDeviceLists() }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun setup() {
        if (!isAccessibilityEnabled()) {
            toast("Enable 'BT Manager Auto-Pair' in Settings → Accessibility for auto-pairing")
            handler.postDelayed({ startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, 2000)
        }

        refreshDeviceLists()

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
        discoveredAdapter = DeviceAdapter(
            devices = discoveredDevices,
            mode = DeviceMode.DISCOVERED,
            onPrimary = { device ->
                btAdapter?.cancelDiscovery()
                toast("Pairing with ${device.name}…")
                device.createBond()
            }
        )
        discoveredList.adapter = discoveredAdapter
    }

    private fun refreshDeviceLists() {
        val bonded = btAdapter?.bondedDevices?.toList() ?: emptyList()
        val connected = bonded.filter { it.address in connectedAddresses }
        val pairedOnly = bonded.filter { it.address !in connectedAddresses }

        val connectedHeader = findViewById<TextView>(R.id.connectedHeader)
        val connectedList = findViewById<RecyclerView>(R.id.connectedList)
        val bondedList = findViewById<RecyclerView>(R.id.bondedList)

        // Connected section
        if (connected.isNotEmpty()) {
            connectedHeader.visibility = View.VISIBLE
            connectedList.visibility = View.VISIBLE
            connectedList.layoutManager = LinearLayoutManager(this)
            connectedList.adapter = DeviceAdapter(
                devices = connected,
                mode = DeviceMode.CONNECTED,
                onPrimary = { device -> disconnectDevice(device) },
                onForget = { device -> confirmForget(device) }
            )
        } else {
            connectedHeader.visibility = View.GONE
            connectedList.visibility = View.GONE
        }

        // Paired (not connected) section
        bondedList.layoutManager = LinearLayoutManager(this)
        bondedList.adapter = DeviceAdapter(
            devices = pairedOnly,
            mode = DeviceMode.BONDED,
            onPrimary = { device -> connectDevice(device) },
            onForget = { device -> confirmForget(device) }
        )
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

    private fun disconnectDevice(device: BluetoothDevice) {
        toast("Disconnecting ${device.name}…")
        btAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        .apply { isAccessible = true }
                        .invoke(proxy, device)
                } catch (e: Exception) {
                    toast("Disconnect failed: ${e.message}")
                } finally {
                    handler.postDelayed({ btAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }, 500)
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun confirmForget(device: BluetoothDevice) {
        AlertDialog.Builder(this)
            .setTitle("Forget Device")
            .setMessage("Remove ${device.name} from paired devices?")
            .setPositiveButton("Forget") { _, _ -> forgetDevice(device) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun forgetDevice(device: BluetoothDevice) {
        try {
            device.javaClass.getMethod("removeBond")
                .apply { isAccessible = true }
                .invoke(device)
            toast("Forgotten: ${device.name}")
        } catch (e: Exception) {
            toast("Could not forget: ${e.message}")
        }
    }

    private fun showAutoConnectDialog(device: BluetoothDevice) {
        if (isActivityVisible) {
            showAutoConnectDialogNow(device)
        } else {
            // Bring MainActivity to front with the device info
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_AUTO_CONNECT, device.address)
            })
        }
    }

    private fun showAutoConnectDialogNow(device: BluetoothDevice) {
        autoConnectTimer?.cancel()
        autoConnectDialog?.dismiss()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Device Auto-Connected")
            .setMessage("${device.name ?: device.address} connected automatically.\n\nAllow? (60s)")
            .setPositiveButton("Allow") { _, _ ->
                autoConnectTimer?.cancel()
                toast("Connection allowed")
            }
            .setNegativeButton("Disconnect") { _, _ ->
                autoConnectTimer?.cancel()
                disconnectDevice(device)
            }
            .setCancelable(false)
            .create()

        dialog.show()
        autoConnectDialog = dialog

        autoConnectTimer = object : CountDownTimer(60_000, 1000) {
            override fun onTick(ms: Long) {
                val s = (ms / 1000).toInt()
                dialog.setMessage("${device.name ?: device.address} connected automatically.\n\nAllow? (${s}s)")
            }
            override fun onFinish() {
                dialog.dismiss()
                disconnectDevice(device)
                toast("Auto-disconnected: ${device.name}")
            }
        }.start()
    }

    private fun autoConfirmPairing(device: BluetoothDevice, variant: Int) {
        try {
            when (variant) {
                2, 3 -> device.javaClass.getMethod("setPairingConfirmation", Boolean::class.java)
                    .apply { isAccessible = true }.invoke(device, true)
                0 -> device.javaClass.getMethod("setPin", ByteArray::class.java)
                    .apply { isAccessible = true }.invoke(device, "0000".toByteArray())
            }
            toast("Auto-confirmed pairing: ${device.name}")
        } catch (e: Exception) {
            toast("Confirm in dialog: ${device.name}")
        }
    }

    private fun releaseProxy() {
        a2dpProxy?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dpProxy = null
    }

    private fun getDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

enum class DeviceMode { CONNECTED, BONDED, DISCOVERED }

class DeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val mode: DeviceMode,
    private val onPrimary: (BluetoothDevice) -> Unit,
    private val onForget: ((BluetoothDevice) -> Unit)? = null
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
        val status: TextView = view.findViewById(R.id.deviceStatus)
        val action: TextView = view.findViewById(R.id.deviceAction)
        val forget: TextView = view.findViewById(R.id.forgetBtn)
        val dot: View = view.findViewById(R.id.statusDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = devices[position]
        holder.name.text = device.name ?: "Unknown"
        holder.address.text = device.address

        when (mode) {
            DeviceMode.CONNECTED -> {
                holder.dot.visibility = View.VISIBLE
                holder.dot.setBackgroundColor(0xFF00CC66.toInt())
                holder.status.visibility = View.VISIBLE
                holder.status.text = "● Connected"
                holder.action.text = "Disconnect"
                holder.action.setTextColor(0xFFFF6644.toInt())
                holder.forget.visibility = View.VISIBLE
            }
            DeviceMode.BONDED -> {
                holder.dot.visibility = View.VISIBLE
                holder.dot.setBackgroundColor(0xFF888888.toInt())
                holder.status.visibility = View.GONE
                holder.action.text = "Connect"
                holder.action.setTextColor(0xFF4488FF.toInt())
                holder.forget.visibility = View.VISIBLE
            }
            DeviceMode.DISCOVERED -> {
                holder.dot.visibility = View.GONE
                holder.status.visibility = View.GONE
                holder.action.text = "Pair"
                holder.action.setTextColor(0xFF4488FF.toInt())
                holder.forget.visibility = View.GONE
            }
        }

        holder.action.setOnClickListener { onPrimary(device) }
        holder.itemView.setOnClickListener { onPrimary(device) }
        holder.forget.setOnClickListener { onForget?.invoke(device) }

        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
        holder.action.isFocusable = true
        holder.forget.isFocusable = true
    }

    override fun getItemCount() = devices.size
}
