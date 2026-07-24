package com.example.smartremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import com.example.smartremote.util.Constants

/**
 * Descoberta via mDNS/DNS-SD usando a API nativa NsdManager. Percorre os
 * service types comuns em Smart TVs (Chromecast, DIAL, AirPlay, webOS).
 */
class MdnsScanner(context: Context) {

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun scan(
        onDeviceFound: (TvDevice) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        activeListeners.clear()
        var remainingTypes = Constants.MDNS_SERVICE_TYPES.size

        fun onTypeDone() {
            remainingTypes--
            if (remainingTypes <= 0) onFinished()
        }

        Constants.MDNS_SERVICE_TYPES.forEach { serviceType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) { /* no-op */ }

                override fun onServiceFound(service: NsdServiceInfo) {
                    resolve(service, onDeviceFound)
                }

                override fun onServiceLost(service: NsdServiceInfo) { /* no-op */ }

                override fun onDiscoveryStopped(regType: String) {
                    onTypeDone()
                }

                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    onError("Falha ao iniciar mDNS ($regType): $errorCode")
                    onTypeDone()
                }

                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                    onTypeDone()
                }
            }

            activeListeners.add(listener)
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                onError("Erro ao buscar $serviceType: ${e.message}")
                onTypeDone()
                return@forEach
            }

            mainHandler.postDelayed({
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    // já parado ou nunca chegou a iniciar de fato
                }
            }, Constants.MDNS_DISCOVERY_TIMEOUT_MS)
        }
    }

    private fun resolve(service: NsdServiceInfo, onDeviceFound: (TvDevice) -> Unit) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // ignora silenciosamente; a descoberta continua com os demais serviços
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val device = TvDevice(
                    name = serviceInfo.serviceName,
                    brand = null,
                    model = null,
                    ip = host,
                    port = serviceInfo.port,
                    protocol = DeviceProtocol.MDNS,
                    os = guessOsFromServiceType(serviceInfo.serviceType),
                    deviceId = serviceInfo.serviceName,
                    connected = false
                )
                mainHandler.post { onDeviceFound(device) }
            }
        })
    }

    private fun guessOsFromServiceType(serviceType: String): TvOperatingSystem = when {
        serviceType.contains("googlecast") -> TvOperatingSystem.GOOGLE_TV
        serviceType.contains("androidtvremote") -> TvOperatingSystem.ANDROID_TV
        serviceType.contains("webos") -> TvOperatingSystem.WEBOS
        else -> TvOperatingSystem.UNKNOWN
    }

    fun stop() {
        activeListeners.forEach {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // ignora - listener já parado
            }
        }
        activeListeners.clear()
    }
}
