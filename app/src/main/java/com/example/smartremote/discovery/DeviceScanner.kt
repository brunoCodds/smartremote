package com.example.smartremote.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.smartremote.model.TvDevice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Ponto único de descoberta de dispositivos na rede local. Combina SSDP
 * (UPnP) e mDNS (NSD), deduplicando resultados por IP. É a única classe que
 * a UI (DeviceDiscoveryActivity) precisa conhecer - ela não sabe nada sobre
 * os protocolos usados internamente.
 */
class DeviceScanner(context: Context) {

    interface Listener {
        fun onDeviceFound(device: TvDevice)
        fun onScanFinished(devices: List<TvDevice>)
        fun onScanError(message: String)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val found = ConcurrentHashMap<String, TvDevice>() // key = ip

    private val ssdpScanner = SsdpScanner()
    private val mdnsScanner = MdnsScanner(context)

    @Volatile private var isScanning = false

    fun startScan(listener: Listener) {
        if (isScanning) return
        isScanning = true
        found.clear()

        var pending = 2
        fun finishIfDone() {
            pending--
            if (pending <= 0) {
                isScanning = false
                listener.onScanFinished(found.values.toList())
            }
        }

        val onDeviceFound: (TvDevice) -> Unit = { device ->
            if (found.putIfAbsent(device.ip, device) == null) {
                mainHandler.post { listener.onDeviceFound(device) }
            }
        }

        executor.execute {
            ssdpScanner.scan(
                onDeviceFound = onDeviceFound,
                onFinished = { mainHandler.post { finishIfDone() } },
                onError = { message -> mainHandler.post { listener.onScanError(message) } }
            )
        }

        mdnsScanner.scan(
            onDeviceFound = onDeviceFound,
            onFinished = { mainHandler.post { finishIfDone() } },
            onError = { message -> mainHandler.post { listener.onScanError(message) } }
        )
    }

    fun stopScan() {
        mdnsScanner.stop()
        isScanning = false
    }
}
