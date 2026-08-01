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
 *
 * Implementa [DiscoveryScanner] (interface comum a todo scanner desta
 * camada) - nenhuma mudança de comportamento em relação a antes desta
 * evolução, só a formalização do contrato e a adição de diagnóstico
 * estruturado (ver [DiscoveryDiagnostics]).
 */
class MdnsScanner(context: Context) : DiscoveryScanner {

    override val name: String = "mDNS"

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()

    override fun scan(
        onDeviceFound: (TvDevice) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        activeListeners.clear()
        DiscoveryDiagnostics.log(name, DiscoveryEventType.SCAN_STARTED, "tipos=${Constants.MDNS_SERVICE_TYPES}")
        var remainingTypes = Constants.MDNS_SERVICE_TYPES.size

        fun onTypeDone() {
            remainingTypes--
            if (remainingTypes <= 0) {
                DiscoveryDiagnostics.log(name, DiscoveryEventType.SCAN_FINISHED)
                onFinished()
            }
        }

        Constants.MDNS_SERVICE_TYPES.forEach { serviceType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    DiscoveryDiagnostics.log(name, DiscoveryEventType.REQUEST_SENT, "discoverServices($regType)")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    DiscoveryDiagnostics.log(name, DiscoveryEventType.RESPONSE_RECEIVED, "serviceFound: ${service.serviceName} ($serviceType)")
                    resolve(service, onDeviceFound)
                }

                override fun onServiceLost(service: NsdServiceInfo) { /* no-op */ }

                override fun onDiscoveryStopped(regType: String) {
                    onTypeDone()
                }

                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    DiscoveryDiagnostics.log(name, DiscoveryEventType.SCAN_ERROR, "$regType: errorCode=$errorCode")
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
                DiscoveryDiagnostics.log(name, DiscoveryEventType.SCAN_ERROR, "$serviceType: ${e.message}")
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
                DiscoveryDiagnostics.log(
                    name, DiscoveryEventType.DEVICE_DISCARDED,
                    "${serviceInfo.serviceName}: falha ao resolver (errorCode=$errorCode)"
                )
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host == null) {
                    DiscoveryDiagnostics.log(name, DiscoveryEventType.DEVICE_DISCARDED, "${serviceInfo.serviceName}: sem host resolvido")
                    return
                }
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
                DiscoveryDiagnostics.log(name, DiscoveryEventType.DEVICE_CREATED, "$host -> nome=\"${device.name}\" os=${device.os}")
                DiscoveryDiagnostics.log(name, DiscoveryEventType.DEVICE_FORWARDED, "$host encaminhado ao DiscoveryAggregator")
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

    override fun stop() {
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
