package com.example.smartremote.discovery

import com.example.smartremote.model.TvDevice

/**
 * Estado TEMPORÁRIO de uma rodada de busca em andamento. Existe só entre
 * `DeviceScanner.startScan()` e o `onScanFinished` correspondente - é
 * sempre descartado (ver [reset]) antes de uma nova busca começar, e
 * NUNCA é persistido em disco.
 *
 * Formaliza a separação de três conceitos que nunca devem se misturar
 * nesta arquitetura:
 *  - **TVs descobertas** (este cache): temporárias, recriadas a cada busca.
 *  - **TVs salvas** ([com.example.smartremote.util.DeviceStorage] /
 *    [com.example.smartremote.manager.TvManager.getPairedDevices]):
 *    persistentes, podem existir várias.
 *  - **TV conectada** ([com.example.smartremote.manager.ConnectionManager]):
 *    no máximo uma por vez.
 *
 * A descoberta não sabe, e não precisa saber, se um TvDevice encontrado
 * está salvo ou conectado - quem cruza essa informação é a
 * `DeviceDiscoveryActivity` (ver `refreshPairedBadges`), DEPOIS que a busca
 * já terminou. Este cache só guarda o resultado cru da rodada atual.
 *
 * Por dentro delega toda a lógica de dedup/merge ao [DiscoveryAggregator] -
 * existe como classe própria para deixar essa separação de conceitos
 * explícita no código, e para dar um lugar único a crescer no futuro (ex:
 * hoje já guarda também os IPs vistos por rodada, usados pelos scanners de
 * CONFIRMAÇÃO como o [SamsungDiscoveryScanner], sem misturar esse conceito
 * com a lógica de merge do Aggregator).
 */
class DiscoveryCache {

    private val aggregator = DiscoveryAggregator()

    // IPs de todo TvDevice já visto nesta rodada, por QUALQUER scanner
    // primário - é a lista de candidatos que os scanners de CONFIRMAÇÃO
    // (que não descobrem "do zero") usam para saber o que tentar.
    private val seenIps = linkedSetOf<String>()

    /** Registra [device] nesta rodada. Ver [DiscoveryAggregator.offer] para o resultado. */
    fun offer(device: TvDevice): DiscoveryAggregator.Result {
        seenIps.add(device.ip)
        return aggregator.offer(device)
    }

    /** Todos os TvDevice consolidados até agora nesta rodada (já deduplicados/mesclados). */
    fun snapshot(): List<TvDevice> = aggregator.snapshot()

    /** IPs vistos até agora nesta rodada - candidatos para scanners de confirmação. */
    fun candidateIps(): Set<String> = seenIps.toSet()

    /** Descarta todo o estado da rodada. Chamado no início de cada nova busca. */
    fun reset() {
        aggregator.reset()
        seenIps.clear()
    }
}
