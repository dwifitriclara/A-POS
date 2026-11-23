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
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class BluetoothPrinterBridge(private val context: Context) {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun getPairedPrinters(): String {
        val result = JSONArray()
        val adapter = getBluetoothAdapter() ?: return result.toString()

        if (!hasBluetoothPermission() || !adapter.isEnabled) {
            return result.toString()
        }

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
        for (device in pairedDevices) {
            val obj = JSONObject()
            obj.put("name", device.name ?: "Printer Bluetooth")
            obj.put("address", device.address)
            result.put(obj)
        }
        return result.toString()
    }

    @JavascriptInterface
    fun getPairedDevices(): String = getPairedPrinters()

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun print(macAddress: String, content: String): String {
        val response = JSONObject()

        if (!hasBluetoothPermission()) {
            return response
                .put("success", false)
                .put("error", "Izin Bluetooth belum diberikan. Buka Pengaturan Aplikasi > Izin > Perangkat di sekitar.")
                .toString()
        }

        val adapter = getBluetoothAdapter()
        if (adapter == null) {
            return response
                .put("success", false)
                .put("error", "Tablet ini tidak mendukung Bluetooth.")
                .toString()
        }

        if (!adapter.isEnabled) {
            return response
                .put("success", false)
                .put("error", "Bluetooth tablet mati. Nyalakan Bluetooth terlebih dahulu.")
                .toString()
        }

        if (macAddress.isBlank()) {
            return response
                .put("success", false)
                .put("error", "Printer belum dipilih. Pilih VSC MP-58A di menu Pengaturan Printer.")
                .toString()
        }

        var socket: BluetoothSocket? = null
        return try {
            val device: BluetoothDevice = adapter.getRemoteDevice(macAddress.trim())
            adapter.cancelDiscovery()

            socket = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: Exception) {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }

            socket.connect()
            val outputStream = socket.outputStream

            outputStream.write(byteArrayOf(0x1B, 0x40))
            outputStream.write(byteArrayOf(0x1B, 0x74, 0x00))
            val cleanText = content.replace("\r\n", "\n")
            outputStream.write(cleanText.toByteArray(Charsets.ISO_8859_1))
            outputStream.write("\n\n\n\n".toByteArray(Charsets.ISO_8859_1))
            outputStream.flush()

            Thread.sleep(250)
            response.put("success", true).toString()
        } catch (e: IllegalArgumentException) {
            response
                .put("success", false)
                .put("error", "Alamat MAC printer tidak valid ($macAddress).")
                .toString()
        } catch (e: IOException) {
            response
                .put("success", false)
                .put("error", "Gagal terhubung ke printer. Pastikan VSC MP-58A menyala, tidak kehabisan kertas, dan berada dekat tablet.")
                .toString()
        } catch (e: Exception) {
            response
                .put("success", false)
                .put("error", "Error cetak: ${e.localizedMessage ?: "Tidak diketahui"}")
                .toString()
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    @JavascriptInterface
    fun printText(macAddress: String, content: String): String = print(macAddress, content)

    @JavascriptInterface
    fun printReceipt(macAddress: String, content: String): String = print(macAddress, content)

    @JavascriptInterface
    fun testPrint(macAddress: String): String {
        val sample = buildString {
            appendLine("================================")
            appendLine("         TES CETAK A-POS        ")
            appendLine("   VSC MP-58A (32 KARAKTER)     ")
            appendLine("================================")
            appendLine("12345678901234567890123456789012")
            appendLine("Status : Koneksi SPP Berhasil")
            appendLine("--------------------------------")
        }
        return print(macAddress, sample)
    }
}
