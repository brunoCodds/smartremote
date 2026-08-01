package com.example.smartremote.discovery

import com.example.smartremote.model.TvDevice

/**
 * Resolução de identidade usada SOMENTE durante a agregação de UMA rodada
 * de busca (ver [DiscoveryAggregator]) - decide se dois [TvDevice] vindos
 * de scanners diferentes (ou do mesmo scanner, em respostas diferentes)
 * representam a MESMA TV física.
 *
 * *** Não deve ser confundida com [TvDevice.stableKey] ***, que é a
 * identidade de PAREAMENTO/PERSISTÊNCIA (ver o KDoc de stableKey() para o
 * motivo dela ter ficado intencionalmente intocada). Esta classe é mais
 * permissiva e mais rica de propósito - ela existe só para decidir "isso
 * que acabou de chegar é a mesma TV que eu já vi nesta busca, só que com
 * mais informação?", nunca para decidir identidade de longo prazo.
 *
 * Prioridade de sinais (do mais forte para o mais fraco) - ver
 * [candidateKeys]:
 *  1. UUID - extraído de QUALQUER string que contenha um UUID no formato
 *     padrão (ex: do deviceId do SSDP, que já é um USN reduzido a
 *     "uuid:<uuid>"; ou do `duid`/`id` retornado pela API HTTP da Samsung,
 *     que usa o MESMO formato "uuid:<uuid>" - por isso uma TV vista tanto
 *     por SSDP quanto pela confirmação Samsung já mescla automaticamente
 *     por este critério, sem nenhuma lógica extra).
 *  2. deviceId bruto (com o protocolo como prefixo, pra não colidir entre
 *     protocolos) - cobre USN sem uuid: reconhecível e o serviceName do
 *     mDNS. USN por si só não vira uma chave própria porque, nos scanners
 *     de hoje, ele já é reduzido a UUID (SSDP) ou nunca existe (mDNS,
 *     Samsung) antes de chegar aqui - se um scanner futuro expuser USN cru
 *     sem essa redução, ele automaticamente entra neste nível.
 *  3. MAC (normalizado) - hoje só a confirmação Samsung fornece.
 *  4. nome (marca) + modelo - quando nenhum identificador único veio, mas
 *     dois scanners concordam em marca E modelo.
 *  5. IP - último recurso, mesmo critério (frágil, mas melhor que nada)
 *     já usado antes desta evolução.
 */
object DeviceIdentity {

    private val UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    /** Extrai um UUID (minúsculo, sem prefixo "uuid:") de qualquer uma das strings passadas, na ordem dada. */
    fun extractUuid(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            UUID_REGEX.find(candidate)?.let { return it.value.lowercase() }
        }
        return null
    }

    /** Normaliza um MAC para comparação (minúsculo, separador ':'), ou null se ausente/placeholder ("none", comum na API Samsung quando a TV está sem Wi-Fi). */
    fun normalizeMac(mac: String?): String? {
        val trimmed = mac?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.equals("none", ignoreCase = true)) return null
        return trimmed.lowercase().replace('-', ':')
    }

    /**
     * Chaves de identidade candidatas para [device], da mais forte
     * (primeira) para a mais fraca (última). O [DiscoveryAggregator]
     * considera "mesma TV física" quando dois TvDevice compartilham
     * QUALQUER uma dessas chaves.
     */
    fun candidateKeys(device: TvDevice): List<String> {
        val keys = mutableListOf<String>()

        extractUuid(device.deviceId)?.let { keys.add("uuid:$it") }

        device.deviceId?.takeIf { it.isNotBlank() }?.let {
            keys.add("devid:${device.protocol.name.lowercase()}:$it")
        }

        normalizeMac(device.mac)?.let { keys.add("mac:$it") }

        if (!device.brand.isNullOrBlank() && !device.model.isNullOrBlank()) {
            keys.add("nm:${device.brand.lowercase()}:${device.model.lowercase()}")
        }

        keys.add("ip:${device.ip}")

        return keys
    }
}
