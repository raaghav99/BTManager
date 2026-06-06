package com.raaghav99.btmanager

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
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

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        loadDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadDevices()
    }

    private fun loadDevices() {
        val devices = adapter?.bondedDevices?.toList() ?: emptyList()
        val list = findViewById<RecyclerView>(R.id.deviceList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = DeviceAdapter(devices, ::connectDevice)
    }

    private fun connectDevice(device: BluetoothDevice) {
        toast("Connecting to ${device.name}…")
        adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    // Disconnect all currently connected devices on this profile
                    proxy.connectedDevices.forEach { connected ->
                        proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            .apply { isAccessible = true }
                            .invoke(proxy, connected)
                    }
                    // Connect selected device
                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        .apply { isAccessible = true }
                        .invoke(proxy, device)
                    toast("Connected: ${device.name}")
                } catch (e: Exception) {
                    toast("Error: ${e.message}")
                } finally {
                    adapter.closeProfileProxy(profile, proxy)
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        // Also connect HID profile (remotes/keyboards)
        adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        .apply { isAccessible = true }
                        .invoke(proxy, device)
                } catch (_: Exception) {}
                finally { adapter.closeProfileProxy(profile, proxy) }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HID_DEVICE)
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
