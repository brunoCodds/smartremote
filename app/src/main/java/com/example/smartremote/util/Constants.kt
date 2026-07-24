package com.example.smartremote.util

object Constants {

    // ===== SSDP / UPnP =====
    const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
    const val SSDP_MULTICAST_PORT = 1900
    const val SSDP_SEARCH_TARGET = "ssdp:all"
    const val SSDP_TOTAL_TIMEOUT_MS = 4000
    const val SSDP_SOCKET_TIMEOUT_MS = 1500

    // ===== mDNS / NSD =====
    // Service types mais comuns anunciados por Smart TVs na rede local.
    val MDNS_SERVICE_TYPES = listOf(
        "_googlecast._tcp.",       // Chromecast built-in / Android TV / Google TV
        "_androidtvremote2._tcp.", // Android TV Remote v2
        "_dial._tcp.",             // DIAL - usado por Netflix/YouTube em várias Smart TVs
        "_airplay._tcp.",          // AirPlay (algumas Sony/Philips)
        "_webos._tcp."             // LG webOS, quando anunciado via mDNS
    )
    const val MDNS_DISCOVERY_TIMEOUT_MS = 5000L

    // ===== Persistência =====
    const val PREFS_NAME = "smart_remote_prefs"

    // Chave antiga (formato: um único objeto TvDevice). Mantida apenas
    // para a migração automática em DeviceStorage - não usar em código
    // novo. Pode ser removida quando não houver mais base instalada com
    // esse formato antigo.
    const val PREF_KEY_SAVED_DEVICE = "saved_tv_device"

    // Chave nova (formato: JSONArray de TvDevice). Usar esta em todo
    // código novo.
    const val PREF_KEY_SAVED_DEVICES = "saved_tv_devices"

    // ===== Credenciais de pareamento (tokens, client-keys, certificados) =====
    const val CREDENTIALS_PREFS_NAME = "smart_remote_credentials"

    // ===== Samsung Tizen (WebSocket "Samsung Remote Control") =====
    const val SAMSUNG_APP_NAME = "SmartRemote"
    const val SAMSUNG_WS_PORT = 8002
    const val SAMSUNG_WS_PATH = "/api/v2/channels/samsung.remote.control"
    const val SAMSUNG_CREDENTIAL_TYPE = "samsung_token"
    const val SAMSUNG_PAIRING_TIMEOUT_MS = 60_000L
}
