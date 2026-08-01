package com.example.smartremote.discovery

import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem

/**
 * Nível de confiança (completude) de um [TvDevice] encontrado durante a
 * descoberta. Usado pelo [DiscoveryAggregator] para decidir, quando dois
 * resultados de scanners diferentes (ou o mesmo scanner, em respostas
 * diferentes) são identificados como a MESMA TV física (ver
 * [DeviceIdentity]), qual dos dois deve "vencer" - isto é, qual conjunto de
 * campos deve ficar como o TvDevice definitivo daquela TV nesta rodada.
 *
 * Não é uma métrica "científica" - é uma pontuação simples e explicável,
 * pensada para responder de forma previsível a pergunta prática: "isto que
 * chegou agora identifica a TV melhor do que o que eu já tinha?"
 *
 * Generaliza o caso que já existia (SSDP genérico "Dispositivo SSDP" sendo
 * substituído por uma entrada identificada) para qualquer combinação de
 * scanners - por exemplo, uma TV vista primeiro só por mDNS (sem marca/
 * modelo) e depois confirmada pela API HTTP da Samsung (com marca, modelo,
 * OS e MAC) deve sofrer o mesmo tipo de upgrade, pelo mesmo motivo.
 */
object DiscoveryConfidence {

    // Nomes usados pelos scanners de hoje quando não conseguem identificar
    // o dispositivo de verdade (ex: SsdpScanner.parseResponse, fallback
    // "Dispositivo SSDP" quando nem friendlyName nem SERVER estão
    // disponíveis). Um nome genérico não pontua como identificação.
    private val GENERIC_NAME_MARKERS = setOf(
        "dispositivo ssdp"
    )

    /** Público de propósito - reutilizado pelo [DiscoveryAggregator] para decidir se um nome "mais rico" em confidence, mas genérico, deve ceder lugar a um nome real já visto. */
    fun isGenericName(name: String): Boolean =
        name.isBlank() || GENERIC_NAME_MARKERS.contains(name.trim().lowercase())

    /**
     * Pontuação de completude de [device]. Quanto maior, mais informação
     * de identificação real ele carrega. Pesos pensados para que
     * marca+modelo+OS reconhecido (o caso "TV identificada de verdade")
     * sempre vença um deviceId ou MAC isolados (o caso "sei que existe algo
     * ali, mas não sei o quê").
     */
    fun score(device: TvDevice): Int {
        var score = 0
        if (!device.brand.isNullOrBlank()) score += 3
        if (!device.model.isNullOrBlank()) score += 3
        if (device.os != TvOperatingSystem.UNKNOWN) score += 2
        if (!device.name.isBlank() && !isGenericName(device.name)) score += 1
        if (!device.deviceId.isNullOrBlank()) score += 1
        if (!DeviceIdentity.normalizeMac(device.mac).isNullOrBlank()) score += 1
        return score
    }
}
