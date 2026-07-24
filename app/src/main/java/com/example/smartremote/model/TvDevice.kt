package com.example.smartremote.model

/**
 * Representa uma Smart TV encontrada na rede local (ou salva anteriormente).
 *
 * @param deviceId identificador estável do dispositivo (ex: USN do SSDP ou
 *                 nome do serviço mDNS), usado para reconhecer a TV mesmo
 *                 que o IP mude (troca de rede, renovação de DHCP, etc).
 */
data class TvDevice(
    val name: String,
    val brand: String?,
    val model: String?,
    val ip: String,
    val port: Int?,
    val protocol: DeviceProtocol,
    val os: TvOperatingSystem = TvOperatingSystem.UNKNOWN,
    val deviceId: String?,
    val connected: Boolean
) {

    /**
     * Chave de identidade estável da TV, usada em toda a arquitetura
     * (DeviceStorage, DeviceScanner, DeviceListAdapter, ConnectionManager,
     * CredentialStore) no lugar do IP puro - o IP pode mudar quando o
     * roteador renovar o DHCP da TV, mas essa chave não deve mudar.
     *
     * Prioridade:
     *  1. deviceId (USN do SSDP / serviceName do mDNS) - caso normal.
     *  2. brand + model - fallback para quando o deviceId não veio na
     *     descoberta (raro: SSDP sem LOCATION parseável, por exemplo).
     *  3. IP - último recurso, quando não há nenhum outro sinal estável.
     *     Sabidamente frágil (muda se o DHCP renovar); mantido isolado
     *     aqui de propósito para nunca vazar essa regra para o resto do
     *     app.
     *
     * O protocolo entra como prefixo em todos os casos para evitar
     * colisão teórica entre identificadores de origens diferentes (ex:
     * um USN SSDP que coincidisse com um serviceName mDNS).
     */
    fun stableKey(): String {
        val protocolPrefix = protocol.name.lowercase()

        deviceId?.takeIf { it.isNotBlank() }?.let {
            return "$protocolPrefix:id:$it"
        }

        if (!brand.isNullOrBlank() && !model.isNullOrBlank()) {
            return "$protocolPrefix:bm:${brand.lowercase()}:${model.lowercase()}"
        }

        return "$protocolPrefix:ip:$ip"
    }
}
