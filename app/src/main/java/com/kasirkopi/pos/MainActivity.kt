package com.kasirkopi.pos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var btSocket: BluetoothSocket? = null
    private var btOutputStream: OutputStream? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Launcher Pemilih Foto / File JSON untuk WebView (<input type="file">)
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        // Wajib dipanggil agar tombol upload foto tidak pernah macet saat dibatalkan
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNeededPermissions()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(AndroidPrinterBridge(), "AndroidPrinter")
        webView.webViewClient = WebViewClient()

        // Mengaktifkan dialog pilih foto untuk Logo Struk, QRIS, dan Restore JSON
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/json"))
                }

                return try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "Pilih Gambar / File"))
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    inner class AndroidPrinterBridge {
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun getPairedDevices(): String {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "[]"
            val arr = JSONArray()
            adapter.bondedDevices?.forEach { device: BluetoothDevice ->
                val obj = JSONObject()
                obj.put("name", device.name ?: "Bluetooth Printer")
                obj.put("address", device.address)
                arr.put(obj)
            }
            return arr.toString()
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun connect(macAddress: String): Boolean {
            return try {
                btOutputStream?.close()
                btSocket?.close()
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
                val device = adapter.getRemoteDevice(macAddress)
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                adapter.cancelDiscovery()
                socket.connect()
                btSocket = socket
                btOutputStream = socket.outputStream
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun printRawBase64(base64Data: String): Boolean {
            return try {
                val data = Base64.decode(base64Data, Base64.DEFAULT)
                btOutputStream?.write(data)
                btOutputStream?.flush()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
