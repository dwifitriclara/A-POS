package com.kasirkopi.pos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

class BluetoothPrinterBridge(private val context: Context) {

    private var webView: WebView? = null

    constructor(context: Context, webView: WebView) : this(context) {
        this.webView = webView
    }

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectedMac: String = ""

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @JavascriptInterface
    fun isBluetoothAvailable(): Boolean {
        val adapter = getBluetoothAdapter()
        return adapter != null && adapter.isEnabled
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun getPairedDevices(): String {
        val result = JSONArray()
        val adapter = getBluetoothAdapter() ?: return result.toString()
        if (!adapter.isEnabled || !hasBluetoothPermission()) {
            return result.toString()
        }

        try {
            val pairedDevices: Set<BluetoothDevice>? = adapter.bondedDevices
            pairedDevices?.forEach { device ->
                val obj = JSONObject()
                obj.put("name", device.name ?: "Perangkat Bluetooth")
                obj.put("address", device.address ?: "")
                result.put(obj)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.toString()
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun connect(macAddress: String): Boolean {
        if (macAddress.isBlank() || !hasBluetoothPermission()) return false
        val adapter = getBluetoothAdapter() ?: return false
        if (!adapter.isEnabled) return false

        if (isConnected() && connectedMac.equals(macAddress, ignoreCase = true)) {
            return true
        }

        disconnect()

        return try {
            adapter.cancelDiscovery()
            val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
            val socket = device.createRfcommSocketToServiceRecord(sppUuid)
            socket.connect()
            bluetoothSocket = socket
            outputStream = socket.outputStream
            connectedMac = macAddress
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    @JavascriptInterface
    fun connectPrinter(macAddress: String): Boolean {
        return connect(macAddress)
    }

    @JavascriptInterface
    fun isConnected(): Boolean {
        return try {
            bluetoothSocket?.isConnected == true && outputStream != null
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun getConnectedAddress(): String {
        return if (isConnected()) connectedMac else ""
    }

    @JavascriptInterface
    fun disconnect(): Boolean {
        return try {
            outputStream?.close()
            bluetoothSocket?.close()
            outputStream = null
            bluetoothSocket = null
            connectedMac = ""
            true
        } catch (e: Exception) {
            outputStream = null
            bluetoothSocket = null
            connectedMac = ""
            false
        }
    }

    @JavascriptInterface
    fun printRawBase64(base64Data: String): Boolean {
        if (base64Data.isBlank()) return false
        if (!isConnected() && connectedMac.isNotBlank()) {
            connect(connectedMac)
        }
        val out = outputStream ?: return false

        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val chunkSize = 384
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(chunkSize, bytes.size - offset)
                out.write(bytes, offset, len)
                out.flush()
                offset += len
                if (offset < bytes.size) {
                    Thread.sleep(15)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    @JavascriptInterface
    fun printText(text: String): Boolean {
        if (!isConnected() && connectedMac.isNotBlank()) {
            connect(connectedMac)
        }
        val out = outputStream ?: return false

        return try {
            val initCmd = byteArrayOf(0x1B, 0x40)
            val payload = (text + "\n\n\n").toByteArray(Charsets.UTF_8)
            out.write(initCmd)
            out.write(payload)
            out.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    @JavascriptInterface
    fun printReceipt(text: String): Boolean {
        return printText(text)
    }
}
