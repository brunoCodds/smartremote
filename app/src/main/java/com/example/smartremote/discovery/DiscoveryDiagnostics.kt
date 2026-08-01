package com.example.smartremote.discovery

import android.util.Log

/**
 * Ponto padronizado do ciclo de vida de uma descoberta individual, comum a
 * QUALQUER scanner (independente de protocolo). Permite responder
 * exatamente à pergunta "onde essa TV foi perdida?" sem precisar vasculhar
 * logs específicos de cada implementação.
 */
enum class DiscoveryEventType {
    /** Scanner começou a rodar nesta rodada de busca. */
    SCAN_STARTED,
    /** Uma requisição foi enviada (M-SEARCH, query mDNS, GET HTTP...). */
    REQUEST_SENT,
    /** Uma resposta chegou, antes de qualquer parsing. */
    RESPONSE_RECEIVED,
    /** Corpo/payload da resposta foi obtido (ex: XML de descrição, JSON). */
    PAYLOAD_RECEIVED,
    /** Payload foi parseado com sucesso. */
    PAYLOAD_PARSED,
    /** Um TvDevice foi montado a partir da resposta. */
    DEVICE_CREATED,
    /** Uma resposta foi descartada sem virar TvDevice - [detail] traz o motivo. */
    DEVICE_DISCARDED,
    /** Um TvDevice já conhecido nesta rodada foi atualizado/mesclado. */
    DEVICE_UPDATED,
    /** Um TvDevice foi encaminhado ao DiscoveryAggregator/DiscoveryManager. */
    DEVICE_FORWARDED,
    /** Erro no scanner (rede, parsing inesperado, etc). */
    SCAN_ERROR,
    /** Scanner terminou de rodar nesta rodada de busca. */
    SCAN_FINISHED
}

/** Um evento único do ciclo de vida da descoberta, emitido por um scanner. */
data class DiscoveryEvent(
    val scannerName: String,
    val type: DiscoveryEventType,
    val detail: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Coletor central de diagnóstico da camada de Discovery. Cada scanner (ver
 * [DiscoveryScanner]) chama [log] nos pontos-chave do próprio ciclo de vida
 * - início, requisição enviada, resposta recebida, dispositivo criado ou
 * descartado (com motivo), atualizado, encaminhado, fim.
 *
 * Complementar (não substitui) os logs `Log.d/w/e` já existentes em cada
 * scanner, que continuam trazendo detalhe específico do protocolo (ex: XML
 * bruto no SsdpScanner) - este coletor padroniza só o ESQUELETO do fluxo,
 * igual para todos os protocolos, para que dê pra comparar/rastrear scanners
 * diferentes lado a lado.
 *
 * Mantém um histórico em memória (círular, com limite) por rodada de busca
 * - é sempre limpo no início de uma nova busca (ver
 * [DeviceScanner.startScan]), nunca persistido.
 *
 * Não deve ser confundido com o `DiagnosticManager` já existente para
 * diagnóstico de CONEXÃO/pareamento - são camadas diferentes do app,
 * mantidas propositalmente separadas.
 */
object DiscoveryDiagnostics {

    private const val TAG_PREFIX = "Discovery"
    private const val MAX_EVENTS = 500

    private val events = ArrayDeque<DiscoveryEvent>()

    @Synchronized
    fun log(scannerName: String, type: DiscoveryEventType, detail: String = "") {
        val event = DiscoveryEvent(scannerName, type, detail)
        events.addLast(event)
        while (events.size > MAX_EVENTS) {
            events.removeFirst()
        }
        Log.d("$TAG_PREFIX-$scannerName", "[$type] $detail")
    }

    /** Todos os eventos da rodada atual (ou da última rodada, se nenhuma estiver em andamento), em ordem cronológica. */
    @Synchronized
    fun snapshot(): List<DiscoveryEvent> = events.toList()

    /** Só os eventos de um scanner específico (ex: "SSDP", "mDNS", "Samsung") - útil pra depurar um protocolo isolado. */
    @Synchronized
    fun eventsFor(scannerName: String): List<DiscoveryEvent> = events.filter { it.scannerName == scannerName }

    /** Limpa o histórico - chamado no início de cada nova rodada de busca. */
    @Synchronized
    fun clear() {
        events.clear()
    }
}
