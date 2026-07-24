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
)
