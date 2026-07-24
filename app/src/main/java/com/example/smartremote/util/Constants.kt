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
    const val PREF_KEY_SAVED_DEVICE = "saved_tv_device"
}
