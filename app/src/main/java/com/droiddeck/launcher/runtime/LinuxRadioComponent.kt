package com.droiddeck.launcher.runtime

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.io.IOException
import org.json.JSONObject

class LinuxRadioComponent(
    context: Context,
    private val rootDir: File,
) : SessionPart() {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    @Suppress("DEPRECATION")
    private val bluetooth: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private val requests = object : Runnable {
        override fun run() {
            take(WIFI_REQUEST)?.let(::setWifi)
            take(BLUETOOTH_REQUEST)?.let(::setBluetooth)
            if (receiver != null) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun start() {
        for (request in listOf(WIFI_REQUEST, BLUETOOTH_REQUEST)) {
            File(rootDir, request).delete()
            File(rootDir, "$request.claimed").delete()
        }
        val registered = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = publish()
        }
        receiver = registered
        appContext.registerReceiver(registered, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
        })
        publish()
        handler.post(requests)
    }

    override fun stop() {
        handler.removeCallbacks(requests)
        receiver?.let { appContext.unregisterReceiver(it) }
        receiver = null
    }

    private fun publish() {
        val adapter = bluetooth
        val state = JSONObject().apply {
            put("bluetooth", JSONObject().apply {
                put("present", adapter != null)
                put("enabled", adapter?.state == BluetoothAdapter.STATE_ON)
                val name = try { adapter?.name } catch (e: SecurityException) { null }
                name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            })
        }
        try {
            val file = File(rootDir, STATE_FILE)
            val staged = File(file.path + ".staged")
            staged.writeText(state.toString())
            if (!staged.renameTo(file)) throw IOException("Could not replace $file")
        } catch (e: IOException) {
            Log.w(TAG, "Could not publish the radio state", e)
        }
    }

    private fun take(path: String): Boolean? {
        val claimed = File(rootDir, "$path.claimed")
        if (!File(rootDir, path).renameTo(claimed)) return null
        val wanted = try { claimed.readText().trim() } catch (e: IOException) { null }
        claimed.delete()
        return when (wanted) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun setWifi(on: Boolean) {
        val accepted = try { wifiManager?.setWifiEnabled(on) } catch (e: SecurityException) { false }
        Log.i(TAG, "Wi-Fi ${if (on) "on" else "off"} for Steam: accepted=$accepted")
    }

    @Suppress("DEPRECATION")
    private fun setBluetooth(on: Boolean) {
        val adapter = bluetooth ?: return
        val accepted = try { if (on) adapter.enable() else adapter.disable() } catch (e: SecurityException) { false }
        Log.i(TAG, "Bluetooth ${if (on) "on" else "off"} for Steam: accepted=$accepted")
    }

    companion object {
        private const val TAG = "LinuxRadio"
        private const val STATE_FILE = "etc/droiddeck-radios.json"
        private const val WIFI_REQUEST = "etc/droiddeck-wifi-request"
        private const val BLUETOOTH_REQUEST = "etc/droiddeck-bluetooth-request"
        private const val POLL_MS = 500L
    }
}
