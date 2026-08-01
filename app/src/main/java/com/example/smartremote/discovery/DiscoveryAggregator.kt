package com.example.smartremote.discovery

import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem

/**
 * Ponto único de deduplicação/merge/enriquecimento de [TvDevice] durante
 * UMA rodada de busca. Antes desta evolução, essa lógica vivia espalhada
 * (inline) dentro do `DeviceScanner.startScan()`; agora é uma classe
 * própria, testável isoladamente e reutilizável por qualquer número de
 * scanners - o `DeviceScanner` (orquestrador) só chama [offer] para cada
 * TvDevice que qualquer scanner encontra, sem saber nada sobre a lógica de
 * merge.
 *
 * Regras:
 *  - Dois TvDevice são considerados a MESMA TV física quando compartilham
 *    QUALQUER chave de [DeviceIdentity.candidateKeys] (ver lá a ordem de
 *    prioridade: UUID > deviceId > MAC > nome+modelo > IP).
 *  - Quando são a mesma TV, o mais completo "vence" (ver
 *    [DiscoveryConfidence.score]) - o TvDevice mesclado herda os melhores
 *    campos de ambos (nunca perde informação que um dos dois já tinha).
 *  - Todas as chaves de identidade já vistas (de qualquer um dos merges)
 *    continuam apontando para o mesmo registro, então uma TV que mude de
 *    sinal identificador NO MEIO da mesma rodada (ex: SSDP achou por IP
 *    puro, depois a confirmação Samsung trouxe o UUID) continua sendo
 *    reconhecida como a mesma TV daí em diante.
 *
 * Efêmero por natureza: [reset] descarta tudo. Nunca persiste nada -
 * persistência é responsabilidade exclusiva de
 * [com.example.smartremote.util.DeviceStorage], que esta classe não
 * conhece.
 *
 * Thread-safety: [offer]/[snapshot]/[reset] são sincronizados porque, na
 * prática, o `DeviceScanner` já funil todas as chamadas para a main thread
 * antes de chamar [offer] - a sincronização aqui é só uma proteção
 * defensiva extra, não uma dependência do chamador.
 */
class DiscoveryAggregator {

    /** Resultado de oferecer um TvDevice recém-encontrado ao Aggregator. */
    sealed class Result {
        /** TV nunca vista nesta rodada - a UI deve adicionar um item novo. */
        data class New(val device: TvDevice) : Result()

        /**
         * TV já conhecida nesta rodada, mas o novo resultado (mesclado com
         * o que já existia) é mais completo - a UI deve SUBSTITUIR o item
         * de chave [previousKey] (a stableKey() que foi emitida da última
         * vez que esta TV foi notificada) pelo [device] mesclado. Mesmo
         * contrato que [com.example.smartremote.discovery.DeviceScanner.Listener.onDeviceUpgraded]
         * já usava antes desta evolução - nenhuma mudança na UI é
         * necessária por causa disso.
         */
        data class Upgraded(val previousKey: String, val device: TvDevice) : Result()

        /**
         * TV já conhecida nesta rodada e o novo resultado não traz mais
         * informação que justifique substituir o que já foi mostrado - a
         * UI não precisa fazer nada. As chaves de identidade do novo
         * resultado ainda são registradas internamente (ver classe acima),
         * mesmo sem notificar a UI.
         */
        object Ignored : Result()
    }

    private data class Entry(var device: TvDevice, val keys: MutableSet<String>)

    // aggregateId (arbitrário, gerado a partir da primeira chave forte que
    // o dispositivo trouxe) -> registro atual daquela TV nesta rodada.
    private val entriesById = LinkedHashMap<String, Entry>()

    // Toda chave de identidade já vista (de qualquer tier) -> aggregateId
    // do registro a que ela pertence. É como um dispositivo com uma chave
    // "fraca" (ex: só IP) é reconhecido depois que uma chave "forte" (ex:
    // UUID) é associada ao mesmo registro por outro resultado.
    private val aggregateIdByKey = HashMap<String, String>()

    @Synchronized
    fun offer(device: TvDevice): Result {
        val candidateKeys = DeviceIdentity.candidateKeys(device)

        // Já existe algum registro que compartilhe QUALQUER uma dessas
        // chaves? A ordem de candidateKeys já é da mais forte pra mais
        // fraca, então o primeiro match encontrado é o melhor disponível.
        val existingAggregateId = candidateKeys.firstNotNullOfOrNull { aggregateIdByKey[it] }

        if (existingAggregateId == null) {
            val aggregateId = candidateKeys.first()
            entriesById[aggregateId] = Entry(device, candidateKeys.toMutableSet())
            candidateKeys.forEach { aggregateIdByKey[it] = aggregateId }
            return Result.New(device)
        }

        val entry = entriesById.getValue(existingAggregateId)
        val previousDevice = entry.device
        val previousStableKey = previousDevice.stableKey()

        // Registra as chaves novas de qualquer forma (mesmo se este
        // resultado específico não for "vencer") - garante que uma chave
        // fraca vista antes (ex: IP) e uma forte vista agora (ex: UUID)
        // fiquem associadas ao MESMO registro dali em diante.
        entry.keys.addAll(candidateKeys)
        candidateKeys.forEach { aggregateIdByKey[it] = existingAggregateId }

        return if (DiscoveryConfidence.score(device) > DiscoveryConfidence.score(previousDevice)) {
            val merged = merge(base = previousDevice, richer = device)
            entry.device = merged
            Result.Upgraded(previousStableKey, merged)
        } else {
            Result.Ignored
        }
    }

    /** Todos os TvDevice consolidados até agora nesta rodada. */
    @Synchronized
    fun snapshot(): List<TvDevice> = entriesById.values.map { it.device }

    /** Descarta todo o estado - chamado no início de cada nova busca. */
    @Synchronized
    fun reset() {
        entriesById.clear()
        aggregateIdByKey.clear()
    }

    /**
     * Mescla [base] (o que já tínhamos) com [richer] (o resultado com
     * confidence maior) preferindo os campos de [richer] quando presentes,
     * mas SEM perder informação que [base] já tinha e [richer] não trouxe
     * (ex: [base] tinha modelo e [richer], apesar de ter confidence maior
     * no geral, não trouxe modelo - mantém o de [base]).
     */
    private fun merge(base: TvDevice, richer: TvDevice): TvDevice = TvDevice(
        name = pickBetterName(base.name, richer.name),
        brand = richer.brand ?: base.brand,
        model = richer.model ?: base.model,
        ip = richer.ip, // o IP mais recente reportado é o mais confiável (o antigo pode ter mudado por DHCP)
        port = richer.port ?: base.port,
        protocol = richer.protocol,
        os = if (richer.os != TvOperatingSystem.UNKNOWN) richer.os else base.os,
        deviceId = richer.deviceId ?: base.deviceId,
        connected = false, // descoberta nunca marca "conectada" - ver TvDevice/TvManager
        mac = DeviceIdentity.normalizeMac(richer.mac) ?: base.mac
    )

    private fun pickBetterName(baseName: String, richerName: String): String {
        if (richerName.isBlank()) return baseName
        if (baseName.isBlank()) return richerName
        // Se o nome "mais rico" em confidence geral for, mesmo assim, um
        // placeholder genérico (ex: veio de um scanner que só confirma IP,
        // sem nome de verdade) e o nome antigo não for, mantém o antigo -
        // nome genérico nunca deve substituir um nome real.
        val richerIsGeneric = DiscoveryConfidence.isGenericName(richerName)
        val baseIsGeneric = DiscoveryConfidence.isGenericName(baseName)
        return if (richerIsGeneric && !baseIsGeneric) baseName else richerName
    }
}
