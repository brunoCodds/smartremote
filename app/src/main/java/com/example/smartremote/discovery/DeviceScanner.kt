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
 * (UPnP) e mDNS (NSD), deduplicando resultados por [TvDevice.stableKey] - e
 * não mais por IP. Isso evita que a mesma TV pareada apareça duplicada na
 * lista quando o IP dela mudar (renovação de DHCP) entre uma busca e outra,
 * ou mesmo dentro da mesma busca caso SSDP e mDNS respondam com IPs
 * diferentes momentaneamente.
 *
 * É a única classe que a UI (DeviceDiscoveryActivity) precisa conhecer -
 * ela não sabe nada sobre os protocolos usados internamente.
 */
class DeviceScanner(context: Context) {

    interface Listener {
        fun onDeviceFound(device: TvDevice)
        fun onScanFinished(devices: List<TvDevice>)
        fun onScanError(message: String)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val found = ConcurrentHashMap<String, TvDevice>() // key = device.stableKey()

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

        val onDeviceFound: (TvDevice) -> Unit = onDeviceFound@{ device ->
            val key = device.stableKey()
            val alreadyKnownByKey = found.containsKey(key)

            // A mesma TV física pode responder por SSDP E mDNS ao mesmo
            // tempo (ex: Samsung expõe UPnP local e também DIAL/Google
            // Cast para receber apps). Cada protocolo gera um deviceId
            // diferente por natureza - não há uma chave em comum entre
            // eles sem buscar dado adicional - então usamos o IP como
            // heurística secundária apenas para não duplicar a MESMA TV
            // na lista quando isso acontecer. Mantém sempre a primeira
            // encontrada; não afeta a deduplicação primária por stableKey
            // (que continua sendo por deviceId, não por IP).
            val alreadyKnownByIp = !alreadyKnownByKey && found.values.any { it.ip == device.ip }

            if (alreadyKnownByKey || alreadyKnownByIp) return@onDeviceFound

            if (found.putIfAbsent(key, device) == null) {
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
