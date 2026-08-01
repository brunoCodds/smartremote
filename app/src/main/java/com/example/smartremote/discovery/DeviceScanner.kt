package com.example.smartremote.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.smartremote.model.TvDevice
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Ponto único de descoberta de dispositivos na rede local. É a ÚNICA classe
 * que a UI (DeviceDiscoveryActivity) precisa conhecer - ela não sabe nada
 * sobre os protocolos usados internamente.
 *
 * *** EVOLUÇÃO - nova arquitetura da camada de Discovery ***
 * Esta classe manteve o NOME e a API PÚBLICA de antes (`startScan(listener)`
 * / `stopScan()`, mesma interface [Listener]) de propósito: a
 * `DeviceDiscoveryActivity` não precisa de NENHUMA mudança por causa desta
 * evolução. Por dentro, porém, o papel mudou - antes ela conhecia
 * diretamente o SsdpScanner e o MdnsScanner e fazia a dedup/merge inline
 * num `ConcurrentHashMap`; agora ela é só um ORQUESTRADOR (é o papel que a
 * proposta de arquitetura chamou de "DiscoveryManager" - o nome da classe
 * em código continua `DeviceScanner`, mesmo papel):
 *
 * ```
 * DeviceDiscoveryActivity
 *         │
 *         ▼
 *    DeviceScanner                  (orquestrador)
 *         │
 *         ├── DiscoveryCache             (estado temporário da rodada atual)
 *         │      └── DiscoveryAggregator (dedup/merge/confidence - ver seu KDoc)
 *         │
 *         ├── primaryScanners: List<DiscoveryScanner>  (descoberta "do zero":
 *         │        SSDP, mDNS - cada um só conhece o próprio protocolo)
 *         │
 *         └── samsungScanner: SamsungDiscoveryScanner   (CONFIRMAÇÃO: roda
 *                  DEPOIS dos primários, usando os IPs que eles já viram
 *                  nesta rodada - ver DiscoveryCache.candidateIps())
 * ```
 *
 * Fluxo de uma busca ([startScan]):
 *  1. [DiscoveryCache] e [DiscoveryDiagnostics] são zerados.
 *  2. Todos os [primaryScanners] rodam EM PARALELO (cada um numa thread do
 *     [executor]). Cada TvDevice que qualquer um encontra é oferecido ao
 *     [DiscoveryCache] (que delega ao Aggregator) - o resultado (novo /
 *     upgrade / ignorado) decide o que a UI recebe.
 *  3. Quando TODOS os primários terminam, o [samsungScanner] roda sobre os
 *     IPs já vistos (`DiscoveryCache.candidateIps()`), oferecendo os
 *     resultados ao mesmo Cache - uma TV que o SSDP só viu genericamente
 *     (ou nem viu) pode ser identificada/enriquecida agora.
 *  4. Só então `onScanFinished` é chamado, com o snapshot final do Cache.
 *
 * Para adicionar um fabricante/protocolo novo no futuro (LG, Android TV,
 * Roku, Fire TV, VIDAA): se a descoberta dele for "do zero" (M-SEARCH/mDNS/
 * broadcast própria), criar uma classe implementando [DiscoveryScanner] e
 * adicioná-la a [primaryScanners]. Se for uma CONFIRMAÇÃO (como a Samsung -
 * só consulta um endpoint conhecido a partir de candidatos já vistos),
 * seguir o mesmo padrão do [samsungScanner] (uma instância própria + uma
 * chamada extra no fluxo acima). Em nenhum dos dois casos é preciso mexer
 * em [DiscoveryAggregator], [DiscoveryCache], [DeviceIdentity] ou em
 * qualquer classe da UI.
 */
class DeviceScanner(context: Context) {

    interface Listener {
        fun onDeviceFound(device: TvDevice)

        /**
         * Uma entrada anteriormente exibida (registrada com a stableKey
         * [previousKey]) foi substituída por [device], uma versão mais
         * completa da MESMA TV física (ver [DiscoveryAggregator.Result.Upgraded]).
         * A UI deve trocar o item já exibido, não adicionar um novo.
         */
        fun onDeviceUpgraded(previousKey: String, device: TvDevice)

        fun onScanFinished(devices: List<TvDevice>)
        fun onScanError(message: String)
    }

    // Pool com threads suficientes para todos os scanners primários (+ a
    // fase de confirmação) rodarem de verdade em paralelo - um único
    // thread serializaria a SSDP (bloqueante, ~5s) antes mesmo do mDNS
    // começar a registrar seus listeners.
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = DiscoveryCache()

    // Scanners PRIMÁRIOS: fazem descoberta "do zero" na rede. Cada um só
    // conhece o próprio protocolo - nenhum sabe da existência do outro, nem
    // do samsungScanner abaixo. Para suportar um protocolo novo cuja
    // descoberta seja "do zero" (ex: um WsDiscoveryScanner hipotético),
    // basta adicionar aqui.
    private val primaryScanners: List<DiscoveryScanner> = listOf(
        SsdpScanner(),
        MdnsScanner(context)
    )

    // Scanner de CONFIRMAÇÃO: não descobre "do zero", só confirma/enriquece
    // candidatos já vistos pelos primários nesta rodada (ver
    // DiscoveryCache.candidateIps()). Fica de fora de [primaryScanners] de
    // propósito - tem um contrato de entrada diferente (recebe IPs em vez
    // de varrer a rede sozinho), então roda numa fase própria, depois dos
    // primários (ver [startScan]).
    private val samsungScanner = SamsungDiscoveryScanner()

    @Volatile private var isScanning = false

    fun startScan(listener: Listener) {
        if (isScanning) return
        isScanning = true
        cache.reset()
        DiscoveryDiagnostics.clear()

        fun offerToCache(device: TvDevice) {
            when (val result = cache.offer(device)) {
                is DiscoveryAggregator.Result.New -> listener.onDeviceFound(result.device)
                is DiscoveryAggregator.Result.Upgraded -> listener.onDeviceUpgraded(result.previousKey, result.device)
                DiscoveryAggregator.Result.Ignored -> Unit // duplicata de menor confiança - UI não precisa saber
            }
        }

        fun runConfirmationPhaseThenFinish() {
            executor.execute {
                samsungScanner.scanCandidates(
                    candidateIps = cache.candidateIps(),
                    onDeviceFound = { device -> mainHandler.post { offerToCache(device) } },
                    onFinished = {
                        mainHandler.post {
                            isScanning = false
                            listener.onScanFinished(cache.snapshot())
                        }
                    }
                )
            }
        }

        var pendingPrimary = primaryScanners.size

        fun onPrimaryScannerFinished() {
            pendingPrimary--
            if (pendingPrimary <= 0) {
                runConfirmationPhaseThenFinish()
            }
        }

        primaryScanners.forEach { scanner ->
            executor.execute {
                scanner.scan(
                    onDeviceFound = { device -> mainHandler.post { offerToCache(device) } },
                    onFinished = { mainHandler.post { onPrimaryScannerFinished() } },
                    onError = { message -> mainHandler.post { listener.onScanError(message) } }
                )
            }
        }
    }

    fun stopScan() {
        primaryScanners.forEach { it.stop() }
        samsungScanner.stop()
        isScanning = false
    }
}
