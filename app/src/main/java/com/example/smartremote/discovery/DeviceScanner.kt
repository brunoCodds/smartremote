package com.example.smartremote.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
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
 *
 * *** CORREÇÃO - serviço SSDP genérico "vencendo a corrida" e escondendo a
 * TV de verdade ***
 * Algumas TVs (observado em Samsung com middleware Ginga/SBTVD) anunciam,
 * pelo MESMO IP, um serviço SSDP adicional sem friendlyName/manufacturer -
 * o SsdpScanner então devolve um TvDevice genérico ("Dispositivo SSDP",
 * os=UNKNOWN) com uma stableKey diferente da TV real (uuid distinto).
 *
 * A dedup por IP abaixo existia para evitar duplicar a MESMA TV quando
 * SSDP e mDNS respondem os dois - mas como não há garantia de ordem de
 * chegada dos pacotes UDP, se esse serviço genérico chegar ANTES da
 * resposta real e identificada da TV, ele "reservava" o IP primeiro e a
 * entrada de verdade (com marca/modelo/OS corretos) era descartada em
 * silêncio - fazendo a TV sumir da lista de forma intermitente (varia a
 * cada busca, conforme a ordem de chegada dos pacotes).
 *
 * Agora: dedup por IP só se aplica quando a entrada JÁ REGISTRADA para
 * aquele IP já é uma TV identificada (tem marca OU SO reconhecido). Se a
 * entrada já registrada for genérica e a nova trouxer identificação de
 * verdade, a nova SUBSTITUI a genérica (ver [Listener.onDeviceUpgraded])
 * em vez de ser descartada.
 */
class DeviceScanner(context: Context) {

    interface Listener {
        fun onDeviceFound(device: TvDevice)

        /**
         * Uma entrada anteriormente exibida (registrada com a stableKey
         * [previousKey], tipicamente genérica/sem identificação) foi
         * substituída por [device], uma versão identificada da MESMA TV
         * física (mesmo IP). A UI deve trocar o item já exibido, não
         * adicionar um novo.
         */
        fun onDeviceUpgraded(previousKey: String, device: TvDevice)

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

            // Mesma chave estável já vista nesta rodada - reanúncio do
            // mesmo serviço (ex: SSDP respondendo de novo ao ssdp:all).
            if (found.containsKey(key)) return@onDeviceFound

            val existingAtSameIp = found.values.firstOrNull { it.ip == device.ip }

            if (existingAtSameIp != null) {
                val existingIsGeneric =
                    existingAtSameIp.brand == null && existingAtSameIp.os == TvOperatingSystem.UNKNOWN
                val newIsMoreInformative =
                    device.brand != null || device.os != TvOperatingSystem.UNKNOWN

                if (existingIsGeneric && newIsMoreInformative) {
                    // A entrada nova identifica de verdade a TV que antes
                    // só tinha uma entrada genérica no mesmo IP - substitui.
                    val previousKey = existingAtSameIp.stableKey()
                    found.remove(previousKey)
                    found[key] = device
                    mainHandler.post { listener.onDeviceUpgraded(previousKey, device) }
                } else {
                    // Mesma TV física já identificada (ou a nova também é
                    // genérica) - mantém a primeira encontrada, ignora esta.
                    return@onDeviceFound
                }
                return@onDeviceFound
            }

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
