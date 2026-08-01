package com.example.smartremote.model

/**
 * Representa uma Smart TV encontrada na rede local (ou salva anteriormente).
 *
 * @param deviceId identificador estável do dispositivo (ex: USN do SSDP ou
 *                 nome do serviço mDNS), usado para reconhecer a TV mesmo
 *                 que o IP mude (troca de rede, renovação de DHCP, etc).
 * @param mac endereço MAC da interface de rede da TV, quando a fonte da
 *            descoberta o fornece (hoje só o SamsungDiscoveryScanner
 *            fornece isso, via o campo `wifiMac` da API HTTP nativa da
 *            Samsung). Opcional e informativo: NÃO participa de
 *            [stableKey] (ver nota abaixo) - é usado apenas como um dos
 *            critérios de identidade DENTRO da camada de Discovery
 *            (ver DeviceIdentity/DiscoveryAggregator), para decidir se dois
 *            resultados de scanners diferentes são a mesma TV física
 *            durante UMA rodada de busca.
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
    val connected: Boolean,
    val mac: String? = null
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
     *
     * *** Por que esta função NÃO foi alterada na evolução da camada de
     * Discovery (dedup por UUID -> deviceId -> USN -> MAC -> nome+modelo
     * -> IP, com confidence score) ***: esta chave é a identidade usada
     * para PAREAMENTO/PERSISTÊNCIA (DeviceStorage) e CREDENCIAIS
     * (CredentialStore) de TVs já salvas. Mudar o algoritmo aqui mudaria a
     * chave de TVs já pareadas por usuários existentes, "órfão-izando"
     * credenciais salvas. A dedup mais rica (com MAC e confidence) roda
     * INTEIRAMENTE dentro da camada de Discovery, só durante a busca
     * (ver DeviceIdentity/DiscoveryAggregator/DiscoveryCache) - o TvDevice
     * final que sai da busca e chega até aqui continua usando o mesmo
     * deviceId de sempre (agora possivelmente mais completo, graças à
     * mesclagem), então stableKey() continua funcionando exatamente como
     * sempre funcionou, sem risco para quem já tem TV pareada.
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
